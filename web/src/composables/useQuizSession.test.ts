import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import type { QuizBatch, QuizVerdict } from '@/api/types';

// 刷题会话状态机测试
//
// 为什么只测状态机，不测页面：答题页的绝大部分逻辑都在这个 composable 里
// （phase 流转、sessionStorage 恢复、提交失败回退、跳过前移指针）。
// 页面本身只剩模板绑定，测它的成本远高于收益。
//
// 为什么 mock `quizApi` 而不是 mock axios：请求层的解包逻辑
// 已有 `request.test.ts` 覆盖；这里要验的是「拿到响应后状态怎么变」，
// 直接替掉 API 层能让每个用例只有三五行。
vi.mock('@/api/quiz', () => ({
    quizApi: {
        startBatch: vi.fn(),
        answer: vi.fn(),
        skip: vi.fn(),
    },
}));

// 必须在 mock 之后 import：ESM 的静态导入会被提升，但 vi.mock 也提升，
// 因此这里拿到的是被替换后的模块
import { quizApi } from '@/api/quiz';
import { QUIZ_STORAGE_KEY, useQuizSession } from './useQuizSession';

const mockedStart = vi.mocked(quizApi.startBatch);
const mockedAnswer = vi.mocked(quizApi.answer);
const mockedSkip = vi.mocked(quizApi.skip);

/** 造一批两题（一单选一多选） */
function batch(): QuizBatch {
    return {
        batchId: '1001',
        mode: 0,
        total: 2,
        questions: [
            {
                id: 'q1',
                type: 0,
                stem: '单选题干',
                options: [
                    { label: 'A', content: '甲' },
                    { label: 'B', content: '乙' },
                ],
            },
            {
                id: 'q2',
                type: 1,
                stem: '多选题干',
                options: [
                    { label: 'A', content: '甲' },
                    { label: 'B', content: '乙' },
                    { label: 'C', content: '丙' },
                ],
            },
        ],
    };
}

/** 造一个判定结果 */
function verdict(overrides: Partial<QuizVerdict> = {}): QuizVerdict {
    return {
        questionId: 'q1',
        type: 0,
        result: 1,
        chosenLabels: 'A',
        correctLabels: 'A',
        analysis: '解析',
        wrongBookAction: 'none',
        remainingStreak: null,
        answeredTotal: 1,
        correctTotal: 1,
        ...overrides,
    };
}

