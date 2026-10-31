<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { postApi } from '@/api/post';
import SmartImage from '@/components/SmartImage.vue';
import { useUserStore } from '@/stores/user';
import type { Post } from '@/api/types';

/** 首页「社区精选」轮播：置顶优先、其次按热度，最多展示 5 篇 */
const FETCH_COUNT = 9;
const MAX_SLIDES = 5;
const user = useUserStore();

/** 热度评分：点赞权重最高（主动表达认可的成本比浏览高得多），评论次之，浏览最低。 */
const HOT_WEIGHT = { like: 6, comment: 4, view: 1 };

/** 文章总数交给首页数据条（社区文章 N 篇），组件内不再维护第二份计数 */
const emit = defineEmits<{ total: [value: number] }>();

const posts = ref<Post[]>([]);
const loading = ref(true);
const failed = ref(false);
/** 失败原因：显式展示而不是静默消失，否则首页看起来像「社区没有文章」 */
const failedReason = ref('');

function hotScore(post: Post): number {
    return (
        (post.likeCount ?? 0) * HOT_WEIGHT.like +
        (post.commentCount ?? 0) * HOT_WEIGHT.comment +
        (post.viewCount ?? 0) * HOT_WEIGHT.view
    );
}

/** 精选：置顶优先（运营的显式意志），其次按热度分倒序 */
const slides = computed(() =>
    [...posts.value]
        .sort((a, b) => (b.pinned ?? 0) - (a.pinned ?? 0) || hotScore(b) - hotScore(a))
        .slice(0, MAX_SLIDES),
);

async function loadPosts() {
    loading.value = true;
    failed.value = false;
    failedReason.value = '';
    try {
        const page = await postApi.list({ current: 1, pageSize: FETCH_COUNT });
        posts.value = page.records ?? [];
        emit('total', Number(page.total ?? 0));
    } catch (e) {
        failed.value = true;
        failedReason.value = (e as Error).message || '社区文章加载失败';
    } finally {
        loading.value = false;
    }
}

/** 重试：先清掉旧数据再拉，避免「新的没回来、旧的还挂着」的中间态 */
function retry() {
    posts.value = [];
    void loadPosts();
}

onMounted(loadPosts);

/** 数字格式化：过万转「万」，与首页数据条同一套规则 */
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

/** 名次编号：轮播本身就是「精选排行」，编号让顺序感更明确 */
function rankOf(index: number): string {
    return `NO.0${index + 1}`;
}
</script>

<template>
    <!-- 失败要显式提示：整块静默消失时，首页看起来像「社区没有文章」，无法与接口故障区分 -->
    <el-alert v-if="failed" type="error" :closable="false" show-icon class="spotlight-failed">
        <template #title>
            <span class="failed-msg">{{ failedReason }}</span>
            <el-button link type="primary" @click="retry">重试</el-button>
        </template>
    </el-alert>

    <!-- 加载中给容器一个最小高度：空容器高度为 0，v-loading 的遮罩会无处附着 -->
    <div v-else class="stage" :class="{ 'is-loading': loading }" v-loading="loading">
        <el-carousel
            v-if="slides.length"
            height="320px"
            :interval="5000"
            arrow="hover"
            pause-on-hover
            class="spotlight"
        >
            <el-carousel-item v-for="(item, index) in slides" :key="item.id">
                <!-- 每屏只有一张文章卡片，整屏一个链接，不产生嵌套 <a> -->
                <router-link :to="`/posts/${item.id}`" class="slide">
                    <div class="cover">
                        <SmartImage :src="item.coverImage" :alt="item.title" :seed="item.id" />
                    </div>

                    <div class="info">
                        <div class="info-top">
                            <span class="rank">{{ rankOf(index) }}</span>
                            <!-- 作者视角：精选轮播里也能认出自己的文章 -->
                            <span v-if="user.userId && item.authorId === user.userId" class="mine-tag">
                                我的文章
                            </span>
                        </div>
                        <h2 class="title">{{ item.title }}</h2>
                        <p class="summary">
                            {{ item.summary || '点击阅读全文，看看成员在这篇里写了什么。' }}
                        </p>

                        <div class="meta">
                            <span class="avatar">
                                <SmartImage
                                    :src="item.authorAvatar"
                                    :alt="item.authorName || '工作室成员'"
                                    :seed="item.authorId"
                                    fallback="avatar"
                                />
                            </span>
                            <span class="author">{{ item.authorName || '工作室成员' }}</span>
                            <span class="sep">·</span>
                            <span>{{ formatDate(item.createdAt) }}</span>
                            <span class="stat">浏览 {{ formatCount(item.viewCount) }}</span>
                            <span class="stat">赞 {{ formatCount(item.likeCount) }}</span>
                            <span class="stat">评论 {{ formatCount(item.commentCount) }}</span>
                            <span class="read">阅读全文 <i class="arrow">→</i></span>
                        </div>
                    </div>
                </router-link>
            </el-carousel-item>
        </el-carousel>

        <!-- 没有文章：给出下一步动作，而不是留一块空白 -->
        <div v-else-if="!loading" class="empty">
            <p class="empty-title">社区还没有文章</p>
            <p class="empty-sub">第一篇技术分享，往往是从解决一个小问题开始的。</p>
            <router-link to="/posts" class="btn primary">去社区看看</router-link>
        </div>
    </div>
