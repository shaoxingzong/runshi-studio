package com.bhu.runshistudioweb.model.vo.statistic;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 官网首页大盘统计视图对象
 *
 * 计数字段一律用 Integer 而不是 Long：项目的 JsonConfig 把 Long 统一序列化成字符串
 * （雪花 ID 是 19 位，超出 JS 的 MAX_SAFE_INTEGER），但「12 人」「27 张证书」这类计数天然不会超过 int 范围，
 * 前端图表要的是数字，拿到 "12" 还得 parseInt。一句话：主键用 Long（出字符串），计数用 Integer（出数字）。
 * 反过来写会让前端图表全是字符串，排序与加法还会按字典序算。
 *
 * 两个分布用 Map<String, Integer> 而不是写死字段：取值由枚举定义，将来加一个取值只需改枚举与 Service 的补齐逻辑。
 * 前端按固定 key 集合渲染图表，因此 Service 必须保证枚举里每个 key 都出现且缺位补 0，key 缺失会导致图表维度错乱。
 *
 * @author shaoshing
 */
@Data
public class StatisticOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 刻意没有成员人数（总数 / 在读 / 已毕业）：本 VO 挂在匿名接口 /statistic/overview 上，
    // 对外暴露「团队有多少人」属于展示成员信息，与「团队成员不对外展示」的规则冲突。
    // 管理端若需要人数，应另开一个要求 admin 的统计接口。

    /** 证书总数（studio_certificate 未删除的全部行数） */
    private Integer certificateTotal;

    /** 证书按级别的分布：key 为 national / provincial / municipal，缺位补 0 */
    private Map<String, Integer> certificateByLevel;

    /** 证书按类型的分布：key 为 competition / soft_copyright / patent / paper，缺位补 0 */
    private Map<String, Integer> certificateByType;
}
