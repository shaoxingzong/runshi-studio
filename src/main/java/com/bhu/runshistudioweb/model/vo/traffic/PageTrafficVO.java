package com.bhu.runshistudioweb.model.vo.traffic;

import lombok.Data;

import java.io.Serializable;

/**
 * 单个页面的流量（热门页面榜的一行）
 *
 * author: shaoshing
 */
@Data
public class PageTrafficVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页面路径（如 /posts、/projects/123） */
    private String pagePath;

    /** 该页面的 PV */
    private Integer pv;

    /** 该页面的 UV（按 visitor_id 去重） */
    private Integer uv;
}
