package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;

import java.util.List;

/**
 * 成员-项目关联服务接口
 *
 * author: shaoshing
 *
 * <p>本模块最重要的一条：<b>{@link #ensureMemberInProject} 与 {@link #bindMember} 是两种语义，不能混用</b>。
 * <ul>
 *     <li>{@code ensure}＝「保证这个成员在项目成员列表里」——<b>幂等</b>，已存在就直接返回，
 *     并发撞唯一索引也静默成功。它是「新增项目 / 换队长」的内部同步动作，
 *     重复执行是<b>正常路径</b>；</li>
 *     <li>{@code bind}＝「管理员显式添加一个成员」——<b>冲突要报错</b>（40000），
 *     因为管理员的显式动作如果没生效，必须让他看见。</li>
 * </ul>
 * 换队长若误用 {@code bind}，管理员每次重复保存项目都会收到 40000「该成员已参与此项目」——
 * 这是本任务最隐蔽的坑。
 *
 * <p><b>不变量</b>：项目队长（{@code studio_project.leader_id}）必然在关联表中有一行。
 * 它由 {@code ensure} 建立，由 {@link #unbindMember} 守卫（当前队长不可被移除）。
 *
 * <p><b>依赖方向铁律</b>：本类被 {@code StudioProjectServiceImpl}（队长同步、级联清理）
 * 与 {@code StudioMemberServiceImpl}（级联清理）依赖，而自己的实现<b>只准注入 Mapper</b>——
 * 一旦注入 {@code StudioProjectService}（例如为了读 leaderId）就构成循环依赖，
 * Spring Boot 默认禁止，**启动直接失败**。查 leader_id 请走 {@code StudioProjectMapper}。
 */
public interface MemberProjectService extends IService<StudioMemberProject> {

    /**
     * 保证成员在项目成员列表中（<b>幂等</b>，供新增项目与换队长使用）
     *
     * <p>与 {@link #bindMember} 的区别（务必看清）：
     * <ul>
     *     <li>已存在 → <b>直接返回</b>，不报错；</li>
     *     <li>并发下撞唯一索引 {@code uk_member_proj} → <b>静默成功</b>，
     *     因为「已经在列表里」正是本方法想要的结果。</li>
     * </ul>
     *
     * @param projectId 项目 ID
     * @param memberId  成员 ID（通常是队长）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法时抛出（40000）
     */
    void ensureMemberInProject(long projectId, long memberId);

    /**
     * 绑定成员到项目（管理员显式动作）
     *
     * @param projectId 项目 ID
     * @param memberId  成员 ID
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（40000）、
     *         项目/成员不存在（40400）、已参与（40000）时抛出
     */
    boolean bindMember(long projectId, long memberId);

    /**
     * 解绑成员（物理删除关联行）
     *
     * @param projectId 项目 ID
     * @param memberId  成员 ID
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（40000）、
     *         未参与（40400）、<b>该成员是当前队长</b>（40000）时抛出
     */
    boolean unbindMember(long projectId, long memberId);

    /**
     * 查参与某项目的全部成员（管理端视图）
     *
     * @param projectId 项目 ID
     * @return 成员列表（按 sort_order → id 倒序）；项目不存在时抛 40400
     */
    List<MemberVO> listMembersByProject(long projectId);

    /**
     * 查某成员参与的全部项目（管理端视图，反向查询）
     *
     * @param memberId 成员 ID
     * @return 项目列表（按 sort_order → created_at → id 倒序）；成员不存在时抛 40400
     */
    List<ProjectVO> listProjectsByMember(long memberId);

    // ==================== C 端：脱敏视图（供官网成员详情页装配） ====================

    /**
     * 查某成员参与的全部项目（<b>C 端脱敏视图</b>）
     *
     * <p>与 {@link #listProjectsByMember} 的唯一差别是 VO 类型：
     * 这里返回 {@code ProjectFrontVO}（不含 content 大字段、不含 leaderId / sortOrder / 审计字段）。
     *
     * <p>排序与管理端一致：{@code sort_order 倒序 → created_at 倒序 → id 倒序}。
     *
     * @param memberId 成员 ID
     * @return 脱敏项目列表；成员不存在时抛 40400
     */
    List<ProjectFrontVO> listFrontProjectsByMember(long memberId);

    /**
     * 查参与某项目的全部成员（<b>C 端脱敏视图</b>，供官网项目详情页装配）
     *
     * <p>注意：<b>队长必然在结果里</b>（leader_id 同步不变量，DESIGN 2.3），
     * 官网「项目成员」区域因此天然包含队长，不需要前端额外拼一个。
     *
     * <p>排序与管理端一致：{@code sort_order 倒序 → id 倒序}。
     *
     * @param projectId 项目 ID
     * @return 脱敏成员列表；项目不存在时抛 40400
     */
    List<MemberFrontVO> listFrontMembersByProject(long projectId);

    /**
     * 清理某项目的全部关联关系（供 {@code deleteProject} 在同一事务内调用）
     *
     * @param projectId 项目 ID
     * @return 清理的行数
     */
    long removeByProjectId(long projectId);

    /**
     * 清理某成员的全部关联关系（供 {@code deleteMember} 在同一事务内调用）
     *
     * @param memberId 成员 ID
     * @return 清理的行数
     */
    long removeByMemberId(long memberId);
}
