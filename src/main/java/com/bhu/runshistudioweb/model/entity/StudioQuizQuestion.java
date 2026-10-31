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
 * 刷题题目实体（对应 studio_quiz_question，题目 / 选项 / 答案 / 章节归属合一）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_quiz_question")
public class StudioQuizQuestion implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 教材名称（NOT NULL，≤128）：没有章节表，教材与章节归属全靠名字列表达 */
    private String textbookName;

    /** 教材顺序：数值越小越靠前（与全站 sort_order 方向相反，见类注释） */
    private Integer textbookSeq;

    /** 章节名称（NOT NULL，≤128） */
    private String chapterName;

    /** 章节在教材内的顺序：数值越小越靠前 */
    private Integer chapterSeq;

    /** 题型：0-单选（恰好 1 个正确项），1-多选（至少 2 个，全对才得分），见 QuizQuestionTypeEnum */
    private Integer type;

    /** 题干（NOT NULL，≤2000）：纯文本，不走 Markdown 渲染，避免给题库引入 XSS 面 */
    private String stem;

    /** 选项 JSON 快照（NOT NULL）：只含展示数据不含正确性；解析失败返回空列表并记 warn，不抛异常 */
    private String options;

    /** 正确选项标签（NOT NULL，升序无分隔）：单选 "B"，多选 "ABD"，判分退化为一次字符串相等 */
    private String correctLabels;

    /** 解析（≤2000，可空）：只在作答之后下发，题目 VO 结构上没有这个字段 */
    private String analysis;

    /** 难度：1-易，2-中，3-难（DDL 默认 1） */
    private Integer difficulty;

    /** 题源（如「2023年真题」「自编」），可空 */
    private String source;

    /** 题序：数值越小越靠前（即教材顺序） */
    private Integer questionSeq;

    /** 状态：0-启用，1-停用；停用可逆（不再抽到、不进错题本，历史数据保留），与逻辑删除不同 */
    private Integer status;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Long deletedAt;
}
