// 与后端 VO 一一对应的类型定义
//
// 约定两点（与后端 JsonConfig 的全局序列化有关，前端必须知道）：
// 1. ID 是字符串：雪花 ID 超出 JS 安全整数范围，后端统一序列化成字符串；
// 2. 分页元数据也是字符串（total / current / size / pages），
// 而计数字段是数字（后端刻意用 Integer）——两者的类型不一致是设计使然，不要"顺手统一"。

/** 统一响应体 */
export interface ApiResponse<T> {
    // 业务状态码（5 位字符串，遵循阿里开发手册）
    // - "00000" = 成功
    // - "Axxxx" = 用户端错误（参数/登录/权限/限流）
    // - "Bxxxx" = 当前系统错误
    // - "Cxxxx" = 第三方服务错误（如 AI 服务不可用）
    code: string;
    data: T;
    message: string;
}

/** 分页结果 */
export interface PageResult<T> {
    records: T[];
    total: string;
    size: string;
    current: string;
    pages: string;
}

/**
 * 首页大盘
 *
 * 只剩证书维度：业务规则「团队成员不对外展示」，
 * 接口里没有 memberTotal / memberInTeam / memberGraduated 这些 key，不要渲染团队人数。
 */
export interface Overview {
    certificateTotal: number;
    certificateByLevel: Record<string, number>;
    certificateByType: Record<string, number>;
}

/**
 * 成员（脱敏）
 *
 * 它只出现在项目详情的「参与成员」里（产品确认保留的例外）——
 * 官网没有成员列表页 / 详情页，也不要用它去调 `/member/list` 或 `/member/detail`
 * （后端没有这些接口，调用会 404）。
 */
export interface Member {
    id: string;
    name: string;
    avatar?: string | null;
    gradeYear?: number | null;
    major?: string | null;
    direction?: string | null;
    teamPosition?: string | null;
    memberStatus?: number | null;
    githubUrl?: string | null;
    summary?: string | null;
}

/** C 端证书（脱敏） */
export interface Certificate {
    id: string;
    title: string;
    awardLevel?: string | null;
    awardType?: string | null;
    awardDate?: string | null;
    imageUrl?: string | null;
}

/**
 * 后台证书视图（后端 CertificateVO）
 *
 * 与 AdminProject 同理：比 C 端的 Certificate 多出 sortOrder / 审计时间，
 * 只在 admin 面前出现，不要与脱敏的 C 端类型混用。
 */
export interface AdminCertificate extends Certificate {
    sortOrder?: number | null;
    createdAt?: string;
    updatedAt?: string;
}

/** C 端项目列表项（不含正文） */
export interface Project {
    id: string;
    title: string;
    description?: string | null;
    coverImage?: string | null;
    status?: number | null;
    techStack?: string[] | null;
    demoUrl?: string | null;
    githubUrl?: string | null;
}

/** C 端项目详情（含正文与参与成员） */
export interface ProjectDetail extends Project {
    content?: string | null;
    members: Member[];
}

/**
 * 后台项目：比 C 端多出 leaderId 等内部字段
 *
 * 注意它与 ProjectDetail 是两种视图——后台要能看到并编辑队长，
 * C 端则刻意不暴露（leaderId 属于内部字段）。不要图省事共用一个类型。
 */
export interface AdminProject extends Project {
    leaderId: string;
    content?: string | null;
    // 置顶权重（后台内部字段，仅 admin 接口返回；`sort_order` 倒序）
    sortOrder?: number | null;
    // 首页轮播排名（仅 admin 接口返回）：1-6 表示排官网首页第 N 位，null 表示不进首页
    //
    // 排名按全局口径计算（sortOrder 倒序 → 创建时间倒序 → id 倒序的前 6 条），
    // 不受后台筛选影响——所以按标题筛选时看到的「首页 N」仍然是全局名次
    homeRank?: number | null;
    // 审计时间：列表页用来展示「最近维护」
    createdAt?: string;
    updatedAt?: string;
}

