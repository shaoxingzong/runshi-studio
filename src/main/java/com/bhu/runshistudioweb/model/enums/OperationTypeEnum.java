package com.bhu.runshistudioweb.model.enums;

/**
 * 管理端操作动作字典（studio_operation_log.action）
 *
 * 一个枚举同时承载三件事：落库的动作码、给人看的中文标签、以及被操作对象的类型。
 * 这样前端下拉、表格标签、后端校验都不必各写一份映射——三处各写一份，必然有一处先漂移。
 *
 * 新增动作要同步三处，漏一步都是静默问题：本枚举、DDL 里 action 列的注释、
 * 以及调用 OperationLogService#record 的埋点位置。
 *
 * 用字符串而非数字：排障时一眼读懂，新增动作也不必改表结构。
 *
 * @author shaoshing
 */
public enum OperationTypeEnum {

    // ==================== 内容：帖子 ====================

    /** 帖子审核通过（批量审核时每篇一行，便于按单篇内容反查） */
    POST_APPROVE("POST_APPROVE", "帖子审核通过", TargetType.POST),

    /** 帖子驳回（detail 带驳回理由——理由本身就是审核证据） */
    POST_REJECT("POST_REJECT", "帖子驳回", TargetType.POST),

    /** 删除帖子（级联删除评论与点赞） */
    POST_DELETE("POST_DELETE", "删除帖子", TargetType.POST),

    /** 置顶 / 取消置顶 */
    POST_PIN("POST_PIN", "帖子置顶变更", TargetType.POST),

    /** 管理员代改帖子（保存后回到待审重审） */
    POST_UPDATE("POST_UPDATE", "管理员编辑帖子", TargetType.POST),

    // ==================== 内容：评论 ====================

    /** 评论审核通过 */
    COMMENT_APPROVE("COMMENT_APPROVE", "评论审核通过", TargetType.COMMENT),

    /** 评论驳回 */
    COMMENT_REJECT("COMMENT_REJECT", "评论驳回", TargetType.COMMENT),

    /** 删除评论（连带删除其回复） */
    COMMENT_DELETE("COMMENT_DELETE", "删除评论", TargetType.COMMENT),

    // ==================== 用户与权限 ====================

    /** 新增用户（detail 只写角色与状态，绝不写初始口令） */
    USER_ADD("USER_ADD", "新增用户", TargetType.USER),

    /**
     * 修改用户
     *
     * 改角色、封禁/解封、重置口令、改资料都走同一个接口，因此共用一个动作码，
     * 靠 detail 里的「改了哪些字段 + 旧值→新值」区分——拆成四个动作会让
     * 「一次请求同时改角色又封禁」这种真实场景无处安放。
     */
    USER_UPDATE("USER_UPDATE", "修改用户", TargetType.USER),

    /** 删除用户（逻辑删除） */
    USER_DELETE("USER_DELETE", "删除用户", TargetType.USER),

    /** 重置用户的 AI 提问次数 */
    USER_AI_QUOTA_RESET("USER_AI_QUOTA_RESET", "重置 AI 配额", TargetType.USER),

    // ==================== 题库 ====================

    /** 新增题目 */
    QUIZ_QUESTION_ADD("QUIZ_QUESTION_ADD", "新增题目", TargetType.QUIZ),

    /** 修改题目（改选项文字会造成历史作答解释力变化，detail 记录改了哪些字段） */
    QUIZ_QUESTION_UPDATE("QUIZ_QUESTION_UPDATE", "修改题目", TargetType.QUIZ),

    /** 删除单题 */
    QUIZ_QUESTION_DELETE("QUIZ_QUESTION_DELETE", "删除题目", TargetType.QUIZ),

    /** 批量删除题目（整批一行：目标对象是一组 ID，逐题一行会让批量删除刷屏） */
    QUIZ_QUESTION_BATCH_DELETE("QUIZ_QUESTION_BATCH_DELETE", "批量删除题目", TargetType.QUIZ),

