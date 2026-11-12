<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
    BOARD_STATUS_TEXT,
    boardApi,
    type BoardItem,
    type BoardOverview,
    type BoardStatus,
} from '@/api/board';
import ErrorRetry from '@/components/ErrorRetry.vue';
import SmartImage from '@/components/SmartImage.vue';
import { renderMarkdown, sanitizeMarkdownHtml } from '@/utils/markdown';

/** 工作室内部事项看板：待办 / 进行中 / 已完成三列，仅成员可见。成员准入由后端与导航共同保证 */
const loading = ref(false);
const error = ref('');
const overview = ref<BoardOverview | null>(null);
const detailVisible = ref(false);
const detailItem = ref<BoardItem | null>(null);
/** 正在流转的事项 ID：同一时刻只允许一个请求，避免连点把状态改两次 */
const changingId = ref<string | null>(null);

const columns = computed(() => [
    { key: 'todo', label: BOARD_STATUS_TEXT[0], items: overview.value?.todo ?? [] },
    { key: 'doing', label: BOARD_STATUS_TEXT[1], items: overview.value?.doing ?? [] },
    { key: 'done', label: BOARD_STATUS_TEXT[2], items: overview.value?.done ?? [] },
]);

async function load() {
    loading.value = true;
    error.value = '';
    try {
        overview.value = await boardApi.board();
    } catch (e) {
        // 请求层已统一提示，这里只把「为什么空白」说清楚
        error.value = e instanceof Error ? e.message : '事项看板加载失败';
    } finally {
        loading.value = false;
    }
}

onMounted(load);

function openDetail(item: BoardItem) {
    detailItem.value = item;
    detailVisible.value = true;
}

/** 清洗后的详情 HTML：与帖子详情同一套白名单，不直接 v-html 原文 */
const detailHtml = computed(() => {
    const source = detailItem.value?.description;
    if (!source) {
        return '';
    }
    return sanitizeMarkdownHtml(renderMarkdown(source).html, { restrictImages: true });
});

/**
 * 推进状态。带上页面看到的状态作为 expectedStatus，两个人同时改同一条时
 * 后提交的人会收到「状态已变更，请刷新」，而不是静默覆盖对方的结果。
 */
async function move(item: BoardItem, target: BoardStatus) {
    if (!item.canChangeStatus || changingId.value) {
        return;
    }
    changingId.value = item.id;
    try {
        await boardApi.changeStatus(item.id, target, item.status);
        ElMessage.success(`已移到「${BOARD_STATUS_TEXT[target]}」`);
        await load();
    } catch {
        // 请求层已提示；失败只刷新一次，让页面回到服务端的真实状态
        await load();
    } finally {
        changingId.value = null;
    }
}

/** 截止日期只展示到分钟；无截止单独标注，避免空白让人以为漏数据 */
function deadlineText(value?: string | null): string {
    return value ? value.slice(5, 16) : '无截止';
}
</script>

