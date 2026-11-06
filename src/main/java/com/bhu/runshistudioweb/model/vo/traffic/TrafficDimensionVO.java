package com.bhu.runshistudioweb.model.vo.traffic;

import lombok.Data;

import java.io.Serializable;

/**
 * 流量维度统计（来源域名 / 设备类型等分组的一行）
 *
 * 与 {@link PageTrafficVO} 拆成两个类而不是复用：页面榜的维度是「路径」，
 * 这里的维度是「任意分组名」。字段恰好相同不代表语义相同，将来页面榜要加
 * 平均停留时长时，这里不需要跟着变。
 *
 * @author shaoshing
 */
@Data
public class TrafficDimensionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 维度值：来源域名（空值归一为「直接访问」）或设备类型（pc / mobile / unknown） */
    private String name;

    /** 该维度下的 PV */
    private Integer pv;

    /** 该维度下的 UV（按 visitor_id 去重） */
    private Integer uv;
}
