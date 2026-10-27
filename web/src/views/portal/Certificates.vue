<script setup lang="ts">
import { onMounted, ref } from 'vue';
import ErrorRetry from '@/components/ErrorRetry.vue';
import { portalApi } from '@/api/portal';
import type { Certificate } from '@/api/types';

const list = ref<Certificate[]>([]);
const loading = ref(false);
/** 加载失败的原因（空 = 正常）。没有它时请求失败只是 loading 消失、表格空白，分不清「没数据」还是「坏了」 */
const error = ref('');

const LEVEL_TEXT: Record<string, string> = { national: '国家级', provincial: '省级', municipal: '市级/校级' };
const TYPE_TEXT: Record<string, string> = {
    competition: '学科竞赛',
    soft_copyright: '软件著作权',
    patent: '专利',
    paper: '论文',
};

async function load() {
    loading.value = true;
    error.value = '';
    try {
        list.value = await portalApi.certificateList();
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div class="page">
        <div class="page-head">
            <h1>荣誉墙</h1>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading" class="card">
            <el-empty v-if="!loading && list.length === 0" description="暂无证书" />
            <el-table v-else :data="list" style="width: 100%">
                <el-table-column prop="title" label="名称" min-width="260" />
                <el-table-column label="级别" width="120">
                    <template #default="{ row }">
                        <span class="tag">{{ LEVEL_TEXT[row.awardLevel] || row.awardLevel }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="类型" width="140">
                    <template #default="{ row }">
                        <span class="tag gray">{{ TYPE_TEXT[row.awardType] || row.awardType }}</span>
                    </template>
                </el-table-column>
                <el-table-column prop="awardDate" label="获奖日期" width="140" />
                <el-table-column label="证书" width="100">
                    <template #default="{ row }">
                        <el-link v-if="row.imageUrl" :href="row.imageUrl" target="_blank" type="primary">查看</el-link>
                        <span v-else class="muted">—</span>
                    </template>
                </el-table-column>
            </el-table>
        </div>
    </div>
</template>
