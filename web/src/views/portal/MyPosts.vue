<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { postApi } from '@/api/post';
import type { PageResult, PostAudit } from '@/api/types';

/**
 * 我的发帖：作者看自己的，含待审与已驳回
 *
 * <p><b>先审后发的体验关键就在这里</b>：提交后内容不会出现在公开列表，
 * 用户需要一个地方看到「我发的还在审核中」——否则会以为发帖失败。
 * 驳回时还要能看到理由，才知道该怎么改。
 */
const router = useRouter();

const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<PostAudit> | null>(null);
const loading = ref(false);

/** 审核状态：0-待审, 1-已通过, 2-已驳回 */
const STATUS_TEXT: Record<number, { text: string; type: 'warning' | 'success' | 'danger' }> = {
    0: { text: '审核中', type: 'warning' },
    1: { text: '已通过', type: 'success' },
    2: { text: '已驳回', type: 'danger' },
};

/**
 * AI 初判结论：0-未判, 1-安全, 2-违规, 3-灰色, 4-判定失败
 *
 * <p>展示它是为了让作者知道内容在正常流转：
 * 「AI 认为需要人工复核」远比只显示「审核中」更有信息量。
 */
const AI_TEXT: Record<number, string> = {
    0: '等待 AI 初判',
    1: 'AI 判为安全，已自动通过',
    2: 'AI 判为违规，已自动驳回',
    3: 'AI 认为需要人工复核',
    4: 'AI 不可用，等待人工审核',
};

async function load() {
    loading.value = true;
    try {
        result.value = await postApi.my({ ...page });
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

        <div class="card" v-loading="loading">
            <el-empty v-if="result && result.records.length === 0" description="还没有发过帖子" />

            <template v-else>
                <el-table :data="result?.records ?? []">
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
                        </template>
                    </el-table-column>

                    <el-table-column label="AI 初判" min-width="180">
                        <template #default="{ row }">
                            <span class="ai">{{ AI_TEXT[row.aiAuditStatus ?? 0] ?? '—' }}</span>
                            <div v-if="row.aiReason" class="ai-reason">{{ row.aiReason }}</div>
                            <div v-if="row.aiAt" class="ai-time">{{ row.aiAt.slice(0, 16) }}</div>
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
                            <!-- 只有已通过的才看得进详情：待审与驳回的内容对外不存在 -->
                            <el-button
                                v-if="row.status === 1"
                                link
                                type="primary"
                                @click="router.push(`/posts/${row.id}`)"
                            >
                                查看
                            </el-button>
                            <span v-else-if="row.status === 2 && row.rejectReason" class="reject">
                                理由：{{ row.rejectReason }}
                            </span>
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

.ai-time {
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

.ai {
    font-size: 13px;
    color: #4b5563;
}

.ai-reason {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
}

.reject {
    font-size: 12px;
    color: #dc2626;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}
</style>