<template>
    <div class="page" v-loading="loading">
        <div class="page-head">
            <div>
                <h1>事项看板</h1>
                <p>工作室最近要做的事，按状态分列；仅工作室成员可见。</p>
            </div>
            <el-button @click="load">刷新</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <template v-if="overview">
            <div class="stats">
                <div class="stat">
                    <span>未完成</span>
                    <b>{{ overview.pendingCount }}</b>
                </div>
                <div class="stat" :class="{ warn: overview.overdueCount > 0 }">
                    <span>已逾期</span>
                    <b>{{ overview.overdueCount }}</b>
                </div>
                <div class="stat">
                    <span>我负责</span>
                    <b>{{ overview.myPendingCount }}</b>
                </div>
                <div class="stat">
                    <span>已完成</span>
                    <b>{{ overview.doneCount }}</b>
                </div>
            </div>

            <div class="kanban">
                <section v-for="column in columns" :key="column.key" class="column">
                    <header class="column-head">
                        <h2>{{ column.label }}</h2>
                        <span class="count">{{ column.items.length }}</span>
                    </header>

                    <div class="column-body">
                        <el-empty
                            v-if="!column.items.length"
                            :description="`暂无${column.label}事项`"
                            :image-size="64"
                        />
                        <article
                            v-for="item in column.items"
                            :key="item.id"
                            class="card"
                            :class="{ overdue: item.overdue }"
                            @click="openDetail(item)"
                        >
                            <div class="card-title">
                                <h3>{{ item.title }}</h3>
                                <el-tag v-if="item.overdue" type="danger" size="small" effect="plain">
                                    逾期
                                </el-tag>
                            </div>

                            <div class="card-meta">
                                <span class="assignee">
                                    <span class="avatar">
                                        <SmartImage
                                            :src="item.assigneeAvatar"
                                            :alt="item.assigneeName || '未指派'"
                                            :seed="item.assigneeMemberId || item.id"
                                            fallback="avatar"
                                        />
                                    </span>
                                    {{ item.assigneeName || '未指派' }}
                                </span>
                                <span class="deadline" :class="{ late: item.overdue }">
                                    截止 {{ deadlineText(item.deadline) }}
                                </span>
                            </div>

                            <!-- 点击按钮不冒泡，否则会连带打开详情抽屉 -->
                            <div class="card-actions" @click.stop>
                                <template v-if="item.canChangeStatus">
                                    <el-button
                                        v-if="item.status === 0"
                                        size="small"
                                        :loading="changingId === item.id"
                                        @click="move(item, 1)"
                                    >
                                        开始
                                    </el-button>
                                    <template v-else-if="item.status === 1">
                                        <el-button
                                            size="small"
                                            type="primary"
                                            :loading="changingId === item.id"
                                            @click="move(item, 2)"
                                        >
                                            完成
                                        </el-button>
                                        <el-button
                                            size="small"
                                            :loading="changingId === item.id"
                                            @click="move(item, 0)"
                                        >
                                            退回
                                        </el-button>
                                    </template>
                                    <el-button
                                        v-else
                                        size="small"
                                        :loading="changingId === item.id"
                                        @click="move(item, 1)"
                                    >
                                        重新打开
                                    </el-button>
                                </template>
                                <span v-else class="readonly-hint">仅负责人或管理员可推进</span>
                            </div>
                        </article>
                    </div>
                </section>
            </div>

            <p class="foot-hint">
                已完成列只展示最近 {{ overview.done?.length ?? 0 }} 条；历史完成数见顶部统计。
            </p>
        </template>

        <el-drawer v-model="detailVisible" :title="detailItem?.title || '事项详情'" size="46%">
            <template v-if="detailItem">
                <div class="detail-meta">
                    <span>状态：{{ detailItem.statusLabel }}</span>
                    <span>负责人：{{ detailItem.assigneeName || '未指派' }}</span>
                    <span>截止：{{ deadlineText(detailItem.deadline) }}</span>
                </div>
                <div v-if="detailHtml" class="markdown-body" v-html="detailHtml" />
                <el-empty v-else description="这条事项没有补充说明" :image-size="64" />
            </template>
        </el-drawer>
    </div>
</template>

<style scoped>
.page {
    max-width: 1240px;
    margin: 0 auto;
    padding: 28px 20px 48px;
}

.page-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
}

.page-head h1 {
    margin: 0 0 6px;
    font-size: 28px;
}

.page-head p {
    margin: 0;
    color: var(--muted, #6b7280);
    font-size: 14px;
}

.stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin-bottom: 20px;
}

.stat {
    padding: 14px 16px;
    border: 1px solid var(--border, #e5e7eb);
    border-radius: 10px;
    background: #fff;
}

.stat span {
    display: block;
    font-size: 12px;
    color: var(--muted, #6b7280);
}

.stat b {
    font-size: 24px;
}

.stat.warn b {
    color: #dc2626;
}

.kanban {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    align-items: start;
}

.column {
    border: 1px solid var(--border, #e5e7eb);
    border-radius: 10px;
    background: #f8fafc;
    overflow: hidden;
}

.column-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 14px;
    border-bottom: 1px solid var(--border, #e5e7eb);
    background: #fff;
}

.column-head h2 {
    margin: 0;
    font-size: 15px;
}

.count {
    min-width: 22px;
    padding: 1px 7px;
    border-radius: 999px;
    background: #eef2f7;
    color: #475569;
    font-size: 12px;
    text-align: center;
}

.column-body {
    padding: 12px;
    min-height: 160px;
}

.card {
    margin-bottom: 10px;
    padding: 12px 13px;
    border: 1px solid var(--border, #e5e7eb);
    border-left: 3px solid var(--brand, #2563eb);
    border-radius: 8px;
    background: #fff;
    cursor: pointer;
    transition: box-shadow 0.15s ease;
}

.card:hover {
    box-shadow: 0 4px 14px rgb(15 23 42 / 8%);
}

.card.overdue {
    border-left-color: #dc2626;
}

.card-title {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
}

.card-title h3 {
    margin: 0;
    font-size: 14px;
    line-height: 1.5;
    word-break: break-word;
}

.card-meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 10px;
    font-size: 12px;
    color: var(--muted, #6b7280);
}

.assignee {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}

.avatar {
    display: inline-flex;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
}

.deadline.late {
    color: #dc2626;
    font-weight: 600;
}

.card-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 10px;
}

.readonly-hint {
    font-size: 11px;
    color: #9ca3af;
}

.foot-hint {
    margin: 16px 0 0;
    font-size: 12px;
    color: #9ca3af;
}

.detail-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    padding-bottom: 14px;
    border-bottom: 1px solid var(--border, #e5e7eb);
    color: var(--muted, #6b7280);
    font-size: 13px;
}

.markdown-body {
    margin-top: 14px;
    line-height: 1.8;
}

@media (max-width: 900px) {
    .kanban {
        grid-template-columns: 1fr;
    }

    .stats {
        grid-template-columns: repeat(2, 1fr);
    }
}
</style>
