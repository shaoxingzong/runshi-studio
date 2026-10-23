package com.bhu.runshistudioweb.model.vo.statistic;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

// 官网首页大盘统计视图对象（DESIGN.md 场景 A 落地）
//
// 本类最值钱的一个知识点：计数字段一律用 Integer，不是 Long。
// 这不是随手选的，是被项目的全局约定推出来的结论：
// - 项目的 JsonConfig 把 Long / long 统一序列化成字符串
// （雪花 ID 是 19 位，超出 JS 的 Number.MAX_SAFE_INTEGER，不转字符串前端会末位失真）；
// - 但「12 人」「27 张证书」这类计数天然不会超过 int 范围（工作室数据量级），
// 前端图表需要的是数字，拿到 "12" 就得自己 parseInt，还得小心 null；
// - 所以用 Integer 正好绕开全局的「Long → 字符串」规则，前端直接拿到数字。
// 一句话记住：主键用 Long（出字符串），计数用 Integer（出数字）。
// 反过来写（用 Long 存计数）是本项目最典型的坑——本机测试照样过，上了前端图表全是字符串，
// 排序与加法还会按字典序算。
//
// 两个分布用 Map<String, Integer> 而不是写死字段：
// 级别/类型的取值由枚举定义（ com.bhu.runshistudioweb.model.enums.CertificateLevelEnum /
// CertificateTypeEnum），将来加一个取值只需要改枚举与 Service 的补齐逻辑，
// VO 结构不用动。前端按固定 key 集合渲染图表，因此 Service 必须保证
// 枚举里的每个 key 都出现且缺位补 0——key 缺失会导致图表维度错乱。
//
// 本期只覆盖证书维度。
// 项目模块（ studio_project）落地后在本类加字段即可，
// 接口路径与响应结构都不用改（这也是用 VO 承载的好处）。
@Data
public class StatisticOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // ⚠️ 原三个成员计数字段（memberTotal / memberInTeam / memberGraduated）已随
    // 「团队成员不对外展示」删除。本 VO 挂在匿名接口 /statistic/overview 上，
    // 对外暴露「团队有多少人」同样属于展示成员信息，因此一并移除；
    // 首页大盘如今只剩证书维度。管理端若将来需要人数，应另开一个要求 admin 的统计接口。

    // 证书总数（studio_certificate 未删除的全部行数）
    private Integer certificateTotal;

    // 证书按级别的分布：key 为 national / provincial / municipal，缺位补 0
    private Map<String, Integer> certificateByLevel;

    // 证书按类型的分布：key 为 competition / soft_copyright / patent / paper，缺位补 0
    private Map<String, Integer> certificateByType;
}
