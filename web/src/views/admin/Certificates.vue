<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import { relationApi } from '@/api/relation';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ImageUploader from '@/components/ImageUploader.vue';
import type { AdminCertificate, AdminMember, Member, PageResult } from '@/api/types';

/** 等级 / 类型的中文映射：后端存英文枚举，表格必须转中文（与官网荣誉墙保持一致） */
const LEVEL_TEXT: Record<string, string> = { national: '国家级', provincial: '省级', municipal: '市级/校级' };
const TYPE_TEXT: Record<string, string> = {
    competition: '学科竞赛',
    soft_copyright: '软件著作权',
    patent: '专利',
    paper: '论文',
};

/** 证书管理（等级与类型是闭集枚举，后端会校验非法值 → A0401） */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<AdminCertificate> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

const dialogVisible = ref(false);
const editing = ref<AdminCertificate | null>(null);
const form = reactive<Record<string, unknown>>({
    title: '',
    awardLevel: 'national',
    awardType: 'competition',
    awardDate: '',
    imageUrl: '',
    sortOrder: 0,
});

// ==================== 获奖成员（关联维护） ====================

/** 已绑定的获奖成员 */
const certMembers = ref<AdminMember[]>([]);
/** 候选成员 */
const memberOptions = ref<Member[]>([]);
const memberLoading = ref(false);
const pickedMember = ref('');

async function loadMembers(certificateId: string) {
    memberLoading.value = true;
    try {
        const [bound, options] = await Promise.all([
            relationApi.certificateMemberList(certificateId),
            adminApi.memberPage({ current: 1, pageSize: 100 }),
        ]);
        certMembers.value = bound ?? [];
        memberOptions.value = options.records ?? [];
    } catch {
        certMembers.value = [];
    } finally {
        memberLoading.value = false;
    }
}

async function addMember() {
    if (!pickedMember.value || !editing.value) {
        return;
    }
    try {
        await relationApi.certificateMemberBind(pickedMember.value, editing.value.id);
        ElMessage.success('已绑定获奖成员');
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
    try {
        await relationApi.certificateMemberUnbind(memberId, editing.value.id);
        await loadMembers(editing.value.id);
    } catch {
        // 提示已在请求层
    }
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.certificatePage({ ...page });
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
        awardLevel: 'national',
        awardType: 'competition',
        awardDate: '',
        imageUrl: '',
        sortOrder: 0,
    });
    // 新增态没有证书 ID，绑不了成员：先保存，再打开编辑即可
    certMembers.value = [];
    dialogVisible.value = true;
}

function openEdit(row: AdminCertificate) {
    editing.value = row;
    Object.assign(form, {
        title: row.title,
        awardLevel: row.awardLevel ?? 'national',
        awardType: row.awardType ?? 'competition',
        awardDate: row.awardDate ?? '',
        imageUrl: row.imageUrl ?? '',
        sortOrder: row.sortOrder ?? 0,
    });
    dialogVisible.value = true;
    void loadMembers(row.id);
}

async function save() {
    if (!form.title) {
        ElMessage.warning('证书名称必填');
        return;
    }
    try {
        if (editing.value) {
            await adminApi.certificateUpdate({ id: editing.value.id, ...form });
        } else {
            await adminApi.certificateAdd(form as never);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 统一提示已在请求层
    }
}

async function remove(row: AdminCertificate) {
    try {
        await ElMessageBox.confirm(`确认删除证书「${row.title}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.certificateDelete(row.id);
        ElMessage.success('已删除');
        await load();
    } catch {
        // 同上
    }
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
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="bar">
            <h2>证书管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增证书</el-button>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="title" label="名称" min-width="220" />
            <el-table-column label="图片" width="90">
                <template #default="{ row }">
                    <el-image
                        v-if="row.imageUrl"
                        :src="row.imageUrl"
                        :preview-src-list="[row.imageUrl]"
                        preview-teleported
                        fit="cover"
                        class="thumb"
                    />
                    <span v-else class="muted">—</span>
                </template>
            </el-table-column>
            <el-table-column label="级别" width="110">
                <template #default="{ row }">
                    <el-tag :type="row.awardLevel === 'national' ? 'danger' : 'info'" effect="light">
                        {{ LEVEL_TEXT[row.awardLevel] || row.awardLevel }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="类型" width="130">
                <template #default="{ row }">{{ TYPE_TEXT[row.awardType] || row.awardType }}</template>
            </el-table-column>
            <el-table-column prop="awardDate" label="日期" width="120" />
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

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑证书' : '新增证书'" width="560px">
            <el-form label-position="top">
                <el-form-item label="证书名称" required><el-input v-model="form.title as string" /></el-form-item>
                <el-form-item label="级别">
                    <el-select v-model="form.awardLevel as string">
                        <el-option label="国家级" value="national" />
                        <el-option label="省级" value="provincial" />
                        <el-option label="市级/校级" value="municipal" />
                    </el-select>
                </el-form-item>
                <el-form-item label="类型">
                    <el-select v-model="form.awardType as string">
                        <el-option label="学科竞赛" value="competition" />
                        <el-option label="软件著作权" value="soft_copyright" />
                        <el-option label="专利" value="patent" />
                        <el-option label="论文" value="paper" />
                    </el-select>
                </el-form-item>
                <el-form-item label="获奖日期">
                    <el-date-picker
                        v-model="form.awardDate as string"
                        type="date"
                        value-format="YYYY-MM-DD"
                        placeholder="选择日期"
                    />
                </el-form-item>
                <el-form-item label="证书图片">
                    <ImageUploader
                        v-model="form.imageUrl as string"
                        tip="仅支持 PNG / JPEG；上传后自动写入站内路径，游客也能查看"
                    />
                </el-form-item>
                <el-form-item label="置顶权重">
                    <el-input-number v-model="form.sortOrder as number" :min="0" :max="999" />
                </el-form-item>

                <!-- 获奖成员：绑定关系依赖证书 ID，所以只在编辑态出现。
                     与项目不同，证书没有「队长」这类不可移除的成员 -->
                <el-form-item v-if="editing" label="获奖成员">
                    <div v-loading="memberLoading" class="members">
                        <div v-if="!certMembers.length" class="muted">暂无获奖成员</div>
                        <div v-for="member in certMembers" :key="member.id" class="member-row">
                            <span class="member-name">{{ member.name }}</span>
                            <el-button link type="danger" @click="removeMember(member.id)">移除</el-button>
                        </div>
                        <div class="member-add">
                            <el-select
                                v-model="pickedMember"
                                placeholder="选择要绑定的成员"
                                filterable
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
                                绑定
                            </el-button>
                        </div>
                    </div>
                </el-form-item>
                <el-form-item v-else>
                    <el-alert
                        type="info"
                        :closable="false"
                        show-icon
                        title="保存之后即可绑定获奖成员（绑定关系需要证书 ID）。"
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

.thumb {
    width: 46px;
    height: 34px;
    border-radius: 4px;
    display: block;
}
</style>
