import { http } from './request';
import type {
    AdminQuizChapter,
    AdminQuizQuestion,
    PageResult,
    QuizBatch,
    QuizImportResult,
    QuizMode,
    QuizOverview,
    QuizTextbook,
    QuizVerdict,
    QuizWrongDetail,
    QuizWrongItem,
} from './types';

/**
 * 刷题接口（仅工作室成员，与考勤共用同一套身份判定）
 *
 * 全部接口只挂 `@SaCheckLogin`，真正的成员准入由后端查 `studio_member` 表判定
 * （认表不认角色），非成员会拿到 A0301 无权限。
 *
 * 答案的边界：`batch` 返回的题目结构上没有答案；
 * 答案与解析只在 `answer` 的返回值里出现，且只针对刚提交的那一题；
 * `skip` 什么都不返回（否则跳过就成了看答案的捷径）；
 * `wrongPage` 不含答案，`wrongDetail` 才含。
 */
export const quizApi = {
    // 教材 → 章节树（只统计启用题）
    catalog: () => http.get<QuizTextbook[]>('/quiz/catalog'),

    // 学习概览（四个数字）
    overview: () => http.get<QuizOverview>('/quiz/overview'),

    // 开一批题目
    //
    // 顺序模式必须同时传 textbookName 与 chapterName；
    // 错题重刷与随机刷题两者都可空（空 = 全库范围）。
    startBatch: (params: {
        mode: QuizMode;
        textbookName?: string;
        chapterName?: string;
        size?: number;
        // 顺序模式：忽略「已作答」排除条件，从头再刷一遍
        restart?: boolean;
    }) => http.post<QuizBatch>('/quiz/batch', params),

    // 提交答案（幂等：重复提交返回首次判定）
    answer: (batchId: string, questionId: string, chosenLabels: string) =>
        http.post<QuizVerdict>('/quiz/answer', { batchId, questionId, chosenLabels }),

    // 跳过本题
    //
    // 后端只返回 true，不含任何题目信息——所以前端拿不到答案，
    // 「跳过」不能被用来偷看正确答案。
    skip: (batchId: string, questionId: string) =>
        http.post<boolean>('/quiz/skip', { batchId, questionId }),

    // 我的错题本（只含待复习；不含答案）
    wrongPage: (params: { current?: number; pageSize?: number; textbookName?: string; chapterName?: string }) =>
        http.get<PageResult<QuizWrongItem>>('/quiz/wrong/page', params),

    // 错题详情（含答案与解析，以及你上次选了什么）
    wrongDetail: (id: string) => http.get<QuizWrongDetail>('/quiz/wrong/detail', { id }),

    // 手动标记已掌握（幂等）
    resolveWrong: (id: string) => http.post<boolean>('/quiz/wrong/resolve', { id }),
};

/**
 * 题库管理接口（全部要求 admin）
 *
 * 与 C 端的 `quizApi` 分开命名空间：两套接口的鉴权、返回结构、
 * 甚至「章节题数」的口径都不同（管理端含停用题，C 端只数启用题），
 * 混在一个对象里迟早用错。
 */
export const adminQuizApi = {
    questionPage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminQuizQuestion>>('/admin/quiz/question/page', params),

    questionDetail: (id: string) => http.get<AdminQuizQuestion>('/admin/quiz/question/detail', { id }),

    questionAdd: (body: Record<string, unknown>) =>
        http.post<string>('/admin/quiz/question/add', body),

    // 修改题目（部分更新：null 表示不修改）
    //
    // 改动正确答案、题型或选项标签集合时，后端会重置该题所有用户的
    // 错题连对进度（但保留「已消解」状态）。界面上应在保存前提示。
    questionUpdate: (body: { id: string } & Record<string, unknown>) =>
        http.post<boolean>('/admin/quiz/question/update', body),

    // 删除题目（同事务清理错题条目；只想临时下线请用 questionStatus）
    questionDelete: (id: string) => http.post<number>('/admin/quiz/question/delete', { id }),

    // 批量删除
    questionBatchDelete: (ids: string[]) =>
        http.post<number>('/admin/quiz/question/batch-delete', { ids }),

    // 启用 / 停用（停用可逆：错题条目保留，重新启用后自动回来）
    questionStatus: (id: string, status: 0 | 1) =>
        http.post<boolean>('/admin/quiz/question/status', { id, status }),

    // 批量导入
    //
    // `dryRun: true` 时只校验不落库，用于「先预览、再确认」。
    // 整批全成功或全失败：有任何错误时 `imported` 为 0。
    questionImport: (body: {
        textbookName: string;
        chapterName: string;
        text: string;
        textbookSeq?: number;
        chapterSeq?: number;
        startSeq?: number;
        dryRun?: boolean;
    }) => http.post<QuizImportResult>('/admin/quiz/question/import', body),

    // 章节列表（含停用题，另给启用数）
    chapterList: () => http.get<AdminQuizChapter[]>('/admin/quiz/chapter/list'),

    // 章节改名 / 调序（改名到已存在的章节时自动合并）
    chapterRename: (body: { textbookName: string; oldName: string; newName: string; chapterSeq?: number }) =>
        http.post<number>('/admin/quiz/chapter/rename', body),

    // 整章移到另一本教材
    chapterMove: (body: {
        fromTextbook: string;
        chapterName: string;
        toTextbook: string;
        toTextbookSeq?: number;
    }) => http.post<number>('/admin/quiz/chapter/move', body),

    // 整章删除（题目逻辑删除 + 清理错题条目）
    chapterDelete: (textbookName: string, chapterName: string) =>
        http.post<number>('/admin/quiz/chapter/delete', { textbookName, chapterName }),

    // 教材改名 / 调序
    textbookRename: (body: { oldName: string; newName: string; textbookSeq?: number }) =>
        http.post<number>('/admin/quiz/textbook/rename', body),
};
