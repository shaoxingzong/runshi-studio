<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ImageUploader from '@/components/ImageUploader.vue';
import { REMOTE_OPTION_LIMIT, useRemoteOptions } from '@/composables/useRemoteOptions';
import type { AdminMember, AdminUser, PageResult } from '@/api/types';

/** 成员管理：分页表格 + 新增/编辑/删除 */
const page = reactive({ current: 1, pageSize: 10 });

/**
 * 筛选条件：空串表示「不限」，与后端「不传即不过滤」的口径一致
 *
 * 后端 MemberQueryRequest 早就支持这些参数，界面没接之前它们一直闲置——
 * 成员一多，只能靠翻页找人。
 */
const filters = reactive<{
    name: string;
    direction: string;
    teamPosition: string;
    memberStatus: '' | 0 | 1;
}>({ name: '', direction: '', teamPosition: '', memberStatus: '' });

const result = ref<PageResult<AdminMember> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

const dialogVisible = ref(false);
const editing = ref<AdminMember | null>(null);
const form = reactive<Record<string, unknown>>({
    // 绑定的登录账号 ID；空 = 未绑定（编辑时留空表示不修改，解绑需另设计）
    userId: '',
    name: '',
    gradeYear: null,
    major: '',
    direction: '',
    teamPosition: 'member',
    memberStatus: 0,
    avatar: '',
    githubUrl: '',
    summary: '',
    sortOrder: 0,
});

const POSITION_TEXT: Record<string, string> = { member: '成员', leader: '队长', tech_lead: '组长' };

/**
 * 候选登录账号：用来把「注册用户」转成「成员」
 *
 * 这是成员能否发帖、能否打卡的唯一开关——成员身份认的是 studio_member.user_id，不是账号角色。
 * 没有这个入口，管理员就只能改数据库才能让自己变成成员。
 */
/**
 * 账号 ID → 账号名的累积缓存
 *
 * 表格里的「绑定账号」要靠它反查。下拉改成远程搜索后，选项只包含当前关键词命中的那一页，
 * 若还从选项里 find，搜索一次其它行的账号名就全变成 ID 了——缓存只增不减，不受筛选影响。
 */
// 用 reactive 而不是普通 Map：表格里的账号名是渲染时读缓存的，
// 普通 Map 的写入不会触发重渲染，首次加载完成后名字会一直显示成 ID
const userAccountById = reactive(new Map<string, string>());

/**
 * 候选登录账号（远程搜索）
 *
 * 后端分页上限 50，原先 pageSize: 100 会被静默截断；改成「前 50 + 关键词搜索」并在被截断时提示。
 */
const {
    options: userOptions,
    total: userTotal,
    truncated: userTruncated,
    loading: userSearching,
    load: loadUsers,
    search: searchUsers,
    dispose: disposeUserSearch,
} = useRemoteOptions<AdminUser>(async (keyword) => {
    const page = await adminApi.userPage({
        current: 1,
        pageSize: REMOTE_OPTION_LIMIT,
        userAccount: keyword || undefined,
    });
    // 每次响应都并入缓存：搜索结果只覆盖当前项，缓存负责记住见过的账号名
    for (const user of page.records ?? []) {
        userAccountById.set(user.id, user.userAccount);
    }
    return page;
});

