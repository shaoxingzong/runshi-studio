<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { portalApi } from '@/api/portal';
import type { Overview } from '@/api/types';
import ProjectSpotlight from '@/components/ProjectSpotlight.vue';

const overview = ref<Overview | null>(null);
const loading = ref(false);
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
        overview.value = await portalApi.overview();
    } catch (e) {
        error.value = (e as Error).message;
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div class="page">
        <section class="hero">
            <h1>润石工作室</h1>
            <p>做真实的项目，拿真实的奖。这里有我们的作品与荣誉——也可以直接问 AI 助手。</p>
            <div class="actions">
                <router-link to="/projects" class="btn primary">看看项目</router-link>
                <router-link to="/ai" class="btn ghost">问问 AI 助手</router-link>
            </div>
        </section>

        <!-- 项目精选：数据驱动轮播（后台增删项目，首页自动跟着变；封面未上传时用渐变占位） -->
        <ProjectSpotlight />

        <el-alert v-if="error" :title="error" type="error" show-icon class="block" />

        <div v-loading="loading">
            <template v-if="overview">
                <!-- 只剩证书维度：团队人数已随「团队成员不对外展示」从接口移除 -->
                <div class="card stat big">
                    <div class="num">{{ overview.certificateTotal }}</div>
                    <div class="label">荣誉证书总数</div>
                </div>

                <div class="card block">
                    <h2>荣誉分布</h2>
                    <h3 class="muted">按级别</h3>
                    <span v-for="(value, key) in overview.certificateByLevel" :key="key" class="tag">
                        {{ LEVEL_TEXT[key] || key }}：{{ value }}
                    </span>
                    <h3 class="muted">按类型</h3>
                    <span v-for="(value, key) in overview.certificateByType" :key="key" class="tag gray">
                        {{ TYPE_TEXT[key] || key }}：{{ value }}
                    </span>
                </div>
            </template>
        </div>
    </div>
</template>

<style scoped>
.hero {
    padding: 46px 0 30px;
    text-align: center;
}

.hero h1 {
    font-size: 38px;
    margin: 0 0 10px;
}

.hero p {
    color: var(--muted);
    margin: 0 auto 22px;
    max-width: 620px;
}

.actions {
    display: flex;
    gap: 12px;
    justify-content: center;
}

.btn {
    padding: 10px 22px;
    border-radius: var(--radius-sm);
    font-size: 15px;
}

.btn.primary {
    background: var(--brand);
    color: #fff;
}

.btn.ghost {
    border: 1px solid var(--brand);
    color: var(--brand);
}

.btn:hover {
    text-decoration: none;
    opacity: 0.9;
}

.block {
    margin-top: 20px;
}

.stat {
    text-align: center;
}

.stat.big {
    padding: 26px;
}

.stat .num {
    font-size: 32px;
    font-weight: 700;
    color: var(--brand);
}

.stat .label {
    color: var(--muted);
    font-size: 13px;
}

h3 {
    font-size: 13px;
    font-weight: 600;
    margin: 16px 0 8px;
}
</style>
