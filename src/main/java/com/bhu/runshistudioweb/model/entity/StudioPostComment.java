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
 * 帖子评论实体（对应 studio_post_comment，楼中楼）
 *
 * author: shaoshing
 *
 * <p><b>楼中楼结构</b>：{@code parentId} 为 {@code null} 表示「直接评论帖子」，
 * 非 null 表示回复某条评论。只支持<b>两层</b>——回复的回复仍挂在同一个顶层下，
 * 不再继续嵌套。这样列表组装只要一次查询 + 内存分组：
 * {@code WHERE post_id = ?} 取回全部，再按 {@code parentId} 挂成树。
 *
 * <p><b>为什么不为 parentId 建索引</b>：它只参与内存里的分组，不进任何 WHERE 条件，
 * 建索引也用不上，白白增加写成本。真正服务查询的是
 * {@code idx_post_status_id (post_id, status, id)}——一次查完某帖的已通过评论并按 id 有序返回。
 *
 * <p><b>{@code content} 用 varchar(1000) 而不是 text</b>：评论是短文本。
 * 定长上限让「Service 层校验长度」与「列宽」是同一个值，
 * 不会出现代码限 1000、列却是 text 的错位（那种错位在严格模式下会以截断报错的形式暴露）。
 *
 * <p><b>{@code deletedBy} 是审计字段</b>：管理员删的是<b>别人的</b>内容。
 * 光有 {@code deletedAt} 只能知道「什么时候删的」，追溯不到「谁删的」；
 * 出争议时必须能查到操作人，因此单独存一列。
 *
 * <p>{@code status} 与帖子共用 {@link com.bhu.runshistudioweb.model.enums.AuditStatusEnum}：
 * 两者的状态机完全一致，不另建一套枚举。
 */
@Data
@TableName("studio_post_comment")
public class StudioPostComment implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属帖子 ID（关联 studio_post.id）
     */
    private Long postId;

    /**
     * 评论人 ID（关联 sys_user.id）
     *
     * <p>登录用户即可评论，<b>不要求</b>是工作室成员——这是与发帖的区别。
     */
    private Long authorId;

    /**
     * 父评论 ID（楼中楼）；null 表示直接评论帖子
     */
    private Long parentId;

    /**
     * 评论正文（纯文本，最长 1000 字符，与列宽一致）
     */
    private String content;

    /**
     * 楼层号（顶层评论从 1 递增；回复某条评论时为 null）
     *
     * <p>只有顶层才有楼层——回复依附于父评论，给它编号会让「第几楼」的语义变得混乱。
     */
    private Integer floor;

    /**
     * 审核状态：0-待审, 1-已通过, 2-已驳回
     */
    private Integer status;

    /**
     * 驳回理由（给用户看；仅「已驳回」时有意义）
     */
    private String rejectReason;

    /**
     * 审核人 ID（管理员）
     *
     * <p>AI 自动处置时<b>留空</b>：AI 不是人，不该伪造管理员 ID，
     * 机器的处置记录见 {@link #aiAuditStatus}。
     */
    private Long auditBy;

    /**
     * 审核时间
     */
    private LocalDateTime auditAt;

    /**
     * AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum}，与帖子共用。
     * 异步审核任务拿它当幂等条件：只处理「未判」的记录。
     */
    private Integer aiAuditStatus;

    /**
     * AI 给出的理由（给管理端参考；给用户看的驳回说明另存 {@link #rejectReason}）
     */
    private String aiReason;

    /**
     * AI 判定时（未判则为 null）
     */
    private LocalDateTime aiAt;

    /**
     * 删除人 ID（管理员删除评论时的审计字段）
     */
    private Long deletedBy;

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
     *
     * <p>删除采用<b>级联</b>：删一条顶层评论时，它的所有回复一并逻辑删除
     * （{@code WHERE id = ? OR parent_id = ?}），
     * 不留「父已删、子还在」的悬空回复——那样前端会渲染出没有上下文的孤儿评论。
     */
    @TableLogic
    private Long deletedAt;
}
