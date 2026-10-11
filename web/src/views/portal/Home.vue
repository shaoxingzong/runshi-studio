<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';
import { portalApi } from '@/api/portal';
import type { Overview } from '@/api/types';
import ProjectSpotlight from '@/components/ProjectSpotlight.vue';

/**
 * ECharts 按需注册：只引入真正用到的图表（柱状 / 饼图）与组件，
 * 避免把近 1MB 的整包 ECharts 打进首屏。
 */
use([CanvasRenderer, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent]);

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

/** 分布数据转成有序数组：图表数据与「是否有数据」的判断共用同一份 */
const levelEntries = computed(() =>
    Object.entries(overview.value?.certificateByLevel ?? {}).filter(([, value]) => value > 0),
);
const typeEntries = computed(() =>
    Object.entries(overview.value?.certificateByType ?? {}).filter(([, value]) => value > 0),
);

/** 按级别的柱状图：级别是可枚举的少数几档，柱子比环形更好比较 */
const levelOption = computed<EChartsOption>(() => ({
    tooltip: { trigger: 'axis' },
    grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
    xAxis: {
        type: 'category',
        data: levelEntries.value.map(([key]) => LEVEL_TEXT[key] || key),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: '#e5e7eb' } },
    },
    yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: '#f1f5f9' } },
    },
    series: [
        {
            type: 'bar',
            barWidth: '46%',
            data: levelEntries.value.map(([, value]) => value),
            itemStyle: {
                borderRadius: [6, 6, 0, 0],
                color: {
                    type: 'linear',
                    x: 0,
                    y: 0,
                    x2: 0,
                    y2: 1,
                    colorStops: [
                        { offset: 0, color: '#3b82f6' },
                        { offset: 1, color: '#2563eb' },
                    ],
                },
            },
        },
    ],
}));

/** 按类型的环形图：类型之间无先后，占比用环形更直观 */
const typeOption = computed<EChartsOption>(() => ({
    tooltip: { trigger: 'item', formatter: '{b}：{c}（{d}%）' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
    color: ['#2563eb', '#38bdf8', '#f59e0b', '#10b981', '#8b5cf6'],
    series: [
        {
            type: 'pie',
            radius: ['42%', '68%'],
            center: ['50%', '42%'],
            label: { formatter: '{b} {c}' },
            itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 6 },
            data: typeEntries.value.map(([key, value]) => ({ name: TYPE_TEXT[key] || key, value })),
        },
    ],
}));

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

                <div class="charts">
                    <div class="card">
                        <h2>荣誉按级别</h2>
                        <v-chart v-if="levelEntries.length" :option="levelOption" autoresize class="chart" />
                        <el-empty v-else description="暂无数据" :image-size="60" />
                    </div>
                    <div class="card">
                        <h2>荣誉按类型</h2>
                        <v-chart v-if="typeEntries.length" :option="typeOption" autoresize class="chart" />
                        <el-empty v-else description="暂无数据" :image-size="60" />
                    </div>
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

/* 两张图表卡片并排；窄屏时自动堆叠，避免图表被压得看不清 */
.charts {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 20px;
    margin-top: 20px;
}

@media (max-width: 760px) {
    .charts {
        grid-template-columns: 1fr;
    }
}

.chart {
    height: 260px;
    margin-top: 8px;
}
</style>
