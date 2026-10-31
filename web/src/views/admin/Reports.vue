<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { reportApi } from '@/api/report';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { PageResult, ReportTicket } from '@/api/types';

/** 举报处理（管理员） */
const page = reactive({ current: 1, pageSize: 10 });

/** 默认只看待处理：举报页的首要任务是清空队列 */
const filters = reactive<{ status: number; targetType: string }>({ status: 0, targetType: '' });

const result = ref<PageResult<ReportTicket> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常） */
const error = ref('');

const STATUS_OPTIONS = [
    { value: 0, label: '待处理' },
    { value: 1, label: '已处置' },
    { value: 2, label: '举报不成立' },
];

const TARGET_OPTIONS = [
    { value: '', label: '全部对象' },
    { value: 'post', label: '帖子' },
    { value: 'comment', label: '评论' },
];

/** 状态标签配色：待处理用 warning 强调，已处置绿色，不成立灰色 */
const STATUS_TAG: Record<number, { type: 'warning' | 'success' | 'info'; text: string }> = {
    0: { type: 'warning', text: '待处理' },
    1: { type: 'success', text: '已处置' },
    2: { type: 'info', text: '举报不成立' },
};

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await reportApi.adminList({
            current: page.current,
            pageSize: page.pageSize,
            status: filters.status,
            // 空串不下发：传空串等于给后端一个「查空类型」的歧义
            targetType: filters.targetType || undefined,
        });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/** 换条件回第一页：否则可能停在已不存在的页码上，看到空列表还以为没数据 */
function query() {
    page.current = 1;
    load();
}

function reset() {
    filters.status = 0;
    filters.targetType = '';
    query();
}

function turn(delta: number) {
    const pages = result.value ? Number(result.value.pages) : 1;
    const next = page.current + delta;
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    load();
}

/**
 * 处置一条举报
 *
 * @param action resolve=撤下 / delete=删除 / dismiss=不成立
 */
async function handle(row: ReportTicket, action: 'resolve' | 'delete' | 'dismiss') {
    const label = action === 'resolve' ? '撤下内容' : action === 'delete' ? '删除内容' : '标记不成立';
    const tip =
        action === 'delete'
            ? `确认删除这条${row.targetTypeLabel}？删除不可恢复，且会连带清理其下内容。`
            : action === 'resolve'
              ? `确认撤下这条${row.targetTypeLabel}？作者会看到「举报受理」的驳回理由。`
              : '确认举报不成立？内容会保留，举报将被关闭。';

    let handleResult: string | undefined;
    try {
        const prompted = await ElMessageBox.prompt(tip, label, {
            type: action === 'dismiss' ? 'info' : 'warning',
            confirmButtonText: label,
            cancelButtonText: '取消',
            inputPlaceholder: '处置说明（选填，仅内部复盘可见）',
            inputValue: '',
            inputValidator: (value) => (value && value.length > 256 ? '不能超过 256 字' : true),
        });
        handleResult = String(prompted.value ?? '').trim() || undefined;
    } catch {
        // 用户取消：正常操作，不是错误
        return;
    }

    loading.value = true;
    try {
        await reportApi.handle({ id: row.id, action, handleResult });
        ElMessage.success(
            action === 'dismiss' ? '已标记为不成立' : action === 'delete' ? '内容已删除' : '内容已撤下',
        );
        await load();
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div v-loading="loading">
        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div class="bar">
            <div>
                <h2>举报处理</h2>
                <span class="muted">默认只看待处理；处置结果会通知举报人并记入操作日志</span>
            </div>
            <span v-if="result" class="muted">共 {{ result.total }} 条</span>
        </div>

        <div class="filters">
            <el-select v-model="filters.status" class="filter">
                <el-option
                    v-for="option in STATUS_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                />
            </el-select>
            <el-select v-model="filters.targetType" class="filter">
                <el-option
                    v-for="option in TARGET_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                />
            </el-select>
            <div class="ops">
                <el-button type="primary" @click="query">查询</el-button>
                <el-button @click="reset">重置</el-button>
            </div>
        </div>

        <el-empty v-if="result && result.records.length === 0" description="没有符合条件的举报" />

        <template v-else>
            <el-table :data="result?.records ?? []" row-key="id">
                <el-table-column label="时间" width="150">
                    <template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 16) }}</template>
                </el-table-column>

                <el-table-column label="举报人" width="140">
                    <template #default="{ row }">{{ row.reporterName || '用户' }}</template>
                </el-table-column>

                <el-table-column label="对象" min-width="260">
                    <template #default="{ row }">
                        <div class="target">
                            <el-tag size="small" effect="plain">{{ row.targetTypeLabel }}</el-tag>
                            <span class="target-title">{{ row.targetTitle || '内容已删除' }}</span>
                        </div>
                        <div class="target-id">ID: {{ row.targetId }}</div>
                    </template>
                </el-table-column>

                <el-table-column label="举报理由" min-width="240">
                    <template #default="{ row }">
                        <el-tooltip :content="row.reason" placement="top" :show-after="300">
                            <span class="reason">{{ row.reason }}</span>
                        </el-tooltip>
                    </template>
                </el-table-column>

                <el-table-column label="状态" width="110">
                    <template #default="{ row }">
                        <el-tag :type="STATUS_TAG[row.status]?.type ?? 'info'" size="small">
                            {{ STATUS_TAG[row.status]?.text ?? row.statusLabel }}
                        </el-tag>
                    </template>
                </el-table-column>

                <el-table-column label="处置" width="180">
                    <template #default="{ row }">
                        <div v-if="row.status !== 0" class="handled">
                            <div>{{ row.handlerName || '管理员' }}</div>
                            <div class="handled-at">
                                {{ row.handleAt?.replace('T', ' ').slice(0, 16) || '—' }}
                            </div>
                        </div>
                        <span v-else class="muted">—</span>
                    </template>
                </el-table-column>

                <el-table-column label="操作" width="220" fixed="right">
                    <template #default="{ row }">
                        <template v-if="row.status === 0">
                            <el-button link type="warning" @click="handle(row, 'resolve')">撤下</el-button>
                            <el-button link type="danger" @click="handle(row, 'delete')">删除</el-button>
                            <el-button link @click="handle(row, 'dismiss')">不成立</el-button>
                        </template>
                        <el-tooltip v-else :content="row.handleResult || '已处置'" placement="top">
                            <span class="muted">已处理</span>
                        </el-tooltip>
                    </template>
                </el-table-column>
            </el-table>

            <div class="pager">
                <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted">
                    第 {{ page.current }} / {{ result?.pages }} 页 · 共 {{ result?.total }} 条
                </span>
                <el-button :disabled="page.current >= Number(result?.pages ?? 1)" @click="turn(1)">
                    下一页
                </el-button>
            </div>
        </template>
    </div>
</template>

<style scoped>
.bar {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
}

h2 {
    margin: 0 0 4px;
    font-size: 18px;
    font-weight: 500;
}

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
    width: 160px;
}

.ops {
    display: flex;
    gap: 8px;
    margin-left: auto;
}

.target {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

.target-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.target-id,
.handled-at {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
}

.reason {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    font-size: 13px;
    color: #4b5563;
    word-break: break-all;
}

.handled {
    font-size: 13px;
    color: #4b5563;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}
</style>
