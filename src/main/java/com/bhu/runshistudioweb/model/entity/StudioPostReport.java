package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容举报实体（对应 studio_post_report）
 *
 * author: shaoshing
 *
 * <p><b>举报是「免审内容」的兜底</b>：被信任的用户发布的内容会自动通过、不经人工，
 * 但仍需给其他人一条反馈通道。举报成立后内容转待审或删除，不成立则保留原样。
 *
 * <p><b>为什么用 {@code targetType + targetId} 的软关联，而不是帖子/评论各建一张举报表</b>：
 * 两张表的字段几乎完全一样（举报人、理由、处理状态、处理人、处理结果），
 * 拆开只是把同一套逻辑复制两份，还会让「待处理举报列表」这类跨类型查询变成两次查询 + 内存合并。
 *
 * <p><b>这个取舍的代价必须记牢</b>：数据库<b>无法</b>用外键保证 {@code targetId} 指向真实存在的内容。
 * 因此删除帖子或评论时，必须在应用层一并处置其举报记录（逻辑删除对应举报，或标记为无需处理），
 * 否则会留下永远查不到目标的悬空举报，把待处理列表污染掉。
 *
 * <p><b>唯一索引 {@code uk_target_reporter} 带了 {@code deletedAt}</b>：
 * 与 {@code uk_account_deleted}、{@code uk_userid_deleted} 是同一套路。
 * 不带的话，一条举报被删除后同一人就无法再次举报同一内容了（唯一键冲突），
 * 而「误举报后撤销、看清问题再举报」是真实会发生的场景。
 */
@Data
@TableName("studio_post_report")
public class StudioPostReport implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 举报目标类型：post-帖子, comment-评论
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.ReportTargetTypeEnum}。
     */
    private String targetType;

    /**
     * 举报目标 ID（指向 studio_post.id 或 studio_post_comment.id，由 targetType 决定）
     */
    private Long targetId;

    /**
     * 举报人 ID（关联 sys_user.id）
     */
    private Long reporterId;

    /**
     * 举报理由（举报人填写）
     */
    private String reason;

    /**
     * 处理状态：0-待处理, 1-已处置, 2-举报不成立
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.ReportStatusEnum}。
     */
    private Integer status;

    /**
     * 处理人 ID（管理员）
     */
    private Long handleBy;

    /**
     * 处理时间
     */
    private LocalDateTime handleAt;

    /**
     * 处理结果说明（内部复盘用）
     *
     * <p>它不返回给举报人——举报人只看到「已处理」这个结论，
     * 处理细节留在后台，避免被人研究出「什么样的举报会被受理」。
     */
    private String handleResult;

    /**
     * 创建人 ID：插入时由 MyMetaObjectHandler 填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 更新人 ID：插入与更新时都会填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 创建时间（datetime，映射 LocalDateTime）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     */
    @TableLogic
    private Long deletedAt;
}
