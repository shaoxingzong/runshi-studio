<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
import SmartImage from '@/components/SmartImage.vue';
import { useUserStore } from '@/stores/user';
import type { PageResult, Post } from '@/api/types';

/** 社区帖子列表（游客可访问，只含已通过的内容） */
const router = useRouter();
const user = useUserStore();

const page = reactive({ current: 1, pageSize: 9 });
const result = ref<PageResult<Post> | null>(null);
const loading = ref(false);
/** 加载失败：非空时展示错误条 + 重试 */
const error = ref('');

/** 搜索关键词 */
const keyword = ref('');

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await postApi.list({
            ...page,
            keyword: keyword.value.trim() || undefined,
        });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/** 换关键词必须回第一页：否则可能停在一个已不存在的页码上，看到空列表还以为没结果 */
function search() {
    page.current = 1;
    load();
}

function resetSearch() {
    keyword.value = '';
    search();
}

function turn(delta: number) {
    /** pages / total 是字符串（Long 序列化），比较前必须转数字 */
    const pages = result.value ? Number(result.value.pages) : 1;
    const next = page.current + delta;
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    load();
}

onMounted(load);
</script>

<template>
    <div class="page">
        <div class="page-head">
            <div>
                <h1>社区</h1>
                <p>工作室成员的技术分享与日常记录。</p>
            </div>
            <!-- 只有成员看得到入口：后端同样只准成员发帖，这里是同一规则的界面表达 -->
            <div v-if="user.isMember" class="head-actions">
                <el-button plain @click="router.push('/posts/my')">我的文章</el-button>
                <el-button type="primary" @click="router.push('/posts/new')">写文章</el-button>
            </div>
        </div>

        <div class="search-bar">
            <el-input
                v-model="keyword"
                class="search-input"
                placeholder="搜索标题或摘要"
                clearable
                maxlength="64"
                @keyup.enter="search"
                @clear="search"
            />
            <el-button type="primary" @click="search">搜索</el-button>
            <el-button v-if="keyword" @click="resetSearch">重置</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading">
            <!-- 空状态要有引导：干巴巴一句「没有数据」等于把人劝退，成员此时正适合被引导去发第一篇文章 -->
            <el-empty v-if="result && result.records.length === 0" description="">
                <template #description>
                    <!-- 搜索无结果与「还没有帖子」是两回事，提示不能复用 -->
                    <p class="empty-title">{{ keyword ? '没有找到相关帖子' : '还没有帖子' }}</p>
                    <p class="empty-sub">
                        {{
                            keyword
                                ? '换个关键词试试，搜索只匹配标题与摘要。'
                                : '成为第一个分享的人，把你的技术实践写下来。'
                        }}
                    </p>
                </template>
                <el-button
                    v-if="user.isMember && !keyword"
                    type="primary"
                    @click="router.push('/posts/new')"
                >
                    写文章
                </el-button>
                <el-button v-else-if="keyword" @click="resetSearch">清空搜索</el-button>
            </el-empty>

            <div v-else class="grid cards-3">
                <router-link
                    v-for="post in result?.records ?? []"
                    :key="post.id"
                    :to="`/posts/${post.id}`"
                    class="post-card"
                >
                    <div class="cover">
                        <!-- 封面可能是外部图床链接（会失效）或已被清理的上传文件，交给 SmartImage 兜底成「标题首字 + 渐变块」，不留裂图 -->
                        <SmartImage :src="post.coverImage" :alt="post.title" :seed="post.id" />
                        <span v-if="post.pinned === 1" class="pin">置顶</span>
                        <!-- 作者视角：在公开列表里也能一眼认出自己的文章 -->
                        <span
                            v-if="user.userId && post.authorId === user.userId"
                            class="mine"
                        >
                            我的
                        </span>
                    </div>

                    <div class="body">
                        <h3>{{ post.title }}</h3>
                        <p class="summary">{{ post.summary || '暂无摘要' }}</p>
                        <div class="meta">
                            <!-- 作者头像：成员档案里有就用真实头像，没有则用统一默认头像 -->
                            <span class="author-avatar">
                                <SmartImage
                                    :src="post.authorAvatar"
                                    :alt="post.authorName || '工作室成员'"
                                    :seed="post.authorId"
                                    fallback="avatar"
                                />
                            </span>
                            <span class="author">{{ post.authorName || '工作室成员' }}</span>
                            <span class="date">{{ post.createdAt?.slice(0, 10) }}</span>
                            <span class="spacer" />
                            <span>赞 {{ post.likeCount ?? 0 }}</span>
                            <span>评论 {{ post.commentCount ?? 0 }}</span>
                            <span>浏览 {{ post.viewCount ?? 0 }}</span>
                        </div>
                    </div>
                </router-link>
            </div>

            <div v-if="result && result.records.length" class="pager">
                <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted">
                    第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 篇
                </span>
                <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">
                    下一页
                </el-button>
            </div>
        </div>
    </div>
