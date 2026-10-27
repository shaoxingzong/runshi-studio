<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AnswerFeedback from '@/components/quiz/AnswerFeedback.vue';
import QuestionCard from '@/components/quiz/QuestionCard.vue';
import { useQuizSession } from '@/composables/useQuizSession';
import { usePageMeta } from '@/composables/usePageMeta';

/**
 * 答题页
 *
 * 进度来自 sessionStorage：服务端不持久化批次（题目不含答案，整批交给前端即可），刷新后靠本地恢复。
 * 换设备不能续答是刻意接受的——刷题不是考试，重开成本为 0。
 *
 * 键盘：A–F 选项、Enter 提交/下一题、S 跳过。桌面端鼠标在选项与「下一题」之间来回移动是最大的时间浪费。
 */
const router = useRouter();
const session = useQuizSession();

usePageMeta({ title: '答题' });

const starting = ref(true);

/** 当前是否已到本轮末尾（进入结果视图） */
const finished = computed(() => session.isFinished.value);

/** 当前题是否可提交（多选需要至少选一个） */
const canSubmit = computed(
    () => session.phase.value === 'idle' && session.chosen.value.length > 0 && !!session.current.value,
);

/** 单选点击即提交：这是效率设计，代价是「点了不能改」——与真实考试一致 */
async function pick(label: string) {
    session.toggle(label);
    if (!session.isMultiple.value) {
        await session.submit();
    }
}

async function submit() {
    if (!canSubmit.value) {
        return;
    }
    await session.submit();
}

async function skip() {
    await session.skip();
}

function next() {
    session.next();
}

/** 结束本轮：清掉本地进度，回首页（结果视图里有「再来一轮」） */
async function finish() {
    session.clear();
    await router.push('/quiz');
}

/** 再来一轮：回首页重新选（保留模式与范围的选择会更贴心，但首页已默认选好第一本教材） */
async function restart() {
    session.clear();
    await router.push('/quiz');
}

/** 本轮正确率；未答时为 null → 显示 "--" */
const accuracyText = computed(() => {
    const value = session.accuracy.value;
    return value === null ? '--' : `${Math.round(value * 100)}%`;
});

/** 键盘事件 */
function onKeydown(event: KeyboardEvent) {
    /** 输入框里不劫持按键（虽然本页没有输入框，但将来可能加「笔记」） */
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) {
        return;
    }
    const key = event.key.toUpperCase();
    if (/^[A-F]$/.test(key) && !session.isGraded.value && !finished.value) {
        event.preventDefault();
        void pick(key);
        return;
    }
    if (key === 'ENTER') {
        event.preventDefault();
        if (session.isGraded.value) {
            next();
        } else if (session.isMultiple.value) {
            void submit();
        }
        return;
    }
    if (key === 'S' && !session.isGraded.value && !finished.value) {
        event.preventDefault();
        void skip();
    }
}

onMounted(async () => {
    // 恢复失败（没有批次、或本地数据损坏）时回首页，而不是显示一个空白的答题页
    if (!session.restore()) {
        ElMessage.warning('没有正在进行的题目，请先开始一轮');
        await router.replace('/quiz');
        return;
    }
    starting.value = false;
    window.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown);
});
</script>

