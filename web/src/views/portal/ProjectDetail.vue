<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { portalApi } from '@/api/portal';
import { usePageMeta } from '@/composables/usePageMeta';
import ErrorRetry from '@/components/ErrorRetry.vue';
import SmartImage from '@/components/SmartImage.vue';
import {
    bindCodeCopy,
    observeTocHighlight,
    renderMarkdown,
    sanitizeMarkdownHtml,
    scrollToHeading,
    TOC_MIN_ITEMS,
} from '@/utils/markdown';
import type { ProjectDetail as ProjectDetailType } from '@/api/types';

const route = useRoute();
const router = useRouter();
const detail = ref<ProjectDetailType | null>(null);
const loading = ref(false);
/** 加载失败（含 id 不存在）：只判断 detail 的话，失败时整页空白 */
const error = ref('');

/**
 * 封面与头像的兜底统一交给 SmartImage
 *
 * 不要自己维护默认图 / 坏 URL 集合：前者依赖一个「站内默认图」文件，文件缺失就是连环 404；
 * 「首字 + 渐变块」不依赖任何图片文件，永远不会裂图。
 */

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

/**
 * 参与成员（永远返回数组）
 *
 * 后端 members 声明成 List 却没加 @NotNull，历史数据可能返回 null；在 null 上取 .length 会抛
 * TypeError 并中断整个组件渲染，表现为「页面显示到一半就没了」。
 */
const members = computed(() => detail.value?.members ?? []);

/** 正文容器：代码块复制与目录高亮都要在真实 DOM 上接线（v-html 的内容 Vue 管不着） */
const bodyRef = ref<HTMLElement | null>(null);

/**
 * 正文渲染：renderMarkdown → 白名单清洗 → v-html
 *
 * 必须清洗：marked v15 起移除了内置 sanitize，而 v-html 直接写 DOM；「管理员录入」也不能免检
 * ——账号被盗、越权都可能让正文不可信。白名单须放行 renderer 的增强输出，否则会被静默洗掉。
 */
const rendered = computed(() => {
    const result = renderMarkdown(detail.value?.content ?? '');
    // 项目正文由管理员录入，可信度高于成员帖子：不限制图片外链。
    // 清洗仍不可省——账号被盗、越权写入都会让正文变成不可信来源
    result.html = sanitizeMarkdownHtml(result.html);
    return result;
});

const contentHtml = computed(() => rendered.value.html);
/** 目录（共享工具单次渲染时顺带抽取，与标题 id 同源，不会对不上） */
const toc = computed(() => rendered.value.toc);
/** 短文不出目录：两行字的文章配个目录框很滑稽 */
const showToc = computed(() => toc.value.length >= TOC_MIN_ITEMS);
/** 当前阅读章节（IntersectionObserver 驱动，见 observeTocHighlight） */
const activeHeading = ref('');

/** 文章重渲染（重试加载、切页）时先摘旧监听再绑新的，不叠加 */
let unbindCopy: (() => void) | null = null;
let unobserve: (() => void) | null = null;

async function bindMarkdownEnhancements() {
    // v-html 的内容要等 DOM 真正更新后才拿得到
    await nextTick();
    unbindCopy?.();
    unobserve?.();
    const el = bodyRef.value;
    if (!el) {
        return;
    }
    unbindCopy = bindCodeCopy(el);
    unobserve = observeTocHighlight(el, (id) => {
        activeHeading.value = id;
    });
}

// 正文 HTML 变化（detail 加载完成 / 重试成功）即重新接线
watch(contentHtml, bindMarkdownEnhancements);

onBeforeUnmount(() => {
    unbindCopy?.();
    unobserve?.();
});

async function load() {
    loading.value = true;
    error.value = '';
    try {
        detail.value = await portalApi.projectDetail(String(route.params.id));
    } catch (e) {
        error.value = (e as Error).message || '项目不存在或加载失败';
    } finally {
        loading.value = false;
    }
}

onMounted(load);

// 标签页标题用真实项目名（与 PostDetail 同一理由：详情页是收录与分享的主体）
//
// description 取项目摘要，比 index.html 里那句全站通用描述准确得多。
usePageMeta({
    title: computed(() => detail.value?.title ?? '项目详情'),
    description: computed(() => detail.value?.description ?? ''),
});
</script>

