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

// 刷题题目实体（对应 studio_quiz_question，题目 / 选项 / 答案 / 章节归属合一）
//
// 本表最重要的性质：展示数据与判定数据是物理分离的两列。
// #options 里只存 label 与 content，绝不存「哪个是对的」；
// 正确项单独放 #correctLabels。这样 C 端的题目 VO 可以把 options 原样下发，
// 不存在「忘了过滤 isCorrect」的泄露面——脱敏靠数据结构，不靠记忆。
//
// #options 在 Java 侧是 String，不是 List：
// 与 StudioProject#getTechStack 完全同一套路——数据库里是 JSON 字符串快照，
// 接口层才转成 List<QuizOptionVO>，转换集中在 Service 一处。
// 绝不能用 BeanUtils.copyProperties 在实体与 VO 之间复制：
// 两侧类型不一致时它会静默跳过该属性（本项目踩过：techStack 永远是 null 且不报错），
// 因此选项必须走显式的映射方法。
//
// 三个 *_seq 的语义与全站 sort_order 相反：
// 这里是「数值越小越靠前」（教材顺序），而 sort_order 是「数值越大越靠前」（置顶权重）。
// 同名会让后来的人按错方向排序，故另起名。
//
// 逻辑删除、时间填充、雪花主键三条全局约定与其它主表一致，见 db/user.sql 文件头。
@Data
@TableName("studio_quiz_question")
public class StudioQuizQuestion implements Serializable {

    // 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败
    private static final long serialVersionUID = 1L;

    // 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 教材名称（NOT NULL，≤128）
    //
    // 章节没有独立表，教材与章节的归属完全由名字列表达：
    // 「教材下拉」= 按本列 GROUP BY，「章节下拉」= 按本列 + chapterName 分组。
    private String textbookName;

    // 教材顺序：数值越小越靠前（与全站 sort_order 方向相反，见类注释）
    private Integer textbookSeq;

    // 章节名称（NOT NULL，≤128）
    private String chapterName;

    // 章节在教材内的顺序：数值越小越靠前
    private Integer chapterSeq;

    // 题型：0-单选（恰好 1 个正确项），1-多选（至少 2 个，全对才得分）
    //
    // 取值见 com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum。
    // 刻意没有「判断」：判断题等价于一道选项为「正确 / 错误」的单选，
    // 模型已经表达得下，单独建一个类型只会多一条判分分支和一套交互。
    private Integer type;

    // 题干（NOT NULL，≤2000）
    //
    // 纯文本：前端用插值渲染，不经过 Markdown 管线。
    // 政治选择题不需要富文本，而 Markdown 渲染会给题库引入一个 XSS 面。
    private String stem;

    // 选项 JSON 快照（NOT NULL，形如 [{"label":"A","content":"运动性"}]）
    //
    // 只含展示数据，不含正确性——见类注释。解析失败（手工改库改坏的）
    // 一律返回空列表并记 warn，绝不抛异常：一条坏数据不能让整个列表接口 500。
    private String options;

    // 正确选项标签（NOT NULL，升序、无分隔）：单选 "B"，多选 "ABD"
    //
    // 判分只认这一列。写成「升序无分隔」而不是 "A,C,D"：
    // 判分退化成一次字符串相等比较，不需要解析、也不存在「顺序不同算错」的坑。
    private String correctLabels;

    // 解析（≤2000，可空）
    //
    // 只在作答之后下发：题目 VO 结构上没有这个字段，答案与解析都只出现在判定结果里。
    private String analysis;

    // 难度：1-易，2-中，3-难（DDL 默认 1）
    private Integer difficulty;

    // 题源（如「2023年真题」「自编」），可空
    private String source;

    // 题序：数值越小越靠前（即教材顺序）
    private Integer questionSeq;

    // 状态：0-启用，1-停用
    //
    // 停用是可逆的下线：不再被抽到、不再出现在错题本里，
    // 但历史作答与错题条目都保留——重新启用后一切自动回来。
    // 它与逻辑删除的分工：删除会连错题条目一起清理，停用不会。
    private Integer status;

    // 创建人 ID：插入时由 MyMetaObjectHandler 填充
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    // 更新人 ID：插入与更新时都会填充
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    // 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // 更新时间
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
    @TableLogic
    private Long deletedAt;
}
