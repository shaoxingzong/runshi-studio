package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;

import java.util.List;

/**
 * 成员-项目关联服务接口
 *
 * @author shaoshing
 */
public interface MemberProjectService extends IService<StudioMemberProject> {

    /**
     * 保证成员在项目成员列表中（幂等，供新增项目与换队长使用）
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID（通常是队长），必须大于 0
     * @throws BusinessException 参数非法时抛出（A0401）
     */
    void ensureMemberInProject(long projectId, long memberId);

    /**
     * 绑定成员到项目（管理员显式动作）
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws BusinessException 参数非法或已参与（A0401）、项目 / 成员不存在（A0402）
     */
    boolean bindMember(long projectId, long memberId);

    /**
     * 解绑成员（物理删除关联行，并拦住「移除当前队长」）
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws BusinessException 参数非法或该成员是当前队长（A0401）、未参与（A0402）
     */
    boolean unbindMember(long projectId, long memberId);

    /**
     * 查参与某项目的全部成员（管理端视图）
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序），必然包含队长，前端不要再去单独取一次；无成员时返回空列表
     * @throws BusinessException 项目不存在时抛 A0402
     */
    List<MemberVO> listMembersByProject(long projectId);

    /**
     * 查某成员参与的全部项目（管理端视图，反向查询）
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 项目列表（按 sort_order → created_at → id 倒序）；无项目时返回空列表
     * @throws BusinessException 成员不存在时抛 A0402
     */
    List<ProjectVO> listProjectsByMember(long memberId);

    // C 端：脱敏视图（供官网成员详情页装配）

    /**
     * 查某成员参与的全部项目（C 端脱敏视图）
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 脱敏项目列表，排序与管理端一致；成员不存在时抛 A0402
     */
    List<ProjectFrontVO> listFrontProjectsByMember(long memberId);

    /**
     * 查参与某项目的全部成员（C 端脱敏视图，供官网项目详情页装配）
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 脱敏成员列表（按 sort_order → id 倒序），必然包含队长；项目不存在时抛 A0402
     */
    List<MemberFrontVO> listFrontMembersByProject(long projectId);

    /**
     * 清理某项目的全部关联关系（供 deleteProject 在同一事务内调用）
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 清理的行数；0 是正常结果（该项目本来就没有关联成员），不是失败
     */
    long removeByProjectId(long projectId);

    /**
     * 清理某成员的全部关联关系（供 deleteMember 在同一事务内调用）
     *
     * 与 removeByProjectId 对称，同样必须在调用方的事务内执行。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数；0 表示该成员本来就没有参与任何项目
     */
    long removeByMemberId(long memberId);
}
