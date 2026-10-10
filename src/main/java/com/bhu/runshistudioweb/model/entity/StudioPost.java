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
 * 帖子实体（对应 studio_post）
 *
 * author: shaoshing
 *
 * <p><b>内容形态是「CSDN 式」：标题 + Markdown 正文，图片内嵌在 {@code content} 里</b>
 * （形如 {@code ![描述](/uploads/2026/10/uuid.png)}）。
 * 因此本模块<b>没有图片关联表</b>——图片在正文流中的位置由 Markdown 决定，
 * 而「关联表 + sort_order」只能表达一个与正文脱节的附件列表，表达不了「图在第几段之后」。
 * 列表页需要的缩略图单独用 {@code coverImage} 存（入库时自动取正文首图，可人工覆盖）。
 *
 * <p><b>{@code summary} 与 {@code commentCount} 都是冗余列，但理由不同</b>：
 * <ul>
 *     <li>{@code summary}：列表页要展示摘要，若每次从 Markdown 正文截取，
 *     就得先剥掉标记语法再截断，一页 20 条就是 20 次字符串处理，且结果不稳定；</li>
 *     <li>{@code commentCount}：列表页要显示评论数，现算就是每帖一次 COUNT（标准 N+1）。
 *     它由评论审核通过 / 删除时维护。</li>
 * </ul>
 * 两者的代价都是「可能与正文不一致」，所以写入逻辑必须集中在 Service 一处，不允许各处各自算。
 *
 * <p><b>{@code status} 的默认值是「待审」而不是「已通过」</b>：本模块先审后发，
 * 新内容一律先进待审队列、仅作者可见。把默认值设成「已通过」会让漏审直接变成发布。
 *
 * <p>字段与列的映射依赖 MyBatis-Plus 的「驼峰 ↔ 下划线」自动转换
 * （{@code authorId → author_id}），无需逐个写 {@code @TableField}。
 * 逻辑删除、时间填充、雪花主键三条全局约定与 {@link SysUser} 完全一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_post")
public class StudioPost implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 帖子标题
     */
    private String title;

    /**
     * 摘要（列表展示用；为空时由 Service 用正文前若干字兜底）
     */
    private String summary;

    /**
     * 封面图 URL（列表缩略图）
     *
     * <p>相对路径（如 {@code /uploads/2026/10/uuid.png}），与 {@link SysUser} 的头像同一口径。
     */
    private String coverImage;

    /**
     * 正文（Markdown，图片内嵌其中）
     *
     * <p>它是 text 列，长度不受 varchar 限制；真正的上限由 Service 层校验
     * （避免几十 MB 的正文打爆响应与数据库）。
     */
    private String content;

    /**
     * 发帖人 ID（关联 sys_user.id）
     *
     * <p>只有<b>绑定的在队成员</b>会产生帖子——「是否成员」由 studio_member 判定，
     * 应用层校验后才会走到这里。
     */
    private Long authorId;

    /**
     * 审核状态：0-待审, 1-已通过, 2-已驳回
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AuditStatusEnum}，
     * 与评论共用同一套状态机。
     */
    private Integer status;

    /**
     * 驳回理由（给用户看；仅「已驳回」时有意义）
     */
    private String rejectReason;

    /**
     * 审核人 ID（管理员）
     *
     * <p>AI 自动处置时<b>留空</b>：AI 不是人，不该伪造一个管理员 ID 填进来，
     * 否则审计时会分不清「这条是谁放行的」。机器的处置记录见 {@link #aiAuditStatus}。
     */
    private Long auditBy;

    /**
     * 审核时间
     */
    private LocalDateTime auditAt;

    /**
     * AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum}，与评论共用。
     * 它和 {@link #status} 是<b>两件事</b>：{@code status} 是内容此刻的结果，
     * 本字段说的是「机器判了什么、判得有多确定」。
     *
     * <p>异步审核任务拿它当幂等条件——只处理「未判」的记录，
     * 否则重复送审会用新结论覆盖掉已有的处置结果。
     */
    private Integer aiAuditStatus;

    /**
     * AI 给出的理由（给管理端参考）
     *
     * <p>与 {@link #rejectReason} 的分工：本字段是机器的判断依据，原样保留；
     * {@code rejectReason} 是给用户看的驳回说明（管理员可以改得更委婉）。
     */
    private String aiReason;

    /**
     * AI 判定时（未判则为 null）
     */
    private LocalDateTime aiAt;

    /**
     * 浏览量
     */
    private Integer viewCount;

    /**
     * 评论数（冗余列，由评论的审核通过 / 删除维护）
     */
    private Integer commentCount;

    /**
     * 是否置顶：0-否, 1-是
     *
     * <p>用 {@code Integer} 而不是 {@code Boolean}：DDL 是 tinyint，
     * 且将来可能扩展出「分区置顶」这类多状态的语义。
     */
    private Integer pinned;

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
     * 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换）
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
     * <p>加 {@code @TableLogic} 后，MP 会自动改写 SQL：查询追加 {@code deleted_at = 0}，
     * 删除改成 UPDATE（值取 yml 里配置的毫秒时间戳表达式）。
     */
    @TableLogic
    private Long deletedAt;
}
