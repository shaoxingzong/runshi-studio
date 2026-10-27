<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { portalApi } from '@/api/portal';
import SmartImage from '@/components/SmartImage.vue';
import type { Project } from '@/api/types';

/**
 * 首页「项目精选」轮播
 *
 * 数据来自 GET /project/list，不写死图片：运营在后台增删项目，首页自动跟着变。
 * 封面有图用图，无图用「渐变底 + 项目名首字」占位，不依赖任何图片资源。
 * 接口失败显示错误条 + 重试；确实没有项目才整块不渲染（空轮播框比没有更糟）。
 */
const MAX_COUNT = 6;

/** 项目状态：取值与后端 ProjectStatusEnum 一致（0 研发中 / 1 已上线 / 2 已结题） */
const STATUS: Record<number, { text: string; type: 'success' | 'warning' | 'info' }> = {
    0: { text: '研发中', type: 'warning' },
    1: { text: '已上线', type: 'success' },
    2: { text: '已结题', type: 'info' },
};

const projects = ref<Project[]>([]);
const failed = ref(false);
/** 失败原因：展示给用户而不是静默消失，否则首页看起来像「没有项目」 */
const failedReason = ref('');

/** 拉取项目；失败置标记 + 记原因，供模板展示错误条与重试 */
async function loadProjects() {
    try {
        const page = await portalApi.projectList({ current: 1, pageSize: MAX_COUNT });
        // 后端已按 sort_order 倒序返回，这里直接取前 N 条即可（置顶的项目自然在最前）
        projects.value = page.records ?? [];
        failed.value = false;
        failedReason.value = '';
    } catch (e) {
        console.warn('项目轮播加载失败', e);
        failed.value = true;
        failedReason.value = (e as Error).message || '项目加载失败';
    }
}

/** 重试：先清掉旧数据再拉，避免「新的没回来、旧的还挂着」的中间态 */
function retry() {
    projects.value = [];
    loadProjects();
}

onMounted(loadProjects);

// 封面兜底（首字 + 渐变）统一交给 SmartImage：
// 各页各写一套会让字号、渐变、颜色都对不上，统一后全站「图挂了长什么样」只有一种答案。

function statusOf(status?: number | null) {
    return STATUS[status ?? 0] ?? STATUS[0];
}
</script>

<template>
    <!-- 失败要显式提示：整块静默消失时，首页看起来像「没有项目」，无法与接口故障区分 -->
    <el-alert
        v-if="failed"
        type="error"
        :closable="false"
        show-icon
        class="spotlight-failed"
    >
        <template #title>
            <span class="failed-msg">{{ failedReason }}</span>
            <el-button link type="primary" @click="retry">重试</el-button>
        </template>
    </el-alert>

    <el-carousel
        v-else-if="projects.length"
        height="300px"
        :interval="5000"
        arrow="hover"
        pause-on-hover
        class="spotlight"
    >
        <el-carousel-item v-for="item in projects" :key="item.id">
            <div class="slide">
                <!-- 封面：有图用图，无图或加载失败都用「渐变 + 首字」占位 -->
                <div class="cover">
                    <SmartImage :src="item.coverImage" :alt="item.title" :seed="item.id" />
                </div>

                <div class="info">
                    <el-tag :type="statusOf(item.status).type" size="small" effect="light">
                        {{ statusOf(item.status).text }}
                    </el-tag>

                    <h2 class="title">{{ item.title }}</h2>
                    <p class="desc">{{ item.description || '暂无简介' }}</p>

                    <div v-if="item.techStack?.length" class="tags">
                        <span v-for="tech in item.techStack" :key="tech" class="tag">{{ tech }}</span>
                    </div>

                    <div class="cta">
                        <router-link :to="`/projects/${item.id}`" class="btn primary">查看详情</router-link>
                        <a
                            v-if="item.githubUrl"
                            :href="item.githubUrl"
                            target="_blank"
                            rel="noopener"
                            class="btn ghost"
                        >
                            源码
                        </a>
                    </div>
                </div>
            </div>
        </el-carousel-item>
    </el-carousel>
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

.spotlight {
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--surface);
    border: 1px solid var(--border);
    box-shadow: var(--shadow);
}

.slide {
    display: flex;
    height: 100%;
}

.cover {
    width: 42%;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
}

/* SmartImage 的图片与兜底色块都铺满封面区；兜底时字号大一些，
   让「项目名首字」在 42% 宽的区块里也足够醒目 */
.cover :deep(.img-fallback__img),
.cover :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
    object-fit: cover;
    font-size: 96px;
}

.info {
    flex: 1;
    min-width: 0;
    padding: 26px 30px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
}

.title {
    margin: 0;
    font-size: 24px;
}

.desc {
    margin: 0;
    color: var(--muted);
    font-size: 14px;
    line-height: 1.7;
    /* 轮播高度固定，描述过长必须截断，否则会把 CTA 挤出可视区 */
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.tag {
    font-size: 12px;
    padding: 2px 10px;
    border-radius: 999px;
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--muted);
}

.cta {
    margin-top: auto;
    display: flex;
    gap: 10px;
    padding-top: 6px;
}

.btn {
    padding: 8px 18px;
    border-radius: var(--radius-sm);
    font-size: 14px;
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

/* 窄屏：收窄封面，保证右侧文字区可读 */
@media (max-width: 720px) {
    .cover {
        width: 34%;
    }

    .initial {
        font-size: 60px;
    }

    .info {
        padding: 18px;
        gap: 8px;
    }

    .title {
        font-size: 19px;
    }

    .desc {
        -webkit-line-clamp: 2;
        line-clamp: 2;
        font-size: 13px;
    }
}
</style>
