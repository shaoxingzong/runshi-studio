<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import { relationApi } from '@/api/relation';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ImageUploader from '@/components/ImageUploader.vue';
import { REMOTE_OPTION_LIMIT, useRemoteOptions } from '@/composables/useRemoteOptions';
import type { AdminMember, AdminProject, Member, PageResult } from '@/api/types';

/** 项目管理：正文（Markdown）与技术栈是知识库的数据源，改完记得同步 */
const page = reactive({ current: 1, pageSize: 10 });

/** 筛选条件：空串表示不限；后端 ProjectQueryRequest 的 title / status 早就支持，只是界面没接 */
const filters = reactive<{ title: string; status: '' | 0 | 1 | 2 }>({ title: '', status: '' });

const result = ref<PageResult<AdminProject> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

const dialogVisible = ref(false);
const editing = ref<AdminProject | null>(null);
const form = reactive<Record<string, unknown>>({
    title: '',
    description: '',
    content: '',
    techStackText: '',
    leaderId: '',
    coverImage: '',
    demoUrl: '',
    githubUrl: '',
    status: 1,
    sortOrder: 0,
});

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

// ==================== 参与成员（关联维护）====================

/** 已绑定的参与成员（含队长） */
const projectMembers = ref<AdminMember[]>([]);
/**
 * 候选成员（远程搜索）
 *
 * 后端分页上限是 50，原先写 pageSize: 100 只会被静默截断——第 51 位成员永远选不到，
 * 界面还没有任何提示。这里改成「先取前 50，再按姓名搜」，并在被截断时明确告知。
 */
const {
    options: memberOptions,
    total: memberTotal,
    truncated: memberTruncated,
    loading: memberSearching,
    load: loadMemberOptions,
    search: searchMemberOptions,
    dispose: disposeMemberSearch,
} = useRemoteOptions<Member>((keyword) =>
    adminApi.memberPage({
        current: 1,
        pageSize: REMOTE_OPTION_LIMIT,
        // 空串不下发：传空串等于给后端一个「查空姓名」的歧义
        name: keyword || undefined,
    }));
const memberLoading = ref(false);
const pickedMember = ref('');

/**
 * 是否为当前队长
 *
 * 队长由 `project.leaderId` 决定，后端拒绝把队长从成员列表里移除。
 * 这里提前禁用按钮，而不是让用户点了才收到报错。
 */
function isLeader(memberId: string): boolean {
    return String(form.leaderId ?? '') === memberId;
}

async function loadMembers(projectId: string) {
    memberLoading.value = true;
    try {
        projectMembers.value = (await relationApi.projectMemberList(projectId)) ?? [];
    } catch {
        projectMembers.value = [];
    } finally {
        memberLoading.value = false;
    }
    // 候选成员与绑定关系互不影响，单独加载
    await loadMemberOptions();
}

async function addMember() {
    if (!pickedMember.value || !editing.value) {
        return;
    }
    try {
        await relationApi.projectMemberBind(editing.value.id, pickedMember.value);
        ElMessage.success('已添加参与成员');
        pickedMember.value = '';
        await loadMembers(editing.value.id);
    } catch {
        // 重复绑定（A0401）等提示已在请求层
    }
}

async function removeMember(memberId: string) {
    if (!editing.value) {
        return;
    }
    if (isLeader(memberId)) {
        ElMessage.warning('队长不可移除：请先在上方修改队长 ID');
        return;
    }
    try {
        await relationApi.projectMemberUnbind(editing.value.id, memberId);
        await loadMembers(editing.value.id);
    } catch {
        // 提示已在请求层
    }
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.projectPage({
            ...page,
            title: filters.title.trim() || undefined,
            status: filters.status === '' ? undefined : filters.status,
        });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, {
        title: '',
        description: '',
        content: '',
        techStackText: '',
        leaderId: '',
        coverImage: '',
        demoUrl: '',
        githubUrl: '',
        status: 1,
        sortOrder: 0,
    });
    // 新增态没有项目 ID，绑不了成员：先保存，再打开编辑即可
    projectMembers.value = [];
    // 但队长下拉需要候选成员，先把名单拉出来
    void loadMemberOptions();
    dialogVisible.value = true;
}

function openEdit(row: AdminProject) {
    editing.value = row;
    Object.assign(form, {
        title: row.title,
        description: row.description ?? '',
        content: row.content ?? '',
        // 数组在表单里用「逗号分隔」编辑，保存时再拆回数组
        techStackText: (row.techStack ?? []).join(', '),
        leaderId: row.leaderId,
        coverImage: row.coverImage ?? '',
        demoUrl: row.demoUrl ?? '',
        githubUrl: row.githubUrl ?? '',
        status: row.status ?? 1,
        sortOrder: row.sortOrder ?? 0,
    });
    dialogVisible.value = true;
    void loadMembers(row.id);
}

