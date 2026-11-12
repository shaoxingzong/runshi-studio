package com.bhu.runshistudioweb.model.enums;

/**
 * 工作室内部事项状态（studio_board_item.status）
 *
 * @author shaoshing
 */
public enum BoardItemStatusEnum {

    /** 待办：还没开始 */
    TODO(0, "待办"),

    /** 进行中：已认领、正在做 */
    DOING(1, "进行中"),

    /** 已完成：完成时写 completed_at */
    DONE(2, "已完成");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文标签：看板列头与日志直接使用 */
    private final String label;

    BoardItemStatusEnum(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /** 是否已完成 */
    public boolean isDone() {
        return this == DONE;
    }

    /**
     * 把数字翻译成状态，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的取值，允许为 null
     */
    public static BoardItemStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (BoardItemStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }

    /** 拼出可直接放进错误提示的合法取值文案，如「0（待办） / 1（进行中） / 2（已完成）」 */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (BoardItemStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.label).append("）");
        }
        return text.toString();
    }
}
