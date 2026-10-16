<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { PageResult, PostAudit } from '@/api/types';

/**
 * 帖子待审队列（管理员）
 *
 * <p><b>队列里为什么绝大多数是「AI 拿不准」的</b>：三分法下，AI 判「安全」的已自动通过、
 * 判「违规」的已自动驳回，两者都不会出现在这里。
 * 管理员真正要处理的只有「灰色」和「AI 不可用」两类——这正是这套机制减负的地方。
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
 * <p>驳回必须填理由：后端强校验，且理由要给作者看——没理由等于让作者猜。
 * 这里用弹窗收集，比让用户先选再跳到别处填更顺手。
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
</style>
