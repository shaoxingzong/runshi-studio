package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

/**
 * 工作室成员服务接口
 *
 * 业务规则：团队成员不对外展示——本接口如今只有管理端方法：
 * addMember、updateMember、getMemberById、deleteMember、
 * listMemberByPage，调用方必须已通过 @SaCheckRole("admin") 校验，
 * 返回含内部字段的 MemberVO。
 * 本接口不含 C 端方法（官网成员列表 / 详情）：成员档案只作为后台内部数据。
 *
 * getMemberFrontVO（脱敏转换）仍然保留：唯一的对外出处是项目详情
 * 里的「参与成员」（ /project/detail，产品确认保留），
 * 由 MemberProjectService#listFrontMembersByProject 使用。
 * 绝不允许把管理端的 MemberVO 用到对外接口上（会公开 user_id 等内部字段）。
 *
 * 约定：所有方法在「参数不合法 / 业务不允许」时抛 BusinessException，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应。
 *
 * @author shaoshing
 */
public interface StudioMemberService extends IService<StudioMember> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 管理员新增成员档案
     *
     * 两个与普通 CRUD 不同的校验点：
     * - 绑定账号（userId 非空时）：必须「账号存在」且「未被其他成员绑定」，
     * 并发竞态由唯一索引 uk_userid_deleted 兜底；
     * - 入学年份必须在 1950~2100 之间（smallint 列，拒绝「2026级」这类字符串输入）。
     *
     * @param memberAddRequest 新增请求
     * @return 新成员 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、账号不存在、
     * 账号已被绑定或入库失败时抛出
     */
    long addMember(MemberAddRequest memberAddRequest);

    /**
     * 管理员更新成员档案（部分更新：字段为 null 表示不修改）
     *
     * 改绑账号时排除自身再校验唯一性：把成员 A 绑定到「已属于 A 自己」的账号是合法的，
     * 若不做排除，重复提交同一个 userId 会被误判为「已被其他成员绑定」。
     *
     * @param memberUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在、账号不存在、
     * 账号已被其他成员绑定、参数不合法时抛出
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
     * 逻辑删除（写 deleted_at 毫秒时间戳）后，该成员绑定过的账号会被释放
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
     * 分页与排序参数的兜底纠正在实现里完成；排序字段走白名单，
     * 且默认按 sort_order 倒序（官网置顶权重），而不是 id 倒序。
     *
     * @param memberQueryRequest 查询条件（允许整体为 null，表示无条件查第一页）
     * @return 分页结果，记录为含内部字段的 MemberVO
     */
    Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest);

    // ==================== C 端：只有一个脱敏转换方法 ====================

    // 本接口刻意没有「官网成员列表 / 官网成员详情」这类匿名方法：
    // 成员档案只经管理端读取（listMemberByPage / getMemberById，均要求 admin）。
    //
    // getMemberFrontVO 是唯一的 C 端出口：项目详情 /project/detail 仍需返回「参与成员」
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

    // ==================== 考勤模块依赖：按登录账号查在队成员 ====================

    /**
     * 按绑定的登录账号 ID 查询在队的成员档案
     *
     * 为什么考勤要的是这个方法，而不是复用 getMemberById：
     * 考勤的准入规则是「当前登录账号是不是被管理员标记过的工作室成员」，
     * 判定链条是 sys_user.id → studio_member.user_id，
     * 且必须是在队（member_status = 0）状态——毕业/离队的成员不再参与考勤。
     * getMemberById 是按成员档案主键查，答不上这个问题。
     *
     * 注意它与 sys_user.user_role 的关系：角色里也有 member 取值，
     * 但那是权限标签，可能与档案不同步。成员身份的权威是 studio_member 表，
     * 因此这里查表而不是查角色。
     *
     * @param userId 登录账号 ID，为 null 时直接返回 null（未登录 / 无绑定）
     * @return 在队的成员档案；查不到返回 null（不是抛异常——
     * 调用方要拿「查不到」这件事来生成 A0301 无权限提示，而不是让它炸掉）
     */
    StudioMember getActiveMemberByUserId(Long userId);
}