<template>
    <div class="page" v-loading="loading">
        <!-- 失败 / 不存在：给出原因与返回入口，而不是一片空白 -->
        <template v-if="error || !detail">
            <ErrorRetry v-if="error" :message="error" @retry="load" />
            <div v-else class="card muted">项目加载中…</div>
            <div class="actions">
                <el-button @click="router.push('/projects')">返回项目列表</el-button>
            </div>
        </template>
        <template v-else>
            <div class="card">
                <!-- 封面失败时不再留裂图：SmartImage 会退成「项目名首字 + 渐变块」 -->
                <div v-if="detail.coverImage" class="cover-wrap">
                    <SmartImage :src="detail.coverImage" :alt="detail.title" :seed="detail.id" />
                </div>
                <h1>{{ detail.title }}</h1>
                <span class="tag" :class="detail.status === 1 ? 'green' : 'gray'">
                    {{ STATUS_TEXT[String(detail.status)] || detail.status }}
                </span>
                <p class="muted">{{ detail.description }}</p>
                <div>
                    <span v-for="tag in detail.techStack ?? []" :key="tag" class="tag">{{ tag }}</span>
                </div>
                <div class="links">
                    <el-link v-if="detail.demoUrl" :href="detail.demoUrl" target="_blank" type="primary">
                        在线体验
                    </el-link>
                    <el-link v-if="detail.githubUrl" :href="detail.githubUrl" target="_blank" type="primary">
                        开源仓库
                    </el-link>
                </div>
            </div>

            <div class="card block">
                <h2>项目详情</h2>
                <!-- 目录：长文（标题 ≥3）才出现。href 保留原生锚点语义
                     （链接可复制、可中键新窗口），click 接平滑滚动 -->
                <nav v-if="showToc" class="toc">
                    <div class="toc-title">目录</div>
                    <a
                        v-for="item in toc"
                        :key="item.id"
                        class="toc-item"
                        :class="{ h3: item.level === 3, active: activeHeading === item.id }"
                        :href="`#${item.id}`"
                        @click.prevent="scrollToHeading(item.id)"
                    >
                        {{ item.text }}
                    </a>
                </nav>
                <!-- 正文为空时给出说明，而不是留一块空白让人以为加载失败 -->
                <el-empty v-if="!contentHtml" description="这个项目还没有补充详细说明" />
                <!-- eslint-disable-next-line vue/no-v-html -->
                <div v-else ref="bodyRef" class="markdown-body" v-html="contentHtml" />
            </div>

            <div class="card block">
                <!-- 成员列表可能为 null（历史数据 / 字段未装配）：用 ?? [] 兜底，
                     直接写 detail.members.length 会在 null 上抛错，整个详情区渲染中断 -->
                <h2>参与成员（{{ members.length }}）</h2>
                <el-empty v-if="members.length === 0" description="暂无成员" />
                <div v-else class="grid cards-3">
                    <!-- 成员信息「不对外展示」（业务规则）：这里只作为项目参与者的信息卡，**不做跳转**——
                         官网没有成员详情路由，跳转会命中兜底路由静默回到首页 -->
                    <div v-for="member in members" :key="member.id" class="member">
                        <div class="member-head">
                            <!-- 头像地址可能是外部图床（会失效）或已被清理的文件：
                                 交给 SmartImage 兜底成「姓名首字 + 渐变块」，不留裂图 -->
                            <span class="avatar">
                                <SmartImage :src="member.avatar" :alt="member.name" :seed="member.id" />
                            </span>
                            <div class="who">
                                <div class="name">{{ member.name }}</div>
                                <span v-if="member.teamPosition" class="tag gray">{{ member.teamPosition }}</span>
                            </div>
                        </div>
                        <div v-if="member.gradeYear || member.direction" class="meta">
                            <span v-if="member.gradeYear">{{ member.gradeYear }} 届</span>
                            <span v-if="member.direction">{{ member.direction }}</span>
                        </div>
                        <p v-if="member.summary" class="summary">{{ member.summary }}</p>
                        <el-link
                            v-if="member.githubUrl"
                            :href="member.githubUrl"
                            target="_blank"
                            type="primary"
                            class="gh"
                        >
                            GitHub
                        </el-link>
                    </div>
                </div>
            </div>

            <el-button link @click="$router.back()">← 返回</el-button>
        </template>
    </div>
</template>

<style scoped>
/* 封面容器：给出确定高度，让 SmartImage 的图片与兜底色块都能填满 */
.cover-wrap {
    width: 100%;
    height: 320px;
    border-radius: var(--radius-sm);
    margin-bottom: 16px;
    overflow: hidden;
    display: flex;
}

h1 {
    margin: 0 0 10px;
    font-size: 26px;
}

.block {
    margin-top: 20px;
}

.links {
    display: flex;
    gap: 16px;
    margin-top: 12px;
}

.member {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 14px;
    color: var(--text);
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.member-head {
    display: flex;
    align-items: center;
    gap: 10px;
}

.avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
    display: inline-flex;
}

/* 头像兜底（姓名首字）时字号小一些，40px 的圆里不会显得挤 */
.avatar :deep(.img-fallback__box) {
    font-size: 16px;
}

.who {
    display: flex;
    flex-direction: column;
    gap: 3px;
}

.name {
    font-weight: 600;
}

.meta {
    display: flex;
    gap: 10px;
    font-size: 12px;
    color: var(--muted);
}

.summary {
    margin: 0;
    font-size: 13px;
    line-height: 1.5;
    color: #6b7280;
}

.gh {
    align-self: flex-start;
    font-size: 13px;
}
</style>