/** 后台知识库文档 */
export interface KnowledgeDoc {
    docId: string;
    title: string;
    sourceType: string;
    sourceId?: string | null;
    status: number;
    chunkCount?: number | null;
    createdAt?: string;
    updatedAt?: string;
}

/** 入库/重建结果 */
export interface IngestResult {
    docId: string;
    title: string;
    chunkCount: number;
    status: number;
    skipped: boolean;
    rebuilt: boolean;
}

/** 批量任务统计（sync-all / reindex-all） */
export interface BatchResult {
    total: number;
    created: number;
    rebuilt: number;
    skipped: number;
    failed: number;
}

/** 检索溯源来源 */
export interface KnowledgeSource {
    docId: string;
    title: string;
    sourceType: string;
    sourceId?: string | null;
    score: number;
}

/** 提问响应 */
export interface ChatResponse {
    sessionId: string;
    answer: string;
    sources: KnowledgeSource[];
}

/** 历史消息 */
export interface ChatMessage {
    id: string;
    role: string;
    content: string;
    createdAt?: string;
}

/**
 * 登录结果 / 当前用户（后端 LoginUserVO）
 *
 * 只有 `/user/login` 会返回 token；`/user/current` 返回同一结构但不含 token
 * （前端已有令牌，重复下发只会增加泄露面）。
 */
export interface LoginResult {
    id: string;
    userAccount: string;
    userName?: string | null;
    userAvatar?: string | null;
    // user / member / admin —— 判断时用这里的原始值，别写死「admin」的大小写变体
    userRole: string;
    // 0-正常，1-封禁
    userStatus?: number | null;
    aiQueryCount?: number | null;
    // 登录接口返回；/user/current 不返回
    token?: string;
    createdAt?: string;
}

/**
 * 站内通知（后端 NotificationVO）
 *
 * `type` 的四种取值（与后端 NotificationTypeEnum 一致）：
 * `POST_APPROVED` 帖子通过 / `POST_REJECTED` 帖子被驳回 /
 * `COMMENT_REPLIED` 评论被回复 / `MEMBER_GRANTED` 获得成员身份。
 *
 * `bizType` + `bizId` 是跳转指针，允许悬空（来源被删后不可点，通知本身仍可读）。
 * 注意 COMMENT_REPLIED 的 biz 是帖子而非评论——同一帖子下的多次回复
 * 按话题聚合成一条（后端幂等键的语义），跳转目标自然是帖子详情。
 */
export interface Notification {
    id: string;
    type: string;
    // 类型中文描述（后端按枚举填，前端不维护映射）
    typeDesc: string;
    bizType?: string | null;
    bizId?: string | null;
    title: string;
    content: string;
    // 0-未读，1-已读
    isRead: number;
    createdAt?: string;
}

/**
 * 后台用户（后端 UserVO，脱敏、无 token）
 *
 * 「成员」身份就是在这里给的：管理员把注册用户的 userRole 从 `user` 改成 `member`。
 */
export interface AdminUser {
    id: string;
    userAccount: string;
    userName?: string | null;
    userAvatar?: string | null;
    userRole: string;
    userStatus?: number | null;
    aiQueryCount?: number | null;
    createdAt?: string;
    updatedAt?: string;
}

// ==================== 帖子 / 评论模块 ====================

/** C 端帖子列表项（后端 PostFrontVO；不含正文——正文只在详情接口返回） */
export interface Post {
    id: string;
    title: string;
    summary?: string | null;
    coverImage?: string | null;
    authorId: string;
    // 作者名（成员姓名，由后端批量查出后填充）
    authorName?: string | null;
    viewCount?: number | null;
    commentCount?: number | null;
    // 点赞数（后端冗余列维护，点赞时原子自增）
    likeCount?: number | null;
    // 当前登录用户是否已赞；游客恒为 false（带「当前用户」语义，不可整体缓存）
    liked?: boolean | null;
    // 是否置顶：0-否，1-是
    pinned?: number | null;
    createdAt?: string;
}

