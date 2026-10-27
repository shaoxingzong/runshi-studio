<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { quizApi } from '@/api/quiz';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ModePicker from '@/components/quiz/ModePicker.vue';
import StatTiles from '@/components/quiz/StatTiles.vue';
import WrongBookDrawer from '@/components/quiz/WrongBookDrawer.vue';
import { QUIZ_STORAGE_KEY } from '@/composables/useQuizSession';
import { usePageMeta } from '@/composables/usePageMeta';
import { useUserStore } from '@/stores/user';
import type { QuizMode, QuizOverview, QuizTextbook } from '@/api/types';

/**
 * 刷题首页（对照设计稿：选择模式 / 教材章节 / 开始 / 学习概览 / 错题本）
 *
 * 准入：只有在队成员能用（认 studio_member 表）。非成员被拦下送回首页——
 * 不是报错，「不是成员」是正常的身份状态。
 *
 * 教材树、概览、上次批次三个请求并发拉：互不依赖，串行会多等两个往返。
 */
const router = useRouter();
const user = useUserStore();

const loading = ref(false);
const starting = ref(false);
const error = ref('');

const catalog = ref<QuizTextbook[]>([]);
const overview = ref<QuizOverview | null>(null);

/** 表单：模式 + 教材 + 章节 + 是否重刷本章 */
const form = reactive({
    mode: 0 as QuizMode,
    textbookName: '',
    chapterName: '',
    restart: false,
});

const wrongDrawer = ref(false);
/** 是否有可继续的批次（只判断 sessionStorage 里有没有，不解析内容） */
const hasOngoing = ref(false);

/** 当前教材的章节列表 */
const chapters = computed(
    () => catalog.value.find((item) => item.name === form.textbookName)?.chapters ?? [],
);

/** 顺序模式必须选教材与章节；其余模式可空（空 = 全库范围） */
const isSequential = computed(() => form.mode === 0);

/** 开始按钮文案：随模式变化，让用户知道点下去会发生什么 */
const startText = computed(() => {
    if (form.mode === 1) {
        const pending = overview.value?.wrongPending ?? 0;
        return pending > 0 ? `重刷 ${Math.min(pending, 20)} 道错题` : '错题本已清空';
    }
    if (form.mode === 2) {
        return '随机抽 20 题';
    }
    return '开始刷题';
});

/** 开始按钮是否可用 */
const canStart = computed(() => {
    if (form.mode === 1) {
        return (overview.value?.wrongPending ?? 0) > 0;
    }
    if (form.mode === 0) {
        return !!form.textbookName && !!form.chapterName;
    }
    // 随机模式：至少要有一本教材（否则题库是空的）
    return catalog.value.length > 0;
});

