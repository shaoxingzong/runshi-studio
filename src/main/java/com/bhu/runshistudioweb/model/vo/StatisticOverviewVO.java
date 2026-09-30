package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 官网首页大盘统计视图对象（DESIGN.md 场景 A 落地）
 *
 * author: shaoshing
 *
 * <p><b>本类最值钱的一个知识点：计数字段一律用 {@code Integer}，不是 {@code Long}。</b>
 * 这不是随手选的，是被项目的全局约定**推出来的结论**：
 * <ul>
 *     <li>项目的 {@code JsonConfig} 把 {@code Long / long} 统一序列化成<b>字符串</b>
 *     （雪花 ID 是 19 位，超出 JS 的 {@code Number.MAX_SAFE_INTEGER}，不转字符串前端会末位失真）；</li>
 *     <li>但「12 人」「27 张证书」这类计数天然不会超过 int 范围（工作室数据量级），
 *     前端图表需要的是<b>数字</b>，拿到 {@code "12"} 就得自己 parseInt，还得小心 null；</li>
 *     <li>所以用 {@code Integer} 正好绕开全局的「Long → 字符串」规则，前端直接拿到数字。</li>
 * </ul>
 * 一句话记住：<b>主键用 Long（出字符串），计数用 Integer（出数字）</b>。
 * 反过来写（用 Long 存计数）是本项目最典型的坑——本机测试照样过，上了前端图表全是字符串，
 * 排序与加法还会按字典序算。
 *
 * <p>两个分布用 {@code Map<String, Integer>} 而不是写死字段：
 * 级别/类型的取值由枚举定义（{@link com.bhu.runshistudioweb.model.enums.CertificateLevelEnum} /
 * {@link CertificateTypeEnum}），将来加一个取值只需要改枚举与 Service 的补齐逻辑，
 * VO 结构不用动。前端按固定 key 集合渲染图表，因此 Service 必须保证
 * <b>枚举里的每个 key 都出现且缺位补 0</b>——key 缺失会导致图表维度错乱。
 *
 * <p>本期只覆盖成员与证书两个模块：项目模块（{@code studio_project}）尚未实现，
 * 等它落地后在<b>本类加字段即可</b>，接口路径与响应结构都不用改（这也是用 VO 承载的好处）。
 */
@Data
public class StatisticOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员总数（studio_member 未删除的全部行数）
     */
    private Integer memberTotal;

    /**
     * 在读/在队成员数（member_status = 0）
     */
    private Integer memberInTeam;

    /**
     * 毕业/离队成员数（member_status = 1）
     */
    private Integer memberGraduated;

    /**
     * 证书总数（studio_certificate 未删除的全部行数）
     */
    private Integer certificateTotal;

    /**
     * 证书按级别的分布：key 为 {@code national / provincial / municipal}，缺位补 0
     */
    private Map<String, Integer> certificateByLevel;

    /**
     * 证书按类型的分布：key 为 {@code competition / soft_copyright / patent / paper}，缺位补 0
     */
    private Map<String, Integer> certificateByType;
}