/** C 端帖子详情（后端 PostFrontDetailVO：比列表多一个 Markdown 正文） */
export interface PostDetail extends Post {
    // 正文（Markdown，图片内嵌其中）
    //
    // 它是成员提交的内容，比管理员录入的项目正文更不可信，
    // 渲染前必须走「marked → DOMPurify 白名单清洗 → v-html」，
    // 且要额外限制 img 的 src 只能是站内路径（见 PostDetail.vue）
    content?: string | null;
}

/**
 * 带审核状态的帖子（后端 PostVO）
 *
 * 它同时服务于三处：「我的发帖」（作者看自己的）、「管理端待审队列」与
 * 「管理端帖子管理」（管理员看全部）。三者拿到的是同一个 VO，因为管理员需要看
 * 作者看得到的全部信息，再额外多给一份只会造成两套类型漂移。
 */
export interface PostAudit {
    id: string;
    title: string;
    summary?: string | null;
    coverImage?: string | null;
    // 作者 ID（管理端用来定位到人；作者本人视角下就是自己）
    authorId?: string | null;
    // 作者展示名（后端批量填充）
    //
    // 在队成员取成员姓名（与门户口径一致），查不到档案时兜底账号昵称——
    // 帖子不会因成员离队而消失，老帖子的作者也不能显示成空白
    authorName?: string | null;
    // 审核状态：0-待审, 1-已通过, 2-已驳回
    status?: number | null;
    // 驳回理由（仅已驳回时有意义）
    rejectReason?: string | null;
    auditAt?: string | null;
    // AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
    //
    // 「未判」与「判定失败」是两回事：前者只是还没轮到，后者说明 AI 出故障了
    aiAuditStatus?: number | null;
    // AI 给出的理由（给管理端参考；作者也能看到针对自己内容的说明）
    aiReason?: string | null;
    aiAt?: string | null;
    viewCount?: number | null;
    commentCount?: number | null;
    // 点赞数（管理端展示内容热度用）
    likeCount?: number | null;
    pinned?: number | null;
    createdAt?: string;
}

/**
 * 管理端帖子详情（后端 PostAdminDetailVO = PostVO + Markdown 正文）
 *
 * 编辑弹窗回填正文专用：列表 VO 刻意不带 content，而公开详情只返回
 * 「已通过」的帖子且会自增浏览量，两者都满足不了编辑场景。
 */
export interface PostAdminDetail extends PostAudit {
    // 正文（Markdown，图片内嵌其中；成员提交的内容，按需做清洗处理）
    content?: string | null;
}

/**
 * 作者看自己「未公开」帖子的详情（后端 PostSelfDetailVO = PostVO + 正文）
 *
 * 改驳回稿的前置步骤：公开详情只返回已通过的帖子，驳回稿的正文只能从这里读。
 * 仅本人 + 仅待审/已驳回，且不累加浏览量（自己预览不算一次访问）。
 */
export interface PostSelfDetail extends PostAudit {
    content?: string | null;
}

/** 单日流量（每日趋势的一行） */
export interface DailyTraffic {
    // 日期（yyyy-MM-dd）
    date: string;
    pv: number;
    // 当日 UV（按 visitor_id 去重）
    uv: number;
}

/** 单个页面的流量（热门页面榜的一行） */
export interface PageTraffic {
    pagePath: string;
    pv: number;
    uv: number;
}

/**
 * 流量总览（管理端「流量分析」页）
 *
 * UV 口径：按前端 localStorage 的随机 uuid 去重，
 * 因此同一人换设备 / 清缓存 / 无痕浏览会算成多个 UV——页面需注明。
 */
export interface TrafficOverview {
    totalPv: number;
    totalUv: number;
    todayPv: number;
    todayUv: number;
    daily: DailyTraffic[];
    topPages: PageTraffic[];
}

/** 公开评论（后端 PostCommentVO，楼中楼） */
export interface PostComment {
    id: string;
    postId: string;
    authorId: string;
    authorName?: string | null;
    // null 表示直接评论帖子；非 null 表示回复某条评论
    parentId?: string | null;
    content: string;
    // 楼层号（回复某条评论时为 null）
    floor?: number | null;
    createdAt?: string;
    // 回复列表（只两层，由后端挂好树）
    //
    // 前端直接渲染这一层即可，不要自行二次组装
    children: PostComment[];
}