function toTechStack(): string[] {
    return String(form.techStackText ?? '')
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean);
}

async function save() {
    if (!form.title || !form.description) {
        ElMessage.warning('项目名称与摘要必填');
        return;
    }
    const payload = {
        title: form.title as string,
        description: form.description as string,
        content: form.content as string,
        techStack: toTechStack(),
        leaderId: form.leaderId as string,
        coverImage: form.coverImage as string,
        demoUrl: form.demoUrl as string,
        githubUrl: form.githubUrl as string,
        status: form.status as number,
        sortOrder: form.sortOrder as number,
    };
    try {
        if (editing.value) {
            await adminApi.projectUpdate({ id: editing.value.id, ...payload });
        } else {
            await adminApi.projectAdd(payload);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 统一提示已在请求层
    }
}

async function remove(row: AdminProject) {
    try {
        await ElMessageBox.confirm(`确认删除项目「${row.title}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.projectDelete(row.id);
        ElMessage.success('已删除');
        await load();
    } catch {
        // 同上
    }
}

/** 换条件回第一页：否则可能停在已不存在的页码上，看到空列表还以为没数据 */
function query() {
    page.current = 1;
    load();
}

function reset() {
    filters.title = '';
    filters.status = '';
    query();
}

function turn(delta: number) {
    const next = page.current + delta;
    const pages = result.value ? Number(result.value.pages) : 1;
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    load();
}

onMounted(load);

onBeforeUnmount(disposeMemberSearch);
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="bar">
            <h2>项目管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增项目</el-button>
        </div>

        <div class="filters">
            <el-input
                v-model="filters.title"
                class="filter"
                placeholder="项目名称"
                clearable
                maxlength="64"
                @keyup.enter="query"
            />
            <el-select v-model="filters.status" class="filter" placeholder="全部状态" clearable>
                <el-option label="研发中" :value="0" />
                <el-option label="已上线" :value="1" />
                <el-option label="已结题" :value="2" />
            </el-select>
            <div class="ops">
                <el-button type="primary" @click="query">查询</el-button>
                <el-button @click="reset">重置</el-button>
            </div>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="title" label="项目" min-width="180" />
            <el-table-column label="封面" width="90">
                <template #default="{ row }">
                    <img v-if="row.coverImage" :src="row.coverImage" :alt="row.title" class="cover-thumb" />
                    <span v-else class="muted">—</span>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.status === 1 ? 'success' : 'info'">
                        {{ STATUS_TEXT[String(row.status)] || row.status }}
                    </el-tag>
                </template>
            </el-table-column>
            <!--
              权重是「首页项目怎么放」的唯一抓手：后端按官网口径（权重倒序 → 创建时间倒序 → id 倒序）
              取前 6 条，列表默认顺序与之相同，所以这里看到的名次就是门户首页的名次
            -->
            <el-table-column label="权重" width="80">
                <template #default="{ row }">
                    {{ row.sortOrder ?? 0 }}
                </template>
            </el-table-column>
            <el-table-column label="首页" width="90">
                <template #default="{ row }">
                    <el-tag v-if="row.homeRank" size="small" type="warning" effect="plain">
                        #{{ row.homeRank }}
                    </el-tag>
                    <span v-else class="muted">—</span>
                </template>
            </el-table-column>
            <el-table-column prop="description" label="摘要" min-width="200" show-overflow-tooltip />
            <el-table-column label="技术栈" min-width="180">
                <template #default="{ row }">
                    <el-tag v-for="tech in row.techStack ?? []" :key="tech" size="small" class="tech">
                        {{ tech }}
                    </el-tag>
                    <span v-if="!row.techStack || !row.techStack.length" class="muted">—</span>
                </template>
            </el-table-column>
            <el-table-column label="最近更新" width="120">
                <template #default="{ row }">
                    {{ (row.updatedAt || row.createdAt || '').slice(0, 10) || '—' }}
                </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                    <el-button link type="danger" @click="remove(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div v-if="result" class="pager">
            <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
            <span class="muted">第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 条</span>
            <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">下一页</el-button>
        </div>

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑项目' : '新增项目'" width="640px">
            <el-form label-position="top">
                <el-form-item label="项目名称" required><el-input v-model="form.title as string" /></el-form-item>
                <el-form-item label="摘要" required><el-input v-model="form.description as string" /></el-form-item>
                <el-form-item label="队长" required>
                    <!-- 远程搜索：后端单页上限 50，成员多时靠关键词定位，不能只靠第一页 -->
                    <el-select
                        v-model="form.leaderId as string"
                        placeholder="选择队长（成员）"
                        filterable
                        remote
                        :remote-method="searchMemberOptions"
                        :loading="memberSearching"
                        class="full"
                    >
                        <el-option
                            v-for="option in memberOptions"
                            :key="option.id"
                            :label="option.name"
                            :value="option.id"
                        />
                    </el-select>
                    <div v-if="memberTruncated" class="hint">
                        共 {{ memberTotal }} 位成员，当前只显示前 {{ memberOptions.length }} 位，可输入姓名搜索
                    </div>
                    <div class="hint">队长会同时写入参与成员列表，且不可从成员中移除。</div>
                </el-form-item>
                <el-form-item label="正文（Markdown，知识库数据源）">
                    <el-input v-model="form.content as string" type="textarea" :rows="6" />
                </el-form-item>
                <el-form-item label="技术栈（逗号分隔）">
                    <el-input v-model="form.techStackText as string" placeholder="Spring Boot, MySQL" />
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="form.status as number">
                        <el-option label="研发中" :value="0" />
                        <el-option label="已上线" :value="1" />
                        <el-option label="已结题" :value="2" />
                    </el-select>
                </el-form-item>
                <el-form-item label="封面图">
                    <ImageUploader v-model="form.coverImage as string" tip="项目封面（可选）；列表与详情页会展示" />
                </el-form-item>
                <el-form-item label="体验地址 / 仓库地址">
                    <el-input v-model="form.demoUrl as string" placeholder="在线体验地址" />
                    <el-input v-model="form.githubUrl as string" placeholder="开源仓库地址" />
                </el-form-item>
                <el-form-item label="置顶权重">
                    <el-input-number v-model="form.sortOrder as number" :min="0" :max="999" />
                    <div class="hint">数值越大越靠前（官网项目列表按它倒序）。</div>
                </el-form-item>

                <!-- 参与成员：绑定关系依赖项目 ID，所以只在编辑态出现 -->
                <el-form-item v-if="editing" label="参与成员">
                    <div v-loading="memberLoading" class="members">
                        <div v-if="!projectMembers.length" class="muted">暂无参与成员</div>
                        <div v-for="member in projectMembers" :key="member.id" class="member-row">
                            <span class="member-name">{{ member.name }}</span>
                            <el-tag v-if="isLeader(member.id)" size="small" type="warning">队长</el-tag>
                            <el-button
                                link
                                type="danger"
                                :disabled="isLeader(member.id)"
                                @click="removeMember(member.id)"
                            >
                                移除
                            </el-button>
                        </div>
                        <div class="member-add">
                            <el-select
                                v-model="pickedMember"
                                placeholder="选择要添加的成员（可输入姓名搜索）"
                                filterable
                                remote
                                :remote-method="searchMemberOptions"
                                :loading="memberSearching"
                                class="member-select"
                            >
                                <el-option
                                    v-for="option in memberOptions"
                                    :key="option.id"
                                    :label="option.name"
                                    :value="option.id"
                                />
                            </el-select>
                            <el-button type="primary" :disabled="!pickedMember" @click="addMember">
                                添加
                            </el-button>
                        </div>
                    </div>
                </el-form-item>
                <el-form-item v-else>
                    <el-alert
                        type="info"
                        :closable="false"
                        show-icon
                        title="保存之后即可绑定参与成员（绑定关系需要项目 ID）。"
                    />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" @click="save">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-bottom: 16px;
    padding: 14px 16px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
}

.filter {
    width: 200px;
}

.ops {
    display: flex;
    gap: 8px;
    margin-left: auto;
}

.bar {
    display: flex;
    align-items: center;
    margin-bottom: 16px;
}

h2 {
    margin: 0;
    font-size: 20px;
}

.spacer {
    flex: 1;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 16px;
}

.members {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.member-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.member-name {
    flex: 1;
}

.member-add {
    display: flex;
    gap: 8px;
    margin-top: 4px;
}

.member-select {
    flex: 1;
}

.cover-thumb {
    width: 48px;
    height: 34px;
    border-radius: 4px;
    object-fit: cover;
    display: block;
}

.full {
    width: 100%;
}

.tech {
    margin: 0 4px 4px 0;
}

.hint {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: #6b7280;
}
</style>
