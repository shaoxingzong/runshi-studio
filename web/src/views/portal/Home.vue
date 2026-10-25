<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { Directive } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';
import { portalApi } from '@/api/portal';
import { postApi } from '@/api/post';
import type { Overview, Post } from '@/api/types';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ProjectSpotlight from '@/components/ProjectSpotlight.vue';

// ECharts 按需注册：只引入真正用到的图表（柱状 / 饼图）与组件，
// 避免把近 1MB 的整包 ECharts 打进首屏。
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

// 首页技术滚动条的内容：纯文案，不增加任何接口请求。
// 只放团队真实在用的技术，避免为了好看堆一串与项目无关的关键词。
const TECH = ['Spring Boot', 'Vue 3', 'MyBatis-Plus', 'MySQL', 'Redis', 'LangChain4j', 'Docker', 'Nginx'];

// ==================== 社区精选 ====================

/**
 * 精选条数：首页只展示 3 篇（1 篇头条 + 2 篇榜单），再多会把首页拉得比项目区还长。
 * 拉取 9 篇而不是 3 篇：公开列表接口只有「置顶优先 + 时间倒序」一种排序，
 * 想挑出「优秀」的文章必须在客户端按热度重排——样本太小会让热度排序失去意义。
 */
const EXCELLENT_COUNT = 3;
const EXCELLENT_FETCH = 9;

const posts = ref<Post[]>([]);
/** 文章总数（分页元数据里的 total，字符串，比较/展示前要转数字） */
const postsTotal = ref<number | null>(null);
const postsLoading = ref(false);
const postsError = ref('');
/** 项目总数：hero 数据条用；失败时保持 null（显示占位符，不打扰首屏） */
const projectTotal = ref<number | null>(null);

/**
 * 热度评分：点赞权重最高（主动表达认可的成本比浏览高得多），
 * 评论次之，浏览最低。权重是产品口径，不是算法——后端并没有热度字段，
 * 首页这个排序只影响展示顺序，不参与任何业务判断，所以放在前端即可。
 */
const HOT_WEIGHT = { like: 6, comment: 4, view: 1 };

function hotScore(post: Post): number {
    return (
        (post.likeCount ?? 0) * HOT_WEIGHT.like +
        (post.commentCount ?? 0) * HOT_WEIGHT.comment +
        (post.viewCount ?? 0) * HOT_WEIGHT.view
    );
}

/** 精选：置顶优先（运营的显式意志），其次按热度分倒序 */
const excellentPosts = computed(() =>
    [...posts.value]
        .sort((a, b) => (b.pinned ?? 0) - (a.pinned ?? 0) || hotScore(b) - hotScore(a))
        .slice(0, EXCELLENT_COUNT),
);

/** 头条文章：精选里的第一篇，用大封面呈现 */
const featuredPost = computed(() => excellentPosts.value[0] ?? null);
/** 榜单文章：其余精选，用横向小卡片 */
const sidePosts = computed(() => excellentPosts.value.slice(1));

/**
 * 首页数据条
 *
 * 为什么数字来自三个不同接口：证书、文章、项目分别由三个模块维护，
 * 没有一个「首页聚合」接口可以少发请求——刻意不新增后端接口，
 * 是因为这三个数字只在首页出现，为它们建聚合接口不值得（首页已有各自的分页接口）。
 */
const metrics = computed(() => [
    {
        key: 'award',
        label: '荣誉证书',
        value: overview.value?.certificateTotal ?? null,
        unit: '项',
        icon: ICON_PATHS.award,
    },
    { key: 'article', label: '社区文章', value: postsTotal.value, unit: '篇', icon: ICON_PATHS.article },
    { key: 'project', label: '项目案例', value: projectTotal.value, unit: '个', icon: ICON_PATHS.project },
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

/**
 * 首页大盘（证书维度）
 *
 * 失败与帖子加载失败是两种独立的错误态：大盘挂了不影响社区精选，
 * 反之亦然——所以拆成两个请求、两套错误提示，而不是一个大 try 包住。
 */
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

/** 拉取社区文章并按热度挑出精选；失败时给出可重试的页内提示，不弹全局消息 */
async function loadPosts() {
    postsLoading.value = true;
    postsError.value = '';
    try {
        const page = await postApi.list({ current: 1, pageSize: EXCELLENT_FETCH });
        posts.value = page.records ?? [];
        postsTotal.value = Number(page.total ?? 0);
    } catch (e) {
        postsError.value = (e as Error).message || '社区文章加载失败';
    } finally {
        postsLoading.value = false;
    }
}

/**
 * 项目总数：只发一次 pageSize=1 的请求，拿分页元数据里的 total。
 * 失败静默——数据条属于装饰性信息，为它弹错误提示反而打断首屏阅读。
 */
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
    void loadPosts();
    void loadProjectTotal();
});

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

