<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { logApi } from '@/api/log';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { OperationActionOption, OperationLog, PageResult } from '@/api/types';

/** 操作日志（管理员） */
const router = useRouter();

const page = reactive({ current: 1, pageSize: 10 });

/** 时间范围：两个元素的字符串数组，null 表示不限 */
const range = ref<[string, string] | null>(null);

const filters = reactive({ action: '', targetType: '', operatorKeyword: '' });

const result = ref<PageResult<OperationLog> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

/** 动作字典（后端派生，含对象类型标签） */
const actions = ref<OperationActionOption[]>([]);

/** 对象类型选项：从动作字典里去重得到，避免再维护一份枚举 */
const targetTypes = computed(() => {
    const seen = new Map<string, string>();
    for (const item of actions.value) {
        if (!seen.has(item.targetType)) {
            seen.set(item.targetType, item.targetTypeLabel);
        }
    }
    return Array.from(seen, ([value, label]) => ({ value, label }));
});

/** 按对象类型分组的下拉项：动作太多时平铺一列会很难找 */
const groupedActions = computed(() => {
    const groups = new Map<string, { label: string; options: OperationActionOption[] }>();
    for (const item of actions.value) {
        if (!groups.has(item.targetType)) {
            groups.set(item.targetType, { label: item.targetTypeLabel, options: [] });
        }
        groups.get(item.targetType)?.options.push(item);
    }
    return Array.from(groups.values());
});

/** 动作标签配色：按语义分档，不逐个动作写映射 */
function actionTagType(action: string): 'success' | 'danger' | 'warning' | 'primary' | 'info' {
    if (action.endsWith('_APPROVE')) {
        return 'success';
    }
    if (action.endsWith('_REJECT') || action.endsWith('_DELETE')) {
        return 'danger';
    }
    if (action.endsWith('_ADD') || action.endsWith('_IMPORT')) {
        return 'primary';
    }
    if (action.endsWith('_UPDATE') || action.endsWith('_PIN')
        || action.endsWith('_STATUS') || action.endsWith('_RENAME') || action.endsWith('_MOVE')) {
        return 'warning';
    }
    return 'info';
}

/** 对象类型 → 对应管理页：点标题能直接跳到处置现场 */
const TARGET_ROUTE: Record<string, string> = {
    POST: '/admin/post-manage',
    COMMENT: '/admin/post-comments',
    USER: '/admin/users',
    QUIZ: '/admin/quiz',
    REPORT: '/admin/reports',
};