<template>
    <div class="page" v-loading="starting">
        <template v-if="!finished && session.current.value">
            <!-- 顶部：进度 + 本轮统计 + 结束 -->
            <div class="topbar">
                <el-button link @click="router.push('/quiz')">← 返回</el-button>
                <span class="counter">
                    第 {{ session.index.value + 1 }} / {{ session.total.value }} 题
                </span>
                <el-progress
                    :percentage="session.progress.value"
                    :show-text="false"
                    :stroke-width="6"
                    class="bar"
                />
                <span class="score">本轮正确 {{ session.correctCount.value }}</span>
                <el-button link type="danger" @click="finish">结束本轮</el-button>
            </div>

            <QuestionCard
                :stem="session.current.value.stem"
                :type="session.current.value.type"
                :options="session.current.value.options"
                :chosen="session.chosen.value"
                :verdict="session.verdict.value"
                :disabled="session.phase.value !== 'idle'"
                @pick="pick"
            />

            <AnswerFeedback v-if="session.verdict.value" :verdict="session.verdict.value" />

            <el-alert
                v-if="session.error.value"
                type="error"
                :title="session.error.value"
                :closable="false"
                show-icon
                class="err"
            />

            <!-- 底部：跳过 / 提交（多选）/ 下一题 -->
            <div class="actions">
                <el-button v-if="!session.isGraded.value" @click="skip">跳过</el-button>
                <span class="spacer" />
                <el-button
                    v-if="session.isMultiple.value && !session.isGraded.value"
                    type="primary"
                    :disabled="!canSubmit"
                    :loading="session.phase.value === 'submitting'"
                    @click="submit"
                >
                    提交答案（已选 {{ session.chosen.value.length }} 项）
                </el-button>
                <el-button v-if="session.isGraded.value" type="primary" @click="next">
                    下一题
                </el-button>
            </div>
        </template>

        <!-- 结果视图：本轮结束时原地切换，不跳新路由（用户点「返回」能直接回首页） -->
        <section v-else-if="finished" class="card result">
            <h2>本轮结束</h2>
            <div class="result-accuracy">{{ accuracyText }}</div>
            <div class="muted">正确率</div>

            <div class="result-stats">
                <div class="result-stat">
                    <div class="value">{{ session.answeredCount.value }}</div>
                    <div class="label">已作答</div>
                </div>
                <div class="result-stat">
                    <div class="value ok">{{ session.correctCount.value }}</div>
                    <div class="label">答对</div>
                </div>
                <div class="result-stat">
                    <div class="value bad">
                        {{ session.answeredCount.value - session.correctCount.value }}
                    </div>
                    <div class="label">答错</div>
                </div>
                <div class="result-stat">
                    <div class="value">{{ session.total.value }}</div>
                    <div class="label">本轮题量</div>
                </div>
            </div>

            <div class="result-actions">
                <el-button type="primary" @click="restart">再来一轮</el-button>
                <el-button @click="finish">返回刷题首页</el-button>
            </div>
        </section>
    </div>
</template>

<style scoped>
.topbar {
    display: flex;
    align-items: center;
    gap: 14px;
    margin-bottom: 16px;
    font-size: 14px;
    color: var(--muted);
    flex-wrap: wrap;
}

.counter {
    color: var(--text);
    font-weight: 500;
    white-space: nowrap;
}

.bar {
    flex: 1;
    min-width: 120px;
}

.score {
    white-space: nowrap;
}

.err {
    margin-top: 14px;
}

.actions {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 20px;
}

.spacer {
    flex: 1;
}

/* ===== 结果视图 ===== */
.result {
    text-align: center;
    padding: 40px 24px;
}

.result h2 {
    margin: 0 0 18px;
    font-size: 20px;
    font-weight: 600;
}

.result-accuracy {
    font-size: 48px;
    font-weight: 700;
    color: var(--brand);
    line-height: 1.1;
}

.result-stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin: 28px 0;
}

.result-stat {
    background: var(--bg);
    border-radius: var(--radius-sm);
    padding: 16px 10px;
}

.result-stat .value {
    font-size: 22px;
    font-weight: 600;
}

.result-stat .value.ok {
    color: var(--success);
}

.result-stat .value.bad {
    color: var(--danger);
}

.result-stat .label {
    margin-top: 4px;
    font-size: 12px;
    color: var(--muted);
}

.result-actions {
    display: flex;
    gap: 12px;
    justify-content: center;
}

/* 手机：顶部统计折行，按钮吸底更易点 */
@media (max-width: 640px) {
    .topbar {
        gap: 8px;
    }

    .bar {
        order: 10;
        flex-basis: 100%;
    }

    .result-stats {
        grid-template-columns: repeat(2, 1fr);
    }

    .actions {
        position: sticky;
        bottom: 0;
        background: var(--bg);
        padding: 12px 0;
        margin-top: 12px;
    }
}
</style>
