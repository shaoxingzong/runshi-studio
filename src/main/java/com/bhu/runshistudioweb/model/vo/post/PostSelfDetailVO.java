package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 作者看自己「未公开」帖子的详情（PostVO + Markdown 正文）
 *
 * author: shaoshing
 *
 * <p><b>与 {@link PostAdminDetailVO} 为什么不同类</b>：两者结构完全一样
 * （都是「列表 VO + 正文」），但服务对象与鉴权完全不同——那个给管理员，
 * 这个给作者本人。合成一个类会让「管理员接口返回作者 VO」这种错配成为可能，
 * 而这类错配在编译期毫无察觉、只在数据泄露时才暴露。
 *
 * <p><b>覆盖范围</b>：只返回<b>本人</b>且<b>待审或已驳回</b>的帖子——
 * 已通过的帖子走公开详情接口（{@code /post/detail}）即可，不需要本类。
 * 因此本 VO 的调用方一定是「想改驳回稿」或「想预览自己待审稿」的作者。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostSelfDetailVO extends PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 正文（Markdown，图片内嵌其中）
     */
    private String content;
}
