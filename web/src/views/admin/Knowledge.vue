<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import type { KnowledgeDoc, PageResult } from '@/api/types';

/**
 * 知识库（RAG）管理
 *
 * 两个批量按钮的区别必须让使用者一眼看懂：
 *  - **全量同步**：把业务数据灌进知识库，幂等（内容没变就跳过，零成本）；
 *  - **重建向量**：重新嵌入已有 chunk **不读业务源、不重新切分**。
 *    应用重启后向量全部丢失，此时 sync-all 会因为内容哈希未变而全部跳过（救不回来），
 *    只有重建向量能恢复——这是最容易踩的坑。
 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<KnowledgeDoc> | null>(null);
const loading = ref(false);
const syncForm = reactive({ sourceType: 'project', sourceId: '' });
const lastResult = ref('');

async function load() {
    loading.value = true;
    try {
        result.value = await adminApi.docPage({ ...page });
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
        <div class="bar">
            <h2>知识库（RAG）</h2>
            <span class="spacer" />
            <el-button type="primary" @click="runBatch('全量同步', adminApi.docSyncAll)">全量同步</el-button>
            <el-button type="warning" @click="runBatch('重建向量', adminApi.docReindexAll)">重建向量</el-button>
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
