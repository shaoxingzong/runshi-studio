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
 * AI 咨询会话实体（对应 studio_ai_session）
 *
 * author: shaoshing
 *
 * <p>一个会话 = 一次「打开聊天窗口」；它下面挂着若干条消息（{@link StudioAiMessage}）。
 * 本表只存会话级信息：归属、标题、时间。
 *
 * <p><b>{@code userId} 可空是本表最关键的设计</b>：
 * <ul>
 *     <li>非空 → 该会话属于某个登录用户，<b>只有本人能续聊</b>
 *     （这条规则由 Service 校验，命中越权时统一返回 A0402，不暴露「会话是否存在」）；</li>
 *     <li>为 null → 匿名会话（游客提问）。游客凭<b>不可枚举</b>的雪花 ID 续聊：
 *     19 位雪花 ID 无法被暴力枚举，因此「知道 ID」等价于「持有凭据」，
 *     这比强制游客登录更适合官网场景（OpenAI 等产品的分享链接是同一思路）。</li>
 * </ul>
 *
 * <p>{@code title} 取首问的前 30 个字（见 {@code AiChatConstant#SESSION_TITLE_MAX_LENGTH}），
 * 只用于会话列表展示；正文不在这里，避免把大字段堆到会话表上。
 *
 * <p>{@code updated_at} 在每次追加消息时由 Service 主动刷新（同一事务内），
 * 这样「最近活跃的会话」可以直接按它排序，不需要去 join 消息表求 MAX(created_at)。
 *
 * <p>审计字段、逻辑删除、雪花主键三条全局约定与其它主表完全一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_ai_session")
public class StudioAiSession implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     * <p>它同时是游客续聊的「凭据」，不可枚举的性质正是匿名可用的前提
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 提问用户 ID（关联 sys_user.id），<b>可为 NULL</b>
     * <p>NULL 表示匿名会话（游客创建），任何人都可以凭该会话 ID 续聊
     */
    private Long userId;

    /**
     * 会话标题：取首问前 30 个字，仅用于会话列表展示
     */
    private String title;

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
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间：<b>每次追加消息都会被刷新</b>（同一事务内），用于「最近活跃」排序
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     *
     * <p>会话不乱删：它同时是消息表的外键来源，物理删除会留下孤儿消息
     */
    @TableLogic
    private Long deletedAt;
}
