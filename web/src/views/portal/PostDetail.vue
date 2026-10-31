<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { postApi } from '@/api/post';
import { reportApi } from '@/api/report';
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
import { useUserStore } from '@/stores/user';
import type { PostComment, PostDetail } from '@/api/types';

/** 帖子详情（游客可访问）＋ 楼中楼评论 */
const route = useRoute();
const router = useRouter();
const user = useUserStore();

const id = String(route.params.id);
const detail = ref<PostDetail | null>(null);
/** 当前登录用户是不是这篇文章的作者：只用于展示作者操作，真正权限在后端 */
const isMine = computed(() => !!user.userId && detail.value?.authorId === user.userId);
const comments = ref<PostComment[]>([]);
const loading = ref(false);
/** 加载失败 / 帖子不存在：非空时展示错误条 + 重试 */
const error = ref('');

/** 顶层评论输入框 */
const commentText = ref('');
/** 正在回复的顶层评论 ID（null 表示没有打开任何回复框） */
const replyTo = ref<string | null>(null);
const replyText = ref('');
const submitting = ref(false);
/** 点赞请求中（按钮 loading，防连点） */
const liking = ref(false);

// 举报

const reportVisible = ref(false);
/** 正在举报的对象：帖子或某条评论 */
const reportTarget = ref<{ type: 'post' | 'comment'; id: string } | null>(null);
const reportReason = ref('');
const reporting = ref(false);

/** 打开举报弹窗 */
function openReport(type: 'post' | 'comment', targetId: string) {
    if (!user.isLogin) {
        ElMessage.warning('登录后才能举报');
        void router.push({ name: 'login', query: { redirect: route.fullPath } });
        return;
    }
    reportTarget.value = { type, id: targetId };
    reportReason.value = '';
    reportVisible.value = true;
}

async function submitReport() {
    const reason = reportReason.value.trim();
    if (!reason) {
        ElMessage.warning('请填写举报理由');
        return;
    }
    if (!reportTarget.value) {
        return;
    }
    reporting.value = true;
    try {
        // targetId 必须是字符串：19 位雪花 ID 走 number 会丢精度，后端按错误 ID 查不到内容
        await reportApi.submit({
            targetType: reportTarget.value.type,
            targetId: reportTarget.value.id,
            reason,
        });
        ElMessage.success('举报已提交，管理员会尽快核实');
        reportVisible.value = false;
    } catch {
        // 重复举报等提示已由请求层统一弹出，这里不重复打扰
    } finally {
        reporting.value = false;
    }
}

/** 评论区容器：点 meta 行里的「评论 N」要能直接滚过去 */
const commentsRef = ref<HTMLElement | null>(null);

