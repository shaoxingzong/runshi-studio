package com.bhu.runshistudioweb.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库文档分页查询条件（管理端，）
 *
 * 筛选口径的分界（与成员模块同一条纪律）：
 * - title 是自由文本，走模糊匹配，不校验；
 * - status 是闭集（0/1/2），但它的取值集合很小且不会对外暴露枚举类，
 * 这里只做「传了就用」的等值筛选——传 99 会返回空列表，语义清楚，不必报错；
 * - sourceType 是闭集枚举，必须校验：传 superman 若静默返回空，
 * 管理员会以为是"库里没有这类文档"，排查方向被带偏。故在 Service 里显式抛 A0401
 * 并列出合法取值。
 *
 * 为什么用 POST + 请求体：与项目模块 /project/list/page 保持一致
 * （本项目后台分页接口一律 POST + JSON，避免把筛选条件拼进 URL 造成日志泄露与长度限制）。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeDocQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题关键词（模糊匹配，可选） */
    private String title;

    /** 来源类型（精确匹配，可选）： manual / project / member / certificate */
    private String sourceType;

    /** 索引状态（精确匹配，可选）：0-待处理，1-已索引，2-失败 */
    private Integer status;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛到上限 */
    private Long pageSize = 10L;
}
