<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { postApi } from '@/api/post';
import ImageUploader from '@/components/ImageUploader.vue';
import type { PageResult, PostAdminDetail, PostAudit } from '@/api/types';

/**
 * 帖子管理（管理员）
 *
 * <p><b>与「帖子审核」的分工</b>：
 * <ul>
 *     <li>帖子审核页只面对<b>待审队列</b>——一种状态、批量处置、先进先出；</li>
 *     <li>本页管理<b>全部</b>帖子——三种状态都能查，能删、能置顶、能代改。</li>
 * </ul>
 * 审核是治理的一个环节，不是全部：已通过的帖子要能置顶 / 下架，
 * 已驳回的要能看看作者改过没有，出问题的要能直接删掉。
 *
 * <p><b>编辑后为什么文章会「消失」</b>：保存时后端把非待审的帖子重置为待审
 * 重新送审（先审后发的后门必须堵住）。所以保存成功的提示里必须说清这件事，
 * 否则管理员会以为改坏了。
 */
const page = reactive({ current: 1, pageSize: 10 });

/**
 * 筛选条件
 *
 * <p>{@code status} 用 {@code null} 表示「全部状态」——与后端口径一致
 * （不传该参数即不按状态过滤）。这与待审队列的关键区别就在这里。
 */
const filters = reactive({
    status: null as number | null,
    titleKeyword: '',
    authorKeyword: '',
});

const result = ref<PageResult<PostAudit> | null>(null);
const loading = ref(false);

/** AI 初判结论：与「帖子审核」页保持同一套映射（同一字段两处展示，映射不一致会误导人） */
const AI_TAG: Record<number, { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
    0: { text: '待 AI 初判', type: 'info' },
    1: { text: 'AI 判安全', type: 'success' },
    2: { text: 'AI 判违规', type: 'danger' },
    3: { text: 'AI 需人工复核', type: 'warning' },
    4: { text: 'AI 不可用', type: 'danger' },
};

/** 审核状态：0-待审, 1-已通过, 2-已驳回 */
const STATUS_TAG: Record<number, { text: string; type: 'info' | 'success' | 'danger' }> = {
    0: { text: '待审', type: 'info' },
    1: { text: '已通过', type: 'success' },
    2: { text: '已驳回', type: 'danger' },
};

const STATUS_OPTIONS = [
    { value: null, label: '全部状态' },
    { value: 0, label: '待审' },
    { value: 1, label: '已通过' },
    { value: 2, label: '已驳回' },
];

async function load() {
    loading.value = true;
    try {
        result.value = await postApi.adminList({
            current: page.current,
            pageSize: page.pageSize,
            status: filters.status ?? undefined,
            // 空串不下发：传了空串等于给后端一个「查空关键词」的歧义
            titleKeyword: filters.titleKeyword.trim() || undefined,
            authorKeyword: filters.authorKeyword.trim() || undefined,
        });
    } finally {
        loading.value = false;
    }
}

/** 换条件必须回到第一页：否则可能停在一个已经不存在的页码上，看到空列表还以为没数据 */
function query() {
    page.current = 1;
    load();
}