/** 管理端评论审核队列项（后端 PostCommentAuditVO：比公开评论多审核与 AI 字段） */
export interface PostCommentAudit {
    id: string;
    postId: string;
    authorId: string;
    authorName?: string | null;
    parentId?: string | null;
    content: string;
    floor?: number | null;
    status?: number | null;
    rejectReason?: string | null;
    auditAt?: string | null;
    aiAuditStatus?: number | null;
    aiReason?: string | null;
    aiAt?: string | null;
    createdAt?: string;
}

// ==================== 考勤模块 ====================

/**
 * 签到结果（后端 AttendanceCheckInVO）
 *
 * 接口刻意不返回 ip / userAgent，网络信息只以 inLan 布尔出现——
 * 前端也不要试图展示网段或位置信息。
 */
export interface AttendanceCheckIn {
    userId: string;
    // 本次签到时刻（东八区，精确到秒）
    checkInAt?: string;
    // 本次是否判为内网（命中工作室网段）
    inLan?: boolean | null;
    // 本月累计签到次数（含本次）
    monthCount?: number | null;
}

/**
 * 今日出勤看板项（后端 AttendanceBoardVO；仅工作室成员可见）
 *
 * 未打卡的成员同样在列表里，用 checkedIn 为 false + checkInAt 为 null 表示，
 * 便于前端区分「今天还没来」而不是直接消失。
 */
export interface AttendanceBoardItem {
    userId: string;
    // 展示名：来自 studio_member.name，不取登录账号
    userName?: string | null;
    // 今日打卡时刻（当天最早的一次）；未打卡为 null
    checkInAt?: string | null;
    // 今日是否已打卡
    checkedIn?: boolean | null;
    // 本月打卡次数（自然月内的签到条数）
    monthCount?: number | null;
    // 今日是否内网签到
    inLan?: boolean | null;
}

/** 我的签到历史项（后端 AttendanceMeVO，只能查自己的） */
export interface AttendanceRecord {
    userId: string;
    // 签到日期（yyyy-MM-dd，与 checkInAt 同源派生，便于按天分组）
    attendanceDate?: string | null;
    // 签到时刻（东八区，精确到秒）
    checkInAt?: string | null;
    // 是否内网签到
    inLan?: boolean | null;
}

// ==================== 后台与 AI 的补充类型 ====================

/**
 * 后台成员视图（后端 MemberVO）
 *
 * 比 C 端的 Member 多出 userId / sortOrder 等内部字段，只在 admin 面前出现——
 * 与 AdminProject 扩展 Project 是同一套路，不要图省事共用 C 端类型。
 */
export interface AdminMember extends Member {
    // 绑定的登录账号 ID；未绑定为 null
    userId?: string | null;
    sortOrder?: number | null;
    createdAt?: string;
    updatedAt?: string;
}

/** AI 会话列表项（后端 AiSessionVO） */
export interface AiSession {
    id: string;
    // 会话标题：取自首问前 30 字
    title?: string | null;
    updatedAt?: string;
    // 会话内的消息条数
    messageCount?: number | null;
}

// ==================== 刷题模块 ====================

/**
 * 刷题模式：0-顺序，1-错题重刷，2-随机
 *
 * 三种模式的职责不重叠：顺序负责「覆盖」（排除已作答的题），
 * 错题负责「消灭」（只出待复习），随机负责「抽查」。
 */
export type QuizMode = 0 | 1 | 2;

/**
 * 题型：只有选择题
 *
 * 0-单选（恰好 1 个正确项，UI 用 radio，点击即提交）
 * 1-多选（≥2 个正确项，UI 用 checkbox，需点提交按钮；全对才得分）
 *
 * 没有「判断」：判断题就是选项为「正确 / 错误」的单选。
 */
