<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Directive } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';
import { portalApi } from '@/api/portal';
import type { Overview } from '@/api/types';
import ErrorRetry from '@/components/ErrorRetry.vue';
import PostSpotlight from '@/components/PostSpotlight.vue';
import ProjectSpotlight from '@/components/ProjectSpotlight.vue';
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
/** 技术滚动条：把工作室实际在用的 Java 全栈技术都列上（后端 → 前端 → 部署运维） */
const TECH = [
    'Java 21',
    'Spring Boot',
    'Spring MVC',
    'Spring Validation',
    'Sa-Token',
    'MyBatis-Plus',
    'MySQL',
    'Redis',
    'LangChain4j',
    'Maven',
    'Lombok',
    'Knife4j',
    'Vue 3',
    'TypeScript',
    'Vite',
    'Pinia',
    'Vue Router',
    'Element Plus',
    'ECharts',
    'Axios',
    'Docker',
    'Nginx',
    'Linux',
    'Git',
];


/** 社区文章总数：由「社区精选」轮播组件加载后回传，首页只用来填数据条 */
const postsTotal = ref<number | null>(null);
/** 项目总数：hero 数据条用；失败时保持 null（显示占位符，不打扰首屏） */
const projectTotal = ref<number | null>(null);

/** 首页可跳转的分区：宽屏侧边快捷栏的锚点与滚动高亮共用这套 key */
type SectionKey = 'posts' | 'projects' | 'stats';

const postsRef = ref<HTMLElement | null>(null);
const projectsRef = ref<HTMLElement | null>(null);
const statsRef = ref<HTMLElement | null>(null);
const activeSection = ref<SectionKey | ''>('');

/** 首页数据条：三个数字分别来自证书 / 文章 / 项目三个模块。刻意不新增聚合接口——它们只在首页出现，为三个数字建一个后端接口不值得。 */
const metrics = computed(() => [
    {
        key: 'award',
        label: '荣誉证书',
        value: overview.value?.certificateTotal ?? null,
        unit: '项',
        icon: ICON_PATHS.award,
    },
    { key: 'article', label: '社区文章', value: postsTotal.value, unit: '篇', icon: ICON_PATHS.article },
    { key: 'project', label: '项目', value: projectTotal.value, unit: '个', icon: ICON_PATHS.project },
]);

/** 分布数据转成有序数组：图表数据与「是否有数据」的判断共用同一份 */
const levelEntries = computed(() =>
    Object.entries(overview.value?.certificateByLevel ?? {}).filter(([, value]) => value > 0),
);
const typeEntries = computed(() =>
    Object.entries(overview.value?.certificateByType ?? {}).filter(([, value]) => value > 0),
);

