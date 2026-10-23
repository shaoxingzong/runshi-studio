<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import ErrorRetry from '@/components/ErrorRetry.vue';
import { notificationApi } from '@/api/notification';
import type { Notification } from '@/api/types';

// 通知中心（C 端，要求登录）
//
// 「已读」在这个页面里是点击即读：条目本身就是跳转链接，
// 点开的瞬间标记已读——单独放一个「标为已读」按钮是多余的交互
// （用户点通知的目的就是去看内容，不是去管理已读状态）。
const router = useRouter();

const page = reactive({ current: 1, pageSize: 10 });
// 已读过滤：null-全部，0-只看未读
const readFilter = ref<number | null>(null);
const result = ref<{ records: Notification[]; total: number; pages: number } | null>(null);
const loading = ref(false);
const error = ref('');

const records = computed(() => result.value?.records ?? []);
const total = computed(() => Number(result.value?.total || 0));
const pages = computed(() => Number(result.value?.pages || 0));

async function load() {
    loading.value = true;
    error.value = '';
    try {
        const data = await notificationApi.list({
            current: page.current,
            pageSize: page.pageSize,
            isRead: readFilter.value ?? undefined,
        });
        result.value = {
            records: data.records ?? [],
            total: Number(data.total || 0),
            pages: Number(data.pages || 0),
        };
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

// 切换「全部 / 未读」时回到第一页：否则可能停在一个不存在的页码上
function switchFilter(value: number | null) {
    readFilter.value = value;
    page.current = 1;
    void load();
}

function turn(delta: number) {
    const next = page.current + delta;
    if (next < 1 || next > pages.value) {
        return;
    }
    page.current = next;
    void load();
}

// 点击条目：标记已读 + 按类型跳转
//
// 跳转目标按 type 决定（后端 biz 语义）：
// 三种帖子/评论类都跳帖子详情（COMMENT_REPLIED 的 biz 是帖子——
// 同一帖子下的多次回复按话题聚合，见 api/types.ts）；成员授予跳工作台
// （新能力所在处，比跳首页有用）。
async function open(item: Notification) {
    if (item.isRead !== 1) {
        try {
            await notificationApi.markRead(item.id);
            // 广播给门户布局的铃铛：不等 60s 轮询，已读数立刻兑现
            window.dispatchEvent(new CustomEvent('notification:read'));
        } catch {
            // 标记失败不阻断跳转：用户要看的内容比已读状态重要
        }
    }
    if (item.type === 'MEMBER_GRANTED') {
        await router.push('/workspace');
    } else if (item.bizId) {
        await router.push(`/posts/${item.bizId}`);
    }
}

// 全部已读：失败时提示（这是用户主动动作，静默会让人以为没生效）
async function markAll() {
    try {
        await notificationApi.markAllRead();
        window.dispatchEvent(new CustomEvent('notification:read'));
        await load();
    } catch (e) {
        console.warn('全部已读失败', e);
    }
}

// 相对时间：通知列表里「3 分钟前」比绝对时间好读
function timeAgo(value?: string): string {
    if (!value) {
        return '';
    }
    const then = new Date(value.replace(' ', 'T')).getTime();
    if (Number.isNaN(then)) {
        return value.slice(0, 16);
    }
    const minutes = Math.floor((Date.now() - then) / 60_000);
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时前`;
    return `${Math.floor(hours / 24)} 天前`;
}

onMounted(load);
</script>

<template>
    <div class="page">
        <div class="page-head">
            <h1>通知</h1>
            <div class="actions">
                <el-radio-group :model-value="readFilter" size="small" @change="switchFilter">
                    <el-radio-button :value="null">全部</el-radio-button>
                    <el-radio-button :value="0">未读</el-radio-button>
                    <el-radio-button :value="1">已读</el-radio-button>
                </el-radio-group>
                <el-button size="small" @click="markAll">全部已读</el-button>
            </div>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading" class="card list">
            <el-empty v-if="!loading && records.length === 0" description="暂无通知" />
            <div
                v-for="item in records"
                :key="item.id"
                class="item"
                :class="{ unread: item.isRead !== 1 }"
                @click="open(item)"
            >
                <div class="item-head">
                    <span class="type-tag">{{ item.typeDesc || item.type }}</span>
                    <span class="spacer" />
                    <span class="time">{{ timeAgo(item.createdAt) }}</span>
                </div>
                <div class="title">{{ item.title }}</div>
                <p class="content">{{ item.content }}</p>
            </div>
        </div>

        <div v-if="pages > 1" class="actions pager">
            <el-button size="small" :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
            <span class="muted">第 {{ page.current }} / {{ pages }} 页 · 共 {{ total }} 条</span>
            <el-button size="small" :disabled="page.current >= pages" @click="turn(1)">下一页</el-button>
        </div>
    </div>
</template>

<style scoped>
.page-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 14px;
}

.actions {
    display: flex;
    align-items: center;
    gap: 10px;
}

/* 未读条目：品牌蓝左边线 + 浅蓝底——用既有设计体系的深浅区分，
   不引入新颜色（通知是工具型功能，视觉让位可读性） */
.item {
    padding: 12px 14px;
    border-left: 3px solid transparent;
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: background 120ms ease;
}

.item:hover {
    background: var(--bg);
}

.item.unread {
    border-left-color: var(--brand);
    background: var(--brand-soft);
}

.item + .item {
    margin-top: 8px;
}

.item-head {
    display: flex;
    align-items: center;
    font-size: 12px;
    color: var(--muted);
}

.type-tag {
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--bg);
    border: 1px solid var(--border);
}

.item.unread .type-tag {
    color: var(--brand);
    border-color: var(--brand);
}

.title {
    margin-top: 6px;
    font-size: 14px;
    font-weight: 600;
    color: var(--text);
}

.item.unread .title {
    color: var(--brand-dark);
}

.content {
    margin: 4px 0 0;
    font-size: 13px;
    line-height: 1.6;
    color: var(--text-secondary);
}

.pager {
    justify-content: flex-end;
    margin-top: 14px;
}

.muted {
    font-size: 12px;
    color: var(--muted);
}

.spacer {
    flex: 1;
}
</style>