/** 日期只取到天：首页的时间信息是「大概什么时候发的」，精确到秒没有意义 */
function formatDate(value?: string | null): string {
    return value ? value.slice(0, 10) : '';
}

/** 封面占位用的首字（与项目轮播同一套策略：没图也不出现空白块） */
function initialOf(text?: string | null): string {
    return text?.trim().charAt(0).toUpperCase() || '润';
}

/** 无封面时的渐变底：按序号轮换，避免几篇文章的占位长得一模一样 */
const COVER_GRADIENTS = [
    'linear-gradient(135deg, #1d4ed8 0%, #38bdf8 100%)',
    'linear-gradient(135deg, #6d28d9 0%, #a78bfa 100%)',
    'linear-gradient(135deg, #0e7490 0%, #2dd4bf 100%)',
    'linear-gradient(135deg, #be123c 0%, #fb7185 100%)',
];

function coverGradient(index: number): string {
    return COVER_GRADIENTS[index % COVER_GRADIENTS.length];
}

/** 数据条图标：单 path 的线性图标，不引图标库（首页只有三个图标，不值得增加依赖） */
const ICON_PATHS = {
    award: 'M8 21h8M12 17v4M7 4h10v4a5 5 0 0 1-10 0V4Zm10 1h3v1.6a3.4 3.4 0 0 1-3.2 3.4M7 5H4v1.6a3.4 3.4 0 0 0 3.2 3.4',
    article: 'M7 3.5h7.5L19 8v12.5H7zM14.5 3.5V8H19M10 12h6M10 15.5h6M10 8.5h2.5',
    project: 'M12 3 20 7.4v9.2L12 21l-8-4.4V7.4L12 3Zm0 0v9m8-4.6-8 4.6-8-4.6',
} as const;

