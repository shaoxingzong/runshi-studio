<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import {
    BOARD_STATUS_TEXT,
    boardApi,
    type BoardItem,
    type BoardOverview,
    type BoardStatus,
} from '@/api/board';
import { createRequestIdHolder } from '@/api/idempotency';
import type { AdminMember, PageResult } from '@/api/types';
import ErrorRetry from '@/components/ErrorRetry.vue';

/** 管理端事项看板：发布 / 编辑 / 指派 / 下线，并可按负责人查「谁还没交」 */
const page = reactive({ current: 1, pageSize: 10 });
const filters = reactive<{
    title: string;
    status: '' | BoardStatus;
    assigneeMemberId: string;
    onlyOverdue: boolean;
}>({ title: '', status: '', assigneeMemberId: '', onlyOverdue: false });

const result = ref<PageResult<BoardItem> | null>(null);
const overview = ref<BoardOverview | null>(null);
const loading = ref(false);
const error = ref('');
/** 保存中：两个弹窗共用，避免连点重复建档 / 重复指派 */
const saving = ref(false);
const changingId = ref<string | null>(null);

const dialogVisible = ref(false);
const editing = ref<BoardItem | null>(null);
const assignVisible = ref(false);
const assignTarget = ref<BoardItem | null>(null);
const assignValue = ref<string>('');

/** 负责人下拉：只取在队成员（后端按 memberStatus 过滤，前端再兜一层） */
const members = ref<AdminMember[]>([]);

/** 新建表单的一次性令牌：失败重试复用，成功后换新 */
const addRequestId = createRequestIdHolder();

const form = reactive({
    title: '',
    description: '',
    deadline: '',
    assigneeMemberId: '',
    status: 0 as BoardStatus,
    sortOrder: 0,
});

function buildQuery(overrides: Record<string, unknown> = {}) {
    return {
        current: page.current,
        pageSize: page.pageSize,
        title: filters.title.trim() || undefined,
        status: filters.status === '' ? undefined : filters.status,
        assigneeMemberId: filters.assigneeMemberId || undefined,
        onlyOverdue: filters.onlyOverdue || undefined,
        ...overrides,
    };
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await boardApi.page(buildQuery());
    } catch (e) {
        error.value = e instanceof Error ? e.message : '事项列表加载失败';
    } finally {
        loading.value = false;
    }
}

/** 顶部统计复用成员看板的聚合接口：管理员也能调，一次拿全量计数 */
async function loadStats() {
    try {
        overview.value = await boardApi.board();
    } catch {
        // 统计失败不影响主列表：卡片留空即可，不弹第二次错误提示
    }
}

async function loadMembers() {
    try {
        const res = await adminApi.memberPage({ current: 1, pageSize: 100, memberStatus: 0 });
        members.value = (res.records ?? []).filter(
            (member) => member.memberStatus === 0 || member.memberStatus == null,
        );
    } catch {
        members.value = [];
    }
}

onMounted(() => {
    void load();
    void loadStats();
    void loadMembers();
});

function resetForm() {
    form.title = '';
    form.description = '';
    form.deadline = '';
    form.assigneeMemberId = '';
    form.status = 0;
    form.sortOrder = 0;
}

function openCreate() {
    editing.value = null;
    resetForm();
    // 每次打开新表单都换令牌，避免上一次失败重试的令牌被新表单复用
    addRequestId.reset();
    dialogVisible.value = true;
}

function openEdit(item: BoardItem) {
    editing.value = item;
    form.title = item.title;
    form.description = item.description ?? '';
    form.deadline = item.deadline ?? '';
    form.status = item.status;
    form.sortOrder = item.sortOrder ?? 0;
    dialogVisible.value = true;
}

async function save() {
    // 守卫必须在所有校验之前：连点第二下会在按钮 loading 渲染前进入函数
    if (saving.value) {
        return;
    }
    if (!form.title.trim()) {
        ElMessage.warning('事项标题必填');
        return;
    }
    saving.value = true;
    try {
        if (editing.value) {
            await boardApi.update({
                id: editing.value.id,
                title: form.title.trim(),
                description: form.description,
                deadline: form.deadline || undefined,
                // 编辑时清空日期选择器 = 取消截止时间，后端需要显式哨兵才能区分「不修改」
                clearDeadline: !form.deadline,
                sortOrder: form.sortOrder,
            });
            ElMessage.success('已保存');
        } else {
            await boardApi.add({
                title: form.title.trim(),
                description: form.description,
                status: form.status,
                assigneeMemberId: form.assigneeMemberId || null,
                deadline: form.deadline || null,
                sortOrder: form.sortOrder,
                requestId: addRequestId.get(),
            });
            addRequestId.reset();
            ElMessage.success('已发布到成员看板');
        }
        dialogVisible.value = false;
        await load();
        await loadStats();
    } catch {
        // 请求层已统一提示；失败时保留弹窗与表单内容，用户可直接重试
    } finally {
        saving.value = false;
    }
}

