package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 管理端帖子详情（作者看自己的 / 管理端列表的 PostVO + Markdown 正文）
 *
 * author: shaoshing
 *
 * <p><b>为什么单独建一个 VO 而不给 {@link PostVO} 加 {@code content}</b>：
 * {@link PostVO} 同时服务于「我的发帖」「待审队列」「管理端列表」三处，
 * 它刻意<b>不带正文</b>——一页十条全文会把响应体撑爆，且没有人在列表里读正文
 * （详见 {@link PostVO} 的类注释）。编辑弹窗要改正文，就必须有一条
 * 「按 id 取单篇全文」的路径，这正是本类的职责。
 *
 * <p>与对外的 {@code PostFrontDetailVO} 是同一思路（列表 VO 不带正文、详情 VO 带），
 * 区别只在服务对象：那个给游客，本类给管理员。
 *
 * <p><b>为什么不复用公开详情接口 {@code /post/detail}</b>，两个理由：
 * <ul>
 *     <li>它只返回<b>已通过</b>的帖子——待审 / 已驳回的取不到，而管理员恰恰要改这类；</li>
 *     <li>它会<b>原子自增浏览量</b>——管理员点开编辑一下，浏览量就 +1，
 *     这个计数是内容热度的依据，不能被管理动作污染。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostAdminDetailVO extends PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 正文（Markdown，图片内嵌其中）
     *
     * <p>仅在管理端详情接口返回；列表接口一律为 null。
     */
    private String content;
}