describe('useQuizSession', () => {
    beforeEach(() => {
        sessionStorage.clear();
        vi.clearAllMocks();
    });

    it('开批后进入 idle，当前题是第一题', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();

        const ok = await session.start({ mode: 0, textbookName: '教材', chapterName: '章节' });

        expect(ok).toBe(true);
        expect(session.phase.value).toBe('idle');
        expect(session.current.value?.id).toBe('q1');
        expect(session.total.value).toBe(2);
        expect(session.index.value).toBe(0);
        // 题目不含答案：composable 拿到的对象里不该出现 correctLabels
        expect(JSON.stringify(session.current.value)).not.toContain('correctLabels');
    });

    it('开批失败：返回 false 并记录原因，不进入答题态', async () => {
        mockedStart.mockRejectedValue(new Error('本章已全部刷完'));
        const session = useQuizSession();

        const ok = await session.start({ mode: 0 });

        expect(ok).toBe(false);
        expect(session.error.value).toBe('本章已全部刷完');
        expect(session.batch.value).toBeNull();
    });

    it('单选：toggle 覆盖旧值（不是累加）', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        session.toggle('A');
        expect(session.chosen.value).toBe('A');

        session.toggle('B');
        expect(session.chosen.value).toBe('B');
    });

    it('多选：toggle 累加并保持升序，再点一次取消', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        // 移到第二题（多选）
        session.next();
        expect(session.current.value?.id).toBe('q2');
        expect(session.isMultiple.value).toBe(true);

        session.toggle('C');
        session.toggle('A');
        expect(session.chosen.value).toBe('AC');

        session.toggle('C');
        expect(session.chosen.value).toBe('A');
    });

    it('提交成功：phase 变 graded，判定写入、计数累加', async () => {
        mockedStart.mockResolvedValue(batch());
        mockedAnswer.mockResolvedValue(verdict());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        session.toggle('A');
        const result = await session.submit();

        expect(result?.result).toBe(1);
        expect(session.phase.value).toBe('graded');
        expect(session.answeredCount.value).toBe(1);
        expect(session.correctCount.value).toBe(1);
        expect(session.accuracy.value).toBe(1);
    });

    it('提交失败：回到 idle 并保留已选（让用户能直接重试）', async () => {
        mockedStart.mockResolvedValue(batch());
        mockedAnswer.mockRejectedValue(new Error('网络异常'));
        const session = useQuizSession();
        await session.start({ mode: 0 });

        session.toggle('A');
        const result = await session.submit();

        expect(result).toBeNull();
        expect(session.phase.value).toBe('idle');
        expect(session.chosen.value).toBe('A');
        expect(session.error.value).toBe('网络异常');
    });

    it('未选就提交：不发请求，给出提示', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        const result = await session.submit();

        expect(result).toBeNull();
        expect(session.error.value).toContain('选择');
        expect(mockedAnswer).not.toHaveBeenCalled();
    });

    it('已判定后 toggle 无效（防「看到答案再改选」）', async () => {
        mockedStart.mockResolvedValue(batch());
        mockedAnswer.mockResolvedValue(verdict());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        session.toggle('A');
        await session.submit();
        session.toggle('B');

        expect(session.chosen.value).toBe('A');
    });

    it('跳过：调接口、前移指针、不产生判定', async () => {
        mockedStart.mockResolvedValue(batch());
        mockedSkip.mockResolvedValue(true);
        const session = useQuizSession();
        await session.start({ mode: 0 });

        const ok = await session.skip();

        expect(ok).toBe(true);
        expect(mockedSkip).toHaveBeenCalledWith('1001', 'q1');
        expect(session.index.value).toBe(1);
        expect(session.verdict.value).toBeNull();
        // 跳过不算作答
        expect(session.answeredCount.value).toBe(0);
    });

    it('答完最后一题后 next：isFinished 为 true', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        session.next();
        session.next();

        expect(session.isFinished.value).toBe(true);
        expect(session.current.value).toBeNull();
    });

    it('进度按「已处理题数」计算', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        expect(session.progress.value).toBe(0);
        session.next();
        expect(session.progress.value).toBe(50);
        session.next();
        expect(session.progress.value).toBe(100);
    });

    it('持久化：进度写入 sessionStorage，restore 能恢复', async () => {
        mockedStart.mockResolvedValue(batch());
        const first = useQuizSession();
        await first.start({ mode: 0 });
        first.next();

        /** 新实例模拟「刷新页面」 */
        const second = useQuizSession();
        const ok = second.restore();

        expect(ok).toBe(true);
        expect(second.index.value).toBe(1);
        expect(second.current.value?.id).toBe('q2');
        expect(second.total.value).toBe(2);
    });

    it('restore：没有数据时返回 false（页面据此回首页）', () => {
        const session = useQuizSession();
        expect(session.restore()).toBe(false);
    });

    it('restore：本地数据损坏时清掉并返回 false，不抛异常', () => {
        sessionStorage.setItem(QUIZ_STORAGE_KEY, '{不是合法 JSON');
        const session = useQuizSession();

        expect(session.restore()).toBe(false);
        expect(sessionStorage.getItem(QUIZ_STORAGE_KEY)).toBeNull();
    });

    it('restore：恢复时若当前题已判定，直接进入 graded', async () => {
        mockedStart.mockResolvedValue(batch());
        mockedAnswer.mockResolvedValue(verdict());
        const first = useQuizSession();
        await first.start({ mode: 0 });
        first.toggle('A');
        await first.submit();

        const second = useQuizSession();
        second.restore();

        expect(second.phase.value).toBe('graded');
        expect(second.verdict.value?.correctLabels).toBe('A');
        expect(second.chosen.value).toBe('A');
    });

    it('clear：清空状态与本地存储', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });
        session.clear();

        expect(session.batch.value).toBeNull();
        expect(sessionStorage.getItem(QUIZ_STORAGE_KEY)).toBeNull();
    });

    it('正确率为 null 时表示「未作答」（前端显示 --）', async () => {
        mockedStart.mockResolvedValue(batch());
        const session = useQuizSession();
        await session.start({ mode: 0 });

        expect(session.accuracy.value).toBeNull();

        mockedAnswer.mockResolvedValue(verdict({ result: 2, correctTotal: 0 }));
        session.toggle('B');
        await session.submit();
        await nextTick();

        expect(session.accuracy.value).toBe(0);
    });
});