/** 跳到评论区 */
function scrollToComments() {
    commentsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/** 作者改自己的文章：改完会重新送审，审核通过前不再公开展示 */
function editMyPost() {
    router.push({ path: '/posts/new', query: { edit: id } });
}

/** 正文容器：代码块复制与目录高亮都要在真实 DOM 上接线（v-html 的内容 Vue 管不着） */
const bodyRef = ref<HTMLElement | null>(null);

/** 正文渲染：renderMarkdown → 白名单清洗 → v-html */
const rendered = computed(() => {
    const result = renderMarkdown(detail.value?.content ?? '');
    // 清洗实现收在工具里：钩子的挂/摘必须成对，写在页面里就有漏摘污染全局的风险
    result.html = sanitizeMarkdownHtml(result.html, { restrictImages: true });
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
        detail.value = await postApi.detail(id);
        comments.value = await postApi.commentList(id);
    } catch (e) {
        // 失败 / 帖子不存在：给出原因与重试，而不是整页空白
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/**
 * 未登录时把人送去登录，并记住来源
 *
 * @param action 动作名（评论 / 回复 / 点赞），用于提示文案
 */
function requireLogin(action: string): boolean {
    if (user.isLogin) {
        return true;
    }
    ElMessage.info(`请先登录后再${action}`);
    router.push({ name: 'login', query: { redirect: route.fullPath } });
    return false;
}

async function submitComment() {
    const text = commentText.value.trim();
    if (!text || !requireLogin('评论')) {
        return;
    }
    submitting.value = true;
    try {
        await postApi.commentAdd({ postId: id, content: text });
        // 先审后发：评论此刻是待审状态，不会出现在列表里，必须说清楚
        ElMessage.success('评论已提交，审核通过后可见');
        commentText.value = '';
    } finally {
        submitting.value = false;
    }
}

async function submitReply(parentId: string) {
    const text = replyText.value.trim();
    if (!text || !requireLogin('回复')) {
        return;
    }
    submitting.value = true;
    try {
        await postApi.commentAdd({ postId: id, content: text, parentId });
        ElMessage.success('回复已提交，审核通过后可见');
        replyText.value = '';
        replyTo.value = null;
    } finally {
        submitting.value = false;
    }
}

/** 删除评论（管理员） */
async function removeComment(id: string, withReplies: boolean) {
    try {
        await ElMessageBox.confirm(
            withReplies ? '删除后该评论及其回复都会移除，且不可恢复。' : '删除后不可恢复。',
            '确认删除',
            { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
        );
    } catch {
        // 用户取消：正常操作，不是错误
        return;
    }
    const rows = await postApi.commentDelete(id);
    ElMessage.success(rows > 1 ? `已删除 ${rows} 条（含回复）` : '已删除');
    // 重新拉取：评论数由后端维护，删完要同步列表与计数
    await load();
}

/** 点赞（只能赞、不能取消） */
async function like() {
    if (!requireLogin('点赞')) {
        return;
    }
    liking.value = true;
    try {
        const count = await postApi.like(id);
        if (detail.value) {
            detail.value.liked = true;
            detail.value.likeCount = count;
        }
    } finally {
        liking.value = false;
    }
}

onMounted(load);
usePageMeta({
    title: computed(() => detail.value?.title ?? '帖子详情'),
    description: computed(() => detail.value?.summary ?? ''),
});
</script>

<template>
    <div class="page" v-loading="loading">
        <!-- 失败 / 不存在：错误条 + 重试 + 返回社区，而不是整页空白 -->
        <template v-if="error || !detail">
            <ErrorRetry v-if="error" :message="error" @retry="load" />
            <div v-else class="card muted">帖子加载中…</div>
            <div class="actions">
                <el-button @click="router.push('/posts')">返回社区</el-button>
            </div>
        </template>
        <template v-else>
            <article class="card post">
                <h1>{{ detail.title }}</h1>
                <!-- 作者区：把「谁写的、什么时候、有多少互动」聚在一起，正文之前先给读者一个上下文 -->
                <div class="meta">
                    <div class="author-box">
                        <!-- 作者头像：有真实头像就用，没有则显示统一默认头像 -->
                        <span class="author-avatar">
                            <SmartImage
                                :src="detail.authorAvatar"
                                :alt="detail.authorName || '工作室成员'"
                                :seed="detail.authorId"
                                fallback="avatar"
                            />
                        </span>
                        <span class="author">{{ detail.authorName || '工作室成员' }}</span>
                        <span class="date">{{ detail.createdAt?.slice(0, 16) }}</span>
                    </div>
                    <span class="spacer" />
                    <span class="stat">浏览 {{ detail.viewCount ?? 0 }}</span>
                    <!-- 评论数做成可点击入口：看到「评论 3」想直接跳过去，不该要求读者自己往下滚找评论区 -->
                    <a class="stat stat-link" href="#comments" @click.prevent="scrollToComments">
                        评论 {{ detail.commentCount ?? 0 }}
                    </a>
                    <!-- 只能赞不能取消：已赞后禁用。尺寸与字重要比统计文字重一档，否则它和「浏览」同级，看起来只是一条统计信息 -->
                    <el-button
                        class="like-btn"
                        :type="detail.liked ? 'primary' : 'default'"
                        :disabled="detail.liked === true"
                        :loading="liking"
                        round
                        @click="like"
                    >
                        {{ detail.liked ? '已赞' : '点赞' }} {{ detail.likeCount ?? 0 }}
                    </el-button>
                    <!-- 举报入口放在 meta 行末尾：它是负向操作，不该比点赞更显眼；自己的文章不显示举报 -->
                    <el-button v-if="!isMine" link class="report-link" @click="openReport('post', detail.id)">
                        举报
                    </el-button>
                </div>

                <!-- 作者操作条：只对自己可见，改稿前先说明会重新送审 -->
                <div v-if="isMine" class="author-bar">
                    <span class="author-bar-title">这是你发布的文章</span>
                    <span class="author-bar-hint">修改后需重新审核，审核通过前不会公开显示。</span>
                    <span class="spacer" />
                    <el-button link type="primary" @click="router.push('/posts/my')">我的文章</el-button>
                    <el-button size="small" type="primary" plain @click="editMyPost">编辑文章</el-button>
                </div>

                <!-- 目录：长文（标题 ≥3）才出现。href 保留原生锚点语义 （链接可复制、可中键新窗口），click 接平滑滚动 -->
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

                <!-- 正文已过 DOMPurify 白名单清洗 -->
                <div ref="bodyRef" class="markdown-body" v-html="contentHtml" />
            </article>

            <section id="comments" ref="commentsRef" class="card comments">
                <h2>评论 <span class="count">{{ detail.commentCount ?? 0 }}</span></h2>

                <div class="composer">
                    <el-input
                        v-model="commentText"
                        type="textarea"
                        :rows="3"
                        maxlength="1000"
                        show-word-limit
                        :placeholder="user.isLogin ? '写下你的评论…' : '登录后即可评论'"
                    />
                    <div class="composer-foot">
                        <span class="muted tip">评论需审核通过后才会公开显示</span>
                        <el-button type="primary" :loading="submitting" @click="submitComment">
                            发表评论
                        </el-button>
                    </div>
                </div>

                <el-empty v-if="comments.length === 0" description="还没有评论，来说两句吧" />

                <div v-else class="comment-list">
                    <div v-for="comment in comments" :key="comment.id" class="comment">
                        <div class="comment-head">
                            <!-- 评论人头像：与作者区同一套兜底（无头像时显示统一默认头像）-->
                            <span class="comment-avatar">
                                <SmartImage
                                    :src="comment.authorAvatar"
                                    :alt="comment.authorName || '用户'"
                                    :seed="comment.authorId"
                                    fallback="avatar"
                                />
                            </span>
                            <span class="author">{{ comment.authorName || '用户' }}</span>
                            <span v-if="user.userId && comment.authorId === user.userId" class="self-tag">我</span>
                            <span v-if="comment.floor" class="floor">{{ comment.floor }} 楼</span>
                            <span class="date">{{ comment.createdAt?.slice(0, 16) }}</span>
                            <el-button link size="small" @click="replyTo = comment.id">回复</el-button>
                            <el-button link size="small" @click="openReport('comment', comment.id)">
                                举报
                            </el-button>
                            <el-button
                                v-if="user.isAdmin"
                                link
                                size="small"
                                type="danger"
                                @click="removeComment(comment.id, (comment.children?.length ?? 0) > 0)"
                            >
                                删除
                            </el-button>
                        </div>
                        <p class="comment-body">{{ comment.content }}</p>

                        <!-- 楼中楼：只两层，children 由后端挂好，这里直接渲染 -->
                        <div v-if="comment.children?.length" class="replies">
                            <div v-for="reply in comment.children" :key="reply.id" class="reply">
                                <span class="reply-avatar">
                                    <SmartImage
                                        :src="reply.authorAvatar"
                                        :alt="reply.authorName || '用户'"
                                        :seed="reply.authorId"
                                        fallback="avatar"
                                    />
                                </span>
                                <span class="author">{{ reply.authorName || '用户' }}</span>
                                <span v-if="user.userId && reply.authorId === user.userId" class="self-tag">我</span>
                                <span class="reply-text">{{ reply.content }}</span>
                                <span class="date">{{ reply.createdAt?.slice(0, 16) }}</span>
                                <el-button link size="small" @click="openReport('comment', reply.id)">
                                    举报
                                </el-button>
                                <el-button
                                    v-if="user.isAdmin"
                                    link
                                    size="small"
                                    type="danger"
                                    @click="removeComment(reply.id, false)"
                                >
                                    删除
                                </el-button>
                            </div>
                        </div>

                        <div v-if="replyTo === comment.id" class="reply-box">
                            <el-input
                                v-model="replyText"
                                type="textarea"
                                :rows="2"
                                maxlength="1000"
                                placeholder="回复这条评论…"
                            />
                            <div class="composer-foot">
                                <el-button link @click="replyTo = null">取消</el-button>
                                <el-button
                                    type="primary"
                                    size="small"
                                    :loading="submitting"
                                    @click="submitReply(comment.id)"
                                >
                                    提交回复
                                </el-button>
                            </div>
                        </div>
                    </div>
                </div>
            </section>
        </template>

        <!-- 举报弹窗：帖子与评论共用，靠 reportTarget.type 区分 -->
        <el-dialog v-model="reportVisible" title="举报内容" width="460px">
            <p class="muted report-tip">
                举报会提交给管理员人工核实，请说明具体问题（如广告、人身攻击、侵权等）。
                同一内容重复举报不会重复受理。
            </p>
            <el-input
                v-model="reportReason"
                type="textarea"
                :rows="4"
                maxlength="256"
                show-word-limit
                placeholder="请填写举报理由（最多 256 字）"
            />
            <template #footer>
                <el-button @click="reportVisible = false">取消</el-button>
                <el-button type="danger" :loading="reporting" @click="submitReport">
                    提交举报
                </el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.post {
    padding: 28px 32px;
}

h1 {
    margin: 0 0 10px;
    font-size: 24px;
    font-weight: 600;
}

.meta {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 13px;
    color: #6b7280;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
    /* 窄屏时允许换行，点赞按钮不被裁切 */
    flex-wrap: wrap;
}

/* 点赞是正文页最主要的互动入口：比 meta 行的统计文字重一档，
   但不抢标题的视觉层级——靠尺寸 + 加粗做到「一眼能找到」 */
.like-btn {
    margin-left: 8px;
    padding: 8px 18px;
    font-weight: 600;
}

/* 举报是负向操作：用次级文字色，不与点赞抢注意力，hover 才提示危险语义 */
.report-link {
    color: var(--muted);
    font-size: 13px;
}

.report-link:hover {
    color: #dc2626;
}

/* ===== 作者操作条 ===== */
.author-bar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 14px;
    margin-top: 16px;
    padding: 10px 14px;
    border: 1px solid rgb(37 99 235 / 14%);
    border-radius: var(--radius-sm);
    background: var(--brand-soft);
    font-size: 13px;
    color: var(--brand-dark);
}

.author-bar-title {
    font-weight: 600;
}

.author-bar-hint {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.6;
}

.author-bar .spacer {
    flex: 1;
}

.report-tip {
    margin: 0 0 12px;
    font-size: 13px;
    line-height: 1.7;
}

/* meta 行的统计文字：与点赞按钮的左邻右舍保持同一视觉重量 */
.stat {
    color: var(--muted);
    white-space: nowrap;
}

/* 「评论 N」是可点入口：给品牌色下划线，暗示可以点 */
.stat-link {
    color: var(--brand);
    text-decoration: none;
    cursor: pointer;
}

.stat-link:hover {
    text-decoration: underline;
}

/* 评论区标题里的计数：小一号的徽标，避免和标题正文抢注意力 */
.comments h2 .count {
    margin-left: 6px;
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-dark);
    font-size: 13px;
    font-weight: 600;
    vertical-align: 2px;
}

.author-box {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 8px;
    line-height: 1.35;
}

/* 作者头像：圆形，与姓名同排；无头像时用首字兜底 */
.author-avatar {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
    display: inline-flex;
    /* 细边框：浅色兜底块在白色卡片上不描边会「糊」在背景里 */
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.author-avatar :deep(.img-fallback__box) {
    font-size: 13px;
    font-weight: 700;
}

.author-avatar :deep(.img-fallback__img) {
    object-fit: cover;
}

/* 评论人头像：比作者头像小一号，避免评论区太抢眼 */
.comment-avatar {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
    display: inline-flex;
    margin-right: 6px;
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.comment-avatar :deep(.img-fallback__box) {
    font-size: 12px;
    font-weight: 700;
}

.comment-avatar :deep(.img-fallback__img) {
    object-fit: cover;
}

/* 楼中楼回复的头像更小：回复是次要信息，不必与顶层评论同等视觉重量 */
.reply-avatar {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
    display: inline-flex;
    margin-right: 5px;
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.reply-avatar :deep(.img-fallback__box) {
    font-size: 11px;
    font-weight: 700;
}

.reply-avatar :deep(.img-fallback__img) {
    object-fit: cover;
}

.author {
    color: #1f2937;
    font-weight: 600;
}

/* 自己的评论：在作者名后补一个很轻的「我」，不改变评论排版 */
.self-tag {
    padding: 0 6px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand);
    font-size: 11px;
    line-height: 18px;
}

.spacer {
    flex: 1;
}

/* Markdown 正文：由 marked 生成，结构不可控，只能用后代选择器 */
.markdown-body {
    padding-top: 18px;
    font-size: 15px;
    line-height: 1.75;
    color: #1f2937;
}

.markdown-body :deep(img) {
    max-width: 100%;
    border-radius: var(--radius);
    margin: 12px 0;
}

.markdown-body :deep(pre) {
    background: #f8fafc;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 12px 14px;
    overflow-x: auto;
}

.markdown-body :deep(code) {
    font-size: 13px;
}

.markdown-body :deep(blockquote) {
    margin: 12px 0;
    padding: 6px 14px;
    border-left: 3px solid #2563eb;
    background: #eff6ff;
    color: #4b5563;
}

.markdown-body :deep(a) {
    color: #2563eb;
}

.comments {
    margin-top: 20px;
    padding: 24px 32px;
}

h2 {
    margin: 0 0 14px;
    font-size: 18px;
    font-weight: 500;
}

.composer {
    margin-bottom: 20px;
}

.composer-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 10px;
}

.tip {
    font-size: 12px;
}

.comment-list {
    display: flex;
    flex-direction: column;
    gap: 18px;
}

.comment-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: #6b7280;
}

.floor {
    padding: 1px 6px;
    border-radius: 4px;
    background: #eff6ff;
    color: #2563eb;
    font-size: 12px;
}

.comment-body {
    margin: 6px 0 0;
    font-size: 14px;
    line-height: 1.7;
    white-space: pre-wrap;
}

/* 回复缩进 + 左侧竖线，形成楼中楼的视觉层次 */
.replies {
    margin-top: 10px;
    padding-left: 14px;
    border-left: 2px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.reply {
    font-size: 13px;
    color: #4b5563;
}

.reply .author {
    margin-right: 6px;
}

.reply-text {
    margin-right: 8px;
}

.date {
    color: #9ca3af;
    font-size: 12px;
}

.reply-box {
    margin-top: 10px;
}
</style>