function openAssign(item: BoardItem) {
    assignTarget.value = item;
    assignValue.value = item.assigneeMemberId ?? '';
    assignVisible.value = true;
}

async function submitAssign() {
    if (saving.value || !assignTarget.value) {
        return;
    }
    saving.value = true;
    try {
        await boardApi.assign(assignTarget.value.id, assignValue.value || null);
        ElMessage.success('已更新负责人');
        assignVisible.value = false;
        await load();
    } catch {
        // 请求层已提示
    } finally {
        saving.value = false;
    }
}

async function remove(item: BoardItem) {
    try {
        await ElMessageBox.confirm(
            `下线后事项不再出现在成员看板（数据仍可追溯）：「${item.title}」`,
            '确认下线',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    await boardApi.delete(item.id);
    ElMessage.success('已下线');
    await load();
    await loadStats();
}

async function move(item: BoardItem, target: BoardStatus) {
    if (changingId.value) {
        return;
    }
    changingId.value = item.id;
    try {
        await boardApi.changeStatus(item.id, target, item.status);
        await load();
        await loadStats();
    } catch {
        await load();
    } finally {
        changingId.value = null;
    }
}

function onSearch() {
    page.current = 1;
    void load();
}

function onPageChange(value: number) {
    page.current = value;
    void load();
}

function memberName(id?: string | null): string {
    if (!id) {
        return '未指派';
    }
    return members.value.find((member) => member.id === id)?.name ?? '成员已离队';
}

function deadlineText(value?: string | null): string {
    return value ? value.slice(0, 16) : '无截止';
}
</script>

<template>
    <div class="page" v-loading="loading">
        <div class="page-head">
            <div>
                <h1>事项看板</h1>
                <p>发布工作室最近要做的事，指派负责人并跟踪截止日期。</p>
            </div>
            <el-button type="primary" @click="openCreate">新增事项</el-button>
        </div>

        <div v-if="overview" class="stats">
            <div class="stat"><span>未完成</span><b>{{ overview.pendingCount }}</b></div>
            <div class="stat" :class="{ warn: overview.overdueCount > 0 }">
                <span>已逾期</span><b>{{ overview.overdueCount }}</b>
            </div>
            <div class="stat"><span>已完成</span><b>{{ overview.doneCount }}</b></div>
        </div>

        <div class="filters">
            <el-input
                v-model="filters.title"
                placeholder="按标题搜索"
                clearable
                class="filter-input"
                @keyup.enter="onSearch"
            />
            <el-select v-model="filters.status" placeholder="状态" clearable class="filter-select">
                <el-option label="待办" :value="0" />
                <el-option label="进行中" :value="1" />
                <el-option label="已完成" :value="2" />
            </el-select>
            <el-select
                v-model="filters.assigneeMemberId"
                placeholder="按负责人筛选"
                clearable
                filterable
                class="filter-select"
            >
                <el-option v-for="member in members" :key="member.id" :label="member.name" :value="member.id" />
            </el-select>
            <el-checkbox v-model="filters.onlyOverdue" @change="onSearch">只看逾期</el-checkbox>
            <el-button @click="onSearch">查询</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <template v-if="result">
            <el-table :data="result.records" size="large" border>
                <el-table-column prop="title" label="事项" min-width="180" show-overflow-tooltip />
                <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                        <el-tag :type="row.status === 2 ? 'success' : row.status === 1 ? 'primary' : 'info'" effect="plain">
                            {{ row.statusLabel || BOARD_STATUS_TEXT[row.status as BoardStatus] }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="负责人" width="130">
                    <template #default="{ row }">{{ memberName(row.assigneeMemberId) }}</template>
                </el-table-column>
                <el-table-column label="截止" width="160">
                    <template #default="{ row }">
                        <span :class="{ late: row.overdue }">{{ deadlineText(row.deadline) }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="逾期" width="80">
                    <template #default="{ row }">
                        <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">是</el-tag>
                        <span v-else>—</span>
                    </template>
                </el-table-column>
                <el-table-column label="操作" min-width="280">
                    <template #default="{ row }">
                        <div class="row-actions">
                            <el-button
                                v-if="row.status !== 2"
                                link
                                size="small"
                                :loading="changingId === row.id"
                                @click="move(row, (row.status + 1) as BoardStatus)"
                            >
                                {{ row.status === 0 ? '开始' : '完成' }}
                            </el-button>
                            <el-button
                                v-else
                                link
                                size="small"
                                :loading="changingId === row.id"
                                @click="move(row, 1)"
                            >
                                重新打开
                            </el-button>
                            <el-button link size="small" @click="openAssign(row)">指派</el-button>
                            <el-button link size="small" @click="openEdit(row)">编辑</el-button>
                            <el-button link size="small" type="danger" @click="remove(row)">下线</el-button>
                        </div>
                    </template>
                </el-table-column>
            </el-table>

            <el-pagination
                class="pager"
                background
                layout="total, prev, pager, next"
                :current-page="page.current"
                :page-size="page.pageSize"
                :total="Number(result.total)"
                @current-change="onPageChange"
            />
        </template>

        <el-dialog
            v-model="dialogVisible"
            :title="editing ? '编辑事项' : '新增事项'"
            width="560px"
        >
            <el-form label-width="96px">
                <el-form-item label="标题" required>
                    <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="一句话说清要做什么" />
                </el-form-item>
                <el-form-item label="说明">
                    <el-input
                        v-model="form.description"
                        type="textarea"
                        :rows="4"
                        maxlength="4000"
                        show-word-limit
                        placeholder="支持 Markdown；可留空"
                    />
                </el-form-item>
                <el-form-item label="截止时间">
                    <el-date-picker
                        v-model="form.deadline"
                        type="datetime"
                        value-format="YYYY-MM-DD HH:mm:ss"
                        placeholder="留空表示不设截止"
                        class="full"
                    />
                    <div class="hint">过期只会高亮提醒，不会自动把事项改成已完成。</div>
                </el-form-item>
                <el-form-item v-if="!editing" label="负责人">
                    <el-select v-model="form.assigneeMemberId" clearable filterable placeholder="可不指派" class="full">
                        <el-option v-for="member in members" :key="member.id" :label="member.name" :value="member.id" />
                    </el-select>
                </el-form-item>
                <el-form-item v-if="!editing" label="初始状态">
                    <el-select v-model="form.status" class="full">
                        <el-option label="待办" :value="0" />
                        <el-option label="进行中" :value="1" />
                        <el-option label="已完成" :value="2" />
                    </el-select>
                </el-form-item>
                <el-form-item label="展示权重">
                    <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
                    <div class="hint">同一列里数值越大越靠前。</div>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" :loading="saving" :disabled="saving" @click="save">
                    保存
                </el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="assignVisible" title="指派负责人" width="420px">
            <p class="hint">{{ assignTarget?.title }}</p>
            <el-select v-model="assignValue" clearable filterable placeholder="选择成员，清空表示取消指派" class="full">
                <el-option v-for="member in members" :key="member.id" :label="member.name" :value="member.id" />
            </el-select>
            <template #footer>
                <el-button @click="assignVisible = false">取消</el-button>
                <el-button type="primary" :loading="saving" :disabled="saving" @click="submitAssign">
                    确定
                </el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.page {
    padding: 4px 0 32px;
}

.page-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
}

.page-head h1 {
    margin: 0 0 6px;
    font-size: 24px;
}

.page-head p {
    margin: 0;
    color: var(--muted, #6b7280);
    font-size: 13px;
}

.stats {
    display: flex;
    gap: 12px;
    margin-bottom: 18px;
}

.stat {
    min-width: 120px;
    padding: 12px 16px;
    border: 1px solid var(--border, #e5e7eb);
    border-radius: 8px;
    background: #fff;
}

.stat span {
    display: block;
    font-size: 12px;
    color: var(--muted, #6b7280);
}

.stat b {
    font-size: 22px;
}

.stat.warn b {
    color: #dc2626;
}

.filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
}

.filter-input {
    width: 220px;
}

.filter-select {
    width: 180px;
}

.row-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
}

.late {
    color: #dc2626;
    font-weight: 600;
}

.pager {
    margin-top: 16px;
    justify-content: flex-end;
}

.full {
    width: 100%;
}

.hint {
    margin: 4px 0 0;
    font-size: 12px;
    color: #9ca3af;
}
</style>
