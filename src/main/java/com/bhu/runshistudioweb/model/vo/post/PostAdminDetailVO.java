package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 管理端帖子详情（PostVO + Markdown 正文）
 *
 * 单独建一个 VO 而不给 PostVO 加 content：PostVO 同时服务「我的发帖」「待审队列」「管理端列表」三处，
 * 刻意不带正文（一页十条全文会撑爆响应体），而编辑弹窗要改正文，就必须有一条「按 id 取单篇全文」的路径。
 *
 * 不复用公开详情接口 /post/detail：它只返回已通过的帖子，而管理员恰恰要改待审、已驳回的；
 * 它还会原子自增浏览量，管理员点开编辑一下就会污染内容热度这个计数。
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostAdminDetailVO extends PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 正文（Markdown，图片内嵌其中）
     *
     * 仅在管理端详情接口返回；列表接口一律为 null。
     */
    private String content;
}