    /** 题目启用 / 停用 */
    QUIZ_QUESTION_STATUS("QUIZ_QUESTION_STATUS", "题目启停", TargetType.QUIZ),

    /** 批量导入题目（dryRun 预检不写日志——什么都没落库） */
    QUIZ_QUESTION_IMPORT("QUIZ_QUESTION_IMPORT", "批量导入题目", TargetType.QUIZ),

    /** 章节重命名 */
    QUIZ_CHAPTER_RENAME("QUIZ_CHAPTER_RENAME", "章节重命名", TargetType.QUIZ),

    /** 题目移动到另一教材 / 章节 */
    QUIZ_CHAPTER_MOVE("QUIZ_CHAPTER_MOVE", "题目移动章节", TargetType.QUIZ),

    /** 删除章节（连带删除该章节的全部题目） */
    QUIZ_CHAPTER_DELETE("QUIZ_CHAPTER_DELETE", "删除章节", TargetType.QUIZ),

    /** 教材重命名（全库批量改） */
    QUIZ_TEXTBOOK_RENAME("QUIZ_TEXTBOOK_RENAME", "教材重命名", TargetType.QUIZ),

    // ==================== 举报处置 ====================

    /** 举报成立：内容撤下（转已驳回，保留可追溯） */
    REPORT_RESOLVE("REPORT_RESOLVE", "举报成立（撤下内容）", TargetType.REPORT),

    /** 举报成立：内容删除 */
    REPORT_DELETE("REPORT_DELETE", "举报成立（删除内容）", TargetType.REPORT),

    /** 举报不成立：内容保留 */
    REPORT_DISMISS("REPORT_DISMISS", "举报不成立", TargetType.REPORT);

    /** 落库的动作码（与 DDL 中 action 列取值一致） */
    private final String code;

    /** 中文标签：前端下拉与表格标签直接使用，不必再维护一份映射 */
    private final String label;

    /** 被操作对象的类型（落库的 target_type 就取自它） */
    private final TargetType targetType;

    OperationTypeEnum(String code, String label, TargetType targetType) {
        this.code = code;
        this.label = label;
        this.targetType = targetType;
    }

    /** 动作码：落库与前端筛选的依据 */
    public String getCode() {
        return code;
    }

    /** 中文标签 */
    public String getLabel() {
        return label;
    }

    /** 对象类型 */
    public TargetType getTargetType() {
        return targetType;
    }

    /**
     * 把动作码翻译成枚举，查不到返回 null
     *
     * 只用在读路径与参数校验：查不到时由调用方决定「忽略筛选」还是「报参数错误」，
     * 本方法不做兜底猜测（兜底会把真正的拼写错误永久藏住）。
     *
     * @param code 动作码，允许为 null
     * @return 命中的枚举；null 或未知动作码时返回 null
     */
    public static OperationTypeEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (OperationTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 被操作对象的类型
     *
     * 与动作码分开一层，是因为「对象类型」既用于落库列，也用于前端筛选下拉；
     * 如果只存动作码，前端要筛「所有用户相关操作」就得自己维护一份 action → 类型 的映射。
     */
    public enum TargetType {

        /** 帖子 */
        POST("POST", "帖子"),

        /** 帖子评论 */
        COMMENT("COMMENT", "评论"),

        /** 系统用户 */
        USER("USER", "用户"),

        /** 题库（题目 / 章节 / 教材） */
        QUIZ("QUIZ", "题库"),

        /** 举报记录（处置动作的对象是举报本身，内容变更另有日志） */
        REPORT("REPORT", "举报");

        /** 落库值（studio_operation_log.target_type） */
        private final String code;

        /** 中文标签 */
        private final String label;

        TargetType(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String getCode() {
            return code;
        }

        public String getLabel() {
            return label;
        }

        /**
         * 把对象类型码翻译成枚举，查不到返回 null
         *
         * @param code 类型码，允许为 null
         * @return 命中的枚举；null 或未知类型时返回 null
         */
        public static TargetType of(String code) {
            if (code == null) {
                return null;
            }
            for (TargetType type : values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            return null;
        }
    }
}
