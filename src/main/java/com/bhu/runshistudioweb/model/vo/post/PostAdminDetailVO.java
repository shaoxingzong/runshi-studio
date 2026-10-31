package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 管理端帖子详情（PostVO + Markdown 正文）
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostAdminDetailVO extends PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 正文（Markdown，图片内嵌其中） */
    private String content;
}
