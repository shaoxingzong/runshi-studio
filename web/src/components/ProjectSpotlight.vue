<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { portalApi } from '@/api/portal';
import SmartImage from '@/components/SmartImage.vue';
import type { Project } from '@/api/types';

/** 首页「项目精选」：静态卡片，固定展示排序最靠前的三个项目（不轮播） */
const MAX_COUNT = 3;

/** 项目状态：取值与后端 ProjectStatusEnum 一致（0 研发中 / 1 已上线 / 2 已结题） */
const STATUS: Record<number, { text: string; type: 'success' | 'warning' | 'info' }> = {
    0: { text: '研发中', type: 'warning' },
    1: { text: '已上线', type: 'success' },
    2: { text: '已结题', type: 'info' },
};

const projects = ref<Project[]>([]);
const loading = ref(true);
const failed = ref(false);
/** 失败原因：展示给用户而不是静默消失，否则首页看起来像「没有项目」 */
const failedReason = ref('');

/** 拉取项目；失败置标记 + 记原因，供模板展示错误条与重试 */
async function loadProjects() {
    loading.value = true;
    failed.value = false;
    failedReason.value = '';
    try {
        const page = await portalApi.projectList({ current: 1, pageSize: MAX_COUNT });
        // 后端已按 sort_order 倒序返回，这里直接取前 N 条即可（置顶的项目自然在最前）
        projects.value = page.records ?? [];
    } catch (e) {
        console.warn('项目精选加载失败', e);
        failed.value = true;
        failedReason.value = (e as Error).message || '项目加载失败';
    } finally {
        loading.value = false;
    }
}

/** 重试：先清掉旧数据再拉，避免「新的没回来、旧的还挂着」的中间态 */
function retry() {
    projects.value = [];
    void loadProjects();
}

onMounted(loadProjects);

function statusOf(status?: number | null) {
    return STATUS[status ?? 0] ?? STATUS[0];
}
</script>

<template>
    <!-- 失败要显式提示：整块静默消失时，首页看起来像「没有项目」，无法与接口故障区分 -->
    <el-alert v-if="failed" type="error" :closable="false" show-icon class="spotlight-failed">
        <template #title>
            <span class="failed-msg">{{ failedReason }}</span>
            <el-button link type="primary" @click="retry">重试</el-button>
        </template>
    </el-alert>

    <!-- 加载中给容器一个最小高度：空容器高度为 0，v-loading 的遮罩会无处附着 -->
    <div v-else class="stage" :class="{ 'is-loading': loading }" v-loading="loading">
        <div v-if="projects.length" class="projects">
            <article v-for="item in projects" :key="item.id" class="project-card">
                <!-- 封面：有图用图，无图或加载失败都用「渐变 + 首字」占位 -->
                <div class="cover">
                    <SmartImage :src="item.coverImage" :alt="item.title" :seed="item.id" />
                    <el-tag class="status" :type="statusOf(item.status).type" size="small" effect="light">
                        {{ statusOf(item.status).text }}
                    </el-tag>
                </div>

                <div class="body">
                    <h3 class="title">
                        <!-- 伸展式链接：标题伪元素铺满整卡，卡片任意位置都能点进详情 -->
                        <router-link :to="`/projects/${item.id}`">{{ item.title }}</router-link>
                    </h3>
                    <p class="desc">{{ item.description || '暂无简介' }}</p>

                    <div v-if="item.techStack?.length" class="tags">
                        <span v-for="tech in item.techStack" :key="tech" class="tag">{{ tech }}</span>
                    </div>

                    <div class="foot">
                        <span class="more">查看详情 <i class="arrow">→</i></span>
                        <a
                            v-if="item.githubUrl"
                            :href="item.githubUrl"
                            target="_blank"
                            rel="noopener"
                            class="source"
                        >
                            源码
                        </a>
                    </div>
                </div>
            </article>
        </div>

        <!-- 没有项目：给出下一步动作，而不是留一块空白 -->
        <div v-else-if="!loading" class="empty">
            <p class="empty-title">还没有项目</p>
            <p class="empty-sub">项目上线后会按排序展示在这里。</p>
            <router-link to="/projects" class="btn primary">去项目页看看</router-link>
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

/* 与卡片同高：加载态不塌成一条空白，也不让下方内容往上跳 */
.stage.is-loading {
    min-height: 300px;
}

.projects {
    display: grid;
    /* 固定三列：项目不足三个时卡片也按 1/3 宽度排，不会被拉成一张巨型卡 */
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
}

.project-card {
    position: relative;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow);
    color: var(--text);
    transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
}

.project-card:hover {
    transform: translateY(-3px);
    border-color: rgb(37 99 235 / 26%);
    box-shadow: var(--shadow-md);
}

.cover {
    position: relative;
    aspect-ratio: 16 / 9;
    overflow: hidden;
    background: #eff6ff;
}

/* SmartImage 的图片与兜底色块都填满封面区（兜底时显示项目名首字 + 渐变底色）*/
.cover :deep(.img-fallback__img),
.cover :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
    object-fit: cover;
    font-size: 64px;
}

.cover :deep(.img-fallback__img) {
    transition: transform 0.5s ease;
}

.project-card:hover .cover :deep(.img-fallback__img) {
    transform: scale(1.04);
}

.status {
    position: absolute;
    top: 12px;
    left: 12px;
    border: none;
    backdrop-filter: blur(6px);
}

.body {
    display: flex;
    flex-direction: column;
    flex: 1;
    gap: 8px;
    padding: 16px 18px 18px;
}

.title {
    margin: 0;
    font-size: 17px;
    line-height: 1.45;
}

.title a {
    display: block;
    overflow: hidden;
    color: var(--text);
    text-overflow: ellipsis;
    white-space: nowrap;
}

.title a:hover {
    color: var(--brand);
    text-decoration: none;
}

/* 键盘可达性：整卡是热区，焦点环也要落在卡片上，而不是缩在标题里 */
.project-card:has(.title a:focus-visible) {
    outline: 2px solid var(--brand);
    outline-offset: 2px;
}

/* 伸展式链接：伪元素铺满整张卡片，卡片任意位置都能点进详情 */
.title a::after {
    content: '';
    position: absolute;
    inset: 0;
    z-index: 1;
}

.desc {
    margin: 0;
    flex: 1;
    color: var(--muted);
    font-size: 13px;
    line-height: 1.7;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    max-height: 54px;
    overflow: hidden;
}

.tag {
    padding: 2px 9px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--bg);
    color: var(--muted);
    font-size: 11px;
}

.foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-top: 4px;
    padding-top: 10px;
    border-top: 1px dashed var(--border);
    font-size: 12px;
}

.more {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--brand);
    font-weight: 600;
}

.project-card:hover .more .arrow {
    transform: translateX(3px);
}

/* 外链抬到覆盖层之上，否则点「源码」会被整卡的详情跳转吃掉 */
.source {
    position: relative;
    z-index: 2;
    color: var(--muted);
}

.source:hover {
    color: var(--brand);
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
    .projects {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}

@media (max-width: 600px) {
    .projects {
        grid-template-columns: 1fr;
    }
}

/* 尊重系统「减少动态效果」设置：关掉装饰动画，内容依然完整可读 */
@media (prefers-reduced-motion: reduce) {
    .project-card,
    .cover :deep(.img-fallback__img),
    .arrow {
        transition: none;
    }
}
</style>
