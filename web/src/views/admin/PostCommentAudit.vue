<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { PageResult, PostCommentAudit } from '@/api/types';

/**
 * 评论待审队列（管理员）
 *
 * 本页没有「删除」：后端删除接口只接受已通过的评论，待审内容应当走驳回，让作者收到反馈。
 * 删除入口在帖子详情页——管理员在那里看到的评论都是已通过的，符合接口语义。
 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<PostCommentAudit> | null>(null);
const selected = ref<string[]>([]);
const loading = ref(false);
/** 队列加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

const AI_TAG: Record<number, { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
    0: { text: '待 AI 初判', type: 'info' },
    1: { text: 'AI 判安全', type: 'success' },
    2: { text: 'AI 判违规', type: 'danger' },
    3: { text: 'AI 需人工复核', type: 'warning' },
    4: { text: 'AI 不可用', type: 'danger' },
};

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await postApi.commentPending({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function onSelectionChange(rows: PostCommentAudit[]) {
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

async function audit(approved: boolean) {
    if (selected.value.length === 0) {
        ElMessage.warning('请先勾选要处理的评论');
        return;
    }

    let rejectReason = '';
    if (!approved) {
        try {
            const prompted = await ElMessageBox.prompt(
                '驳回理由会展示给评论作者',
                '批量驳回',
                { inputValidator: (value) => (value?.trim() ? true : '驳回理由不能为空') },
            );
            rejectReason = String(prompted.value ?? '').trim();
        } catch {
            return;
        }
    }

    loading.value = true;
    try {
        const rows = await postApi.commentAudit({
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

onMounted(load);
</script>

<template>
    <div v-loading="loading">
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="bar">
            <div>
                <h2>评论审核</h2>
                <span class="muted">仅待审；通过后评论公开并计入帖子评论数</span>
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

                <el-table-column label="评论内容" min-width="260">
                    <template #default="{ row }">
                        <div class="content">{{ row.content }}</div>
                        <div class="meta">
                            {{ row.authorName || '用户' }}
                            <span v-if="row.floor" class="floor">{{ row.floor }} 楼</span>
                            <span v-else class="floor">回复</span>
                            <!-- 队列里看不出评论属于哪篇帖子会很难判断上下文，给出跳转 -->
                            <router-link class="post-link" :to="`/posts/${row.postId}`">
                                查看所属帖子
                            </router-link>
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

                <el-table-column label="评论时间" width="150">
                    <template #default="{ row }">{{ row.createdAt?.slice(0, 16) }}</template>
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

.content {
    line-height: 1.6;
    white-space: pre-wrap;
}

.meta {
    margin-top: 4px;
    font-size: 12px;
    color: #6b7280;
}

.floor {
    margin-left: 6px;
    padding: 1px 6px;
    border-radius: 4px;
    background: #eff6ff;
    color: #2563eb;
}

.post-link {
    margin-left: 8px;
    color: var(--brand);
    font-size: 12px;
}

.reason {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}
</style>
