package com.bhu.runshistudioweb.model.dto.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端题目分页查询请求（GET + 对象绑定）
 *
 * 与 PostAdminQueryRequest / ProjectQueryRequest 同一惯例：
 * 查询参数用 GET 传递、由本类自己声明分页字段，Service 转成 MyBatis-Plus 的 Page。
 *
 * 分页与筛选的非法值不在这里报错：这些参数走 GET，校验失败抛的是
 * BindException，会落到全局异常处理器的兜底分支变成 B0001，体验很差；
 * 且「页码传错」没必要让整个查询失败。因此一律由 Service 兜底纠正
 * （页码 < 1 视为 1、pageSize 收敛到上限）。
 *
 * 字段语义：为 null 表示该条件不参与筛选，不是「查 null 值」。
 */
@Data
public class QuizQuestionQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（精确匹配；教材是分组名，模糊匹配会把「马原」和「马原习题集」混在一起） */
    private String textbookName;

    /** 章节名称（精确匹配） */
    private String chapterName;

    /** 题型（精确匹配）：0-单选，1-多选；非法值由 Service 返回 A0401 */
    private Integer type;

    /** 题干关键词（模糊匹配） */
    private String keyword;

    /** 状态（精确匹配）：0-启用，1-停用；不传表示全部 */
    private Integer status;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛 */
    private Long pageSize = 10L;
}
