<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { portalApi } from '@/api/portal';
import type { ProjectDetail as ProjectDetailType } from '@/api/types';

const route = useRoute();
const detail = ref<ProjectDetailType | null>(null);
const loading = ref(false);

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

/**
 * 正文是 Markdown，渲染链路是：Markdown → HTML → **白名单清洗** → v-html
 *
 * ⚠️ 为什么必须清洗：marked 只负责「Markdown 转 HTML」，
 * 它 v15 起已移除内置的 sanitize——原文里的 `<script>`、`onerror=` 会被原样保留。
 * 而 `v-html` 是直接写入 DOM，等于把正文作者当成了可信来源。
 *
 * 因此这里统一走 DOMPurify 白名单清洗：
 *  - 保留正文需要的标签（标题/列表/代码/链接/图片）；
 *  - 剥掉脚本、事件属性、`javascript:` 协议等注入载体。
 * 「管理员录入」不能替代清洗——账号被盗、越权、导入外部数据都可能让正文不可信。
 */
const contentHtml = computed(() => {
    const raw = marked.parse(detail.value?.content ?? '（暂无正文）') as string;
    return DOMPurify.sanitize(raw, {
        ALLOWED_TAGS: [
            'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
            'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'strong', 'em', 'del',
            'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span',
        ],
        ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'rel', 'class'],
    });
});

async function load() {
    loading.value = true;
    try {
        detail.value = await portalApi.projectDetail(String(route.params.id));
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div class="page" v-loading="loading">
        <template v-if="detail">
            <div class="card">
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
                <!-- eslint-disable-next-line vue/no-v-html -->
                <div class="markdown-body" v-html="contentHtml" />
            </div>

            <div class="card block">
                <h2>参与成员（{{ detail.members.length }}）</h2>
                <p class="muted small">队长必然在列表中：后端把 leader_id 作为权威源，建/改项目时同步写入关联表。</p>
                <el-empty v-if="detail.members.length === 0" description="暂无成员" />
                <div v-else class="grid cards-4">
                    <router-link
                        v-for="member in detail.members"
                        :key="member.id"
                        :to="`/members/${member.id}`"
                        class="member"
                    >
                        <div class="name">{{ member.name }}</div>
                        <span class="tag gray">{{ member.teamPosition }}</span>
                    </router-link>
                </div>
            </div>

            <el-button link @click="$router.back()">← 返回</el-button>
        </template>
    </div>
</template>

<style scoped>
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

.small {
    font-size: 12px;
}

.member {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 12px;
    color: var(--text);
}

.member:hover {
    text-decoration: none;
    border-color: var(--brand);
}

.name {
    font-weight: 600;
    margin-bottom: 4px;
}
</style>