async function load() {
    loading.value = true;
    error.value = '';
    try {
        const [tree, stats] = await Promise.all([quizApi.catalog(), quizApi.overview()]);
        catalog.value = tree ?? [];
        overview.value = stats;
        // 默认选中第一本教材的第一章：让用户不必先点两次下拉才能开始
        if (!form.textbookName && catalog.value.length > 0) {
            form.textbookName = catalog.value[0].name;
            form.chapterName = catalog.value[0].chapters[0]?.name ?? '';
        }
        hasOngoing.value = !!sessionStorage.getItem(QUIZ_STORAGE_KEY);
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/** 切换教材时把章节重置为新教材的第一章（否则会带着上一本书的章节名） */
function onTextbookChange() {
    form.chapterName = chapters.value[0]?.name ?? '';
}

async function start() {
    if (!canStart.value || starting.value) {
        return;
    }
    starting.value = true;
    try {
        const params = {
            mode: form.mode,
            size: 20,
            textbookName: form.textbookName || undefined,
            chapterName: isSequential.value ? form.chapterName : form.chapterName || undefined,
            restart: form.mode === 0 ? form.restart : undefined,
        };
        const batch = await quizApi.startBatch(params);
        // 把整批交给答题页：题目不含答案，存 sessionStorage 无泄露风险
        sessionStorage.setItem(
            QUIZ_STORAGE_KEY,
            JSON.stringify({
                batchId: batch.batchId,
                mode: batch.mode,
                questions: batch.questions,
                index: 0,
                answered: 0,
                correct: 0,
                verdicts: {},
            }),
        );
        await router.push('/quiz/play');
    } catch (e) {
        // 具体原因（章节刷完 / 错题本为空）已在请求层弹出，这里不再重复
        error.value = (e as Error).message || '开始失败';
    } finally {
        starting.value = false;
    }
}

/** 继续上次：直接进答题页，由它从 sessionStorage 恢复 */
async function resume() {
    await router.push('/quiz/play');
}

onMounted(async () => {
    usePageMeta({ title: '刷题', description: '政治选择题刷题：顺序、错题重刷与随机抽题，错题本自动收录。' });
    // 布局不会在路由切换时重建，这里主动拉一次确保身份最新。
    //
    // 必须自己兜住异常：onMounted 是 async 的，退出登录后仍停留在本页时请求会失败，
    // rejection 冒泡到全局处理器会弹出「页面显示异常」——而页面其实好好的。
    try {
        await user.loadMemberProfile();
    } catch {
        /* 登录态失效：请求层已清理本地状态，走下面的分支 */
    }
    if (!user.isMember) {
        if (!user.isLogin) {
            ElMessage.info('登录后即可使用刷题功能');
        } else {
            ElMessage.warning('仅工作室成员可以使用刷题功能');
        }
        await router.replace('/');
        return;
    }
    await load();
});
</script>

<template>
    <div class="page" v-loading="loading">
        <div class="page-head">
            <h1>刷题</h1>
            <p>政治选择题练习，错题自动收进错题本。</p>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <!-- 继续上次：有未完成的批次时才出现 -->
        <div v-if="hasOngoing" class="resume">
            <span>你有一轮还没刷完</span>
            <el-button type="primary" link @click="resume">继续上次 →</el-button>
        </div>

        <!-- ① 选择模式 + 教材章节 + 开始 -->
        <section class="card section">
            <h2 class="section-title"><span class="step">①</span> 选择模式</h2>

            <ModePicker v-model="form.mode" :wrong-pending="overview?.wrongPending ?? 0" />

            <div class="selectors">
                <div class="field">
                    <label>教材</label>
                    <el-select v-model="form.textbookName" placeholder="请选择教材" @change="onTextbookChange">
                        <el-option v-for="book in catalog" :key="book.name" :label="book.name" :value="book.name" />
                    </el-select>
                </div>
                <div class="field">
                    <label>章节</label>
                    <el-select v-model="form.chapterName" :placeholder="isSequential ? '请选择章节' : '全部章节'">
                        <el-option v-if="!isSequential" label="全部章节" value="" />
                        <el-option
                            v-for="chapter in chapters"
                            :key="chapter.name"
                            :label="chapter.name"
                            :value="chapter.name"
                        />
                    </el-select>
                </div>
            </div>

            <!-- 顺序模式的重刷开关：刷完一整章后才需要 -->
            <div v-if="isSequential" class="restart">
                <el-checkbox v-model="form.restart">重新刷本章（忽略已作答记录）</el-checkbox>
            </div>

            <el-button
                type="primary"
                size="large"
                class="start-btn"
                :loading="starting"
                :disabled="!canStart"
                @click="start"
            >
                {{ startText }}
            </el-button>
        </section>

        <!-- ② 学习概览 -->
        <section class="card section">
            <h2 class="section-title">学习概览</h2>
            <StatTiles :overview="overview" />
        </section>

        <!-- ③ 错题本入口 -->
        <section class="card section">
            <div class="wrong-head">
                <h2 class="section-title">
                    错题本
                    <span class="count">{{ overview?.wrongPending ?? 0 }} 题</span>
                </h2>
                <el-button
                    v-if="(overview?.wrongPending ?? 0) > 0"
                    link
                    type="primary"
                    @click="wrongDrawer = true"
                >
                    查看全部
                </el-button>
            </div>

            <div v-if="(overview?.wrongPending ?? 0) === 0" class="wrong-empty">
                还没有错题。答错的题会自动收进这里。
            </div>
            <div v-else class="wrong-hint">
                有 {{ overview?.wrongPending }} 道题等你复习，选「错题重刷」开始。
            </div>
        </section>

        <WrongBookDrawer v-model="wrongDrawer" @refresh="load" />
    </div>
</template>

<style scoped>
.page-head {
    margin-bottom: 20px;
}

.section {
    margin-bottom: 18px;
}

.section-title {
    margin: 0 0 16px;
    font-size: 17px;
    font-weight: 600;
    color: var(--text);
    display: flex;
    align-items: center;
    gap: 8px;
}

.step {
    color: var(--brand);
    font-weight: 700;
}

.selectors {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    margin-top: 18px;
    padding-top: 18px;
    border-top: 1px solid var(--border);
}

.field {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.field label {
    font-size: 13px;
    color: var(--muted);
}

.restart {
    margin-top: 12px;
}

.start-btn {
    width: 100%;
    margin-top: 18px;
    height: 46px;
    font-size: 16px;
}

.resume {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px 18px;
    margin-bottom: 18px;
    border-radius: var(--radius-sm);
    background: var(--brand-soft);
    color: var(--brand-dark);
    font-size: 14px;
}

.wrong-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.wrong-head .section-title {
    margin-bottom: 0;
}

.count {
    font-size: 12px;
    font-weight: 500;
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--bg);
    color: var(--muted);
}

.wrong-empty {
    padding: 28px 0;
    text-align: center;
    color: var(--muted);
    font-size: 14px;
}

.wrong-hint {
    padding: 18px 0 6px;
    color: var(--text-secondary);
    font-size: 14px;
}

/* 手机：教材与章节改单列 */
@media (max-width: 640px) {
    .selectors {
        grid-template-columns: 1fr;
    }
}
</style>
