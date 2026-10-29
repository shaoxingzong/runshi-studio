<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import { bindCodeCopy, renderMarkdown, sanitizeMarkdownHtml } from '@/utils/markdown';
import type { PageResult, PostAdminDetail, PostAudit } from '@/api/types';

/**
 * 帖子待审队列（管理员）
 *
 * 队列里绝大多数是「AI 拿不准」的：AI 判安全已自动通过、判违规已自动驳回，都不会出现在这里。
 * 管理员只需处理「灰色」和「AI 不可用」两类，这正是这套机制减负的地方。
 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<PostAudit> | null>(null);
const selected = ref<string[]>([]);
const loading = ref(false);
/** 队列加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

/** AI 初判结论：0-未判, 1-安全, 2-违规, 3-灰色待人工, 4-判定失败 */
const AI_TAG: Record<number, { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
    0: { text: '待 AI 初判', type: 'info' },
    1: { text: 'AI 判安全', type: 'success' },
    2: { text: 'AI 判违规', type: 'danger' },
    // 3 与 4 才是队列里的常客，用 warning / danger 强调，便于优先处理
    3: { text: 'AI 需人工复核', type: 'warning' },
    4: { text: 'AI 不可用', type: 'danger' },
};

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await postApi.pending({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function onSelectionChange(rows: PostAudit[]) {
    selected.value = rows.map((row) => row.id);
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
 * 批量处置
 *
 * 驳回必须填理由：后端强校验，且理由要给作者看——没理由等于让作者猜。
 */
async function audit(approved: boolean) {
    if (selected.value.length === 0) {
        ElMessage.warning('请先勾选要处理的帖子');
        return;
    }

    let rejectReason = '';
    if (!approved) {
        try {
            const prompted = await ElMessageBox.prompt(
                '驳回理由会展示给作者，请说明需要修改的地方',
                '批量驳回',
                { inputValidator: (value) => (value?.trim() ? true : '驳回理由不能为空') },
            );
            rejectReason = String(prompted.value ?? '').trim();
        } catch {
            // 用户取消：这是正常操作，不是错误
            return;
        }
    }

    loading.value = true;
    try {
        const rows = await postApi.audit({
            ids: selected.value,
            action: approved ? 'approve' : 'reject',
            rejectReason: rejectReason || undefined,
        });
        ElMessage.success(`已处理 ${rows} 条`);
        page.current = 1;
        await load();
    } finally {
        loading.value = false;
    }
}

// ==================== 正文抽屉 ====================

const drawerVisible = ref(false);
const detailLoading = ref(false);
/** 正文加载失败的原因（空 = 正常）。失败时给原因 + 重试，不留一个空白抽屉 */
const detailError = ref('');
const detail = ref<PostAdminDetail | null>(null);
const detailId = ref('');
/** 正文容器：代码块复制要在真实 DOM 上挂事件委托（v-html 的内容 Vue 管不着） */
const bodyRef = ref<HTMLElement | null>(null);
/** 解绑函数：抽屉重开 / 关闭都要摘掉旧监听，否则打开几次就叠加几层 */
let unbindCopy: (() => void) | null = null;

/**
 * 正文渲染：与门户帖子详情完全同一条链路（renderMarkdown → 白名单清洗 → v-html）
 *
 * 管理端也不能免检：待审内容恰恰是最不可信的输入。图片只放行站内 /uploads/——
 * 外链图可当追踪像素用，管理员点开一篇稿子就等于给对方报了一次「谁在看」。
 */
const detailHtml = computed(() => {
    const rendered = renderMarkdown(detail.value?.content ?? '');
    return sanitizeMarkdownHtml(rendered.html, { restrictImages: true });
});

function bindEnhancements() {
    unbindCopy?.();
    unbindCopy = bodyRef.value ? bindCodeCopy(bodyRef.value) : null;
}

async function loadDetail() {
    if (!detailId.value) {
        return;
    }
    detailLoading.value = true;
    detailError.value = '';
    detail.value = null;
    try {
        detail.value = await postApi.adminDetail(detailId.value);
        // v-html 的内容要等 DOM 真正更新后才拿得到节点
        await nextTick();
        bindEnhancements();
    } catch (e) {
        detailError.value = (e as Error).message || '正文加载失败';
    } finally {
        detailLoading.value = false;
    }
}

function openDetail(row: PostAudit) {
    detailId.value = row.id;
    drawerVisible.value = true;
    void loadDetail();
}

// 关闭时摘掉事件委托：容器节点已销毁，留着监听只会随打开次数累积
watch(drawerVisible, (visible) => {
    if (!visible) {
        unbindCopy?.();
        unbindCopy = null;
    }
});

/**
 * 抽屉内单篇处置
 *
 * 与批量审核共用同一个接口与同一套提示：单篇只是 ids 长度为 1。
 * 另写一套实现，必然会在「驳回理由必填」这类规则上先分叉。
 */
async function auditOne(approved: boolean) {
    if (!detail.value) {
        return;
    }

    let rejectReason = '';
    if (!approved) {
        try {
            const prompted = await ElMessageBox.prompt(
                '驳回理由会展示给作者，请说明需要修改的地方',
                '驳回帖子',
                { inputValidator: (value) => (value?.trim() ? true : '驳回理由不能为空') },
            );
            rejectReason = String(prompted.value ?? '').trim();
        } catch {
            // 用户取消：正常操作，不是错误
            return;
        }
    }

    loading.value = true;
    try {
        const rows = await postApi.audit({
            ids: [detail.value.id],
            action: approved ? 'approve' : 'reject',
            rejectReason: rejectReason || undefined,
        });
        ElMessage.success(`已处理 ${rows} 条`);
        drawerVisible.value = false;
        page.current = 1;
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
                <h2>帖子审核</h2>
                <span class="muted">仅待审，按提交时间先进先出</span>
            </div>
            <div class="actions">
                <span v-if="selected.length" class="muted">已选 {{ selected.length }} 条</span>
                <el-button type="success" :disabled="!selected.length" @click="audit(true)">
                    批量通过
                </el-button>
                <el-button type="danger" :disabled="!selected.length" @click="audit(false)">
                    批量驳回
                </el-button>
            </div>
        </div>

        <el-empty v-if="result && result.records.length === 0" description="待审队列已清空" />

        <template v-else>
            <el-table
                :data="result?.records ?? []"
                row-key="id"
                @selection-change="onSelectionChange"
            >
                <el-table-column type="selection" width="50" />

                <el-table-column label="标题" min-width="260">
                    <template #default="{ row }">
                        <div class="title-cell">
                            <img v-if="row.coverImage" :src="row.coverImage" :alt="row.title" class="thumb" />
                            <div class="title-main">
                                <div class="title">
                                    <el-tag v-if="row.pinned === 1" size="small" type="warning" effect="plain">
                                        置顶
                                    </el-tag>
                                    {{ row.title }}
                                </div>
                                <div v-if="row.summary" class="summary">{{ row.summary }}</div>
                            </div>
                        </div>
                    </template>
                </el-table-column>

                <el-table-column label="AI 初判" width="180">
                    <template #default="{ row }">
                        <el-tag :type="AI_TAG[row.aiAuditStatus ?? 0]?.type ?? 'info'" size="small">
                            {{ AI_TAG[row.aiAuditStatus ?? 0]?.text ?? '未知' }}
                        </el-tag>
                        <div v-if="row.aiReason" class="reason">{{ row.aiReason }}</div>
                        <div v-if="row.aiAt" class="reason">{{ row.aiAt.slice(0, 16) }}</div>
                    </template>
                </el-table-column>

                <el-table-column label="数据" width="150">
                    <template #default="{ row }">
                        <span class="stat">浏览 {{ row.viewCount ?? 0 }}</span>
                        <span class="stat">评论 {{ row.commentCount ?? 0 }}</span>
                        <span class="stat">赞 {{ row.likeCount ?? 0 }}</span>
                    </template>
                </el-table-column>

                <el-table-column label="提交时间" width="150">
                    <template #default="{ row }">{{ row.createdAt?.slice(0, 16) }}</template>
                </el-table-column>

                <!-- 审核不能盲审：列表故意不带正文（一页十条全文会撑爆响应体），
                     点开抽屉再按需拉取，既轻量又看得到全文 -->
                <el-table-column label="操作" width="110" fixed="right">
                    <template #default="{ row }">
                        <el-button link type="primary" @click="openDetail(row)">查看正文</el-button>
                    </template>
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

        <!-- 正文抽屉：右侧滑出，正文独立滚动，底部固定处置按钮 -->
        <el-drawer v-model="drawerVisible" size="720px" :title="detail?.title || '帖子正文'">
            <div v-loading="detailLoading" class="drawer-body">
                <ErrorRetry v-if="detailError" :message="detailError" @retry="loadDetail" />

                <template v-else-if="detail">
                    <div class="drawer-meta">
                        <el-tag size="small" type="info" effect="plain">待审</el-tag>
                        <span class="muted">作者：{{ detail.authorName || '—' }}</span>
                        <span class="muted">提交：{{ detail.createdAt?.slice(0, 16) || '—' }}</span>
                        <el-tag
                            :type="AI_TAG[detail.aiAuditStatus ?? 0]?.type ?? 'info'"
                            size="small"
                        >
                            {{ AI_TAG[detail.aiAuditStatus ?? 0]?.text ?? '未知' }}
                        </el-tag>
                    </div>
                    <el-alert
                        v-if="detail.aiReason"
                        :title="`AI 初判理由：${detail.aiReason}`"
                        type="warning"
                        :closable="false"
                        show-icon
                    />
                    <!-- 正文已过 DOMPurify 白名单清洗（外链图被剥 src） -->
                    <div ref="bodyRef" class="markdown-body" v-html="detailHtml" />
                </template>
            </div>

            <template #footer>
                <div class="drawer-footer">
                    <el-button @click="drawerVisible = false">关闭</el-button>
                    <div class="drawer-actions">
                        <el-button type="danger" :disabled="!detail" @click="auditOne(false)">驳回</el-button>
                        <el-button type="primary" :disabled="!detail" @click="auditOne(true)">通过</el-button>
                    </div>
                </div>
            </template>
        </el-drawer>
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

.actions {
    display: flex;
    align-items: center;
    gap: 10px;
}

.title-cell {
    display: flex;
    align-items: center;
    gap: 10px;
}

.thumb {
    width: 56px;
    height: 40px;
    border-radius: 6px;
    object-fit: cover;
    flex-shrink: 0;
}

.title-main {
    min-width: 0;
}

.title {
    font-weight: 500;
    display: flex;
    align-items: center;
    gap: 6px;
}

.summary,
.reason {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
}

.stat {
    display: inline-block;
    margin-right: 8px;
    font-size: 12px;
    color: #6b7280;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}

/* ==================== 正文抽屉 ==================== */

.drawer-body {
    min-height: 240px;
}

.drawer-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
}

.drawer-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.drawer-actions {
    display: flex;
    gap: 8px;
}

/*
 * 正文样式必须用 :deep()：v-html 渲染出的节点没有 scoped 属性，
 * 普通选择器一条都匹配不到，症状是「正文完全没有排版」且不报错。
 */
.markdown-body {
    margin-top: 14px;
    font-size: 15px;
    line-height: 1.7;
    color: var(--text, #1f2937);
    word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
    margin: 20px 0 10px;
    font-weight: 600;
    line-height: 1.4;
}

.markdown-body :deep(p) {
    margin: 10px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
    padding-left: 22px;
}

.markdown-body :deep(img) {
    max-width: 100%;
    border-radius: 8px;
}

.markdown-body :deep(blockquote) {
    margin: 12px 0;
    padding: 8px 14px;
    border-left: 3px solid #cbd5e1;
    background: #f8fafc;
    color: #4b5563;
}

.markdown-body :deep(pre) {
    margin: 12px 0;
    padding: 12px 14px;
    border-radius: 8px;
    background: #0f172a;
    color: #e2e8f0;
    overflow: auto;
}

.markdown-body :deep(code) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
}

.markdown-body :deep(.code-block) {
    position: relative;
}

.markdown-body :deep(.copy-btn) {
    position: absolute;
    top: 8px;
    right: 8px;
    padding: 2px 8px;
    font-size: 12px;
    color: #e2e8f0;
    background: rgba(255, 255, 255, 0.12);
    border: 1px solid rgba(255, 255, 255, 0.25);
    border-radius: 4px;
    cursor: pointer;
}

.markdown-body :deep(.copy-btn.is-error) {
    color: #fca5a5;
}

.markdown-body :deep(table) {
    width: 100%;
    margin: 12px 0;
    border-collapse: collapse;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
    padding: 6px 10px;
    border: 1px solid #e5e7eb;
    text-align: left;
}
</style>
