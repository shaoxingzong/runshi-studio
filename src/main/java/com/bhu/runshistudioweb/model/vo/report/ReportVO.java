package com.bhu.runshistudioweb.model.vo.report;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端举报视图（一行 = 一条举报）
 *
 * targetTitle 是**读取时**从业务表取的展示快照（帖子标题 / 评论正文摘要），不落库：
 * 举报表只存 targetId，若也冗余一份标题，内容改名后两个地方就对不上了。
 * 目标已被删除时该字段为 null，前端展示成「内容已删除」即可——举报本身仍有处置价值。
 *
 * reporterName 同理来自 sys_user 批量查询；账号被删后为空，前端兜底显示「用户」。
 *
 * @author shaoshing
 */
@Data
public class ReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报 ID（19 位雪花 ID，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 目标类型：post / comment */
    private String targetType;

    /** 目标类型中文标签 */
    private String targetTypeLabel;

    /** 目标 ID（帖子或评论主键） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetId;

    /** 目标标题：帖子取 title，评论取正文摘要；内容已删除时为 null */
    private String targetTitle;

    /** 举报人 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reporterId;

    /** 举报人昵称（查不到时为空，前端兜底） */
    private String reporterName;

    /** 举报理由（举报人填写，不对外公开） */
    private String reason;

    /** 处理状态：0-待处理, 1-已处置, 2-举报不成立 */
    private Integer status;

    /** 处理状态中文标签 */
    private String statusLabel;

    /** 处理人 ID（待处理时为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long handleBy;

    /** 处理人昵称（待处理时为 null） */
    private String handlerName;

    /** 处理时间 */
    private LocalDateTime handleAt;

    /** 处置说明（内部复盘用） */
    private String handleResult;

    /** 举报时间 */
    private LocalDateTime createdAt;
}