/**
 * 滚动入场指令 v-reveal
 *
 * 为什么不用动画库：只需要「进入视口淡入上移」这一种效果，
 * 引一个动画库（几十 KB）不划算。IntersectionObserver 原生就有，逻辑 15 行。
 *
 * 约定：
 * - 系统开启「减少动态效果」时直接不注册观察者——元素保持默认可见，绝不隐藏内容；
 * - 类名由 JS 添加（而不是初始就写在模板上），这样脚本异常时页面依然完整可读。
 */
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
                    <p class="eyebrow"><span class="eyebrow-dot"></span> STUDENT SOFTWARE STUDIO</p>
                    <h1>润石工作室</h1>
                    <p class="lead">这里陈列我们做过的项目与获得的荣誉，也可以直接向 AI 助手提问。</p>
                    <div class="actions">
                        <router-link to="/projects" class="btn primary">看看项目 <span class="arrow">→</span></router-link>
                        <router-link to="/posts" class="btn ghost">读读社区</router-link>
                        <router-link to="/ai" class="btn ghost">问问 AI 助手</router-link>
                    </div>
                    <div class="hero-meta">
                        <span>项目实战</span>
                        <span>荣誉沉淀</span>
                        <span>社区分享</span>
                        <span>AI 问答</span>
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

        <section v-reveal class="section">
            <div class="section-head">
                <div>
                    <p class="eyebrow">FEATURED PROJECTS</p>
                    <h2>项目精选</h2>
                </div>
                <router-link to="/projects" class="more">全部项目 <span class="more-arrow">→</span></router-link>
            </div>
            <div class="spotlight-wrap">
                <ProjectSpotlight />
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

        <!-- ==================== 社区精选 ==================== -->
        <section v-reveal class="section">
            <div class="section-head">
                <div>
                    <p class="eyebrow">COMMUNITY PICKS</p>
                    <h2>社区精选</h2>
                </div>
                <router-link to="/posts" class="more">逛逛社区 <span class="more-arrow">→</span></router-link>
            </div>

            <!-- 帖子接口失败：独立错误条 + 重试，不影响下方荣誉数据 -->
            <div v-if="postsError" class="posts-error">
                <span>{{ postsError }}</span>
                <el-button link type="primary" @click="loadPosts">重试</el-button>
            </div>

            <!-- 加载中给容器一个最小高度：空容器高度为 0，v-loading 的遮罩会无处附着，
                 用户只看到一块空白，分不清「正在加载」和「加载坏了」 -->
            <div
                v-else
                v-loading="postsLoading"
                class="picks-wrap"
                :class="{ 'picks-wrap--loading': postsLoading }"
            >
                <!-- 没有文章：给出下一步动作，而不是留一块空白 -->
                <div v-if="!postsLoading && !featuredPost" class="picks-empty">
                    <p class="picks-empty-title">社区还没有文章</p>
                    <p class="picks-empty-sub">第一篇技术分享，往往是从解决一个小问题开始的。</p>
                    <router-link to="/posts" class="btn primary">去社区看看</router-link>
                </div>

                <div v-else class="picks" :class="{ single: sidePosts.length === 0 }">
                    <!-- 头条：大封面 + 完整摘要 -->
                    <router-link v-if="featuredPost" :to="`/posts/${featuredPost.id}`" class="pick-feature">
                        <div class="pick-cover" :style="{ background: coverGradient(0) }">
                            <img
                                v-if="featuredPost.coverImage"
                                :src="featuredPost.coverImage"
                                :alt="featuredPost.title"
                                loading="lazy"
                            />
                            <span v-else class="pick-initial">{{ initialOf(featuredPost.title) }}</span>
                            <div class="pick-badges">
                                <span class="pick-rank">TOP 1</span>
                                <span v-if="featuredPost.pinned === 1" class="pick-pin">置顶</span>
                            </div>
                        </div>

                        <div class="pick-body">
                            <div class="pick-author">
                                <span class="avatar">{{ initialOf(featuredPost.authorName) }}</span>
                                <span class="author-name">{{ featuredPost.authorName || '工作室成员' }}</span>
                                <span class="sep">·</span>
                                <span>{{ formatDate(featuredPost.createdAt) }}</span>
                            </div>
                            <h3 class="pick-title">{{ featuredPost.title }}</h3>
                            <p class="pick-summary">
                                {{ featuredPost.summary || '点击阅读全文，看看成员在这篇里写了什么。' }}
                            </p>
                            <div class="pick-stats">
                                <span>浏览 {{ formatCount(featuredPost.viewCount) }}</span>
                                <span>赞 {{ formatCount(featuredPost.likeCount) }}</span>
                                <span>评论 {{ formatCount(featuredPost.commentCount) }}</span>
                                <span class="pick-read">阅读全文 <i class="arrow">→</i></span>
                            </div>
                        </div>
                    </router-link>

                    <!-- 榜单：横向小卡片，名次用编号强化「精选」语义 -->
                    <div v-if="sidePosts.length" class="pick-list">
                        <router-link
                            v-for="(post, index) in sidePosts"
                            :key="post.id"
                            :to="`/posts/${post.id}`"
                            class="pick-item"
                        >
                            <div class="pick-thumb" :style="{ background: coverGradient(index + 1) }">
                                <img
                                    v-if="post.coverImage"
                                    :src="post.coverImage"
                                    :alt="post.title"
                                    loading="lazy"
                                />
                                <span v-else class="thumb-initial">{{ initialOf(post.title) }}</span>
                            </div>
                            <div class="pick-item-body">
                                <div class="rank-row">
                                    <span class="rank-no">NO.0{{ index + 2 }}</span>
                                    <span v-if="post.pinned === 1" class="mini-pin">置顶</span>
                                </div>
                                <h3>{{ post.title }}</h3>
                                <p class="sum">{{ post.summary || '暂无摘要' }}</p>
                                <div class="mini-meta">
                                    <span class="author-name">{{ post.authorName || '工作室成员' }}</span>
                                    <span>浏览 {{ formatCount(post.viewCount) }}</span>
                                    <span>赞 {{ formatCount(post.likeCount) }}</span>
                                </div>
                            </div>
                        </router-link>
                    </div>
                </div>
            </div>
        </section>

        <!-- 大盘数据加载失败：用带重试按钮的统一组件。此前是裸 el-alert——
             提示会一直在，但没有恢复手段，用户只能刷新整个页面 -->
        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading">
            <template v-if="overview">
                <section v-reveal class="section">
                    <div class="section-head">
                        <div>
                            <p class="eyebrow">ACHIEVEMENTS</p>
                            <h2>荣誉数据</h2>
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
                <p class="eyebrow">JOIN THE STUDIO</p>
                <h2>把想法做成作品</h2>
                <p>从一行代码到上线项目，和一群认真做事的人一起成长。</p>
            </div>
            <div class="cta-actions">
                <router-link to="/posts" class="btn light">逛逛社区</router-link>
                <router-link to="/ai" class="btn outline-light">问问 AI 助手</router-link>
            </div>
        </section>
    </div>