/** 由 userId 反查账号名，让表格里的绑定状态一眼可读 */
function accountOf(userId: string): string {
    return userAccountById.get(userId) ?? userId;
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.memberPage({
            ...page,
            // 空串不下发：传了空串等于给后端一个「查空关键词」的歧义
            name: filters.name.trim() || undefined,
            direction: filters.direction.trim() || undefined,
            teamPosition: filters.teamPosition || undefined,
            memberStatus: filters.memberStatus === '' ? undefined : filters.memberStatus,
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
        userId: '',
        name: '',
        gradeYear: null,
        major: '',
        direction: '',
        teamPosition: 'member',
        memberStatus: 0,
        avatar: '',
        githubUrl: '',
        summary: '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function openEdit(row: AdminMember) {
    editing.value = row;
    Object.assign(form, {
        userId: row.userId ?? '',
        name: row.name,
        gradeYear: row.gradeYear ?? null,
        major: row.major ?? '',
        direction: row.direction ?? '',
        teamPosition: row.teamPosition ?? 'member',
        memberStatus: row.memberStatus ?? 0,
        avatar: row.avatar ?? '',
        githubUrl: row.githubUrl ?? '',
        summary: row.summary ?? '',
        sortOrder: row.sortOrder ?? 0,
    });
    dialogVisible.value = true;
}

async function save() {
    if (!form.name) {
        ElMessage.warning('姓名必填');
        return;
    }
    /**
     * 新增时未绑账号：**不阻止**，但必须让管理员知道后果
     *
     * {@code db/user.sql} 保留「可以有档案没账号」的能力，强制必填属于过度修正。但准入判据是
     * 「档案的 userId 命中当前登录账号」，没绑定的档案永远不会生效——管理员却在列表里看到「这个人是成员」。
     */
    if (!editing.value && !form.userId) {
        try {
            await ElMessageBox.confirm(
                '没有绑定登录账号，这条成员档案不会对任何账号生效：' +
                    '本人登录后看不到工作台与刷题入口。\n\n' +
                    '如果只是先建档、稍后再绑定，可以继续；否则建议先选择要绑定的账号。',
                '未绑定登录账号',
                { type: 'warning', confirmButtonText: '仍然保存', cancelButtonText: '返回选择' },
            );
        } catch {
            return;
        }
    }
    /**
     * 未选账号时不带该字段：后端 @Positive 不接受空串，而「null = 不修改绑定」是编辑时的既有语义
     * ——留空不等于解绑
     */
    const payload: Record<string, unknown> = { ...form };
    if (!payload.userId) {
        delete payload.userId;
    }
    try {
        if (editing.value) {
            await adminApi.memberUpdate({ id: editing.value.id, ...payload });
        } else {
            await adminApi.memberAdd(payload as Partial<AdminMember>);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 错误提示已在请求层统一处理
    }
}

async function remove(row: AdminMember) {
    try {
        await ElMessageBox.confirm(`确认删除成员「${row.name}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.memberDelete(row.id);
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
    filters.name = '';
    filters.direction = '';
    filters.teamPosition = '';
    filters.memberStatus = '';
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

onMounted(() => {
    load();
    void loadUsers();
});

onBeforeUnmount(disposeUserSearch);
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="bar">
            <h2>成员管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增成员</el-button>
        </div>

        <div class="filters">
            <el-input
                v-model="filters.name"
                class="filter"
                placeholder="姓名"
                clearable
                maxlength="64"
                @keyup.enter="query"
            />
            <el-input
                v-model="filters.direction"
                class="filter"
                placeholder="技术方向"
                clearable
                maxlength="64"
                @keyup.enter="query"
            />
            <el-select v-model="filters.teamPosition" class="filter" placeholder="全部职务" clearable>
                <el-option label="成员" value="member" />
                <el-option label="队长" value="leader" />
                <el-option label="组长" value="tech_lead" />
            </el-select>
            <el-select v-model="filters.memberStatus" class="filter" placeholder="全部状态" clearable>
                <el-option label="在读 / 在队" :value="0" />
                <el-option label="毕业 / 离队" :value="1" />
            </el-select>
            <div class="ops">
                <el-button type="primary" @click="query">查询</el-button>
                <el-button @click="reset">重置</el-button>
            </div>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="name" label="姓名" min-width="120" />
            <el-table-column prop="gradeYear" label="届别" width="90" />
            <el-table-column prop="major" label="专业" min-width="120" />
            <el-table-column prop="direction" label="方向" min-width="120" />
            <el-table-column label="职务" width="100">
                <template #default="{ row }">
                    {{ POSITION_TEXT[row.teamPosition] || row.teamPosition }}
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.memberStatus === 1 ? 'info' : 'success'">
                        {{ row.memberStatus === 1 ? '毕业' : '在队' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="绑定账号" min-width="130">
                <template #default="{ row }">
                    <span v-if="row.userId" class="bound">{{ accountOf(row.userId) }}</span>
                    <span v-else class="muted">未绑定</span>
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

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑成员' : '新增成员'" width="560px">
            <el-form label-position="top">
                <el-form-item label="姓名" required>
                    <el-input v-model="form.name as string" />
                </el-form-item>
                <el-form-item label="头像">
                    <ImageUploader
                        v-model="form.avatar as string"
                        tip="成员头像（可选）；项目详情的「参与成员」卡片会用到"
                    />
                </el-form-item>
                <el-form-item label="届别">
                    <el-input-number v-model="form.gradeYear as number" :min="1990" :max="2100" />
                </el-form-item>
                <el-form-item label="专业"><el-input v-model="form.major as string" /></el-form-item>
                <el-form-item label="技术方向"><el-input v-model="form.direction as string" /></el-form-item>
                <el-form-item label="职务">
                    <el-select v-model="form.teamPosition as string">
                        <el-option label="成员" value="member" />
                        <el-option label="队长" value="leader" />
                        <el-option label="组长" value="tech_lead" />
                    </el-select>
                </el-form-item>
                <el-form-item label="绑定登录账号">
                    <el-select
                        v-model="form.userId as string"
                        :placeholder="editing ? '未绑定（留空表示不修改）' : '建议选择：不绑定则档案不生效'"
                        clearable
                        filterable
                        remote
                        :remote-method="searchUsers"
                        :loading="userSearching"
                        class="full"
                    >
                        <el-option
                            v-for="option in userOptions"
                            :key="option.id"
                            :label="
                                option.userName
                                    ? `${option.userAccount}（${option.userName}）`
                                    : option.userAccount
                            "
                            :value="option.id"
                        />
                    </el-select>
                    <div v-if="userTruncated" class="hint">
                        共 {{ userTotal }} 个账号，当前只显示前 {{ userOptions.length }} 个，可输入账号关键词搜索
                    </div>
                    <div class="hint">
                        绑定后，该账号登录即可使用工作台（考勤）与刷题。
                        <b v-if="!editing">不绑定的档案不会对任何账号生效。</b>
                        <b v-else>编辑时留空表示「不修改」现有绑定，不能用来解绑。</b>
                    </div>
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="form.memberStatus as number">
                        <el-option label="在读/在队" :value="0" />
                        <el-option label="毕业/离队" :value="1" />
                    </el-select>
                </el-form-item>
                <el-form-item label="GitHub"><el-input v-model="form.githubUrl as string" /></el-form-item>
                <el-form-item label="简介">
                    <el-input v-model="form.summary as string" type="textarea" :rows="3" />
                </el-form-item>
                <el-form-item label="置顶权重">
                    <el-input-number v-model="form.sortOrder as number" :min="0" :max="999" />
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
    width: 180px;
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

.full {
    width: 100%;
}

.hint {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: #6b7280;
}

/* 已绑定的账号用绿色，与「未绑定」的灰色形成对比 */
.bound {
    color: #059669;
}
</style>
