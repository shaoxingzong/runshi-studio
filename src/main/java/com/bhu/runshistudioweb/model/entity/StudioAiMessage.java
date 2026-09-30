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
 * AI 咨询消息实体（对应 studio_ai_message）
 *
 * author: shaoshing
 *
 * <p>一条消息 = 一句话（用户提问 或 AI 回答），通过 {@code sessionId} 归属于某个会话。
 *
 * <p><b>{@code role} 是闭集取值</b>：{@code user / assistant}
 * （见 {@link com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum}）。
 * 用 varchar 而不是 tinyint，是因为它同时要原样送给模型（OpenAI 兼容协议的 role 字段），
 * 数字存法反而要在两处做映射，多一层出错机会。
 *
 * <p>{@code content} 是 text：AI 回答可能上千字，不能按短文本存。
 * 它<b>不建全文索引</b>：本期没有「搜消息内容」的需求，
 * 而 text 上建索引会让每次写入都多一次索引维护开销。
 *
 * <p><b>顺序问题</b>：{@code created_at} 是 {@code datetime}（秒级精度），
 * 同一秒内产生的 user / assistant 两条消息会**时间相同**，
 * 因此查询一律追加 {@code id} 作为次级排序键（雪花 ID 单调递增，等价于时间序且唯一）。
 * 只按 created_at 排序会在「同一秒的两条消息」上出现随机顺序——表现为对话记录偶尔颠倒。
 *
 * <p>审计字段、逻辑删除、雪花主键三条全局约定与其它主表一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_ai_message")
public class StudioAiMessage implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属会话 ID（关联 studio_ai_session.id），走 idx_session_created 的最左前缀
     */
    private Long sessionId;

    /**
     * 消息角色：{@code user}（用户提问） / {@code assistant}（AI 回答）
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum}
     */
    private String role;

    /**
     * 消息正文（用户问题 或 AI 回答）
     */
    private String content;

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
     * 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充）
     * <p>秒级精度，排序时必须配合 id 使用（见类注释）
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
