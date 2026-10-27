<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { PageResult, PostAudit } from '@/api/types';

/**
 * 我的发帖：作者看自己的，含待审与已驳回
 *
 * 先审后发的体验关键就在这里：提交后内容不进公开列表，用户需要一个地方看到「还在审核中」，
 * 否则会以为发帖失败；驳回时还要能看到理由，才知道怎么改。
 */
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

/**
 * 整行可点：表格里只有「查看」一个按钮时，用户习惯点标题行，点不动会以为页面卡了。
 *
 * 只对「已通过」的行生效：待审与驳回的内容对外不存在（详情页 404），点进去比不可点更糟。
 */
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
                <h1>我的发帖</h1>
                <p>这里可以看到尚未通过审核、以及被驳回的帖子。</p>
            </div>
            <el-button type="primary" @click="router.push('/posts/new')">写文章</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div class="card" v-loading="loading">
            <el-empty v-if="result && result.records.length === 0" description="还没有发过帖子" />

            <template v-else>
                <el-table
                    :data="result?.records ?? []"
                    :row-class-name="rowClassName"
                    @row-click="onRowClick"
                >
                    <el-table-column label="标题" min-width="280">
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

                    <el-table-column label="状态" width="110">
                        <template #default="{ row }">
                            <el-tag :type="STATUS_TEXT[row.status ?? 0]?.type ?? 'info'" size="small">
                                {{ STATUS_TEXT[row.status ?? 0]?.text ?? '未知' }}
                            </el-tag>
                            <!-- 驳回理由直接跟在状态下：作者只关心「为什么不通过、怎么改」，
                                 审核链路内部状态（AI 初判/判定时间）不对外暴露 -->
                            <div v-if="row.status === 2 && row.rejectReason" class="reject">
                                理由：{{ row.rejectReason }}
                            </div>
                        </template>
                    </el-table-column>

                    <el-table-column label="数据" width="130">
                        <template #default="{ row }">
                            <span class="stat">浏览 {{ row.viewCount ?? 0 }}</span>
                            <span class="stat">评论 {{ row.commentCount ?? 0 }}</span>
                        </template>
                    </el-table-column>

                    <el-table-column label="提交时间" width="150">
                        <template #default="{ row }">
                            {{ row.createdAt?.slice(0, 16) }}
                        </template>
                    </el-table-column>

                    <el-table-column label="操作" width="110">
                        <template #default="{ row }">
                            <!--
                              只有已通过的能进详情：待审与驳回的内容对外不存在。驳回稿额外给「修改重投」。
                              .stop 必须加：按钮在行内，不加的话行点击会先跳详情再跳编辑，两个都执行
                            -->
                            <el-button
                                v-if="row.status === 1"
                                link
                                type="primary"
                                @click.stop="router.push(`/posts/${row.id}`)"
                            >
                                查看
                            </el-button>
                            <el-button
                                v-else-if="row.status === 2"
                                link
                                type="primary"
                                @click.stop="router.push(`/posts/new?edit=${row.id}`)"
                            >
                                修改重投
                            </el-button>
                            <span v-else class="muted">—</span>
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

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}

/**
 * 可点击行的视觉反馈
 *
 * 必须用 :deep()：行是 el-table 内部渲染的 <tr>，不带本组件的 scope 属性。
 * 背景色用 Element Plus 自己的 hover 变量，保证与主题一致，也不盖掉斑马纹。
 */
:deep(.row-clickable) {
    cursor: pointer;
}
</style>
