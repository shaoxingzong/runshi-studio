package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberSelfUpdateRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

/**
 * 工作室成员服务接口
 *
 * @author shaoshing
 */
public interface StudioMemberService extends IService<StudioMember> {

    // 管理端：调用方必须已通过 @SaCheckRole("admin")

    /**
     * 管理员新增成员档案
     *
     * @param memberAddRequest 新增请求，不能为 null；姓名与入学年份必填；userId 选填（非空时必须是存在且未被占用的账号）；teamPosition / memberStatus 不传时分别按「成员」「在队」处理
     * @return 新成员档案 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws BusinessException 参数不合法、账号不存在或已被绑定（A0401）、入库失败（B0001）
     */
    long addMember(MemberAddRequest memberAddRequest);

    /**
     * 管理员更新成员档案（部分更新：字段为 null 表示不修改）
     *
     * @param memberUpdateRequest 更新请求，不能为 null；id 必填；其余字段为 null 表示保持原值，传了空白的姓名会抛 A0401
     * @return true 表示更新成功；不代表数据真的变了，什么都没改时也是 true
     * @throws BusinessException 成员不存在（A0402）、账号不存在或已被其他成员绑定（A0401）、参数不合法（A0401）
     */
    boolean updateMember(MemberUpdateRequest memberUpdateRequest);

    /**
     * 管理员按 id 查询成员详情
     *
     * @param id 成员 ID
     * @return 含内部字段的成员信息（管理端视图），永不为 null
     * @throws BusinessException 成员不存在时抛出（A0402）
     */
    MemberVO getMemberById(long id);

    /**
     * 管理员逻辑删除成员档案
     *
     * @param id 成员 ID
     * @return true 表示删除成功
     * @throws BusinessException 成员不存在时抛出（A0402）
     */
    boolean deleteMember(long id);

    /**
     * 管理员分页查询成员列表
     *
     * @param memberQueryRequest 查询条件，允许整体为 null（表示无条件查第一页）；字段为 null 表示该条件不参与筛选，不是「查 null 值」
     * @return 分页结果，记录为含内部字段的 MemberVO；没有匹配数据时 records 为空列表（不是 null）
     */
    Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest);

    // C 端：成员自己的档案

    /**
     * 成员自助编辑本人档案（只更新展示字段，null 表示不修改）
     *
     * <p>调用方必须已登录；本方法按 userId 查在队档案，不是成员时拒绝。
     * 可改字段由 {@link MemberSelfUpdateRequest} 的白名单决定：姓名 / 头像 / 专业 /
     * 技术方向 / GitHub / 简介；登录账号、届别、职务、成员状态、排序不在白名单内。
     *
     * @param userId 当前登录账号 ID
     * @param request 自助更新请求，不能为 null；姓名为非 null 时 trim 后不能为空
     * @return 更新后的 C 端脱敏档案，供前端直接刷新展示
     * @throws BusinessException 参数不合法（A0401）、不是在队成员（A0301）
     */
    MemberFrontVO updateMyProfile(long userId, MemberSelfUpdateRequest request);

    /**
     * 实体转「管理端」VO
     *
     * @param member 成员实体，允许为 null
     * @return 含内部字段的管理端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberVO getMemberVO(StudioMember member);

    /**
     * 实体转「C 端」VO
     *
     * @param member 成员实体，允许为 null
     * @return 脱敏后的 C 端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberFrontVO getMemberFrontVO(StudioMember member);

    // 考勤模块依赖：按登录账号查在队成员

    /**
     * 按绑定的登录账号 ID 查询在队的成员档案
     *
     * @param userId 登录账号 ID，为 null 时直接返回 null（未登录 / 无绑定）
     * @return 在队的成员档案；查不到返回 null（不抛异常）
     */
    StudioMember getActiveMemberByUserId(Long userId);
}