/** 时间展示：ISO 串的 T 换成空格并截到分钟（日志排查不需要秒以下的精度，何况本来也只到秒） */
function formatTime(value?: string | null): string {
    return value ? value.replace('T', ' ').slice(0, 16) : '—';
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await logApi.list({
            current: page.current,
            pageSize: page.pageSize,
            startTime: range.value?.[0],
            endTime: range.value?.[1],
            // 空串不下发：传空串等于给后端一个「查空动作码」的歧义
            action: filters.action || undefined,
            targetType: filters.targetType || undefined,
            operatorKeyword: filters.operatorKeyword.trim() || undefined,
        });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/** 换条件必须回到第一页：否则可能停在一个已不存在的页码上，看到空列表还以为没数据 */
function query() {
    page.current = 1;
    load();
}

function reset() {
    range.value = null;
    filters.action = '';
    filters.targetType = '';
    filters.operatorKeyword = '';
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

function openTarget(row: OperationLog) {
    const path = TARGET_ROUTE[row.targetType];
    if (path) {
        router.push(path);
    }
}

// 细节弹窗

const detailVisible = ref(false);
const detailRow = ref<OperationLog | null>(null);

function showDetail(row: OperationLog) {
    detailRow.value = row;
    detailVisible.value = true;
}

onMounted(async () => {
    try {
        actions.value = await logApi.actions();
    } catch {
        actions.value = [];
    }
    await load();
});
</script>

<template>
    <div v-loading="loading">
        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div class="bar">
            <div>
                <h2>操作日志</h2>
                <span class="muted">管理员每一次处置都会留痕，按时间倒序</span>
            </div>
            <span v-if="result" class="muted">共 {{ result.total }} 条</span>
        </div>

        <div class="filters">
            <el-date-picker
                v-model="range"
                class="range"
                type="datetimerange"
                value-format="YYYY-MM-DD HH:mm:ss"
                range-separator="至"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
            />
            <el-select v-model="filters.action" class="filter" placeholder="全部动作" clearable>
                <el-option-group
                    v-for="group in groupedActions"
                    :key="group.label"
                    :label="group.label"
                >
                    <el-option
                        v-for="option in group.options"
                        :key="option.code"
                        :label="option.label"
                        :value="option.code"
                    />
                </el-option-group>
            </el-select>
            <el-select v-model="filters.targetType" class="filter" placeholder="全部对象" clearable>
                <el-option
                    v-for="option in targetTypes"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                />
            </el-select>
            <el-input
                v-model="filters.operatorKeyword"
                class="filter"
                placeholder="操作人（账号 / 昵称）"
                clearable
                maxlength="64"
                @keyup.enter="query"
            />
            <div class="ops">
                <el-button type="primary" @click="query">查询</el-button>
                <el-button @click="reset">重置</el-button>
            </div>
        </div>

        <el-empty v-if="result && result.records.length === 0" description="还没有操作记录" />

        <template v-else>
            <el-table :data="result?.records ?? []" row-key="id">
                <el-table-column label="时间" width="150">
                    <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
                </el-table-column>

                <el-table-column label="操作人" width="150">
                    <template #default="{ row }">
                        <div class="operator">{{ row.operatorName || row.operatorAccount || '系统' }}</div>
                        <div v-if="row.operatorName && row.operatorAccount" class="account">
                            {{ row.operatorAccount }}
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="动作" width="170">
                    <template #default="{ row }">
                        <el-tag :type="actionTagType(row.action)" size="small" effect="light">
                            {{ row.actionLabel }}
                        </el-tag>
                    </template>
                </el-table-column>

                <el-table-column label="对象" min-width="240">
                    <template #default="{ row }">
                        <div class="target">
                            <el-tag size="small" effect="plain">{{ row.targetTypeLabel }}</el-tag>
                            <el-button
                                v-if="row.targetTitle"
                                link
                                type="primary"
                                class="target-title"
                                @click="openTarget(row)"
                            >
                                {{ row.targetTitle }}
                            </el-button>
                            <span v-else class="muted">—</span>
                        </div>
                        <div v-if="row.targetId" class="target-id">ID: {{ row.targetId }}</div>
                    </template>
                </el-table-column>

                <el-table-column label="细节" min-width="240">
                    <template #default="{ row }">
                        <div class="detail">
                            <span class="detail-text">{{ row.detail || '—' }}</span>
                            <el-button v-if="row.detail" link type="primary" @click="showDetail(row)">
                                查看
                            </el-button>
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="IP" width="140">
                    <template #default="{ row }">{{ row.ip || '—' }}</template>
                </el-table-column>
            </el-table>

            <div class="pager">
                <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted">
                    第 {{ page.current }} / {{ result?.pages }} 页 · 共 {{ result?.total }} 条
                </span>
                <el-button
                    :disabled="page.current >= Number(result?.pages ?? 1)"
                    @click="turn(1)"
                >
                    下一页
                </el-button>
            </div>
        </template>

        <el-dialog v-model="detailVisible" title="处置细节" width="560px">
            <template v-if="detailRow">
                <el-descriptions :column="1" border size="small">
                    <el-descriptions-item label="动作">{{ detailRow.actionLabel }}</el-descriptions-item>
                    <el-descriptions-item label="操作人">
                        {{ detailRow.operatorName || '—' }}
                        <span class="muted">（{{ detailRow.operatorAccount || '系统' }}）</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="对象">
                        {{ detailRow.targetTypeLabel }} / {{ detailRow.targetTitle || '—' }}
                    </el-descriptions-item>
                    <el-descriptions-item label="时间">{{ formatTime(detailRow.createdAt) }}</el-descriptions-item>
                    <el-descriptions-item label="IP">{{ detailRow.ip || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="细节">
                        <div class="detail-full">{{ detailRow.detail || '—' }}</div>
                    </el-descriptions-item>
                </el-descriptions>
            </template>
            <template #footer>
                <el-button @click="detailVisible = false">关闭</el-button>
            </template>
        </el-dialog>
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

.range {
    width: 380px;
}

.filter {
    width: 180px;
}

.ops {
    display: flex;
    gap: 8px;
    margin-left: auto;
}

.operator {
    font-weight: 500;
}

.account,
.target-id {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
}

.target {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

.target-title {
    max-width: 200px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.detail {
    display: flex;
    align-items: flex-start;
    gap: 8px;
}

.detail-text {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    font-size: 13px;
    color: #4b5563;
    word-break: break-all;
}

.detail-full {
    white-space: pre-wrap;
    word-break: break-all;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}
</style>
