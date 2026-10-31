package com.bhu.runshistudioweb.model.vo.log;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端操作日志视图（列表行）
 *
 * @author shaoshing
 */
@Data
public class OperationLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志 ID（序列化为字符串：19 位雪花 ID 超出前端 Number 安全整数范围） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 操作人 ID（关联 sys_user.id；0 表示系统或未登录） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /** 操作人账号快照 */
    private String operatorAccount;

    /** 操作人昵称快照 */
    private String operatorName;

    /** 动作码，取值见 OperationTypeEnum */
    private String action;

    /** 动作中文标签（后端字典给出，前端直接展示） */
    private String actionLabel;

    /** 对象类型码：POST / COMMENT / USER / QUIZ */
    private String targetType;

    /** 对象类型中文标签 */
    private String targetTypeLabel;

    /** 对象 ID；章节 / 教材等动作没有单一对象 ID，为 null */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetId;

    /** 对象标题快照（帖子标题 / 题干摘要 / 用户名等） */
    private String targetTitle;

    /** 处置细节（驳回理由、变更项）；不含口令与正文 */
    private String detail;

    /** 来源 IP；解析不到时为 null */
    private String ip;

    /** 发生时间 */
    private LocalDateTime createdAt;
}
