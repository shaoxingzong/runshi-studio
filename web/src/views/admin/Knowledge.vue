<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { KnowledgeDoc, PageResult } from '@/api/types';

/**
 * 知识库（RAG）管理
 *
 * 两个批量按钮的区别必须让使用者一眼看懂：
 * - 全量同步：把业务数据灌进知识库，幂等（内容没变就跳过，零成本）；
 * - 重建向量：重新嵌入已有 chunk 不读业务源、不重新切分。
 * 应用重启后向量全部丢失，此时 sync-all 会因为内容哈希未变而全部跳过（救不回来），
 * 只有重建向量能恢复——这是最容易踩的坑。
 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<KnowledgeDoc> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');
const syncForm = reactive({ sourceType: 'project', sourceId: '' });
const lastResult = ref('');

// ==================== 手工录入 ====================

const manualVisible = ref(false);
const manualSaving = ref(false);
const manualForm = reactive({ title: '', content: '' });

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.docPage({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

async function runBatch(name: string, action: () => Promise<unknown>) {
    loading.value = true;
    try {
        const data = await action();
        lastResult.value = `${name}：${JSON.stringify(data)}`;
        ElMessage.success(`${name} 完成`);
        await load();
    } catch {
        // 统一提示已在请求层；单条失败不中断，统计里会体现 failed
    } finally {
        loading.value = false;
    }
}

async function syncOne() {
    if (!syncForm.sourceId) {
        ElMessage.warning('请填写业务 ID');
        return;
    }
    await runBatch('同步单条', () => adminApi.docSync(syncForm.sourceType, syncForm.sourceId));
}

/**
 * 手工录入一篇文档
 *
 * 与「同步」的区别：同步是从业务数据（项目 / 成员 / 证书）读内容，
 * 手工录入是管理员直接写标题与正文，用来补充那些不在业务表里的资料
 * （比如团队约定、技术规约）。每次调用都会新建一篇，重复点会产生重复文档。
 *
 * ⚠️ 耗时要说清楚：后端会真实调用 Embedding，两万字的正文可能超过
 * 请求层 30 秒的超时。所以按钮 loading 期间要在界面上说明「请勿关闭」，
 * 否则用户会以为卡死，甚至关掉页面——那样后端其实还在跑，只是没人看结果了。
 */
async function submitManual() {
    if (!manualForm.title.trim() || !manualForm.content.trim()) {
        ElMessage.warning('标题与正文必填');
        return;
    }
    manualSaving.value = true;
    try {
        await runBatch('手工录入', () =>
            adminApi.docManual(manualForm.title.trim(), manualForm.content),
        );
        manualVisible.value = false;
        manualForm.title = '';
        manualForm.content = '';
    } finally {
        manualSaving.value = false;
    }
}

async function rebuild(row: KnowledgeDoc) {
    await runBatch('重建', () => adminApi.docRebuild(row.docId));
}

async function remove(row: KnowledgeDoc) {
    try {
        await ElMessageBox.confirm(
            `确认删除文档「${row.title}」？会同时删除它的切分块（后端逻辑删除 + 清理向量）。`,
            '删除确认',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    await runBatch('删除', () => adminApi.docDelete(row.docId));
}

function statusText(status: number) {
    if (status === 1) return '已索引';
    if (status === 2) return '失败';
    return '待处理';
}

function turn(delta: number) {
    const next = page.current + delta;
    const pages = result.value ? Number(result.value.pages) : 1;
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    load();
}

onMounted(load);
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="bar">
            <h2>知识库（RAG）</h2>
            <span class="spacer" />
            <el-button type="primary" @click="runBatch('全量同步', adminApi.docSyncAll)">全量同步</el-button>
            <el-button type="warning" @click="runBatch('重建向量', adminApi.docReindexAll)">重建向量</el-button>
            <el-button type="success" @click="manualVisible = true">手工录入</el-button>
        </div>

        <el-alert
            class="tip"
            type="info"
            show-icon
            :closable="false"
            title="发布或重启应用后，请点「重建向量」：向量库在内存中，重启即丢失；此时全量同步会全部跳过，救不回来。"
        />

        <div class="sync-one">
            <el-select v-model="syncForm.sourceType" style="width: 130px">
                <el-option label="项目" value="project" />
                <el-option label="成员" value="member" />
                <el-option label="证书" value="certificate" />
            </el-select>
            <el-input v-model="syncForm.sourceId" placeholder="业务 ID" style="width: 220px" />
            <el-button @click="syncOne">同步单条</el-button>
        </div>

        <div v-if="lastResult" class="result">{{ lastResult }}</div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column label="来源" min-width="160">
                <template #default="{ row }">
                    {{ row.sourceType }}<span v-if="row.sourceId"> / {{ row.sourceId }}</span>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
                        {{ statusText(row.status) }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="块数" width="90" />
            <el-table-column label="创建时间" width="160">
                <template #default="{ row }">{{ (row.createdAt ?? '').slice(0, 16) || '—' }}</template>
            </el-table-column>
            <el-table-column label="更新时间" width="160">
                <template #default="{ row }">{{ (row.updatedAt ?? '').slice(0, 16) || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                    <el-button link type="primary" @click="rebuild(row)">重建</el-button>
                    <el-button link type="danger" @click="remove(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div v-if="result" class="pager">
            <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
            <span class="muted">第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 条</span>
            <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">下一页</el-button>
        </div>

        <el-dialog v-model="manualVisible" title="手工录入文档" width="640px">
            <el-form label-position="top">
                <el-form-item label="标题" required>
                    <el-input v-model="manualForm.title" maxlength="128" show-word-limit />
                </el-form-item>
                <el-form-item label="正文" required>
                    <el-input
                        v-model="manualForm.content"
                        type="textarea"
                        :rows="12"
                        maxlength="20000"
                        show-word-limit
                        placeholder="直接粘贴正文；录入后会被切分并向量化"
                    />
                </el-form-item>
            </el-form>

            <!-- 耗时提示放在提交按钮附近：现在的等待是真实的，不说清楚会被当成卡死 -->
            <el-alert
                type="info"
                show-icon
                :closable="false"
                title="长文需要调用 Embedding，可能耗时较久：点「录入」后请耐心等待，不要关闭页面（后端仍在运行）。每次录入都会新建一篇，重复提交会产生重复文档。"
            />

            <template #footer>
                <el-button @click="manualVisible = false">取消</el-button>
                <el-button type="primary" :loading="manualSaving" @click="submitManual">录入</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.bar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
}

h2 {
    margin: 0;
    font-size: 20px;
}

.spacer {
    flex: 1;
}

.tip {
    margin-bottom: 14px;
}

.sync-one {
    display: flex;
    gap: 10px;
    margin-bottom: 14px;
}

.result {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 10px 12px;
    font-size: 13px;
    margin-bottom: 14px;
    word-break: break-all;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 16px;
}
</style>