</template>

<style scoped>
.home {
    position: relative;
    /* 多层径向光晕：蓝主色 + 青色补光 + 一点紫，让首屏与纯白背景分开；
       纯渐变绘制，不加载任何背景图 */
    background:
        radial-gradient(1100px 520px at 50% -180px, rgb(37 99 235 / 14%), transparent 70%),
        radial-gradient(760px 420px at 92% 4%, rgb(14 165 233 / 10%), transparent 72%),
        radial-gradient(620px 380px at 6% 16%, rgb(139 92 246 / 8%), transparent 70%);
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

/* 光晕只允许向页面内溢出一小段：
   位置保持在页面 padding 之内，且动画只做纵向位移，
   否则绝对定位元素会把 body 的 scrollWidth 撑宽（窄屏会出现横向滚动条）。 */
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
    border-radius: 28px;
    background:
        radial-gradient(620px 280px at 100% 0%, rgb(14 165 233 / 10%), transparent 70%),
        linear-gradient(135deg, #eff6ff 0%, #ffffff 55%, #f8fafc 100%);
    box-shadow: 0 24px 60px rgb(15 23 42 / 8%), 0 4px 14px rgb(15 23 42 / 4%);
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
    border-radius: 24px;
    background: rgb(255 255 255 / 72%);
    border: 1px solid rgb(37 99 235 / 14%);
    box-shadow: 0 18px 40px rgb(37 99 235 / 14%);
    animation: floaty 7s ease-in-out infinite;
}

.hero-cover {
    display: block;
    width: 100%;
    aspect-ratio: 1 / 1;
    object-fit: contain;
    border-radius: 16px;
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

.eyebrow {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 10px;
    color: var(--brand);
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.18em;
}

.eyebrow-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--brand);
    box-shadow: 0 0 0 4px rgb(37 99 235 / 12%);
    animation: pulse 2.4s ease-in-out infinite;
}

.hero h1 {
    margin: 0 0 12px;
    font-size: clamp(34px, 4vw, 48px);
    line-height: 1.15;
    letter-spacing: -0.02em;
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
    border-radius: 10px;
    font-size: 15px;
    font-weight: 600;
    transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.btn.primary {
    background: linear-gradient(135deg, var(--brand) 0%, #0ea5e9 100%);
    color: #fff;
    box-shadow: 0 10px 24px rgb(37 99 235 / 28%);
}

.btn.primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 14px 30px rgb(37 99 235 / 34%);
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
    gap: 8px 18px;
    margin-top: 20px;
    padding-top: 16px;
    border-top: 1px dashed rgb(37 99 235 / 16%);
    color: var(--text-secondary);
    font-size: 13px;
}

.hero-meta span {
    display: inline-flex;
    align-items: center;
    gap: 7px;
}

.hero-meta span::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #93c5fd;
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
    border-radius: 16px;
    /* 半透明玻璃：压在首屏与下一版块的交界处，靠模糊把两层内容区分开 */
    background: rgb(255 255 255 / 86%);
    backdrop-filter: blur(10px);
    box-shadow: 0 14px 30px rgb(15 23 42 / 6%);
    transition: transform 0.25s ease, box-shadow 0.25s ease;
}

.metric:hover {
    transform: translateY(-3px);
    box-shadow: 0 18px 38px rgb(37 99 235 / 12%);
}