/** 按级别的柱状图：级别是可枚举的少数几档，柱子比环形更好比较 */
const levelOption = computed<EChartsOption>(() => ({
    animationDuration: 900,
    animationEasing: 'cubicOut',
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
    animationDuration: 900,
    animationEasing: 'cubicOut',
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

/** 首页大盘（证书维度） */
async function load() {
    loading.value = true;
    error.value = '';
    try {
        overview.value = await portalApi.overview();
    } catch (e) {
        error.value = (e as Error).message;
    } finally {
        loading.value = false;
        // 荣誉分区随 overview 挂载/卸载：等 DOM 更新后重算一次高亮，避免侧边栏状态落后于页面
        void nextTick(updateActiveSection);
    }
}

/** 项目总数：只发一次 pageSize=1 的请求，拿分页元数据里的 total。失败静默——数据条属于装饰性信息，为它弹错误提示反而打断首屏阅读。 */
async function loadProjectTotal() {
    try {
        const page = await portalApi.projectList({ current: 1, pageSize: 1 });
        projectTotal.value = Number(page.total ?? 0);
    } catch {
        // 静默：见上方注释
    }
}

onMounted(() => {
    void load();
    void loadProjectTotal();
    window.addEventListener('scroll', onScroll, { passive: true });
    updateActiveSection();
});

onBeforeUnmount(() => {
    window.removeEventListener('scroll', onScroll);
    if (spyFrame) {
        window.cancelAnimationFrame(spyFrame);
        spyFrame = 0;
    }
});

/** 轮播组件加载完成后回传文章总数：数据条用它显示「社区文章 N 篇」 */
function onPostsTotal(total: number) {
    postsTotal.value = total;
}

/** 数字格式化：过万转「万」，避免数据条被长数字撑开 */
function formatCount(value?: number | null): string {
    const num = value ?? 0;
    if (num >= 10000) {
        return `${(num / 10000).toFixed(1)}万`;
    }
    if (num >= 1000) {
        return `${(num / 1000).toFixed(1)}k`;
    }
    return String(num);
}

/** 数据条图标：单 path 的线性图标，不引图标库（首页只有三个图标，不值得增加依赖） */
const ICON_PATHS = {
    award: 'M8 21h8M12 17v4M7 4h10v4a5 5 0 0 1-10 0V4Zm10 1h3v1.6a3.4 3.4 0 0 1-3.2 3.4M7 5H4v1.6a3.4 3.4 0 0 0 3.2 3.4',
    article: 'M7 3.5h7.5L19 8v12.5H7zM14.5 3.5V8H19M10 12h6M10 15.5h6M10 8.5h2.5',
    project: 'M12 3 20 7.4v9.2L12 21l-8-4.4V7.4L12 3Zm0 0v9m8-4.6-8 4.6-8-4.6',
} as const;

/** 分区顺序与页面实际顺序一致：社区 → 项目 → 荣誉 */
const SECTIONS: { key: SectionKey; label: string; icon: string }[] = [
    { key: 'posts', label: '社区', icon: ICON_PATHS.article },
    { key: 'projects', label: '项目', icon: ICON_PATHS.project },
    { key: 'stats', label: '荣誉', icon: ICON_PATHS.award },
];

/** 荣誉锚点只在有数据（分区真实存在）时展示，避免点了没反应 */
const railSections = computed(() => SECTIONS.filter((item) => item.key !== 'stats' || overview.value !== null));

/** 锚点滚动要留出吸顶导航的高度，否则分区标题会被导航条盖住 */
const ANCHOR_OFFSET = 74;

function sectionEl(key: SectionKey): HTMLElement | null {
    return { posts: postsRef.value, projects: projectsRef.value, stats: statsRef.value }[key];
}

function prefersReducedMotion(): boolean {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function scrollToSection(key: SectionKey) {
    const el = sectionEl(key);
    if (!el) {
        return;
    }
    const top = el.getBoundingClientRect().top + window.scrollY - ANCHOR_OFFSET;
    window.scrollTo({ top: Math.max(top, 0), behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
}

function scrollToTop() {
    window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
}

/**
 * 滚动高亮：分区顶边越过判定线即为当前分区。
 * 不用 IntersectionObserver——分区高度差异大，按「进入视口比例」判断容易跳，按顶边位置更贴近阅读位置。
 */
let spyFrame = 0;

function updateActiveSection() {
    spyFrame = 0;
    const line = ANCHOR_OFFSET + 66;
    let current: SectionKey | '' = '';
    for (const item of SECTIONS) {
        const el = sectionEl(item.key);
        if (el && el.getBoundingClientRect().top <= line) {
            current = item.key;
        }
    }
    activeSection.value = current;
}

function onScroll() {
    if (!spyFrame) {
        spyFrame = window.requestAnimationFrame(updateActiveSection);
    }
}

/** 滚动入场指令 v-reveal */
const vReveal: Directive<HTMLElement, number | undefined> = {
    mounted(el, binding) {
        if (!('IntersectionObserver' in window)) {
            return;
        }
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return;
        }
        el.classList.add('reveal');
        if (binding.value) {
            el.style.transitionDelay = `${binding.value}ms`;
        }
        const observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (entry.isIntersecting) {
                        el.classList.add('revealed');
                        observer.disconnect();
                        break;
                    }
                }
            },
            // threshold 0.12：元素露出约一成时触发，太晚会出现「滚到了才动」的迟滞感
            { threshold: 0.12 },
        );
        observer.observe(el);
        (el as HTMLElement & { __revealObserver?: IntersectionObserver }).__revealObserver = observer;
    },
    unmounted(el) {
        (el as HTMLElement & { __revealObserver?: IntersectionObserver }).__revealObserver?.disconnect();
    },
};
</script>

<template>
    <div class="home-view">
        <!-- 全幅氛围层：.page 只有 1180px 宽，光晕挂在这里才能覆盖宽屏两侧的留白 -->
        <div class="home-atmosphere" aria-hidden="true"></div>
        <!-- 超宽屏两侧竖向刻度：贴近视口边缘，只做氛围，不参与内容布局 -->
        <span class="edge-mark edge-mark--left" aria-hidden="true">
            <span class="edge-line"></span>
            <span class="edge-text">RUNSHI STUDIO</span>
        </span>
        <span class="edge-mark edge-mark--right" aria-hidden="true">
            <span class="edge-line"></span>
            <span class="edge-text">社区 · 项目 · 荣誉</span>
        </span>

        <div class="page home">
            <section class="hero">
                <span class="hero-orb orb-a" aria-hidden="true"></span>
                <span class="hero-orb orb-b" aria-hidden="true"></span>
                <!-- 极光层：大半径 conic 渐变 + 模糊，只做旋转（合成层动画），不参与布局 -->
                <span class="hero-aurora" aria-hidden="true"></span>

                <div class="hero-card">
                    <div class="hero-visual">
                        <div class="hero-cover-frame">
                            <img src="/logo.jpg" alt="润石工作室" class="hero-cover" />
                        </div>
                        <span class="hero-badge">RUNSHI STUDIO</span>
                    </div>

                    <div class="hero-copy">
                        <h1>润石工作室</h1>
                        <p class="hero-tagline">让知识走出课堂，把想法变成真正落地的产品</p>
                        <p class="lead">我们是一支由北华大学与润石软件共建的团队——深耕真实项目、冲击竞赛奖项、输出技术干货，在实战中全面提升综合素质，为学院人才培养提供有效支撑。</p>
                        <div class="actions">
                            <router-link to="/projects" class="btn primary">看看我们做过什么 <span class="arrow">→</span></router-link>
                            <router-link to="/ai" class="btn ghost">问问 AI 助手</router-link>
                        </div>
                        <!-- 能力标签：给第一次来的人一个「他们能做什么」的快速印象 -->
                        <div class="hero-meta">
                            <span>网站与小程序开发</span>
                            <span>AI 应用落地</span>
                            <span>数据分析与可视化</span>
                            <span>系统设计与实现</span>
                        </div>
                    </div>
                </div>

                <!-- 数据条：悬浮在首屏卡片下沿，用「半透明玻璃」卡片把三个模块的数字并排展示 -->
                <div class="hero-metrics">
                    <div v-for="item in metrics" :key="item.key" class="metric">
                        <span class="metric-icon">
                            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                                <path
                                    :d="item.icon"
                                    stroke="currentColor"
                                    stroke-width="1.6"
                                    stroke-linecap="round"
                                    stroke-linejoin="round"
                                />
                            </svg>
                        </span>
                        <div class="metric-body">
                            <div class="metric-value">
                                <template v-if="item.value === null">—</template>
                                <template v-else>{{ formatCount(item.value) }}<span class="metric-unit">{{ item.unit }}</span></template>
                            </div>
                            <div class="metric-label">{{ item.label }}</div>
                        </div>
                    </div>
                </div>
            </section>

            <!-- 社区精选：轮播区块保留，按需求去掉上方标题行 -->
            <section ref="postsRef" v-reveal class="section">
                <div class="spotlight-wrap">
                    <PostSpotlight @total="onPostsTotal" />
                </div>
            </section>

            <!-- 技术滚动条：纯 CSS transform 动画，列表只复制一份用于无缝循环，不增加接口与图片 -->
            <div class="tech-strip" aria-hidden="true">
                <div class="tech-track">
                    <div v-for="copy in 2" :key="copy" class="tech-group">
                        <span v-for="tech in TECH" :key="`${copy}-${tech}`">{{ tech }}</span>
                    </div>
                </div>
            </div>

            <!-- 项目精选：静态卡片，固定展示排序最靠前的三个项目 -->
            <section ref="projectsRef" v-reveal class="section">
                <div class="section-head">
                    <div>
                        <h2>项目精选</h2>
                    </div>
                    <router-link to="/projects" class="more">全部项目 <span class="more-arrow">→</span></router-link>
                </div>
                <ProjectSpotlight />
            </section>

            <!-- 大盘数据加载失败：用带重试按钮的统一组件，裸 alert 没有恢复手段，用户只能刷新整页 -->
            <ErrorRetry v-if="error" :message="error" @retry="load" />

            <div v-loading="loading">
                <template v-if="overview">
                    <section ref="statsRef" v-reveal class="section stats-section">
                        <div class="section-head">
                            <div>
                                <h2>荣誉数据</h2>
                                <p class="section-sub">统计自 2024 年起</p>
                            </div>
                            <router-link to="/certificates" class="more">进入荣誉墙 <span class="more-arrow">→</span></router-link>
                        </div>

                        <!-- 大盘只有证书维度：对外暴露团队人数属于展示成员信息，与「成员不对外展示」冲突 -->
                        <div class="stat-card">
                            <div class="stat-icon-wrap">
                                <svg class="stat-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                                    <circle cx="12" cy="9" r="5.5" stroke="currentColor" stroke-width="1.6" />
                                    <path
                                        d="M8.6 13.4 7 21.5l5-2.8 5 2.8-1.6-8.1"
                                        stroke="currentColor"
                                        stroke-width="1.6"
                                        stroke-linecap="round"
                                        stroke-linejoin="round"
                                    />
                                </svg>
                            </div>

                            <div class="stat-body">
                                <div class="stat-top">
                                    <div class="num">{{ overview.certificateTotal }}</div>
                                    <div class="label">荣誉证书总数</div>
                                </div>
                                <div v-if="levelEntries.length" class="level-chips">
                                    <span v-for="([key, value]) in levelEntries" :key="key" class="level-chip" :class="key">
                                        <i class="dot"></i>{{ LEVEL_TEXT[key] || key }} {{ value }}
                                    </span>
                                </div>
                            </div>

                            <svg class="stat-watermark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                                <circle cx="12" cy="9" r="5.5" stroke="currentColor" stroke-width="1.2" />
                                <path
                                    d="M8.6 13.4 7 21.5l5-2.8 5 2.8-1.6-8.1"
                                    stroke="currentColor"
                                    stroke-width="1.2"
                                    stroke-linecap="round"
                                    stroke-linejoin="round"
                                />
                            </svg>
                        </div>

                        <div class="charts">
                            <div class="card chart-card">
                                <h2>荣誉按级别</h2>
                                <p class="chart-sub">国家级 / 省级 / 市级与校级</p>
                                <v-chart v-if="levelEntries.length" :option="levelOption" autoresize class="chart" />
                                <el-empty v-else description="暂无数据" :image-size="60" />
                            </div>
                            <div class="card chart-card">
                                <h2>荣誉按类型</h2>
                                <p class="chart-sub">竞赛 / 软著 / 专利 / 论文</p>
                                <v-chart v-if="typeEntries.length" :option="typeOption" autoresize class="chart" />
                                <el-empty v-else description="暂无数据" :image-size="60" />
                            </div>
                        </div>
                    </section>
                </template>
            </div>

            <section v-reveal class="cta-band">
                <div class="cta-copy">
                    <h2>把想法做成作品</h2>
                    <p>从一行代码到上线项目，和一群认真做事的人一起成长。</p>
                </div>
                <div class="cta-actions">
                    <router-link to="/posts" class="btn light">逛逛社区</router-link>
                    <router-link to="/ai" class="btn outline-light">问问 AI 助手</router-link>
                </div>
            </section>
        </div>

        <!-- 宽屏右侧快捷栏：只在两侧留白足够时出现，窄屏完全隐藏，不占用内容宽度 -->
        <aside class="side-rail" aria-label="快捷导航">
            <button
                v-for="item in railSections"
                :key="item.key"
                type="button"
                class="rail-item"
                :class="{ active: activeSection === item.key }"
                :aria-current="activeSection === item.key ? 'true' : undefined"
                :title="item.label"
                @click="scrollToSection(item.key)"
            >
                <svg class="rail-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                        :d="item.icon"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                    />
                </svg>
                <span class="rail-label">{{ item.label }}</span>
            </button>

            <span class="rail-divider" aria-hidden="true"></span>

            <router-link to="/ai" class="rail-item" title="AI 助手">
                <svg class="rail-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                        d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linejoin="round"
                    />
                    <path
                        d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"
                        fill="currentColor"
                        opacity=".75"
                    />
                </svg>
                <span class="rail-label">AI</span>
            </router-link>

            <span class="rail-divider" aria-hidden="true"></span>

            <button type="button" class="rail-item" title="回到顶部" @click="scrollToTop">
                <svg class="rail-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                        d="M12 19V5M6 11l6-6 6 6"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                    />
                </svg>
                <span class="rail-label">顶部</span>
            </button>
        </aside>
    </div>
</template>

<style scoped>
/* 外层不限宽：全幅氛围层与两侧装饰都以整个页面为参照，而不是 1180px 的内容盒 */
.home-view {
    position: relative;
    isolation: isolate;
}

/* 全幅氛围层：光晕原先挂在 .page 上，会停在 1180px 的内容边界；
   放到外层后，宽屏两侧的留白也能被同一套光晕覆盖。纯渐变绘制，不加载背景图 */
.home-atmosphere {
    position: absolute;
    inset: 0;
    z-index: -1;
    background:
        radial-gradient(1100px 520px at 50% -180px, rgb(37 99 235 / 14%), transparent 70%),
        radial-gradient(760px 420px at 92% 4%, rgb(14 165 233 / 10%), transparent 72%),
        radial-gradient(620px 380px at 6% 16%, rgb(139 92 246 / 8%), transparent 70%),
        radial-gradient(720px 420px at 0% 46%, rgb(14 165 233 / 7%), transparent 72%),
        radial-gradient(720px 420px at 100% 58%, rgb(37 99 235 / 7%), transparent 72%);
    pointer-events: none;
}

/* 极淡的点阵只铺在页面上半段，并由 mask 向下淡出：两侧留白不再是纯白，又不会干扰下半段阅读 */
.home-atmosphere::before {
    content: '';
    position: absolute;
    inset: 0;
    background-image: radial-gradient(rgb(37 99 235 / 9%) 1px, transparent 1px);
    background-size: 28px 28px;
    opacity: 0.5;
    -webkit-mask-image: linear-gradient(180deg, #000 0%, rgb(0 0 0 / 35%) 42%, transparent 72%);
    mask-image: linear-gradient(180deg, #000 0%, rgb(0 0 0 / 35%) 42%, transparent 72%);
    pointer-events: none;
}

.home {
    position: relative;
}

/* ===== 宽屏右侧快捷栏 ===== */
.side-rail {
    display: none;
}

@media (min-width: 1440px) {
    .side-rail {
        position: fixed;
        z-index: 80;
        top: 50%;
        /* 右沿停在 .page 外沿右侧 4px，与卡片之间留 24px；用 calc 跟随内容中线，宽屏不会越拉越远 */
        right: calc((100vw - 1180px) / 2 - 62px);
        display: flex;
        flex-direction: column;
        gap: 2px;
        width: 58px;
        padding: 6px;
        border: 1px solid rgb(37 99 235 / 12%);
        border-radius: var(--radius-lg);
        background: rgb(255 255 255 / 88%);
        backdrop-filter: blur(12px);
        box-shadow: var(--shadow-md);
        transform: translateY(-50%);
    }
}

.rail-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    height: 46px;
    padding: 0;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--muted);
    font: inherit;
    font-size: 10px;
    text-decoration: none;
    cursor: pointer;
    transition: background 0.2s ease, color 0.2s ease;
}

.rail-item:hover {
    background: var(--brand-soft);
    color: var(--brand);
    text-decoration: none;
}

.rail-item.active {
    background: var(--brand-soft);
    color: var(--brand);
    font-weight: 600;
}

.rail-icon {
    width: 18px;
    height: 18px;
}

.rail-label {
    line-height: 1;
    white-space: nowrap;
}

.rail-divider {
    height: 1px;
    margin: 4px 5px;
    background: linear-gradient(90deg, transparent, var(--border), transparent);
}

/* ===== 超宽屏两侧竖向刻度 ===== */
.edge-mark {
    display: none;
}

@media (min-width: 1536px) {
    .edge-mark {
        position: fixed;
        z-index: 40;
        top: 50%;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 14px;
        width: 16px;
        color: #94a3b8;
        pointer-events: none;
        user-select: none;
        transform: translateY(-50%);
    }

    .edge-mark--left {
        left: 26px;
    }

    .edge-mark--right {
        right: 26px;
    }

    .edge-line {
        width: 1px;
        height: 86px;
        background: linear-gradient(180deg, transparent, rgb(148 163 184 / 55%));
    }

    .edge-mark--right .edge-line {
        background: linear-gradient(0deg, transparent, rgb(148 163 184 / 55%));
    }

    .edge-text {
        writing-mode: vertical-rl;
        font-size: 10px;
        font-weight: 600;
        letter-spacing: 0.26em;
        opacity: 0.7;
    }

    /* 左侧文字旋转 180° 后自下而上阅读，与右侧形成方向上的对照 */
    .edge-mark--left .edge-text {
        transform: rotate(180deg);
    }
}

.hero {
    position: relative;
    padding: 40px 0 34px;
    /* 裁掉极光与光晕的溢出：否则绝对定位的大圆会把页面撑出横向滚动条 */
    overflow: hidden;
}

/* 首屏背后的两团光晕：只做 transform 动画，不触发重排 */
.hero-orb {
    position: absolute;
    z-index: 0;
    border-radius: 50%;
    pointer-events: none;
    opacity: 0.9;
}

/* 光晕只允许向页面内溢出一小段：绝对定位元素会把 body 的 scrollWidth 撑宽，
   窄屏就出现横向滚动条 */
.orb-a {
    width: 300px;
    height: 300px;
    left: -18px;
    top: -70px;
    background: radial-gradient(circle, rgb(37 99 235 / 18%) 0%, transparent 68%);
    animation: drift 13s ease-in-out infinite alternate;
}

.orb-b {
    width: 240px;
    height: 240px;
    right: -18px;
    bottom: -100px;
    background: radial-gradient(circle, rgb(14 165 233 / 16%) 0%, transparent 68%);
    animation: drift 16s ease-in-out infinite alternate-reverse;
}

/* 极光：conic-gradient 旋转出缓慢流动的色带，模糊后成为背景氛围光 */
.hero-aurora {
    position: absolute;
    z-index: 0;
    top: -360px;
    left: 50%;
    width: 840px;
    height: 840px;
    margin-left: -420px;
    border-radius: 50%;
    background: conic-gradient(
        from 90deg,
        rgb(37 99 235 / 30%),
        rgb(14 165 233 / 22%),
        rgb(168 85 247 / 20%),
        rgb(37 99 235 / 30%)
    );
    filter: blur(80px);
    opacity: 0.55;
    animation: aurora-spin 28s linear infinite;
    pointer-events: none;
}

.hero-card {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr);
    gap: 38px;
    align-items: center;
    padding: 30px 36px;
    border: 1px solid rgb(37 99 235 / 14%);
    border-radius: var(--radius-xl);
    background:
        radial-gradient(620px 280px at 100% 0%, rgb(14 165 233 / 10%), transparent 70%),
        linear-gradient(135deg, #eff6ff 0%, #ffffff 55%, #f8fafc 100%);
    box-shadow: var(--shadow-lg);
    overflow: hidden;
}

/* 细点阵底纹：用径向渐变绘制，不引入图片；mask 让点阵向左上角自然淡出 */
.hero-card::before {
    content: '';
    position: absolute;
    inset: 0;
    background-image: radial-gradient(rgb(37 99 235 / 14%) 1px, transparent 1px);
    background-size: 20px 20px;
    opacity: 0.35;
    pointer-events: none;
    -webkit-mask-image: linear-gradient(120deg, #000 0%, transparent 55%);
    mask-image: linear-gradient(120deg, #000 0%, transparent 55%);
}

/* 缓慢扫过卡片的高光：只改 transform，合成层动画，不参与布局 */
.hero-card::after {
    content: '';
    position: absolute;
    top: -45%;
    left: -35%;
    width: 34%;
    height: 190%;
    background: linear-gradient(90deg, transparent, rgb(255 255 255 / 55%), transparent);
    transform: rotate(18deg) translateX(-160%);
    animation: sheen 11s ease-in-out infinite;
    pointer-events: none;
}

.hero-visual {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 14px;
}

.hero-cover-frame {
    width: 100%;
    padding: 10px;
    border-radius: var(--radius-xl);
    background: rgb(255 255 255 / 72%);
    border: 1px solid rgb(37 99 235 / 14%);
    box-shadow: var(--shadow-md);
    animation: floaty 7s ease-in-out infinite;
}

.hero-cover {
    display: block;
    width: 100%;
    aspect-ratio: 1 / 1;
    object-fit: contain;
    border-radius: var(--radius-lg);
    background: #fff;
}

.hero-badge {
    padding: 4px 12px;
    border-radius: 999px;
    border: 1px solid rgb(37 99 235 / 14%);
    background: rgb(37 99 235 / 6%);
    color: var(--brand);
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.22em;
}

.hero-copy {
    position: relative;
    z-index: 1;
    min-width: 0;
}

/* 区块标题不用小标（eyebrow）：中文小字放在标题上方显得局促，也没有提供额外信息 */

.hero h1 {
    margin: 0 0 12px;
    font-size: clamp(36px, 4.4vw, 52px);
    line-height: 1.12;
    letter-spacing: -0.025em;
    color: var(--text);
}

/* 仅在浏览器支持 background-clip: text 时才使用渐变文字，避免不支持时文字变透明 */
@supports (background-clip: text) or (-webkit-background-clip: text) {
    .hero h1 {
        background: linear-gradient(120deg, #0f172a 0%, #1d4ed8 58%, #0ea5e9 100%);
        -webkit-background-clip: text;
        background-clip: text;
        -webkit-text-fill-color: transparent;
        color: transparent;
    }
}

/* 一句话定位：比 h1 更具体地说明「这群人做什么」，
   字号介于标题与正文之间，让扫读的人不必读完整段 lead */
.hero-tagline {
    margin: 6px 0 14px;
    font-size: 17px;
    font-weight: 600;
    color: #1d4ed8;
    letter-spacing: 0.01em;
}

.lead {
    max-width: 560px;
    margin: 0 0 24px;
    color: var(--muted);
    font-size: 15px;
    line-height: 1.8;
}

.actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}

.btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 11px 22px;
    border-radius: var(--radius-sm);
    font-size: 15px;
    font-weight: 600;
    transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.btn.primary {
    background: linear-gradient(135deg, var(--brand) 0%, #0ea5e9 100%);
    color: #fff;
    box-shadow: 0 8px 20px rgb(37 99 235 / 22%);
}

.btn.primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 10px 26px rgb(37 99 235 / 26%);
    text-decoration: none;
}

.btn.ghost {
    border: 1px solid rgb(37 99 235 / 28%);
    background: rgb(255 255 255 / 70%);
    color: var(--brand);
}

.btn.ghost:hover {
    transform: translateY(-2px);
    background: #fff;
    box-shadow: 0 10px 24px rgb(37 99 235 / 12%);
    text-decoration: none;
}

.arrow {
    transition: transform 0.2s ease;
}

.btn.primary:hover .arrow {
    transform: translateX(3px);
}

.hero-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 22px;
    padding-top: 18px;
    border-top: 1px dashed rgb(37 99 235 / 16%);
    color: var(--text-secondary);
    font-size: 13px;
}

/* 能力标签做成胶囊：比「圆点 + 文字」更像一组能力，也更容易扫读 */
.hero-meta span {
    display: inline-flex;
    align-items: center;
    padding: 5px 12px;
    border: 1px solid rgb(37 99 235 / 16%);
    border-radius: 999px;
    background: rgb(255 255 255 / 70%);
    color: #1e40af;
    font-size: 12.5px;
    font-weight: 500;
    transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.hero-meta span:hover {
    border-color: rgb(37 99 235 / 40%);
    background: #eff6ff;
    transform: translateY(-1px);
}

/* ===== 首屏数据条 ===== */
.hero-metrics {
    position: relative;
    z-index: 2;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    margin: -22px 28px 0;
}

.metric {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 18px;
    border: 1px solid rgb(37 99 235 / 12%);
    border-radius: var(--radius-lg);
    /* 半透明玻璃：压在首屏与下一版块的交界处，靠模糊把两层内容区分开 */
    background: rgb(255 255 255 / 86%);
    backdrop-filter: blur(10px);
    box-shadow: var(--shadow);
    transition: transform 0.25s ease, box-shadow 0.25s ease;
}

.metric:hover {
    transform: translateY(-3px);
    box-shadow: var(--shadow-md);
}

.metric-icon {
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: var(--radius);
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 6px 16px rgb(37 99 235 / 18%);
}

.metric-icon svg {
    width: 20px;
    height: 20px;
}

.metric-body {
    min-width: 0;
}

.metric-value {
    display: flex;
    align-items: baseline;
    gap: 3px;
    font-size: 20px;
    font-weight: 800;
    line-height: 1.2;
    letter-spacing: -0.02em;
    color: var(--text);
}

.metric-unit {
    font-size: 12px;
    font-weight: 500;
    color: var(--muted);
}

.metric-label {
    color: var(--muted);
    font-size: 12px;
}

.section {
    margin-top: 52px;
}

/* 区块标题：左侧一条渐变色竖条作视觉锚点——比小字更安静，同样能说清「这是新的一节」 */
.section-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
}

.section-head > div {
    position: relative;
    padding-left: 14px;
}

.section-head > div::before {
    content: '';
    position: absolute;
    left: 0;
    top: 6px;
    bottom: 6px;
    width: 4px;
    border-radius: 999px;
    background: linear-gradient(180deg, #2563eb, #38bdf8);
}

.section-head h2 {
    margin: 0;
    font-size: 25px;
    font-weight: 700;
    letter-spacing: -0.015em;
    color: var(--text);
}

/* 分区副标题：用于补充统计口径等说明，字号比正文更轻 */
.section-sub {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.4;
}

/* 荣誉区块：用极淡的蓝底把它从上下两段白底内容里区分出来，
   让长页面有「章节感」而不是一条平铺直叙的白带 */
.stats-section {
    padding: 32px 26px;
    border-radius: var(--radius-xl);
    background: linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
    border: 1px solid rgb(37 99 235 / 8%);
}

.more {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    white-space: nowrap;
    color: var(--brand);
    font-size: 13px;
    font-weight: 600;
    transition: gap 0.2s ease;
}

.more:hover {
    gap: 9px;
    text-decoration: none;
}

.more-arrow {
    transition: transform 0.2s ease;
}

.more:hover .more-arrow {
    transform: translateX(2px);
}

.tech-strip {
    overflow: hidden;
    margin-top: 28px;
    padding: 11px 0;
    border-top: 1px solid var(--border);
    border-bottom: 1px solid var(--border);
    background: linear-gradient(90deg, transparent, rgb(37 99 235 / 4%), transparent);
    -webkit-mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent);
    mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent);
}

.tech-track {
    display: flex;
    width: max-content;
    /* 条目变多后按比例放慢：速度保持原来看得清的水平，而不是越滚越快 */
    animation: marquee 60s linear infinite;
}

.tech-group {
    display: flex;
    align-items: center;
    gap: 30px;
    padding-right: 30px;
}

.tech-group span {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    white-space: nowrap;
    color: var(--muted);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
}

.tech-group span::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--brand);
    opacity: 0.45;
}

