<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import SmartImage from '@/components/SmartImage.vue';
import type { PageResult, PostAudit } from '@/api/types';

/** 我的文章：作者看自己的，含待审与已驳回；三种状态都可进入编辑器改稿 */
const router = useRouter();

const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<PostAudit> | null>(null);
const loading = ref(false);
/** 加载失败：非空时展示错误条 + 重试 */
const error = ref('');

/** 审核状态：0-待审, 1-已通过, 2-已驳回 */
const STATUS_TEXT: Record<number, { text: string; type: 'warning' | 'success' | 'danger' }> = {
    0: { text: '审核中', type: 'warning' },
    1: { text: '已通过', type: 'success' },
    2: { text: '已驳回', type: 'danger' },
};

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await postApi.my({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
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

onMounted(load);

/** 整行可点：表格里只有「查看」一个按钮时，用户习惯点标题行，点不动会以为页面卡了。 */
function onRowClick(row: PostAudit) {
    if (row.status === 1) {
        router.push(`/posts/${row.id}`);
    }
}

/** 可点的行给个手型光标，否则鼠标移到行上没有任何反馈，用户不知道能点 */
function rowClassName({ row }: { row: PostAudit }) {
    return row.status === 1 ? 'row-clickable' : '';
}
</script>

<template>
    <div class="page">
        <div class="page-head">
            <div>
                <h1>我的文章</h1>
                <p>待审、已通过、已驳回的文章都在这里；修改后会重新送审。</p>
            </div>
            <div class="head-actions">
                <el-button @click="router.push('/posts')">返回社区</el-button>
                <el-button type="primary" @click="router.push('/posts/new')">写文章</el-button>
            </div>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div class="card" v-loading="loading">
            <el-empty v-if="result && result.records.length === 0" description="">
                <template #description>
                    <p class="empty-title">还没有发过文章</p>
                    <p class="empty-sub">把第一篇技术分享写下来，审核状态会在这里更新。</p>
                </template>
                <el-button type="primary" @click="router.push('/posts/new')">写第一篇</el-button>
            </el-empty>

            <template v-else>
                <el-table
                    :data="result?.records ?? []"
                    :row-class-name="rowClassName"
                    @row-click="onRowClick"
                >
                    <el-table-column label="标题" min-width="280">
                        <template #default="{ row }">
                            <div class="title-cell">
                                <span class="thumb">
                                    <SmartImage :src="row.coverImage" :alt="row.title" :seed="row.id" />
                                </span>
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

                    <el-table-column label="状态" width="120">
                        <template #default="{ row }">
                            <el-tag :type="STATUS_TEXT[row.status ?? 0]?.type ?? 'info'" size="small">
                                {{ STATUS_TEXT[row.status ?? 0]?.text ?? '未知' }}
                            </el-tag>
                            <!-- 驳回理由直接跟在状态下：作者只关心「为什么不通过、怎么改」，审核链路内部状态（AI 初判/判定时间）不对外暴露 -->
                            <div v-if="row.status === 2 && row.rejectReason" class="reject">
                                理由：{{ row.rejectReason }}
                            </div>
                        </template>
                    </el-table-column>

                    <el-table-column label="数据" width="150">
                        <template #default="{ row }">
                            <span class="stat">浏览 {{ row.viewCount ?? 0 }}</span>
                            <span class="stat">赞 {{ row.likeCount ?? 0 }}</span>
                            <span class="stat">评论 {{ row.commentCount ?? 0 }}</span>
                        </template>
                    </el-table-column>

                    <el-table-column label="提交时间" width="150">
                        <template #default="{ row }">
                            {{ row.createdAt?.slice(0, 16) }}
                        </template>
                    </el-table-column>

                    <el-table-column label="操作" width="150">
                        <template #default="{ row }">
                            <!-- 三种状态都能改：已通过的改完会退回待审，审核通过前不再公开显示。.stop 必须加：按钮在行内，不加的话行点击会先跳详情 -->
                            <template v-if="row.status === 1">
                                <el-button link type="primary" @click.stop="router.push(`/posts/${row.id}`)">
                                    查看
                                </el-button>
                                <el-button link @click.stop="router.push(`/posts/new?edit=${row.id}`)">
                                    编辑
                                </el-button>
                            </template>
                            <el-button
                                v-else
                                link
                                type="primary"
                                @click.stop="router.push(`/posts/new?edit=${row.id}`)"
                            >
                                {{ row.status === 2 ? '修改重投' : '修改' }}
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>

                <div class="pager">
                    <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                    <span class="muted">
                        第 {{ page.current }} / {{ result?.pages }} 页 · 共 {{ result?.total }} 篇
                    </span>
                    <el-button
                        :disabled="page.current >= Number(result?.pages ?? 1)"
                        @click="turn(1)"
                    >
                        下一页
                    </el-button>
                </div>
            </template>
        </div>
    </div>
</template>

<style scoped>
.page-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
}

.head-actions {
    display: flex;
    flex-shrink: 0;
    gap: 10px;
}

.title-cell {
    display: flex;
    align-items: center;
    gap: 10px;
}

/* 缩略图容器：封面缺失时 SmartImage 用品牌蓝兜底，不再出现空白列 */
.thumb {
    display: inline-flex;
    flex-shrink: 0;
    width: 56px;
    height: 40px;
    overflow: hidden;
    border-radius: var(--radius-xs);
    background: var(--brand-soft);
}

.thumb :deep(.img-fallback__img),
.thumb :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
}

.thumb :deep(.img-fallback__box) {
    font-size: 14px;
    font-weight: 700;
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

.summary {
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

/* 驳回理由：跟在状态标签下方，用警示色让作者一眼看到「为什么不通过」 */
.reject {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: #dc2626;
}

.empty-title {
    margin: 0 0 4px;
    font-size: 15px;
    font-weight: 500;
    color: var(--text);
}

.empty-sub {
    margin: 0;
    font-size: 13px;
    color: var(--muted);
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}

/** 可点击行的视觉反馈 */
:deep(.row-clickable) {
    cursor: pointer;
}
</style>
