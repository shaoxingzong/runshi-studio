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
 * 本模块最重要的一条：ensureMemberInProject 与 bindMember 是两种语义，不能混用。
 * - ensure 是「保证这个成员在项目成员列表里」——幂等（重复调用结果一致），
 * 已存在就直接返回，并发撞唯一索引也静默成功。它是「新增项目 / 换队长」的
 * 内部同步动作，重复执行是正常路径；
 * - bind 是「管理员显式添加一个成员」——冲突要报错（A0401），
 * 因为管理员的显式动作如果没生效，必须让他看见。
 * 换队长若误用 bind，管理员每次重复保存项目都会收到 A0401「该成员已参与此项目」，
 * 看起来像 bug，实际是两种语义被混用了。
 *
 * 不变量：项目队长（studio_project.leader_id）必然在关联表中有一行。
 * 它由 ensure 建立，由 unbindMember 守卫（当前队长不可被移除）。
 *
 * 依赖方向铁律：本接口被 StudioProjectServiceImpl（队长同步、级联清理）
 * 与 StudioMemberServiceImpl（级联清理）依赖，而它自己的实现只准注入 Mapper——
 * 一旦实现里注入 StudioProjectService（例如为了读 leaderId）就构成循环依赖，
 * Spring Boot 默认禁止循环引用，启动直接失败。查 leader_id 请走 StudioProjectMapper。
 *
 * @author shaoshing
 */
public interface MemberProjectService extends IService<StudioMemberProject> {

    /**
     * 保证成员在项目成员列表中（幂等，供新增项目与换队长使用）
     *
     * <p>为什么这样设计：调用方要的是「结果状态」而不是「插入一次」。
     * 管理员重复保存项目、队长没有变化时，这个动作会被反复执行，
     * 每次都不该报错。所以这里的契约是「保证」，不是「添加」。
     * 与 bindMember 的区别务必看清：已存在 → 直接返回不报错；
     * 并发下撞唯一索引 uk_member_proj → 静默成功，
     * 因为「已经在列表里」正是本方法想要的结果。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数；</li>
     *     <li>② 先查一次（乐观检查）：重复保存这类常见路径在这里就返回了；</li>
     *     <li>③ 插入关联行，并吞掉并发下的唯一索引冲突。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步的 catch 不能省。第 ② 步的查询只是优化，
     * 两个并发请求可能同时查到「不存在」再同时插入，真正拦住重复的是唯一索引。
     * 不 catch 的话，并发时会抛英文 SQL 异常，前端看到 B0001。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID（通常是队长），必须大于 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法时抛出（A0401）
     */
    void ensureMemberInProject(long projectId, long memberId);

    /**
     * 绑定成员到项目（管理员显式动作）
     *
     * <p>为什么这样设计：显式动作必须「所见即所得」——点了绑定，要么成功，
     * 要么明确告诉管理员为什么没成。所以重复绑定要报 A0401，
     * 不能像 ensure 那样静默返回。两层防御缺一不可：
     * 应用层查重负责给出可读的中文提示，唯一索引负责兜住并发、保证不出现重复数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数；</li>
     *     <li>② 校验项目与成员都存在（逻辑删除的等同不存在）；</li>
     *     <li>③ 应用层查重，命中就给可读提示；</li>
     *     <li>④ 插入，并捕获并发下的唯一索引冲突，翻成与第 ③ 步相同的文案。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「省一次查询」去掉第 ③ 步，只留唯一索引。
     * 那样用户看到的会是一段英文 SQLException，落到全局兜底后变成 B0001。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或已参与时抛出（A0401）；
     * 项目 / 成员不存在时抛出（A0402）
     */
    boolean bindMember(long projectId, long memberId);

    /**
     * 解绑成员（物理删除关联行，并拦住「移除当前队长」）
     *
     * <p>为什么这样设计：项目表存 leader_id，关联表存成员名单，两者必须一致。
     * 若允许把队长从关联表里删掉，就会出现「项目有队长、但成员列表里没有他」
     * 的破窗状态——官网成员区域看不到负责人，而项目本身确实有负责人。
     * 所以这里读 leader_id 做守卫：想换人，必须先走「更换队长」。
     * 关联表用物理删除，因为它没有 deleted_at 列：若改成逻辑删除，
     * 同一对 (project_id, member_id) 会与已删除的行冲突，重复绑定就再也插不进去了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数；</li>
     *     <li>② 先确认关联存在，不存在给 A0402；</li>
     *     <li>③ 读项目表做队长守卫，命中就拒绝并提示下一步动作；</li>
     *     <li>④ 物理删除关联行。</li>
     * </ol>
     *
     * <p>容易踩的坑：守卫读的必须是 studio_project.leader_id（权威源），
     * 而不是关联表里的某个标记位。多一份需要同步的冗余状态，
     * 就多一次两边不一致、守卫失效的机会。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法、
     * 该成员是当前队长时抛出（A0401）；未参与时抛出（A0402）
     */
    boolean unbindMember(long projectId, long memberId);

