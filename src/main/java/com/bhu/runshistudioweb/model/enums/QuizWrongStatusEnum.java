package com.bhu.runshistudioweb.model.enums;

/**
 * 错题本状态（studio_quiz_wrong.status）
 *
 * 取值与 DDL 列注释同源：0-待复习，1-已消解。
 * 「已消解」不是永久移除：同一道题再答错时条目自动回到待复习、清空 resolved_at，
 * 不需要任何「重开」代码。
 *
 * @author shaoshing
 */
public enum QuizWrongStatusEnum {

    /** 待复习：出现在错题本列表与「错题重刷」的候选池里 */
    PENDING(0, "待复习"),

    /** 已消解：不再出现在待复习列表，但条目保留（再答错会回到待复习） */
    RESOLVED(1, "已消解");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与前端标签 */
    private final String desc;

    QuizWrongStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与前端标签 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数字翻译成错题本状态，查不到返回 null，不兜成 PENDING
     *
     * 兜底会把已消解的题显示成待复习，用户反复看到自己已经掌握的题；
     * 作为筛选参数时非法值须报错，否则用户会以为错题本空了。
     *
     * @param value 待翻译的取值，允许为 null
     * @return 命中的枚举；null 或不是 0 / 1 时返回 null
     */
    public static QuizWrongStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (QuizWrongStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // ③ 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }
}
