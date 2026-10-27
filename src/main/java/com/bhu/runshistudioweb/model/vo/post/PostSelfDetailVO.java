package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 作者看自己「未公开」帖子的详情（PostVO + Markdown 正文）
 *
 * 与 PostAdminDetailVO 结构完全一样，但服务对象与鉴权完全不同：那个给管理员，这个给作者本人。
 * 合成一个类会让「管理员接口返回作者 VO」这种错配成为可能，而这类错配编译期毫无察觉、只在数据泄露时才暴露。
 *
 * 只返回本人且待审或已驳回的帖子——已通过的走公开详情接口即可。
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostSelfDetailVO extends PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 正文（Markdown，图片内嵌其中） */
    private String content;
}
