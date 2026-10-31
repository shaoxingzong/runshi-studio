package com.bhu.runshistudioweb.model.vo.statistic;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 官网首页大盘统计视图对象
 *
 * @author shaoshing
 */
@Data
public class StatisticOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 证书总数（studio_certificate 未删除的全部行数） */
    private Integer certificateTotal;

    /** 证书按级别的分布：key 为 national / provincial / municipal，缺位补 0 */
    private Map<String, Integer> certificateByLevel;

    /** 证书按类型的分布：key 为 competition / soft_copyright / patent / paper，缺位补 0 */
    private Map<String, Integer> certificateByType;
}