    /**
     * 查参与某项目的全部成员（管理端视图）
     *
     * <p>为什么这样设计：先校验项目存在，再查成员。顺序不能反——
     * 反过来的话，项目 ID 根本不存在时也会返回空列表，
     * 前端会理解成「这个项目还没有成员」，而实际上项目本身不存在。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验项目存在，不存在给 A0402；</li>
     *     <li>③ 两步查询取成员（关联表取 ID、主表 IN 查），再转管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回结果里必然包含队长（同步不变量），
     * 前端不要再去单独取一次队长，否则同一个人会出现两次。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序）；无成员时返回空列表；
     * 项目不存在时抛 A0402
     */
    List<MemberVO> listMembersByProject(long projectId);

    /**
     * 查某成员参与的全部项目（管理端视图，反向查询）
     *
     * <p>为什么这样设计：与 listMembersByProject 对称，先校验成员存在再查项目，
     * 否则「成员不存在」会被伪装成「这个人没有项目」。
     * 反向查询走 idx_proj_member / uk_member_proj 的另一侧前缀，不必新建索引。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验成员存在，不存在给 A0402；</li>
     *     <li>③ 两步查询取项目，再转管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：管理端 VO 带 content 正文大字段，
     * 返回条数等于该成员参与的项目数。数量少时没问题，
     * 一旦某人参与几十个项目，这里就该换成不含正文的列表 VO。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 项目列表（按 sort_order → created_at → id 倒序）；
     * 无项目时返回空列表；成员不存在时抛 A0402
     */
    List<ProjectVO> listProjectsByMember(long memberId);

    // ==================== C 端：脱敏视图（供官网成员详情页装配）====================

    /**
     * 查某成员参与的全部项目（C 端脱敏视图）
     *
     * <p>为什么这样设计：与 listProjectsByMember 的唯一差别是 VO 类型——
     * 这里返回 ProjectFrontVO（不含 content 大字段、不含 leaderId / sortOrder / 审计字段）。
     * 但取数逻辑完全复用同一个私有方法，这样「后台看到几个、官网就看到几个」
     * 是代码结构保证的，不靠人工核对。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验成员存在，不存在给 A0402；</li>
     *     <li>③ 复用同一套两步查询，再转脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：脱敏靠 ProjectFrontVO 的结构实现，不是靠这里手动过滤。
     * 若给那个 VO 补上 leaderId，这个接口会静默泄露内部字段。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 脱敏项目列表，排序与管理端一致；成员不存在时抛 A0402
     */
    List<ProjectFrontVO> listFrontProjectsByMember(long memberId);

    /**
     * 查参与某项目的全部成员（C 端脱敏视图，供官网项目详情页装配）
     *
     * <p>为什么这样设计：官网项目详情要展示「这个项目是谁做的」，
     * 这是本项目唯一对外暴露成员信息的出口（产品确认保留的例外）。
     * 脱敏靠 MemberFrontVO 的结构实现——它没有 userId 与审计字段，
     * 结构上就不可能把账号 ID 带出去。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验项目存在，不存在给 A0402；</li>
     *     <li>③ 复用同一套两步查询，再转脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：队长必然在结果里（leader_id 同步不变量），
     * 官网「项目成员」区域因此天然包含队长，前端不需要额外拼一个。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 脱敏成员列表（按 sort_order → id 倒序）；项目不存在时抛 A0402
     */
    List<MemberFrontVO> listFrontMembersByProject(long projectId);

    /**
     * 清理某项目的全部关联关系（供 deleteProject 在同一事务内调用）
     *
     * <p>为什么这样设计：主表删除后，「谁参与了这个项目」的关联必须一起消失，
     * 否则会留下指向不存在项目的悬空行。这一步必须在删主表之前、
     * 且在调用方的事务内执行——否则两次写会各自提交，破坏原子性。
     * 本方法刻意不自己加 @Transactional：事务边界由调用方统一划定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 按 project_id 物理删除，返回行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是行数而不是 boolean。
     * 对「删除一个本来就没有成员的项目」来说，0 行是正常结果，不是失败。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 清理的行数（0 表示该项目本来就没有关联成员）
     */
    long removeByProjectId(long projectId);

    /**
     * 清理某成员的全部关联关系（供 deleteMember 在同一事务内调用）
     *
     * <p>为什么这样设计：与 removeByProjectId 对称——删成员时也要把
     * 「他参与了哪些项目」一次性清掉。同样必须在调用方的事务内执行，
     * 且不自己加 @Transactional。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 按 member_id 物理删除，返回行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：删成员前若不清关联，这些行会一直留在表里，
     * 而查询侧虽然会因为主表的 deleted_at 过滤掉它们，
     * 但唯一索引仍被占用——以后想把同一个成员 ID 重新绑进项目就会冲突。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数（0 表示该成员本来就没有参与任何项目）
     */
    long removeByMemberId(long memberId);
}
