<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { portalApi } from '@/api/portal';
import type { PageResult, Project } from '@/api/types';

/** 项目案例列表（C 端真分页，列表 VO 不含正文——正文只在详情页取） */
const filter = reactive({ title: '', status: '' as string });
const page = reactive({ current: 1, pageSize: 6 });
const result = ref<PageResult<Project> | null>(null);
const loading = ref(false);

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

async function load() {
    loading.value = true;
    try {
        result.value = await portalApi.projectList({ ...filter, ...page });
    } finally {
        loading.value = false;
    }
}

function search() {
    page.current = 1;
    load();
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
    <div class="page">
        <div class="page-head">
            <h1>项目案例</h1>
            <p>我们做过的真实项目。</p>
        </div>

        <div class="card filters">
            <el-input
                v-model="filter.title"
                placeholder="项目名称"
                clearable
                style="width: 200px"
                @keyup.enter="search"
            />
            <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
                <el-option label="研发中" value="0" />
                <el-option label="已上线" value="1" />
                <el-option label="已结题" value="2" />
            </el-select>
            <el-button type="primary" @click="search">搜索</el-button>
        </div>

        <div v-loading="loading">
            <el-empty v-if="result && result.records.length === 0" description="没有符合条件的项目" />
            <div v-else class="grid cards-3">
                <router-link
                    v-for="project in result?.records ?? []"
                    :key="project.id"
                    :to="`/projects/${project.id}`"
                    class="project-card"
                >
                    <div class="head">
                        <h3>{{ project.title }}</h3>
                        <span class="tag" :class="project.status === 1 ? 'green' : 'gray'">
                            {{ STATUS_TEXT[String(project.status)] || project.status }}
                        </span>
                    </div>
                    <p class="muted">{{ project.description }}</p>
                    <div>
                        <span v-for="tag in project.techStack ?? []" :key="tag" class="tag gray">{{ tag }}</span>
                    </div>
                </router-link>
            </div>

            <div v-if="result && result.records.length" class="pager">
                <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted">第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 个</span>
                <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">下一页</el-button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.filters {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 20px;
}

.project-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 18px;
    color: var(--text);
    transition: box-shadow 0.2s, transform 0.2s;
}

.project-card:hover {
    text-decoration: none;
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
}

.head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
}

h3 {
    margin: 0 0 8px;
    font-size: 17px;
}

p {
    font-size: 14px;
    margin: 0 0 12px;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 24px;
}
</style>
