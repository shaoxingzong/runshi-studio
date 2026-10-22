<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import DOMPurify from 'dompurify';
import { portalApi } from '@/api/portal';
import { usePageMeta } from '@/composables/usePageMeta';
import ErrorRetry from '@/components/ErrorRetry.vue';
import {
    bindCodeCopy,
    MARKDOWN_ALLOWED_ATTR,
    MARKDOWN_ALLOWED_TAGS,
    observeTocHighlight,
    renderMarkdown,
    scrollToHeading,
    TOC_MIN_ITEMS,
} from '@/utils/markdown';
import type { ProjectDetail as ProjectDetailType } from '@/api/types';

const route = useRoute();
const router = useRouter();
const detail = ref<ProjectDetailType | null>(null);
const loading = ref(false);
/** 加载失败（含 id 不存在）：此前提炼只有 v-if="detail"，失败时整页空白 */
const error = ref('');

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

/** 正文容器：代码块复制与目录高亮都要在真实 DOM 上接线（v-html 的内容 Vue 管不着） */
const bodyRef = ref<HTMLElement | null>(null);

/**
 * 正文是 Markdown，渲染链路是：共享工具（renderMarkdown）→ **白名单清洗** → v-html
 *
 * ⚠️ 为什么必须清洗：marked 只负责「Markdown 转 HTML」，它 v15 起已移除内置的
 * sanitize——原文里的 `<script>`、`onerror=` 会被原样保留。而 `v-html` 是直接写入
 * DOM，等于把正文作者当成了可信来源。
 *
 * 因此这里统一走 DOMPurify 白名单清洗：
 *  - 保留正文需要的标签（标题/列表/代码/链接/图片）；
 *  - 剥掉脚本、事件属性、`javascript:` 协议等注入载体。
 * 「管理员录入」不能替代清洗——账号被盗、越权、导入外部数据都可能让正文不可信。
 *
 * 白名单里额外放行 {@code div/button/id/type/aria-label/loading/decoding}：
 * 这些是共享 renderer 的增强输出（代码块包装、复制按钮、标题锚点、图片懒加载），
 * 不放行会被静默洗掉——表现是「复制按钮不见了但没人报错」。
 */
const rendered = computed(() => {
    const result = renderMarkdown(detail.value?.content ?? '');
    // 白名单与工具同源（MARKDOWN_ALLOWED_*）：项目页无额外的 img 限制（管理员录入，
    // 可信度高于成员帖子），帖子页的那一套更严，两边差异只在钩子不在名单
    result.html = DOMPurify.sanitize(result.html, {
        ALLOWED_TAGS: MARKDOWN_ALLOWED_TAGS,
        ALLOWED_ATTR: MARKDOWN_ALLOWED_ATTR,
    }) as string;
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

/**
 * 标签页标题用真实项目名（与 PostDetail 同一理由：详情页是收录与分享的主体）
 *
 * <p>description 取项目摘要——它是给搜索引擎与分享预览看的浓缩介绍，
 * 比 index.html 里那句全站通用描述准确得多。
 */
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
                <img v-if="detail.coverImage" :src="detail.coverImage" :alt="detail.title" class="cover" />
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
                <!-- eslint-disable-next-line vue/no-v-html -->
                <div ref="bodyRef" class="markdown-body" v-html="contentHtml" />
            </div>

            <div class="card block">
                <h2>参与成员（{{ detail.members.length }}）</h2>
                <el-empty v-if="detail.members.length === 0" description="暂无成员" />
                <div v-else class="grid cards-3">
                    <!-- ⚠️ 成员信息「不对外展示」（业务规则）：这里只作为项目参与者的信息卡，**不做跳转**——
                         官网没有成员详情路由，跳转会命中兜底路由静默回到首页 -->
                    <div v-for="member in detail.members" :key="member.id" class="member">
                        <div class="member-head">
                            <img v-if="member.avatar" :src="member.avatar" :alt="member.name" class="avatar" />
                            <span v-else class="avatar fallback">{{ member.name.slice(0, 1) }}</span>
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
.cover {
    width: 100%;
    max-height: 320px;
    object-fit: cover;
    border-radius: var(--radius-sm);
    margin-bottom: 16px;
    display: block;
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
    object-fit: cover;
    flex-shrink: 0;
}

/* 没有头像时用姓名首字兜底，避免出现空框 */
.avatar.fallback {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, #2563eb, #1d4ed8);
    color: #fff;
    font-size: 16px;
    font-weight: 600;
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
