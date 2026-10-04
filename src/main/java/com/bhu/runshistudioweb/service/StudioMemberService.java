package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;

/**
 * 工作室成员服务接口
 *
 * author: shaoshing
 *
 * <p><b>业务规则：团队成员不对外展示</b>——本接口如今<b>只有管理端方法</b>：
 * {@link #addMember}、{@link #updateMember}、{@link #getMemberById}、{@link #deleteMember}、
 * {@link #listMemberByPage}，调用方必须已通过 {@code @SaCheckRole("admin")} 校验，
 * 返回含内部字段的 {@link MemberVO}。
 * 原 C 端方法（官网成员列表 / 详情）已删除，成员档案只作为后台内部数据。
 *
 * <p>{@link #getMemberFrontVO}（脱敏转换）仍然保留：唯一的对外出处是<b>项目详情</b>
 * 里的「参与成员」（{@code /project/detail}，产品确认保留），
 * 由 {@code MemberProjectService#listFrontMembersByProject} 使用。
 * **绝不允许**把管理端的 {@link MemberVO} 用到对外接口上（会公开 user_id 等内部字段）。
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
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出（A0402）
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

    // ==================== C 端：已随「团队成员不对外展示」删除 ====================

    // 这里原有两个方法，已删除：
    //   - listFrontMembers(MemberQueryRequest)  —— 官网成员列表（匿名）
    //   - getFrontMemberDetail(long)            —— 官网成员详情（匿名，聚合证书与项目）
    // 连同专用的 MemberDetailFrontVO 一并移除；白名单里对应的三条路径也已下线。
    // 成员档案此后只经管理端读取（listMemberByPage / getMemberById，均要求 admin）。
    //
    // {@link #getMemberFrontVO} 保留：项目详情 /project/detail 仍需返回「参与成员」
    // （产品确认的例外），由 MemberProjectService#listFrontMembersByProject 调用。

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