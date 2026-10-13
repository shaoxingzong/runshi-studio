<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { postApi } from '@/api/post';
import ErrorRetry from '@/components/ErrorRetry.vue';
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

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await postApi.list({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function turn(delta: number) {
    // pages / total 是字符串（Long 序列化），比较前必须转数字
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
            <el-button v-if="user.isMember" type="primary" @click="router.push('/posts/new')">
                写文章
            </el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading">
            <!-- 空状态要有引导：干巴巴一句「没有数据」等于把人劝退，
                 成员此时正适合被引导去发第一篇文章 -->
            <el-empty v-if="result && result.records.length === 0" description="">
                <template #description>
                    <p class="empty-title">还没有帖子</p>
                    <p class="empty-sub">成为第一个分享的人，把你的技术实践写下来。</p>
                </template>
                <el-button v-if="user.isMember" type="primary" @click="router.push('/posts/new')">
                    写文章
                </el-button>
            </el-empty>

            <div v-else class="grid cards-3">
                <router-link
                    v-for="post in result?.records ?? []"
                    :key="post.id"
                    :to="`/posts/${post.id}`"
                    class="post-card"
                >
                    <div class="cover">
                        <img v-if="post.coverImage" :src="post.coverImage" :alt="post.title" />
                        <div v-else class="cover-fallback">{{ post.title.slice(0, 1) }}</div>
                        <span v-if="post.pinned === 1" class="pin">置顶</span>
                    </div>

                    <div class="body">
                        <h3>{{ post.title }}</h3>
                        <p class="summary">{{ post.summary || '暂无摘要' }}</p>
                        <div class="meta">
                            <span class="avatar">{{ (post.authorName || '成').slice(0, 1) }}</span>
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
.page-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
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
}

.cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
}

/* 没有封面时用标题首字兜底，避免卡片出现大片空白 */
.cover-fallback {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 34px;
    font-weight: 600;
    color: #2563eb;
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

/* 姓名首字徽标：列表拿不到头像图源，用它让「人」出现在卡片上，
   比只放一个名字更有社区感 */
.avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: linear-gradient(135deg, #2563eb, #1d4ed8);
    color: #fff;
    font-size: 12px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
}

.author {
    color: #4b5563;
    font-weight: 500;
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
