package com.bhu.runshistudioweb.model.vo.traffic;

import lombok.Data;

import java.io.Serializable;

/**
 * 单日流量（每日趋势的一行）
 *
 * @author shaoshing
 */
 */
@Data
public class DailyTrafficVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日期（yyyy-MM-dd） */
    private String date;

    /** 当日 PV */
    private Integer pv;

    /** 当日 UV（按 visitor_id 去重） */
    private Integer uv;
}