.metric-icon {
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: 12px;
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 8px 18px rgb(37 99 235 / 24%);
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
    margin-top: 34px;
}

.section-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;
}

.section-head .eyebrow {
    margin: 0;
}

.section-head h2 {
    margin: 4px 0 0;
    font-size: 24px;
    letter-spacing: -0.01em;
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

/* 轮播外层的 1px 渐变描边：比整块重做轮播样式更容易维护 */
.spotlight-wrap {
    padding: 1px;
    border-radius: 16px;
    background: linear-gradient(135deg, rgb(37 99 235 / 35%), rgb(14 165 233 / 10%), rgb(37 99 235 / 5%));
    box-shadow: 0 22px 50px rgb(37 99 235 / 10%);
    transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.spotlight-wrap:hover {
    transform: translateY(-3px);
    box-shadow: 0 28px 60px rgb(37 99 235 / 16%);
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
    animation: marquee 30s linear infinite;
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

/* ===== 社区精选 ===== */
.picks-wrap--loading {
    position: relative;
    min-height: 240px;
}

.posts-error {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 16px;
    border: 1px solid rgb(220 38 38 / 22%);
    border-radius: 12px;
    background: #fef2f2;
    color: #b91c1c;
    font-size: 13px;
}

.picks {
    display: grid;
    grid-template-columns: 1.1fr 1fr;
    gap: 18px;
    align-items: stretch;
}

/* 只有一篇精选时让头条铺满整行，不要在右侧留一个空洞的列 */
.picks.single {
    grid-template-columns: 1fr;
}

.picks-empty {
    padding: 44px 24px;
    border: 1px dashed rgb(37 99 235 / 22%);
    border-radius: 20px;
    background: linear-gradient(120deg, #fff, #f0f7ff);
    text-align: center;
}

.picks-empty-title {
    margin: 0 0 6px;
    font-size: 17px;
    font-weight: 700;
    color: var(--text);
}

.picks-empty-sub {
    margin: 0 0 18px;
    color: var(--muted);
    font-size: 13px;
}

.pick-feature {
    display: flex;
    flex-direction: column;
    border: 1px solid var(--border);
    border-radius: 20px;
    background: var(--surface);
    box-shadow: var(--shadow);
    overflow: hidden;
    color: var(--text);
    transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.pick-feature:hover {
    transform: translateY(-4px);
    box-shadow: 0 26px 55px rgb(37 99 235 / 14%);
    text-decoration: none;
}

.pick-cover {
    position: relative;
    aspect-ratio: 16 / 9;
    overflow: hidden;
}

.pick-cover img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    /* 图片缓慢放大：hover 时给一点「叙述感」，只改 transform，不触发布局 */
    transition: transform 0.5s ease;
}

.pick-feature:hover .pick-cover img {
    transform: scale(1.05);
}

.pick-initial {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: rgb(255 255 255 / 92%);
    font-size: 72px;
    font-weight: 800;
    text-shadow: 0 4px 18px rgb(0 0 0 / 18%);
    user-select: none;
}

.pick-badges {
    position: absolute;
    top: 12px;
    left: 12px;
    display: flex;
    gap: 8px;
}

.pick-rank,
.pick-pin {
    padding: 3px 10px;
    border-radius: 999px;
    color: #fff;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
}

.pick-rank {
    background: rgb(15 23 42 / 78%);
    backdrop-filter: blur(6px);
}

.pick-pin {
    background: var(--brand);
    box-shadow: 0 6px 16px rgb(37 99 235 / 35%);
}

.pick-body {
    display: flex;
    flex-direction: column;
    flex: 1;
    gap: 10px;
    padding: 18px 22px 20px;
}

.pick-author {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 12px;
}

.avatar {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    border-radius: 50%;
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    font-size: 12px;
    font-weight: 700;
}

.author-name {
    color: var(--text-secondary);
    font-weight: 600;
}

.sep {
    color: var(--border);
}

.pick-title {
    margin: 0;
    font-size: 21px;
    line-height: 1.4;
    letter-spacing: -0.01em;
    /* 标题最多两行，避免长标题把摘要与数据挤出卡片 */
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.pick-summary {
    margin: 0;
    color: var(--muted);
    font-size: 14px;
    line-height: 1.75;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.pick-stats {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 14px;
    margin-top: auto;
    padding-top: 10px;
    border-top: 1px dashed var(--border);
    color: var(--muted);
    font-size: 12px;
}

.pick-read {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
    color: var(--brand);
    font-weight: 600;
}

.pick-feature:hover .pick-read .arrow {
    transform: translateX(3px);
}

.pick-list {
    display: flex;
    flex-direction: column;
    gap: 14px;
}

/* 两张榜单卡片均分右列高度，与左侧头条底边对齐 */
.pick-item {
    position: relative;
    display: flex;
    flex: 1;
    gap: 14px;
    min-width: 0;
    padding: 14px;
    border: 1px solid var(--border);
    border-radius: 16px;
    background: var(--surface);
    box-shadow: var(--shadow);
    color: var(--text);
    transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
}

.pick-item:hover {
    transform: translateY(-3px);
    border-color: rgb(37 99 235 / 26%);
    box-shadow: 0 18px 36px rgb(37 99 235 / 12%);
    text-decoration: none;
}

.pick-thumb {
    position: relative;
    flex: 0 0 124px;
    width: 124px;
    aspect-ratio: 4 / 3;
    border-radius: 12px;
    overflow: hidden;
}

.pick-thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
}

.thumb-initial {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: rgb(255 255 255 / 92%);
    font-size: 30px;
    font-weight: 800;
    text-shadow: 0 2px 12px rgb(0 0 0 / 18%);
    user-select: none;
}

.pick-item-body {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
}

.rank-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.rank-no {
    color: var(--brand);
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.1em;
}

.mini-pin {
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand);
    font-size: 10px;
    font-weight: 600;
}

.pick-item h3 {
    margin: 0;
    font-size: 15px;
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.pick-item .sum {
    margin: 0;
    color: var(--muted);
    font-size: 12.5px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.mini-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: auto;
    color: var(--muted);
    font-size: 12px;
}

/* ===== 荣誉数据 ===== */
.stat-card {
    position: relative;
    display: flex;
    align-items: center;
    gap: 22px;
    padding: 26px 30px;
    border: 1px solid rgb(37 99 235 / 14%);
    border-radius: 20px;
    background: linear-gradient(120deg, #fff 0%, #f0f7ff 100%);
    box-shadow: 0 18px 40px rgb(37 99 235 / 8%);
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
    border-radius: 18px;
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 12px 26px rgb(37 99 235 / 28%);
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
    border-radius: 24px;
    overflow: hidden;
    background: linear-gradient(120deg, #0f172a 0%, #1e3a8a 58%, #0369a1 100%);
    box-shadow: 0 24px 55px rgb(15 23 42 / 18%);
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

.cta-copy .eyebrow {
    margin-bottom: 8px;
    color: #93c5fd;
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

    /* 914px 以下两列太挤：头条与榜单改成上下排布 */
    .picks {
        grid-template-columns: 1fr;
    }

    .pick-thumb {
        flex-basis: 160px;
        width: 160px;
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

    .hero-copy .eyebrow {
        justify-content: center;
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
        border-radius: 15px;
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

@media (max-width: 560px) {
    /* 榜单卡片的缩略图在窄屏让位给文字：124px 宽图 + 两行摘要已经放不下 */
    .pick-item {
        padding: 12px;
    }

    .pick-thumb {
        flex-basis: 92px;
        width: 92px;
    }

    .thumb-initial {
        font-size: 24px;
    }

    .pick-title {
        font-size: 19px;
    }

    .pick-body {
        padding: 16px 16px 18px;
    }
}

/* 尊重系统「减少动态效果」设置：所有装饰动画与过渡都关闭，内容依然完整可读 */
@media (prefers-reduced-motion: reduce) {
    .hero-cover-frame,
    .hero-orb,
    .hero-aurora,
    .hero-card::after,
    .eyebrow-dot,
    .tech-track {
        animation: none;
    }

    .btn,
    .more,
    .spotlight-wrap,
    .chart-card,
    .metric,
    .pick-feature,
    .pick-item,
    .pick-cover img,
    .reveal {
        transition: none;
    }

    .reveal {
        opacity: 1;
        transform: none;
    }
}
</style>
