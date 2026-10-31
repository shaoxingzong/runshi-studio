

/** 统一响应体 */
export interface ApiResponse<T> {
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

/** 首页大盘 */
export interface Overview {
    certificateTotal: number;
    certificateByLevel: Record<string, number>;
    certificateByType: Record<string, number>;
}

/** 成员（脱敏） */
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

/** 后台证书视图（后端 CertificateVO） */
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

/** 后台项目：比 C 端多出 leaderId 等内部字段 */
export interface AdminProject extends Project {
    leaderId: string;
    content?: string | null;
    // 置顶权重（后台内部字段，仅 admin 接口返回；`sort_order` 倒序）
    sortOrder?: number | null;
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

/** 登录结果 / 当前用户（后端 LoginUserVO） */
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

/** 站内通知（后端 NotificationVO） */
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

/** 后台用户（后端 UserVO，脱敏、无 token） */
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

// 帖子 / 评论模块

/** C 端帖子列表项（后端 PostFrontVO；不含正文——正文只在详情接口返回） */
export interface Post {
    id: string;
    title: string;
    summary?: string | null;
    coverImage?: string | null;
    authorId: string;
    // 作者名（成员姓名，由后端批量查出后填充）
    authorName?: string | null;
    // 作者头像（成员档案里的头像；未上传或查不到档案时为空，前端用姓名首字兜底）
    authorAvatar?: string | null;
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
    content?: string | null;
}

/** 带审核状态的帖子（后端 PostVO） */
export interface PostAudit {
    id: string;
    title: string;
    summary?: string | null;
    coverImage?: string | null;
    // 作者 ID（管理端用来定位到人；作者本人视角下就是自己）
    authorId?: string | null;
    authorName?: string | null;
    // 审核状态：0-待审, 1-已通过, 2-已驳回
    status?: number | null;
    // 驳回理由（仅已驳回时有意义）
    rejectReason?: string | null;
    auditAt?: string | null;
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

/** 管理端帖子详情（后端 PostAdminDetailVO = PostVO + 正文） */
export interface PostAdminDetail extends PostAudit {
    // 正文（Markdown，图片内嵌其中；成员提交的内容，按需做清洗处理）
    content?: string | null;
}

/** 作者看自己「未公开」帖子的详情（后端 PostSelfDetailVO = PostVO + 正文） */
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

/** 流量总览（管理端「流量分析」页） */
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
    // 评论人头像（未上传时为空，前端用姓名首字兜底）
    authorAvatar?: string | null;
    // null 表示直接评论帖子；非 null 表示回复某条评论
    parentId?: string | null;
    content: string;
    // 楼层号（回复某条评论时为 null）
    floor?: number | null;
    createdAt?: string;
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

// 考勤模块

/** 签到结果（后端 AttendanceCheckInVO） */
export interface AttendanceCheckIn {
    userId: string;
    checkInAt?: string;
    monthCount?: number | null;
}

/** 今日出勤看板项（后端 AttendanceBoardVO；仅工作室成员可见） */
export interface AttendanceBoardItem {
    userId: string;
    // 展示名：来自 studio_member.name，不取登录账号
    userName?: string | null;
    // 今日打卡时刻（当天最早的一次）；未打卡为 null
    checkInAt?: string | null;
    // 今日是否已打卡
    checkedIn?: boolean | null;
    nextCheckInAt?: string | null;
    // 本月打卡次数（自然月内的签到条数）
    monthCount?: number | null;
}

/** 我的签到历史项（后端 AttendanceMeVO，只能查自己的） */
export interface AttendanceRecord {
    userId: string;
    // 签到日期（yyyy-MM-dd，与 checkInAt 同源派生，便于按天分组）
    attendanceDate?: string | null;
    // 签到时刻（东八区，精确到秒）
    checkInAt?: string | null;
}

// 后台与 AI 的补充类型

/** 后台成员视图（后端 MemberVO） */
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

// 刷题模块

/** 刷题模式：0-顺序，1-错题重刷，2-随机 */
export type QuizMode = 0 | 1 | 2;

/** 题型：0-单选（点击即提交），1-多选（需点提交，全对才得分） */
export type QuizQuestionType = 0 | 1;

/** 选项（不含正确性：正确答案由 QuizVerdict 单独给出） */
export interface QuizOption {
    label: string;
    content: string;
}

/** 题目（答题前） */
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

/** 管理端操作日志（后端 OperationLogVO） */
export interface OperationLog {
    id: string;
    operatorId: string;
    // 操作人账号快照（可能为空串：账号已被物理删除）
    operatorAccount: string;
    operatorName?: string | null;
    // 动作码（与后端 OperationTypeEnum 一致，仅用于分支判断，展示一律用 actionLabel）
    action: string;
    actionLabel: string;
    targetType: string;
    targetTypeLabel: string;
    // 章节 / 教材 / 批量导入等动作没有单一对象 ID
    targetId?: string | null;
    targetTitle?: string | null;
    detail?: string | null;
    ip?: string | null;
    createdAt: string;
}

/** 操作动作字典项（后端 OperationActionOptionVO，下拉与标签共用） */
export interface OperationActionOption {
    code: string;
    label: string;
    targetType: string;
    targetTypeLabel: string;
}

/** 管理端：成员某月签到汇总（后端 AttendanceMonthStatVO） */
export interface AttendanceMonthStat {
    userId: string;
    userName?: string | null;
    // 0-在读/在队，1-毕业/离队（历史月份可能是在队、现已离队）
    memberStatus?: number | null;
    // 当月签到天数（按日期去重）
    days: number;
    // 当月签到次数（原始记录数）
    times: number;
    firstAt?: string | null;
    lastAt?: string | null;
}

/** 管理端：某成员某月的一次签到（后端 AttendanceRecordVO） */
export interface AttendanceCheckInRecord {
    checkInAt: string;
    ip?: string | null;
}

/** 管理端举报记录（后端 ReportVO） */
export interface ReportTicket {
    id: string;
    // post / comment
    targetType: string;
    targetTypeLabel: string;
    targetId: string;
    targetTitle?: string | null;
    reporterId: string;
    reporterName?: string | null;
    reason: string;
    // 0-待处理 / 1-已处置 / 2-举报不成立
    status: number;
    statusLabel: string;
    handleBy?: string | null;
    handlerName?: string | null;
    handleAt?: string | null;
    handleResult?: string | null;
    createdAt: string;
}
