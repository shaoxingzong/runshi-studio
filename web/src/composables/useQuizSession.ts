import { computed, ref } from 'vue';
import { quizApi } from '@/api/quiz';
import type { QuizMode, QuizQuestion, QuizVerdict } from '@/api/types';

/** 一轮刷题的状态机 */
export type QuizPhase = 'idle' | 'submitting' | 'graded';

/** sessionStorage 的键：单数，因为同时只会有一轮 */
export const QUIZ_STORAGE_KEY = 'quiz_batch';

/** 存进 sessionStorage 的结构（题目不含答案，存浏览器无泄露风险） */
interface StoredBatch {
    batchId: string;
    mode: QuizMode;
    questions: QuizQuestion[];
    // 当前指针：第几题（0 起）
    index: number;
    // 本轮已回答的题数（用于进度条与「本轮正确」）
    answered: number;
    // 本轮答对数
    correct: number;
    // 已判定过的题：questionId → 判定结果（用于「上一题」回看）
    verdicts: Record<string, QuizVerdict>;
}

/**
 * 刷题会话：一批题的完整生命周期（开批 → 答题 / 跳过 → 下一题 → 结算）
 *
 * 题目本身不含答案，存浏览器没有泄露风险；页面卸载时无需手动清理。
 */
export function useQuizSession() {
    const batch = ref<StoredBatch | null>(null);
    const phase = ref<QuizPhase>('idle');
    /** 当前已选标签（单选只有一个字符） */
    const chosen = ref<string>('');
    /** 当前题的判定结果（graded 时才有值） */
    const verdict = ref<QuizVerdict | null>(null);
    /** 提交失败的原因（空 = 正常） */
    const error = ref('');

    const current = computed<QuizQuestion | null>(() => {
        const data = batch.value;
        if (!data || data.index < 0 || data.index >= data.questions.length) {
            return null;
        }
        return data.questions[data.index];
    });

    const total = computed(() => batch.value?.questions.length ?? 0);
    const index = computed(() => batch.value?.index ?? 0);
    const isFinished = computed(() => total.value > 0 && index.value >= total.value);
    const progress = computed(() => (total.value === 0 ? 0 : Math.round((index.value / total.value) * 100)));

    /** 当前题是否已判定（决定选项禁用与「下一题」可用性） */
    const isGraded = computed(() => phase.value === 'graded');

    /** 当前题是否为多选（决定 radio / checkbox 与「点击即提交」） */
    const isMultiple = computed(() => current.value?.type === 1);

    // 持久化

    /** 从 sessionStorage 恢复（刷新页面后接着答） */
    function restore(): boolean {
        try {
            const raw = sessionStorage.getItem(QUIZ_STORAGE_KEY);
            if (!raw) {
                return false;
            }
            const parsed = JSON.parse(raw) as StoredBatch;
            if (!parsed || !parsed.batchId || !Array.isArray(parsed.questions)) {
                return false;
            }
            batch.value = parsed;
            /** 恢复时如果当前题已有判定，直接进 graded（回看上一题的状态） */
            const question = parsed.questions[parsed.index];
            if (question && parsed.verdicts[question.id]) {
                verdict.value = parsed.verdicts[question.id];
                chosen.value = parsed.verdicts[question.id].chosenLabels;
                phase.value = 'graded';
            }
            return true;
        } catch {
            // 本地数据损坏（手工改过、格式不匹配）：当作没有，让用户重开一轮
            sessionStorage.removeItem(QUIZ_STORAGE_KEY);
            return false;
        }
    }

    function persist() {
        if (!batch.value) {
            return;
        }
        try {
            sessionStorage.setItem(QUIZ_STORAGE_KEY, JSON.stringify(batch.value));
        } catch {
        }
    }

    /** 清空本轮（结束或退出时调用） */
    function clear() {
        batch.value = null;
        phase.value = 'idle';
        chosen.value = '';
        verdict.value = null;
        error.value = '';
        sessionStorage.removeItem(QUIZ_STORAGE_KEY);
    }

    // 开始一轮

    /**
     * 开一批并进入答题
     *
     * @return 是否成功；失败时 `error` 有值
     */
    async function start(params: {
        mode: QuizMode;
        textbookName?: string;
        chapterName?: string;
        size?: number;
        restart?: boolean;
    }): Promise<boolean> {
        error.value = '';
        try {
            const data = await quizApi.startBatch(params);
            batch.value = {
                batchId: data.batchId,
                mode: data.mode,
                questions: data.questions ?? [],
                index: 0,
                answered: 0,
                correct: 0,
                verdicts: {},
            };
            phase.value = 'idle';
            chosen.value = '';
            verdict.value = null;
            persist();
            return true;
        } catch (e) {
            error.value = (e as Error).message || '开始失败';
            return false;
        }
    }

    // 选择与提交

    /**
     * 切换某个选项：单选直接覆盖，多选加入或移除
     *
     * 已判定时忽略——选项此时已禁用，这里再挡一层防键盘事件绕过。
     */
    function toggle(label: string) {
        if (phase.value !== 'idle') {
            return;
        }
        if (isMultiple.value) {
            chosen.value = chosen.value.includes(label)
                ? chosen.value.replace(label, '')
                : (chosen.value + label).split('').sort().join('');
        } else {
            chosen.value = label;
        }
    }

    /**
     * 提交当前题
     *
     * @return 判定结果；失败返回 null（`error` 有值，`phase` 回到 idle 且保留已选）
     */
    async function submit(): Promise<QuizVerdict | null> {
        const data = batch.value;
        const question = current.value;
        if (!data || !question || phase.value !== 'idle') {
            return null;
        }
        if (!chosen.value) {
            error.value = '请先选择答案';
            return null;
        }

        phase.value = 'submitting';
        error.value = '';
        try {
            const result = await quizApi.answer(data.batchId, question.id, chosen.value);
            verdict.value = result;
            data.verdicts[question.id] = result;
            data.answered += 1;
            if (result.result === 1) {
                data.correct += 1;
            }
            phase.value = 'graded';
            persist();
            return result;
        } catch (e) {
            // 回到 idle 并保留已选：让用户能直接重试，而不是重选一遍
            phase.value = 'idle';
            error.value = (e as Error).message || '提交失败';
            return null;
        }
    }

    /** 跳过当前题：不产生判定、不进错题本、不返回答案 */
    async function skip(): Promise<boolean> {
        const data = batch.value;
        const question = current.value;
        if (!data || !question || phase.value === 'submitting') {
            return false;
        }
        try {
            await quizApi.skip(data.batchId, question.id);
            next();
            return true;
        } catch (e) {
            error.value = (e as Error).message || '跳过失败';
            return false;
        }
    }

    /** 进入下一题（或结束本轮） */
    function next() {
        const data = batch.value;
        if (!data) {
            return;
        }
        data.index += 1;
        phase.value = 'idle';
        chosen.value = '';
        verdict.value = null;
        error.value = '';
        persist();
    }

    // 本轮统计

    /** 本轮已答数（不含跳过） */
    const answeredCount = computed(() => batch.value?.answered ?? 0);
    /** 本轮答对数 */
    const correctCount = computed(() => batch.value?.correct ?? 0);
    /** 本轮正确率；未答时为 null（显示 "--"） */
    const accuracy = computed(() => {
        const answered = answeredCount.value;
        return answered === 0 ? null : correctCount.value / answered;
    });

    return {
        // 状态
        batch,
        phase,
        chosen,
        verdict,
        error,
        // 派生
        current,
        total,
        index,
        isFinished,
        progress,
        isGraded,
        isMultiple,
        answeredCount,
        correctCount,
        accuracy,
        // 动作
        restore,
        persist,
        clear,
        start,
        toggle,
        submit,
        skip,
        next,
    };
}