</template>

<style scoped>
.spotlight-failed {
    margin-bottom: 16px;
}

.spotlight-failed :deep(.el-alert__title) {
    display: flex;
    align-items: center;
    gap: 10px;
}

.failed-msg {
    flex: 1;
    min-width: 0;
    word-break: break-all;
}

.stage {
    position: relative;
}

/* 与轮播同高：加载态不塌成一条空白，也不让下方内容往上跳 */
.stage.is-loading {
    min-height: 320px;
}

.spotlight {
    border-radius: var(--radius-lg);
    overflow: hidden;
    background: var(--surface);
}

.slide {
    display: flex;
    height: 100%;
    color: var(--text);
}

.slide:hover {
    text-decoration: none;
}

.cover {
    position: relative;
    width: 44%;
    flex-shrink: 0;
    overflow: hidden;
    background: #eff6ff;
}

/* SmartImage 的图片与兜底色块都铺满封面区；兜底时用文章名首字 */
.cover :deep(.img-fallback__img),
.cover :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
    object-fit: cover;
    font-size: 64px;
}

/* 封面缓慢放大：只改 transform，不触发布局 */
.cover :deep(.img-fallback__img) {
    transition: transform 0.5s ease;
}

.slide:hover .cover :deep(.img-fallback__img) {
    transform: scale(1.04);
}

.info {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    flex: 1;
    min-width: 0;
    gap: 10px;
    padding: 26px 30px;
}

.rank {
    color: var(--brand);
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.12em;
}

.info-top {
    display: flex;
    align-items: center;
    gap: 8px;
}

/* 与社区卡片的「我的」标记同一语义：白底蓝字，安静但一眼能认出 */
.mine-tag {
    padding: 1px 8px;
    border: 1px solid rgb(37 99 235 / 20%);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand);
    font-size: 11px;
    font-weight: 600;
}

.title {
    margin: 0;
    font-size: 23px;
    line-height: 1.4;
    letter-spacing: -0.01em;
    /* 轮播高度固定：标题与摘要都要截断，否则会把底部的数据行挤出可视区 */
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.summary {
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

.meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    width: 100%;
    margin-top: auto;
    padding-top: 12px;
    border-top: 1px dashed var(--border);
    color: var(--muted);
    font-size: 12px;
}

.avatar {
    display: inline-flex;
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    border-radius: 50%;
    overflow: hidden;
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
}

.avatar :deep(.img-fallback__box) {
    font-size: 11px;
}

.author {
    color: var(--text-secondary);
    font-weight: 600;
}

.sep {
    color: var(--border);
}

.stat {
    white-space: nowrap;
}

.read {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
    color: var(--brand);
    font-weight: 600;
}

.slide:hover .read .arrow {
    transform: translateX(3px);
}

.arrow {
    transition: transform 0.2s ease;
}

.empty {
    padding: 44px 24px;
    border: 1px dashed rgb(37 99 235 / 22%);
    border-radius: var(--radius-xl);
    background: linear-gradient(120deg, #fff, #f0f7ff);
    text-align: center;
}

.empty-title {
    margin: 0 0 6px;
    font-size: 17px;
    font-weight: 700;
    color: var(--text);
}

.empty-sub {
    margin: 0 0 18px;
    color: var(--muted);
    font-size: 13px;
}

.btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 11px 22px;
    border-radius: var(--radius-sm);
    background: linear-gradient(135deg, var(--brand) 0%, #0ea5e9 100%);
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    box-shadow: 0 8px 20px rgb(37 99 235 / 22%);
}

.btn:hover {
    text-decoration: none;
}

@media (max-width: 900px) {
    .cover {
        width: 38%;
    }

    .info {
        padding: 22px;
    }

    .title {
        font-size: 20px;
    }
}

@media (max-width: 720px) {
    .cover {
        width: 34%;
    }

    .info {
        padding: 18px;
        gap: 8px;
    }

    .title {
        font-size: 18px;
    }

    .summary {
        font-size: 13px;
        -webkit-line-clamp: 2;
        line-clamp: 2;
    }
}

/* 尊重系统「减少动态效果」设置：关掉装饰动画，内容依然完整可读 */
@media (prefers-reduced-motion: reduce) {
    .cover :deep(.img-fallback__img),
    .arrow {
        transition: none;
    }
}
</style>
