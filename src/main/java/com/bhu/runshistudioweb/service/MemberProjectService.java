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
 * ensureMemberInProject 与 bindMember 是两种语义，不能混用：前者是「保证这个成员在名单里」，
 * 幂等、并发撞唯一索引也静默成功，供新增项目 / 换队长内部同步；后者是「管理员显式添加」，
 * 冲突要报 A0401，因为显式动作没生效必须让管理员看见。换队长若误用 bind，
 * 管理员每次重复保存项目都会收到「该成员已参与此项目」，看起来像 bug。
 *
 * @author shaoshing
 */
public interface MemberProjectService extends IService<StudioMemberProject> {

    /**
     * 保证成员在项目成员列表中（幂等，供新增项目与换队长使用）
     *
     * 调用方要的是「结果状态」而不是「插入一次」：管理员重复保存项目时这个动作会被反复执行，
     * 每次都不该报错。已存在就直接返回，并发撞唯一索引 uk_member_proj 也静默成功。
     *
     * 坑：吞掉唯一索引冲突的那段 catch 不能省。先查一次只是优化，
     * 两个并发请求可能同时查到「不存在」再同时插入，不 catch 会抛英文 SQL 异常变成 B0001。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID（通常是队长），必须大于 0
     * @throws BusinessException 参数非法时抛出（A0401）
     */
    void ensureMemberInProject(long projectId, long memberId);

    /**
     * 绑定成员到项目（管理员显式动作）
     *
     * 显式动作必须「所见即所得」：重复绑定要报 A0401，不能像 ensure 那样静默返回。
     * 两层防御缺一不可：应用层查重给出可读中文提示，唯一索引兜住并发。
     *
     * 坑：不要为了省一次查询去掉查重，只留唯一索引——用户看到的会是一段英文 SQLException，
     * 落到全局兜底后变成 B0001。
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
     * 项目表存 leader_id、关联表存名单，两者必须一致。若允许把队长从关联表里删掉，
     * 就会出现「项目有队长、但成员列表里没有他」的破窗状态。想换人必须先走「更换队长」。
     *
     * 坑：守卫读的必须是 studio_project.leader_id（权威源），而不是关联表里的标记位。
     * 多一份需要同步的冗余状态，就多一次两边不一致、守卫失效的机会。
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
     * 必须先校验项目存在再查成员：反过来，项目 ID 根本不存在时也会返回空列表，
     * 前端会理解成「这个项目还没有成员」。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序），必然包含队长，前端不要再去单独取一次；
     *         无成员时返回空列表
     * @throws BusinessException 项目不存在时抛 A0402
     */
    List<MemberVO> listMembersByProject(long projectId);

    /**
     * 查某成员参与的全部项目（管理端视图，反向查询）
     *
     * 与 listMembersByProject 对称：先校验成员存在再查项目，
     * 否则「成员不存在」会被伪装成「这个人没有项目」。
     *
     * 坑：管理端 VO 带 content 正文大字段，返回条数等于该成员参与的项目数。
     * 某人参与几十个项目时，这里就该换成不含正文的列表 VO。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 项目列表（按 sort_order → created_at → id 倒序）；无项目时返回空列表
     * @throws BusinessException 成员不存在时抛 A0402
     */
    List<ProjectVO> listProjectsByMember(long memberId);

    // ==================== C 端：脱敏视图（供官网成员详情页装配）====================

    /**
     * 查某成员参与的全部项目（C 端脱敏视图）
     *
     * 与 listProjectsByMember 的唯一差别是 VO 类型：ProjectFrontVO 不含 content 大字段、
     * 不含 leaderId / sortOrder / 审计字段。取数逻辑复用同一个私有方法，
     * 「后台看到几个、官网就看到几个」由代码结构保证。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 脱敏项目列表，排序与管理端一致；成员不存在时抛 A0402
     */
    List<ProjectFrontVO> listFrontProjectsByMember(long memberId);

    /**
     * 查参与某项目的全部成员（C 端脱敏视图，供官网项目详情页装配）
     *
     * 这是本项目唯一对外暴露成员信息的出口（产品确认保留的例外）。
     * 脱敏靠 MemberFrontVO 的结构实现——它没有 userId 与审计字段。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 脱敏成员列表（按 sort_order → id 倒序），必然包含队长；
     *         项目不存在时抛 A0402
     */
    List<MemberFrontVO> listFrontMembersByProject(long projectId);

    /**
     * 清理某项目的全部关联关系（供 deleteProject 在同一事务内调用）
     *
     * 本方法刻意不自己加 @Transactional：事务边界由调用方统一划定，
     * 且必须在删主表之前执行，否则两次写各自提交会破坏原子性。
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
     * 坑：删成员前若不清关联，这些行会一直占着唯一索引，
     * 以后想把同一个成员 ID 重新绑进项目就会冲突。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数；0 表示该成员本来就没有参与任何项目
     */
    long removeByMemberId(long memberId);
}