</template>

<style scoped>
/* 搜索条：与卡片区域同宽，输入框限宽避免大屏上拉得过长 */
.search-bar {
    display: flex;
    gap: 10px;
    margin-bottom: 18px;
}

.search-input {
    max-width: 360px;
}

.page-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
}

.head-actions {
    display: flex;
    flex-shrink: 0;
    gap: 10px;
}

.post-card {
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    overflow: hidden;
    color: var(--text);
    transition: box-shadow 0.2s, transform 0.2s;
}

.post-card:hover {
    text-decoration: none;
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
}

.cover {
    position: relative;
    aspect-ratio: 16 / 9;
    background: #eff6ff;
    /* 兜底色块要铺满封面区，因此这里给出确定尺寸，由 SmartImage 填满 */
    display: flex;
    overflow: hidden;
}

/* SmartImage 的图片与兜底块都填满封面区 */
.cover :deep(.img-fallback__img),
.cover :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
    font-size: 34px;
}

.pin {
    position: absolute;
    top: 10px;
    left: 10px;
    padding: 2px 8px;
    border-radius: 999px;
    background: #2563eb;
    color: #fff;
    font-size: 12px;
}

/* 「我的」标记：白底蓝字，压在封面上但比置顶更安静 */
.mine {
    position: absolute;
    top: 10px;
    right: 10px;
    padding: 2px 8px;
    border-radius: 999px;
    background: rgb(255 255 255 / 92%);
    color: var(--brand);
    font-size: 12px;
    font-weight: 600;
    box-shadow: var(--shadow-xs);
    backdrop-filter: blur(6px);
}

/* 作者头像：卡片底部最显眼的身份标识，28px 才看得出是头像（20px 只剩一个小色点）*/
.author-avatar {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    overflow: hidden;
    flex-shrink: 0;
    display: inline-flex;
    /* 细边框让浅色兜底块与卡片背景分得开 */
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.author-avatar :deep(.img-fallback__box) {
    font-size: 13px;
    font-weight: 700;
}

.author-avatar :deep(.img-fallback__img) {
    object-fit: cover;
}

.body {
    padding: 14px 16px 16px;
    display: flex;
    flex-direction: column;
    flex: 1;
}

h3 {
    margin: 0 0 8px;
    font-size: 16px;
    line-height: 1.4;
}

.summary {
    margin: 0 0 12px;
    font-size: 13px;
    color: #6b7280;
    /* 摘要只留两行，卡片高度才整齐 */
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    flex: 1;
}

.meta {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: #6b7280;
}

.author {
    color: #4b5563;
    font-weight: 600;
}

.empty-title {
    margin: 0 0 4px;
    font-size: 15px;
    font-weight: 500;
    color: #1f2937;
}

.empty-sub {
    margin: 0;
    font-size: 13px;
    color: #6b7280;
}

.spacer {
    flex: 1;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 24px;
}
</style>
