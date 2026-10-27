<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { portalApi } from '@/api/portal';
import ErrorRetry from '@/components/ErrorRetry.vue';
import SmartImage from '@/components/SmartImage.vue';
import type { PageResult, Project } from '@/api/types';

/** 项目案例列表（C 端真分页，列表 VO 不含正文——正文只在详情页取） */
const filter = reactive({ title: '', status: '' as string });
const page = reactive({ current: 1, pageSize: 6 });
const result = ref<PageResult<Project> | null>(null);
const loading = ref(false);
/** 加载失败信息：非空时展示错误条 + 重试，而不是留一个空白列表让人猜 */
const error = ref('');

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await portalApi.projectList({ ...filter, ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function search() {
    page.current = 1;
    load();
}

function turn(delta: number) {
    const next = page.current + delta;
    const pages = result.value ? Number(result.value.pages) : 1;
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
            <h1>项目案例</h1>
            <p>工作室的项目列表。</p>
        </div>

        <div class="card filters">
            <el-input
                v-model="filter.title"
                placeholder="项目名称"
                clearable
                maxlength="64"
                style="width: 200px"
                @keyup.enter="search"
            />
            <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
                <el-option label="研发中" value="0" />
                <el-option label="已上线" value="1" />
                <el-option label="已结题" value="2" />
            </el-select>
            <el-button type="primary" @click="search">搜索</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div v-loading="loading">
            <el-empty v-if="result && result.records.length === 0" description="没有符合条件的项目" />
            <div v-else class="grid cards-3">
                <!--
                  卡片整块可点，用「伸展式链接」而不是换成 <router-link>：卡内还有两个外链，
                  嵌套 <a> 是非法 HTML。做法：标题伪元素铺满整卡，外链再抬到覆盖层之上
                -->
                <div v-for="project in result?.records ?? []" :key="project.id" class="project-card">
                    <!-- 封面不做独立链接：整卡点击已由标题的伸展式伪元素接管，
                         再套一层 <router-link> 只会多出一个 Tab 停留点，键盘用户要按两次 -->
                    <div class="cover">
                        <!-- 封面可能是外部图床链接（会失效）或已清理的上传文件，
                             交给 SmartImage 兜底成「项目名首字 + 渐变块」，不留裂图 -->
                        <SmartImage
                            :src="project.coverImage"
                            :alt="project.title"
                            :seed="project.id"
                        />
                    </div>
                    <div class="body">
                        <div class="head">
                            <router-link :to="`/projects/${project.id}`" class="title">
                                {{ project.title }}
                            </router-link>
                            <span class="tag" :class="project.status === 1 ? 'green' : 'gray'">
                                {{ STATUS_TEXT[String(project.status)] || project.status }}
                            </span>
                        </div>
                        <p class="muted">{{ project.description }}</p>
                        <div class="techs">
                            <span v-for="tag in project.techStack ?? []" :key="tag" class="tag gray">{{ tag }}</span>
                        </div>
                        <div v-if="project.demoUrl || project.githubUrl" class="links">
                            <a
                                v-if="project.demoUrl"
                                :href="project.demoUrl"
                                target="_blank"
                                rel="noopener"
                                class="link"
                            >
                                在线体验
                            </a>
                            <a
                                v-if="project.githubUrl"
                                :href="project.githubUrl"
                                target="_blank"
                                rel="noopener"
                                class="link"
                            >
                                开源仓库
                            </a>
                        </div>
                    </div>
                </div>
            </div>

            <div v-if="result && result.records.length" class="pager">
                <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted">第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 个</span>
                <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">下一页</el-button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.filters {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 20px;
}

.project-card {
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    overflow: hidden;
    color: var(--text);
    transition: box-shadow 0.2s, transform 0.2s;
    /* 伸展式链接的定位基准：标题的 ::after 以它为准铺满整张卡片 */
    position: relative;
}

.project-card:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
}

/* 键盘可达性：覆盖层让整卡成为热区，焦点环要落在卡片上而不是缩在标题里 */
.project-card:has(.title:focus-visible) {
    outline: 2px solid var(--brand);
    outline-offset: 2px;
}

.cover {
    display: flex;
    aspect-ratio: 16 / 9;
    background: #eff6ff;
    /* 封面不参与点击（交给整卡覆盖层），但也不该挡住图片本身的拖拽/右键另存 */
    flex-shrink: 0;
    overflow: hidden;
}

/* SmartImage 的图片与兜底块都填满封面区（兜底时显示项目名首字 + 渐变底色） */
.cover :deep(.img-fallback__img),
.cover :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
    font-size: 34px;
}

.body {
    padding: 16px 18px 18px;
    display: flex;
    flex-direction: column;
    flex: 1;
}

.head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 8px;
}

.title {
    font-size: 17px;
    font-weight: 600;
    color: var(--text);
}

/**
 * 伸展式链接：伪元素铺满整张卡片，卡片任意位置都能点进详情
 *
 * 不能用「把卡片包进 <router-link>」：卡内两个外链会形成嵌套 <a>，浏览器会拆标签。
 * 不能用 onclick + router.push：中键 / Ctrl+点击「新标签打开」会失效，伪元素则保留原生行为。
 */
.title::after {
    content: '';
    position: absolute;
    inset: 0;
    /* 覆盖层只负责接收点击，本身不该有视觉表现 */
    z-index: 1;
}

.title:hover {
    color: var(--brand);
    text-decoration: none;
}

.techs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

p {
    font-size: 14px;
    margin: 0 0 12px;
    flex: 1;
}

.links {
    display: flex;
    gap: 14px;
    margin-top: 12px;
    font-size: 13px;
    /* 抬到覆盖层之上，否则点「在线体验」会被卡片的跳转吃掉 */
    position: relative;
    z-index: 2;
}

.link {
    color: var(--brand);
}

.link:hover {
    text-decoration: underline;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 24px;
}
</style>
