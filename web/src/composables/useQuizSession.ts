import { computed, ref } from 'vue';
import { quizApi } from '@/api/quiz';
import type { QuizMode, QuizQuestion, QuizVerdict } from '@/api/types';

// 一轮刷题的状态机
//
// 为什么用 composable 而不是 Pinia store：会话状态只属于当前页面，
// 刷新后由 sessionStorage 恢复；放进全局 store 反而要处理「离开页面后何时清空」。
// 项目里 Pinia 目前只承载登录态（`stores/user.ts`），保持这个边界。
//
// 为什么进度存在 sessionStorage：服务端不持久化批次（题目不含答案，
// 整批交给前端即可），所以刷新页面后只能靠本地恢复。用 sessionStorage 而不是
// localStorage：关掉标签页就该结束这一轮，而不是下次打开还停在三周前的第 7 题。
//
// 三个 phase 的含义：
// - idle：还没选答案，或提交失败退回（保留已选）；
// - submitting：请求在飞，选项与提交按钮禁用；
// - graded：已拿到判定，选项全部禁用——防止「看到答案再改选」。
//
// 提交失败不本地判分：判分是服务端的职责，前端兜底判分会与后端规则分叉
// （例如多选题的部分分规则），而且用户会看到与统计不一致的结果。
// 失败时回到 idle，由请求层统一弹提示。
export type QuizPhase = 'idle' | 'submitting' | 'graded';

// sessionStorage 的键：单数，因为同时只会有一轮
export const QUIZ_STORAGE_KEY = 'quiz_batch';

// 存进 sessionStorage 的结构（题目不含答案，存浏览器无泄露风险）
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

export function useQuizSession() {
    const batch = ref<StoredBatch | null>(null);
    const phase = ref<QuizPhase>('idle');
    // 当前已选标签（单选只有一个字符）
    const chosen = ref<string>('');
    // 当前题的判定结果（graded 时才有值）
    const verdict = ref<QuizVerdict | null>(null);
    // 提交失败的原因（空 = 正常）
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

    // 当前题是否已判定（决定选项禁用与「下一题」可用性）
    const isGraded = computed(() => phase.value === 'graded');

    // 当前题是否为多选（决定 radio / checkbox 与「点击即提交」）
    const isMultiple = computed(() => current.value?.type === 1);

    // ==================== 持久化 ====================

    // 从 sessionStorage 恢复（刷新页面后接着答）
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
            // 恢复时如果当前题已有判定，直接进 graded（回看上一题的状态）
            const question = parsed.questions[parsed.index];
            if (question && parsed.verdicts[question.id]) {
                verdict.value = parsed.verdicts[question.id];
                chosen.value = parsed.verdicts[question.id].chosenLabels;
                phase.value = 'graded';
            }
            return true;
        } catch {
            // 本地数据损坏（手工改过、旧版本格式）：当作没有，让用户重开一轮
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
            // 存储写满 / 隐私模式：静默失败。进度丢失只影响「刷新续答」，
            // 不该因此中断正在进行的答题
        }
    }

    // 清空本轮（结束或退出时调用）
    function clear() {
        batch.value = null;
        phase.value = 'idle';
        chosen.value = '';
        verdict.value = null;
        error.value = '';
        sessionStorage.removeItem(QUIZ_STORAGE_KEY);
    }

    // ==================== 开始一轮 ====================

    // 开一批并进入答题
    //
    // @param params 开批参数（mode 必填）
    // @return 是否成功；失败时 `error` 有值
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
            // 具体原因（章节刷完 / 错题本为空）已由请求层弹出，
            // 这里额外留一份给页面内的提示条
            error.value = (e as Error).message || '开始失败';
            return false;
        }
    }

    // ==================== 选择与提交 ====================

    // 切换某个选项
    //
    // 单选：直接覆盖（只有一个值）；多选：加入或移除。
    // 已判定时忽略——选项在 graded 阶段是禁用的，这里再挡一层防键盘事件绕过。
    //
    // @param label 选项标签（A–F）
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

    // 提交当前题
    //
    // @return 判定结果；失败时返回 null（`error` 有值，`phase` 回到 idle 且保留已选）
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

    // 跳过当前题
    //
    // 跳过不产生判定、不进错题本、不返回答案。
    // 前端直接前移指针即可——服务端只落一条「跳过过」的记录。
    //
    // @return 是否成功
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

    // 进入下一题（或结束本轮）
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

    // ==================== 本轮统计 ====================

    // 本轮已答数（不含跳过）
    const answeredCount = computed(() => batch.value?.answered ?? 0);
    // 本轮答对数
    const correctCount = computed(() => batch.value?.correct ?? 0);
    // 本轮正确率；未答时为 null（显示 "--"）
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