.tech-strip:hover .tech-track {
    animation-play-state: paused;
}

/* ===== 荣誉数据 ===== */
.stat-card {
    position: relative;
    display: flex;
    align-items: center;
    gap: 22px;
    padding: 26px 30px;
    border: 1px solid rgb(37 99 235 / 14%);
    border-radius: var(--radius-xl);
    background: linear-gradient(120deg, #fff 0%, #f0f7ff 100%);
    box-shadow: var(--shadow-md);
    overflow: hidden;
}

.stat-icon-wrap {
    position: relative;
    z-index: 1;
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    border-radius: var(--radius-lg);
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 8px 20px rgb(37 99 235 / 20%);
}

.stat-icon {
    width: 32px;
    height: 32px;
}

.stat-body {
    position: relative;
    z-index: 1;
    flex: 1;
    min-width: 0;
}

.stat-top {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 14px;
}

.num {
    font-size: clamp(40px, 5vw, 56px);
    font-weight: 800;
    line-height: 1;
    letter-spacing: -0.04em;
    color: var(--brand);
}

@supports (background-clip: text) or (-webkit-background-clip: text) {
    .num {
        background: linear-gradient(135deg, #1d4ed8, #0ea5e9);
        -webkit-background-clip: text;
        background-clip: text;
        -webkit-text-fill-color: transparent;
        color: transparent;
    }
}

.label {
    color: var(--muted);
    font-size: 14px;
}

.level-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 14px;
}

.level-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 999px;
    border: 1px solid var(--border);
    background: #fff;
    color: var(--text-secondary);
    font-size: 12px;
}

.dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--brand);
}

.level-chip.national .dot {
    background: #2563eb;
}

.level-chip.provincial .dot {
    background: #0ea5e9;
}

.level-chip.municipal .dot {
    background: #22c55e;
}

.stat-watermark {
    position: absolute;
    right: -18px;
    bottom: -34px;
    width: 180px;
    height: 180px;
    color: rgb(37 99 235 / 6%);
    pointer-events: none;
}

.charts {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 20px;
    margin-top: 20px;
}

.chart-card {
    position: relative;
    overflow: hidden;
    transition: transform 0.25s ease, box-shadow 0.25s ease;
}

.chart-card::before {
    content: '';
    position: absolute;
    inset: 0 0 auto;
    height: 3px;
    background: linear-gradient(90deg, #2563eb, #0ea5e9, #22d3ee);
    opacity: 0.85;
}

.chart-card:hover {
    transform: translateY(-3px);
    box-shadow: var(--shadow-md);
}

.chart-card h2 {
    margin: 0 0 2px;
    font-size: 17px;
}

.chart-sub {
    margin: 0;
    color: var(--muted);
    font-size: 12px;
}

.chart {
    height: 260px;
    margin-top: 8px;
}

.cta-band {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 28px;
    margin-top: 38px;
    padding: 30px 34px;
    border-radius: var(--radius-xl);
    overflow: hidden;
    background: linear-gradient(120deg, #0f172a 0%, #1e3a8a 58%, #0369a1 100%);
    box-shadow: var(--shadow-lg);
}

.cta-band::before,
.cta-band::after {
    content: '';
    position: absolute;
    border-radius: 50%;
    pointer-events: none;
}

.cta-band::before {
    width: 220px;
    height: 220px;
    right: -60px;
    top: -100px;
    background: radial-gradient(circle, rgb(56 189 248 / 28%), transparent 68%);
}

.cta-band::after {
    width: 180px;
    height: 180px;
    left: 38%;
    bottom: -120px;
    background: radial-gradient(circle, rgb(37 99 235 / 30%), transparent 68%);
}

.cta-copy {
    position: relative;
    z-index: 1;
}

.cta-copy h2 {
    margin: 0 0 8px;
    color: #fff;
    font-size: 26px;
    letter-spacing: -0.01em;
}

.cta-copy p {
    margin: 0;
    color: rgb(226 232 240 / 82%);
    font-size: 14px;
}

.cta-actions {
    position: relative;
    z-index: 1;
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}

.btn.light {
    background: #fff;
    color: #1e3a8a;
    box-shadow: 0 10px 24px rgb(2 6 23 / 20%);
}

.btn.light:hover {
    transform: translateY(-2px);
    text-decoration: none;
}

.btn.outline-light {
    border: 1px solid rgb(255 255 255 / 45%);
    background: rgb(255 255 255 / 8%);
    color: #fff;
}

.btn.outline-light:hover {
    transform: translateY(-2px);
    background: rgb(255 255 255 / 16%);
    text-decoration: none;
}

/* ===== 滚动入场（由 v-reveal 指令添加类名，脚本失效时元素默认可见）===== */
.reveal {
    opacity: 0;
    transform: translateY(18px);
    transition: opacity 0.6s ease, transform 0.6s cubic-bezier(0.22, 0.61, 0.36, 1);
}

.reveal.revealed {
    opacity: 1;
    transform: none;
}

@keyframes floaty {
    0%,
    100% {
        transform: translateY(0);
    }
    50% {
        transform: translateY(-7px);
    }
}

@keyframes drift {
    0% {
        transform: translate3d(0, 0, 0);
    }
    100% {
        transform: translate3d(0, 14px, 0);
    }
}

@keyframes sheen {
    0%,
    55% {
        transform: rotate(18deg) translateX(-160%);
    }
    78%,
    100% {
        transform: rotate(18deg) translateX(420%);
    }
}

@keyframes pulse {
    0%,
    100% {
        opacity: 1;
    }
    50% {
        opacity: 0.45;
    }
}

@keyframes aurora-spin {
    from {
        transform: rotate(0deg);
    }
    to {
        transform: rotate(360deg);
    }
}

@keyframes marquee {
    from {
        transform: translate3d(0, 0, 0);
    }
    to {
        transform: translate3d(-50%, 0, 0);
    }
}

@media (max-width: 900px) {
    .hero-card {
        grid-template-columns: 220px minmax(0, 1fr);
        gap: 26px;
        padding: 26px;
    }

    .stat-card {
        padding: 22px;
    }
}

@media (max-width: 760px) {
    .hero {
        padding: 28px 0 24px;
    }

    .hero-card {
        grid-template-columns: 1fr;
        gap: 20px;
        padding: 24px;
        text-align: center;
    }

    .hero-visual {
        max-width: 220px;
        margin: 0 auto;
    }

    .hero h1 {
        font-size: 34px;
    }

    .lead {
        margin-left: auto;
        margin-right: auto;
    }

    .actions,
    .hero-meta {
        justify-content: center;
    }

    /* 三列数字条在手机上会挤成豆腐块：改成单列，图标与数字并排 */
    .hero-metrics {
        grid-template-columns: 1fr;
        gap: 10px;
        margin: -16px 12px 0;
    }

    .metric {
        padding: 12px 16px;
    }

    .section-head {
        align-items: flex-start;
        flex-direction: column;
        gap: 6px;
    }

    .section-head h2 {
        font-size: 22px;
    }

    .stat-card {
        align-items: flex-start;
        gap: 16px;
        padding: 20px;
    }

    .stat-icon-wrap {
        width: 52px;
        height: 52px;
        border-radius: var(--radius-lg);
    }

    .stat-icon {
        width: 26px;
        height: 26px;
    }

    .charts {
        grid-template-columns: 1fr;
    }

    .cta-band {
        flex-direction: column;
        align-items: flex-start;
        padding: 26px;
    }

    .cta-actions {
        width: 100%;
    }

    .btn.light,
    .btn.outline-light {
        flex: 1;
    }
}

/* 尊重系统「减少动态效果」设置：所有装饰动画与过渡都关闭，内容依然完整可读 */
@media (prefers-reduced-motion: reduce) {
    .hero-cover-frame,
    .hero-orb,
    .hero-aurora,
    .hero-card::after,
    .tech-track {
        animation: none;
    }

    .btn,
    .more,
    .chart-card,
    .metric,
    .reveal {
        transition: none;
    }

    .reveal {
        opacity: 1;
        transform: none;
    }
}
</style>
