package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 成员详情视图对象（<b>C 端</b>官网「成员详情」页使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p>结构 = 「基础档案」+「证书」+「项目」三段：
 * <ul>
 *     <li>{@link #profile}：复用成员列表的 {@link MemberFrontVO}——
 *     详情页先展示的就是列表卡片上那些字段，没必要另建一个几乎相同的 VO；</li>
 *     <li>{@link #certificates}：{@link CertificateFrontVO}（脱敏，不含 sortOrder / 审计字段）；</li>
 *     <li>{@link #projects}：{@link ProjectFrontVO}（脱敏，<b>不含 content</b> 大字段——
 *     详情页的项目卡片只需要标题/封面/摘要，点进去才走项目详情接口）。</li>
 * </ul>
 *
 * <p><b>本类最重要的规则：三个字段全部用 Front 系列 VO。</b>
 * 绝不能复用 {@code MemberVO / CertificateVO / ProjectVO}——它们含
 * {@code userId / leaderId / sortOrder / content / 审计字段}，
 * 一旦混进来就是「内部字段泄露到匿名接口」。这也是本类存在的意义：
 * 把「给游客看的聚合结构」与「给管理员看的聚合结构」在类型上彻底分开。
 *
 * <p><b>为什么详情要一次返回三段，而不是前端并发调三个接口</b>：
 * 官网详情页是三块内容同时渲染的，合成一个接口后：
 * ① 前端一次请求拿到完整页面数据；② 服务端可以用批量查询代替「先查成员再逐个查他的证书/项目」
 * （见 StudioMemberServiceImpl 的装配注释，那里写了 N+1 的反面教材）；
 * ③ 成员不存在时只有一个 40400，不会出现「档案拿到了、证书请求却 500」的半残页面。
 *
 * <p>空数据一律返回空数组而不是 null：前端可以直接 {@code forEach}，
 * 不用为「这个字段是不是 null」到处写判断。
 */
@Data
public class MemberDetailFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员基础档案（脱敏，与官网列表卡片同一套字段）
     */
    private MemberFrontVO profile;

    /**
     * 该成员持有的证书列表（脱敏）
     * <p>排序：{@code sort_order 倒序 → award_date 倒序 → id 倒序}（与证书模块既定规则一致）
     */
    private List<CertificateFrontVO> certificates;

    /**
     * 该成员参与的项目列表（脱敏，不含 content）
     * <p>排序：{@code sort_order 倒序 → created_at 倒序 → id 倒序}（与项目模块既定规则一致）
     */
    private List<ProjectFrontVO> projects;
}
