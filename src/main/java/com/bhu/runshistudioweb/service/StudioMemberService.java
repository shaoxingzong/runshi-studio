package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;

import java.util.List;

/**
 * 工作室成员服务接口
 *
 * author: shaoshing
 *
 * <p>方法按使用者分成两组（与 UserService 同一条纪律）：
 * <ul>
 *     <li><b>管理端（管理员）</b>：{@link #addMember}、{@link #updateMember}、
 *     {@link #getMemberById}、{@link #deleteMember}、{@link #listMemberByPage}——
 *     调用方必须已通过 {@code @SaCheckRole("admin")} 校验，返回含内部字段的 {@link MemberVO}；</li>
 *     <li><b>C 端（游客可访问）</b>：{@link #listFrontMembers}——只返回官网展示字段的
 *     {@link MemberFrontVO}，**绝不允许**把管理端的 VO 拿来复用（会把 user_id 等内部字段公开）。</li>
 * </ul>
 *
 * <p>约定：所有方法在「参数不合法 / 业务不允许」时**抛 BusinessException**，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应。
 */
public interface StudioMemberService extends IService<StudioMember> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 管理员新增成员档案
     *
     * <p>两个与普通 CRUD 不同的校验点：
     * <ul>
     *     <li>绑定账号（userId 非空时）：必须「账号存在」且「未被其他成员绑定」，
     *     并发竞态由唯一索引 {@code uk_userid_deleted} 兜底；</li>
     *     <li>入学年份必须在 1950~2100 之间（smallint 列，拒绝「2026级」这类字符串输入）。</li>
     * </ul>
     *
     * @param memberAddRequest 新增请求
     * @return 新成员 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、账号不存在、
     *         账号已被绑定或入库失败时抛出
     */
    long addMember(MemberAddRequest memberAddRequest);

    /**
     * 管理员更新成员档案（部分更新：字段为 null 表示不修改）
     *
     * <p>改绑账号时排除自身再校验唯一性：把成员 A 绑定到「已属于 A 自己」的账号是合法的，
     * 若不做排除，重复提交同一个 userId 会被误判为「已被其他成员绑定」。
     *
     * @param memberUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在、账号不存在、
     *         账号已被其他成员绑定、参数不合法时抛出
     */
    boolean updateMember(MemberUpdateRequest memberUpdateRequest);

    /**
     * 管理员按 id 查询成员详情
     *
     * @param id 成员 ID
     * @return 含内部字段的成员信息（管理端视图）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出（40400）
     */
    MemberVO getMemberById(long id);

    /**
     * 管理员逻辑删除成员档案
     *
     * <p>逻辑删除（写 deleted_at 毫秒时间戳）后，该成员绑定过的账号会被释放
     * （唯一索引带 deleted_at），可以重新绑定到新档案。
     *
     * @param id 成员 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出
     */
    boolean deleteMember(long id);

    /**
     * 管理员分页查询成员列表
     *
     * <p>分页与排序参数的兜底纠正在实现里完成；排序字段走白名单，
     * 且**默认按 {@code sort_order} 倒序**（官网置顶权重），而不是 id 倒序。
     *
     * @param memberQueryRequest 查询条件（允许整体为 null，表示无条件查第一页）
     * @return 分页结果，记录为含内部字段的 {@link MemberVO}
     */
    Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest);

    // ==================== C 端：游客可访问 ====================

    /**
     * C 端查询成员列表（官网展示，匿名可访问）
     *
     * <p>与 {@link #listMemberByPage} 的两点差别（安全边界）：
     * <ul>
     *     <li>返回脱敏的 {@link MemberFrontVO}，不含 userId / sortOrder / 审计时间；</li>
     *     <li>忽略请求里的分页与排序参数，固定按置顶权重倒序——
     *     公开接口不接受任意排序字段，排序规则由产品定义而非请求方决定。</li>
     * </ul>
     *
     * @param memberQueryRequest 查询条件（可选：id/姓名/届别/方向/职务/状态），允许为空
     * @return 官网展示用的成员列表
     */
    List<MemberFrontVO> listFrontMembers(MemberQueryRequest memberQueryRequest);

    /**
     * 实体转「管理端」VO
     *
     * @param member 成员实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    MemberVO getMemberVO(StudioMember member);

    /**
     * 实体转「C 端」VO
     *
     * @param member 成员实体，允许为 null
     * @return 脱敏后的 VO；入参为 null 时返回 null
     */
    MemberFrontVO getMemberFrontVO(StudioMember member);
}