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
 * 项目案例实体（对应 studio_project）
 *
 * author: shaoshing
 *
 * <p><b>本表最重要的字段：{@code leaderId} 是「队长」的权威数据源（DESIGN 2.3）。</b>
 * 它由本字段单独表达，<b>同时</b>在项目-成员关联表 {@code studio_member_project} 里
 * 冗余地存在一行（保证「队长必然出现在参与成员列表中」）。
 * 权威源只有一个（{@code leader_id}），关联表那行是它的同步结果——
 * 读接口一律以 {@code leader_id} 为准，关联表只用于回答「谁参与了这个项目」。
 * 同步动作由 Service 在<b>同一事务</b>内完成（见 StudioProjectServiceImpl）。
 *
 * <p>其它字段要点：
 * <ul>
 *     <li><b>{@code content} 是 text 大字段</b>（Markdown 正文，RAG 切分数据源）。
 *     列表接口<b>绝不能</b>带它——每一行都回传正文会让列表响应膨胀几十倍，
 *     所以 C 端列表 VO（{@code ProjectFrontVO}）结构上就没有这个字段，
 *     只有详情 VO 才取；</li>
 *     <li><b>{@code techStack} 是 varchar(256) 的 JSON 快照</b>，不是关联表。
 *     数据库里存的是字符串（如 {@code ["Java","Spring Boot"]}），
 *     接口对外是 {@code List<String>}，转换在 Service 里做；
 *     它不参与筛选与统计、<b>不建索引</b>（DESIGN 3.3：JSON 列用 LIKE 筛选会误匹配，
 *     例如搜 "Java" 会命中 "JavaScript"）。将来若产品真要按技术栈筛选，
 *     正确做法是拆出标签表，而不是给它加索引；</li>
 *     <li><b>{@code title / description / leaderId} 是 NOT NULL</b>：
 *     必须在入参侧校验非空，别等数据库抛「Column cannot be null」；</li>
 *     <li><b>{@code status} 的 DDL 默认值是 1（已上线）</b>，
 *     Service 的兜底默认值必须与之保持一致。</li>
 * </ul>
 *
 * <p>默认排序：{@code sort_order 倒序 + created_at 倒序 + id 倒序}，
 * 与 {@code idx_status_sort_time (status, sort_order DESC, created_at DESC)} 同向。
 *
 * <p>逻辑删除、时间填充、雪花主键三条全局约定与其它主表一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_project")
public class StudioProject implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 项目名称（NOT NULL，≤128）
     */
    private String title;

    /**
     * 项目封面 URL（一般先调 /file/upload 拿到相对路径再填这里）
     */
    private String coverImage;

    /**
     * 项目摘要（NOT NULL，≤512）：列表页展示的短描述
     */
    private String description;

    /**
     * 项目详情正文（Markdown，RAG 切分数据源）
     *
     * <p>大字段：只有详情接口才返回它（见 ProjectFrontDetailVO）
     */
    private String content;

    /**
     * 技术栈标签的 JSON 快照（数据库里的形态是字符串）
     *
     * <p>不要直接把它暴露给前端：接口层用 {@code List<String>}（见 VO），
     * 序列化/反序列化与长度校验由 Service 负责
     */
    private String techStack;

    /**
     * 在线体验地址
     */
    private String demoUrl;

    /**
     * 开源仓库地址
     */
    private String githubUrl;

    /**
     * 项目队长 ID（NOT NULL，权威数据源，关联 studio_member.id）
     */
    private Long leaderId;

    /**
     * 项目状态：0-研发中，1-已上线，2-已结题（DDL 默认 1）
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.ProjectStatusEnum}
     */
    private Integer status;

    /**
     * 展示置顶权重：数值越大越靠前（默认排序的第一关键字）
     */
    private Integer sortOrder;

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
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     *
     * <p>加 {@code @TableLogic} 后 MP 会自动改写 SQL：查询追加 {@code deleted_at = 0}，
     * 删除改成 UPDATE（值取 yml 里配置的毫秒时间戳表达式）。
     */
    @TableLogic
    private Long deletedAt;
}