export type QuizQuestionType = 0 | 1;

/** 选项（不含正确性：正确答案由 QuizVerdict 单独给出） */
export interface QuizOption {
    label: string;
    content: string;
}

/**
 * 题目（答题前）
 *
 * 结构上没有 correctLabels / analysis：
 * 正确答案与解析只出现在提交后的 QuizVerdict 里。
 * 不要为了「前端先判一下」往这里加字段——那等于把答案发给了客户端。
 */
export interface QuizQuestion {
    id: string;
    type: QuizQuestionType;
    stem: string;
    options: QuizOption[];
    // 该题当前是否在错题本里（只给标记，不泄露答案）
    inWrongBook?: boolean | null;
}

/** 章节 */
export interface QuizChapter {
    name: string;
    seq: number;
    // 该章启用的题目数
    questionCount: number;
}

/** 教材（名字即标识：教材没有独立表） */
export interface QuizTextbook {
    name: string;
    seq: number;
    questionCount: number;
    chapters: QuizChapter[];
}

/** 开批结果 */
export interface QuizBatch {
    batchId: string;
    mode: QuizMode;
    total: number;
    questions: QuizQuestion[];
}

/** 提交后的判定：答案与解析只在这里出现 */
export interface QuizVerdict {
    questionId: string;
    type: QuizQuestionType;
    // 1-正确，2-错误（跳过走另一个接口，不返回答案）
    result: 1 | 2;
    chosenLabels: string;
    correctLabels: string;
    analysis?: string | null;
    // 本题对错题本的影响：
    // added-首次入本 / reopened-消解后再错 / progress-连对进度 / resolved-已消解 / none-无副作用
    wrongBookAction: 'added' | 'reopened' | 'progress' | 'resolved' | 'none';
    // 还差几次消解（仅 progress 时有值）
    remainingStreak?: number | null;
    answeredTotal: number;
    correctTotal: number;
}

/** 学习概览 */
export interface QuizOverview {
    // 累计作答（不含跳过，含重做）
    answeredTotal: number;
    correctTotal: number;
    // 正确率 0~1；累计作答为 0 时为 null（前端显示 "--"，不是 "0%"）
    accuracy: number | null;
    wrongPending: number;
    wrongResolved: number;
}

/** 错题本条目（列表用，不含答案） */
export interface QuizWrongItem {
    id: string;
    questionId: string;
    type: QuizQuestionType;
    stem: string;
    textbookName: string;
    chapterName: string;
    status: 0 | 1;
    wrongCount: number;
    correctStreak: number;
    lastWrongAt?: string | null;
    resolvedAt?: string | null;
    resolveType?: number | null;
}

/** 错题详情（含答案与解析，以及「你上次选了什么」） */
export interface QuizWrongDetail extends QuizWrongItem {
    options: QuizOption[];
    correctLabels: string;
    analysis?: string | null;
    lastChosenLabels?: string | null;
}

/** 管理端题目（含选项与答案） */
export interface AdminQuizQuestion {
    id: string;
    textbookName: string;
    textbookSeq: number;
    chapterName: string;
    chapterSeq: number;
    type: QuizQuestionType;
    stem: string;
    options: QuizOption[];
    correctLabels: string;
    analysis?: string | null;
    difficulty: number;
    source?: string | null;
    questionSeq: number;
    status: 0 | 1;
    answerCount: number;
    // 正确率 0~1；作答数为 0 时为 null
    accuracy: number | null;
    createdAt?: string;
    updatedAt?: string;
}

/** 管理端章节（含停用题，与 C 端口径不同） */
export interface AdminQuizChapter {
    textbookName: string;
    textbookSeq: number;
    name: string;
    seq: number;
    questionCount: number;
    enabledCount: number;
}

/** 导入错误（带行号） */
export interface QuizImportError {
    line: number;
    message: string;
}

/** 导入结果（dryRun 时 imported 恒为 0） */
export interface QuizImportResult {
    parsed: number;
    imported: number;
    dryRun: boolean;
    errors: QuizImportError[];
}
