package com.bhu.runshistudioweb.constant;

// 刷题模块公共常量
//
// 为什么选项数与标签范围写成常量而不是配置项：
// 它们不是「运维想调就能调」的参数，而是与前端交互形态绑定的契约——
// 前端最多渲染 6 行选项、键盘只绑定 A–F 六个键。把上限做成可配置，
// 运维调大后前端根本渲染不出来，只会得到一堆「第 7 行选项在哪」的困惑。
// 列宽与选项数一样是契约，不该由运行期配置决定（与
// AttendanceConstant#USER_AGENT_MAX_LENGTH 同一条纪律）。
//
// 真正该配置的是「一轮多少题」「连对几次算掌握」这类产品口径，
// 它们在 config/QuizProperties（ studio.quiz.*）。
public final class QuizConstant {

    // 选项数下限：单选至少 2 个、多选至少 3 个
    //
    // 多选的下限单独写成 3：多选题只有 2 个选项、且两个都正确，
    // 等于「全选就得分」——这不是多选，是送分题。
    public static final int OPTION_MIN_SINGLE = 2;

    // 多选的选项数下限（见上一条说明）
    public static final int OPTION_MIN_MULTIPLE = 3;

    // 选项数上限：6（对应标签 A–F）
    //
    // 与前端选项行的最大行数、键盘绑定的 A–F 一致。改这个值要同步改前端。
    public static final int OPTION_MAX = 6;

    // 选项标签的合法范围：从 A 连续到 F（跳号会在界面上显示断档，用户会以为渲染坏了）
    public static final char LABEL_FIRST = 'A';
    public static final char LABEL_LAST = 'F';

    // 选项正文长度上限：500（与 studio_quiz_question.options JSON 里的 content 对应）
    //
    // JSON 列本身是 text，但单个选项超长没有业务意义，
    // 且会在判分响应里被整段回传。
    public static final int OPTION_CONTENT_MAX_LENGTH = 500;

    // 题干长度上限（与 DDL 的 varchar(2000) 严格一致）
    public static final int STEM_MAX_LENGTH = 2000;

    // 解析长度上限（与 DDL 的 varchar(2000) 严格一致）
    public static final int ANALYSIS_MAX_LENGTH = 2000;

    // 教材名 / 章节名长度上限（与 DDL 的 varchar(128) 严格一致）
    public static final int NAME_MAX_LENGTH = 128;

    // 题源长度上限（与 DDL 的 varchar(128) 严格一致）
    public static final int SOURCE_MAX_LENGTH = 128;

    // 选项 JSON 序列化后的长度上限：64KB
    //
    // 列是 text（约 64KB），超过会在入库时报错。
    // 在入参侧拦住能给出「选项内容过长」的明确提示，而不是让前端收到数据库异常。
    // 按 6 个选项 × 500 字算最多约 3KB，这个上限只是兜底。
    public static final int OPTIONS_JSON_MAX_LENGTH = 65535;

    // 分页每页条数的默认值与上限（上限防「一次拉全表」，与其它模块一致）
    public static final long DEFAULT_PAGE_SIZE = 10L;
    public static final long MAX_PAGE_SIZE = 50L;

    // 错题本「最近 N 条」在首页展示的数量
    public static final int HOME_WRONG_PREVIEW = 5;

    // 批量导入的题目数量上限
    //
    // 整批在一个事务里完成（全成功或全失败），一次 2000 题已经足够
    // 粘贴一整本教材的一章；再多会让单次请求的耗时不必要地变长。
    public static final int IMPORT_MAX_QUESTIONS = 2000;

    private QuizConstant() {
    }
}
