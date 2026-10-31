package com.bhu.runshistudioweb.constant;

/**
 * 刷题模块公共常量
 *
 * @author shaoshing
 */
public final class QuizConstant {

    /** 选项数下限：单选至少 2 个、多选至少 3 个 */
    public static final int OPTION_MIN_SINGLE = 2;

    /** 多选的选项数下限（见上一条说明） */
    public static final int OPTION_MIN_MULTIPLE = 3;

    /** 选项数上限：6（对应标签 A–F） */
    public static final int OPTION_MAX = 6;

    /** 选项标签的合法范围：从 A 连续到 F（跳号会在界面上显示断档，用户会以为渲染坏了） */
    public static final char LABEL_FIRST = 'A';
    public static final char LABEL_LAST = 'F';

    /** 选项正文长度上限：500（与 studio_quiz_question.options JSON 里的 content 对应） */
    public static final int OPTION_CONTENT_MAX_LENGTH = 500;

    /** 题干长度上限（与 DDL 的 varchar(2000) 严格一致） */
    public static final int STEM_MAX_LENGTH = 2000;

    /** 解析长度上限（与 DDL 的 varchar(2000) 严格一致） */
    public static final int ANALYSIS_MAX_LENGTH = 2000;

    /** 教材名 / 章节名长度上限（与 DDL 的 varchar(128) 严格一致） */
    public static final int NAME_MAX_LENGTH = 128;

    /** 题源长度上限（与 DDL 的 varchar(128) 严格一致） */
    public static final int SOURCE_MAX_LENGTH = 128;

    /** 选项 JSON 序列化后的长度上限：64KB */
    public static final int OPTIONS_JSON_MAX_LENGTH = 65535;

    /** 分页每页条数的默认值与上限（上限防「一次拉全表」，与其它模块一致） */
    public static final long DEFAULT_PAGE_SIZE = 10L;
    public static final long MAX_PAGE_SIZE = 50L;

    /** 错题本「最近 N 条」在首页展示的数量 */
    public static final int HOME_WRONG_PREVIEW = 5;

    /** 批量导入的题目数量上限 */
    public static final int IMPORT_MAX_QUESTIONS = 2000;

    private QuizConstant() {
    }
}
