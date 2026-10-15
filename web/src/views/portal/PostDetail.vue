<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import { useUserStore } from '@/stores/user';
import type { PostComment, PostDetail } from '@/api/types';

/** 帖子详情（游客可访问）＋ 楼中楼评论 */
const route = useRoute();
const router = useRouter();
const user = useUserStore();

const id = String(route.params.id);
const detail = ref<PostDetail | null>(null);
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

/**
 * 正文渲染：Markdown → HTML → **白名单清洗** → v-html
 *
 * <p>为什么必须清洗：marked 只负责「Markdown 转 HTML」，
 * 它 v15 起已移除内置 sanitize——正文里的 `<script>`、`onerror=` 会被原样保留，
 * 而 `v-html` 是直接写 DOM，等于把发帖人当成了可信来源。
 *
 * <p>比项目详情页<b>更严一步</b>：帖子是<b>成员自己提交</b>的，
 * 可信度低于管理员录入的项目正文，因此这里额外限制 `img` 的 `src`
 * 只能是站内 `/uploads/` 路径——外链图既会被后端拒绝，
 * 也可能成为追踪像素泄露访客 IP。
 */
const contentHtml = computed(() => {
    const raw = marked.parse(detail.value?.content ?? '') as string;

    // hook 是全局的，用完立刻摘掉：这里是同步代码块，不会影响其它页面的清洗
    DOMPurify.addHook('afterSanitizeAttributes', (node) => {
        if (node.tagName === 'IMG') {
            const src = node.getAttribute('src') || '';
            // 只放行站内上传目录；相对路径以外的（含 data:、http:）一律剥掉 src
            if (!src.startsWith('/uploads/')) {
                node.removeAttribute('src');
            }
        }
    });

    try {
        return DOMPurify.sanitize(raw, {
            ALLOWED_TAGS: [
                'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
                'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'strong', 'em', 'del',
                'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span',
            ],
            ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'rel', 'class'],
        });
    } finally {
        DOMPurify.removeHook('afterSanitizeAttributes');
    }
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

/**
 * 删除评论（管理员）
 *
 * <p><b>为什么删除入口在这里而不是审核队列</b>：本页的评论列表本身只含<b>已通过</b>的内容，
 * 正好符合后端「只能删除已通过评论」的约束；而审核队列里全是待审内容，
 * 在那里删会被后端拒绝（待审应当走驳回，让作者收到反馈）。
 *
 * <p>删除是<b>级联</b>的：删顶层评论会连它的回复一起删，
 * 不留「父已删、子还在」的孤儿回复——所以确认文案必须说清楚会连带影响回复。
 */
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

/**
 * 点赞（只能赞、不能取消）
 *
 * <p>数字<b>以后端返回为准</b>，不做乐观 +1：后端是幂等的，
 * 前端自己加 1 会出现「重复点击数字跳两次」的错乱。
 *
 * <p>已赞后按钮即禁用：本模块没有取消点赞的能力，
 * 界面上就不该再留一个可点的入口。
 */
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
                <!-- 作者区：把「谁写的、什么时候、有多少互动」聚在一起，
                     正文之前先给读者一个上下文 -->
                <div class="meta">
                    <div class="author-box">
                        <span class="author">{{ detail.authorName || '工作室成员' }}</span>
                        <span class="date">{{ detail.createdAt?.slice(0, 16) }}</span>
                    </div>
                    <span class="spacer" />
                    <span>浏览 {{ detail.viewCount ?? 0 }}</span>
                    <span>评论 {{ detail.commentCount ?? 0 }}</span>
                    <!-- 只能赞不能取消：已赞后禁用，不提供取消入口 -->
                    <el-button
                        class="like-btn"
                        :type="detail.liked ? 'primary' : 'default'"
                        :disabled="detail.liked === true"
                        :loading="liking"
                        round
                        size="small"
                        @click="like"
                    >
                        {{ detail.liked ? '已赞' : '赞' }} {{ detail.likeCount ?? 0 }}
                    </el-button>
                </div>

                <!-- 正文已过 DOMPurify 白名单清洗 -->
                <div class="markdown-body" v-html="contentHtml" />
            </article>

            <section class="card comments">
                <h2>评论</h2>

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
                            <span class="author">{{ comment.authorName || '用户' }}</span>
                            <span v-if="comment.floor" class="floor">{{ comment.floor }} 楼</span>
                            <span class="date">{{ comment.createdAt?.slice(0, 16) }}</span>
                            <el-button link size="small" @click="replyTo = comment.id">回复</el-button>
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
                                <span class="author">{{ reply.authorName || '用户' }}</span>
                                <span class="reply-text">{{ reply.content }}</span>
                                <span class="date">{{ reply.createdAt?.slice(0, 16) }}</span>
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

/* 点赞是轻量互动：small + round 与 meta 行文字高度协调，视觉权重低于标题正文 */
.like-btn {
    margin-left: 2px;
}

.author-box {
    display: flex;
    flex-direction: column;
    line-height: 1.35;
}

.author {
    color: #1f2937;
    font-weight: 500;
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
