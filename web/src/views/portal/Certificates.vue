<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { portalApi } from '@/api/portal';
import type { Certificate } from '@/api/types';

const list = ref<Certificate[]>([]);
const loading = ref(false);

const LEVEL_TEXT: Record<string, string> = { national: '国家级', provincial: '省级', municipal: '市级/校级' };
const TYPE_TEXT: Record<string, string> = {
    competition: '学科竞赛',
    soft_copyright: '软件著作权',
    patent: '专利',
    paper: '论文',
};

async function load() {
    loading.value = true;
    try {
        list.value = await portalApi.certificateList();
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
            <p>工作室历年获奖、软著、专利与论文。</p>
        </div>

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
