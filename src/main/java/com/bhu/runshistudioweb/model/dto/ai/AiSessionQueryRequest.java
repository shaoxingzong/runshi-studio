package com.bhu.runshistudioweb.model.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 会话分页查询请求（「我的会话」列表用）
 *
 * author: shaoshing
 *
 * <p>只有分页参数，没有任何筛选条件——这样设计是有意的：
 * 列表本身就是「当前登录用户的会话」，用户维度由服务端从登录态取，
 * <b>绝不能</b>让前端传 {@code userId}：那等于把「看别人的会话列表」做成了功能。
 *
 * <p>分页字段的写法与其它模块一致（普通参数载体 + 自己声明分页字段，
 * 由 Service 转成 MyBatis-Plus 的 {@code Page}）：
 * 页码 &lt; 1 视为 1，pageSize &lt; 1 视为 10、&gt; 50 收敛到 50。
 */
@Data
public class AiSessionQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 页码，从 1 开始；不传或 &lt; 1 按 1 处理
     */
    private Long current = 1L;

    /**
     * 每页条数；不传或 &lt; 1 按 10 处理，超过上限（50）会被收敛
     */
    private Long pageSize = 10L;
}