function reset() {
    filters.status = null;
    filters.titleKeyword = '';
    filters.authorKeyword = '';
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

// ==================== 置顶 ====================

/**
 * 置顶 / 取消置顶
 *
 * <p>只有「已通过」的行开关可点（后端同规则）：置顶待审帖在公开列表里
 * 没有效果——公开列表只展示已通过的内容，属于典型误操作。
 *
 * <p>失败时<b>必须刷新</b>：开关会停在用户点到的位置，与后端真实状态不一致，
 * 不刷新的话界面就在撒谎。
 */
async function togglePin(row: PostAudit, pinned: number) {
    loading.value = true;
    try {
        await postApi.pin(row.id, pinned);
        ElMessage.success(pinned === 1 ? '已置顶' : '已取消置顶');
    } finally {
        loading.value = false;
        await load();
    }
}

// ==================== 删除 ====================

/**
 * 删除帖子（级联删除评论与点赞）
 *
 * <p>确认文案必须写清「连带删除」与「无法恢复」——逻辑删除对用户不可见，
 * 不说清楚的话，管理员会以为这只是从列表里隐藏。
 */
async function remove(row: PostAudit) {
    try {
        await ElMessageBox.confirm(
            `确认删除帖子「${row.title}」？将连带删除该帖的全部评论与点赞，且无法恢复。`,
            '删除确认',
            { type: 'warning' },
        );
    } catch {
        // 用户取消：正常操作，不是错误
        return;
    }
    loading.value = true;
    try {
        const rows = await postApi.delete(row.id);
        // 返回值含帖子本身（1），评论数要减掉它
        ElMessage.success(`已删除（含 ${Math.max(rows - 1, 0)} 条评论）`);
        await load();
    } finally {
        loading.value = false;
    }
    // 失败时**不刷新列表**：删除是单次请求，失败意味着服务端什么都没改，
    // 列表展示与真实状态仍然一致（与 togglePin 不同——开关是本地立即变化的，
    // 失败后必须刷新才能把开关拨回真实状态）
}

// ==================== 编辑 ====================

const editVisible = ref(false);
const editLoading = ref(false);
/** 保存中的独立 loading：用页面级 loading 会把整个编辑弹窗一起遮住 */
const saving = ref(false);
const form = reactive({
    id: '',
    title: '',
    summary: '',
    coverImage: '',
    content: '',
});

/**
 * 打开编辑弹窗
 *
 * <p>列表 VO <b>不含正文</b>（后端刻意如此：一页十条全文会撑爆响应体），
 * 所以这里先把已知字段填进去让弹窗立刻可交互，再去拉正文——
 * 正文没回来之前保存按钮不可用，避免「半模态数据」被提交。
 */
async function openEdit(row: PostAudit) {
    form.id = row.id;
    form.title = row.title;
    form.summary = row.summary ?? '';
    form.coverImage = row.coverImage ?? '';
    form.content = '';
    editVisible.value = true;

    editLoading.value = true;
    try {
        const detail: PostAdminDetail = await postApi.adminDetail(row.id);
        form.title = detail.title;
        form.summary = detail.summary ?? '';
        form.coverImage = detail.coverImage ?? '';
        form.content = detail.content ?? '';
    } catch (e) {
        // 拉不到正文时给一句明确提示：否则弹窗里正文是空的，
        // 用户会以为「这篇本来就没内容」而直接提交一篇空壳
        ElMessage.error((e as Error).message || '正文加载失败，请重试');
    } finally {
        editLoading.value = false;
    }
}

async function save() {
    if (!form.title.trim() || !form.content.trim()) {
        ElMessage.warning('标题与正文不能为空');
        return;
    }
    saving.value = true;
    try {
        await postApi.update({
            id: form.id,
            title: form.title.trim(),
            content: form.content,
            summary: form.summary.trim() || undefined,
            coverImage: form.coverImage || undefined,
        });
        // 这句话是**必须**的：保存后帖子已回到待审，不说清楚管理员会以为改坏了
        ElMessage.success('已保存，修改内容将重新送审');
        editVisible.value = false;
        await load();
    } finally {
        saving.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div v-loading="loading">
        <div class="bar">
            <div>
                <h2>帖子管理</h2>
                <span class="muted">全部帖子，支持删除、置顶与代改</span>
            </div>
        </div>

        <!-- 筛选栏：条件不传即「不按该条件过滤」，与后端口径一致 -->
        <div class="filters">
            <el-select v-model="filters.status" class="filter" placeholder="审核状态">
                <el-option
                    v-for="option in STATUS_OPTIONS"
                    :key="String(option.value)"
                    :label="option.label"
                    :value="option.value"
                />
            </el-select>
            <el-input
                v-model="filters.titleKeyword"
                class="filter"
                placeholder="标题关键词"
                clearable
                @keyup.enter="query"
            />
            <el-input
                v-model="filters.authorKeyword"
                class="filter"
                placeholder="作者（昵称 / 账号 / 成员姓名）"
                clearable
                @keyup.enter="query"
            />
            <div class="ops">
                <el-button type="primary" @click="query">查询</el-button>
                <el-button @click="reset">重置</el-button>
            </div>
        </div>

        <el-empty v-if="result && result.records.length === 0" description="没有符合条件的帖子" />

        <template v-else>
            <el-table :data="result?.records ?? []" row-key="id">
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

                <el-table-column label="作者" width="130">
                    <template #default="{ row }">
                        {{ row.authorName || '—' }}
                    </template>
                </el-table-column>

                <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                        <el-tag :type="STATUS_TAG[row.status ?? 0]?.type ?? 'info'" size="small" effect="light">
                            {{ STATUS_TAG[row.status ?? 0]?.text ?? '未知' }}
                        </el-tag>
                    </template>
                </el-table-column>

                <el-table-column label="AI 初判" width="160">
                    <template #default="{ row }">
                        <el-tag :type="AI_TAG[row.aiAuditStatus ?? 0]?.type ?? 'info'" size="small">
                            {{ AI_TAG[row.aiAuditStatus ?? 0]?.text ?? '未知' }}
                        </el-tag>
                        <div v-if="row.rejectReason" class="reason">驳回：{{ row.rejectReason }}</div>
                    </template>
                </el-table-column>

                <el-table-column label="数据" width="150">
                    <template #default="{ row }">
                        <span class="stat">浏览 {{ row.viewCount ?? 0 }}</span>
                        <span class="stat">评论 {{ row.commentCount ?? 0 }}</span>
                        <span class="stat">赞 {{ row.likeCount ?? 0 }}</span>
                    </template>
                </el-table-column>

                <el-table-column label="置顶" width="80">
                    <template #default="{ row }">
                        <!--
                          禁用条件只挡「置顶」、不挡「取消置顶」：后端同规则
                          （置顶要求已通过，取消不限制）。不能反过来一刀切禁用——
                          帖子被编辑后会打回待审但 pinned 仍为 1，
                          那时若取消不了置顶，管理员就卡在一个改不回来的状态里
                        -->
                        <el-switch
                            :model-value="row.pinned === 1"
                            :disabled="row.pinned !== 1 && row.status !== 1"
                            @update:model-value="(value: string | number | boolean) => togglePin(row, value ? 1 : 0)"
                        />
                    </template>
                </el-table-column>

                <el-table-column label="提交时间" width="150">
                    <template #default="{ row }">{{ row.createdAt?.slice(0, 16) }}</template>
                </el-table-column>

                <el-table-column label="操作" width="150" fixed="right">
                    <template #default="{ row }">
                        <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                        <!--
                          待审行禁用删除：后端同规则（删了作者什么都收不到，该走驳回）。
                          在**点之前**就说明原因，而不是等用户点了才收到报错——
                          tooltip 套一层 span 是因为禁用态的按钮不派发鼠标事件
                        -->
                        <el-tooltip
                            v-if="(row.status ?? 0) === 0"
                            content="待审帖子请改用驳回，让作者收到理由"
                            placement="top"
                        >
                            <span class="op-wrap">
                                <el-button link type="danger" disabled>删除</el-button>
                            </span>
                        </el-tooltip>
                        <el-button v-else link type="danger" @click="remove(row)">删除</el-button>
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

        <!-- 编辑弹窗 -->
        <el-dialog v-model="editVisible" title="编辑帖子" width="640px">
            <div v-loading="editLoading">
                <el-form label-position="top">
                    <el-form-item label="标题" required>
                        <el-input v-model="form.title" maxlength="128" show-word-limit />
                    </el-form-item>
                    <el-form-item label="摘要">
                        <el-input
                            v-model="form.summary"
                            type="textarea"
                            :rows="2"
                            maxlength="512"
                            show-word-limit
                            placeholder="留空则用正文前若干字自动生成"
                        />
                    </el-form-item>
                    <el-form-item label="封面">
                        <ImageUploader
                            v-model="form.coverImage"
                            tip="留空则取正文第一张图；仅支持 PNG / JPEG"
                        />
                    </el-form-item>
                    <el-form-item label="正文（Markdown）" required>
                        <el-input
                            v-model="form.content"
                            type="textarea"
                            :rows="10"
                            placeholder="图片需先在发帖编辑器上传后，把 /uploads/... 路径写进正文（外链图片会被拒绝）"
                        />
                    </el-form-item>
                </el-form>
                <el-alert
                    type="warning"
                    :closable="false"
                    title="保存后该帖将回到「待审」重新送审，审核通过前不会出现在门户社区。"
                />
            </div>
            <template #footer>
                <el-button @click="editVisible = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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

.filter {
    width: 200px;
}

.ops {
    display: flex;
    gap: 8px;
    margin-left: auto;
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

/* 包住禁用按钮的 span：让 tooltip 能挂上去（禁用态按钮不派发鼠标事件） */
.op-wrap {
    display: inline-block;
    cursor: not-allowed;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 18px;
}
</style>
