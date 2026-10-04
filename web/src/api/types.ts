/**
 * 与后端 VO 一一对应的类型定义
 *
 * 约定两点（与后端 JsonConfig 的全局序列化有关，前端必须知道）：
 * 1. **ID 是字符串**：雪花 ID 超出 JS 安全整数范围，后端统一序列化成字符串；
 * 2. **分页元数据也是字符串**（total / current / size / pages），
 *    而**计数字段是数字**（后端刻意用 Integer）——两者的类型不一致是设计使然，不要"顺手统一"。
 */

/** 统一响应体 */
export interface ApiResponse<T> {
    code: number;
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
 * **只剩证书维度**：业务规则「团队成员不对外展示」，
 * 后端已移除 memberTotal / memberInTeam / memberGraduated 三个字段。
 * 前端不要再试图渲染团队人数——接口里根本没有这些 key。
 */
export interface Overview {
    certificateTotal: number;
    certificateByLevel: Record<string, number>;
    certificateByType: Record<string, number>;
}

/**
 * 成员（脱敏）
 *
 * ⚠️ 它如今**只出现在项目详情的「参与成员」里**（产品确认保留的例外）。
 * 官网成员列表页 / 成员详情页已随「团队成员不对外展示」下线，
 * 因此不要再用它去调 `/member/list` 或 `/member/detail`（接口已删除，会 404）。
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
 * 注意它与 {@link ProjectDetail} 是两种视图——后台要能看到并编辑队长，
 * C 端则刻意不暴露（leaderId 属于内部字段）。不要图省事共用一个类型。
 */
export interface AdminProject extends Project {
    leaderId: string;
    content?: string | null;
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
    /** user / member / admin —— 判断时用这里的原始值，别写死「admin」的大小写变体 */
    userRole: string;
    /** 0-正常，1-封禁 */
    userStatus?: number | null;
    aiQueryCount?: number | null;
    /** 登录接口返回；/user/current 不返回 */
    token?: string;
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
