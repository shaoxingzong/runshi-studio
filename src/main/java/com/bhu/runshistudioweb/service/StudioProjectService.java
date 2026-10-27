package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.project.ProjectAddRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectQueryRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;

/**
 * 项目案例服务接口
 *
 * 与成员、证书模块同一套约定：参数不合法或业务不允许时抛 BusinessException，
 * 不返回错误码、不返回 null，所有失败路径由全局异常处理器统一转成标准响应。
 *
 * 两个考点：leader_id 是队长唯一的权威数据源，写入时必须同步关联表，
 * 且要用幂等的 ensure 而不是 bind——重复保存项目是正常路径，不该报 A0401；
 * tech_stack 在库里是 varchar(256) 快照、对外是 List<String>，序列化后超过 256 必须抛 A0401，
 * 读回时解析失败返回空列表，绝不因为一条脏数据让列表接口 500。
 *
 * 事务边界：addProject、updateProject、deleteProject 都是「主表写 + 关联表写」两次写，
 * 必须在同一事务内。注解写在实现类的方法上——Spring 的 @Transactional 运行期由代理织入，
 * 放在接口上对基于类代理的配置不生效。
 *
 * @author shaoshing
 */
public interface StudioProjectService extends IService<StudioProject> {

    // ==================== 管理端 ====================

    /**
     * 新增项目，并在同一事务内把队长同步进关联表
     *
     * 项目主表只记一个 leader_id，而官网要展示参与成员，名单存在关联表里，两次写必须同生共死。
     * 这里不用「先写主表、失败再补偿」的方案，因为补偿逻辑本身也会失败。
     *
     * 坑：同步队长必须用 ensure 而不是 bindMember，否则管理员重复保存同一个项目时会收到
     * A0401「该成员已参与此项目」——那是「显式添加」与「保证在里面」两种语义被混用了。
     *
     * @param projectAddRequest 新增请求，不允许为 null；title、description、leaderId 必填
     * @return 新项目 ID（19 位 Long，接口层序列化成字符串以防前端丢精度）
     * @throws BusinessException 参数不合法、状态不在枚举内、技术栈超长（A0401）；
     *         队长不存在或已逻辑删除（A0402）
     */
    long addProject(ProjectAddRequest projectAddRequest);

    /**
     * 更新项目（部分更新：字段为 null 表示不修改；传了 leaderId 即换队长）
     *
     * 换队长时只 ensure 新队长进关联表，不移除旧队长——卸任不等于退出项目，
     * 自动踢人等于用代码替管理员做业务决定。真要移除由管理员走解绑接口，
     * 而解绑守卫保证「当前队长不可被移除」。
     *
     * 坑：techStack 传空数组时要序列化成 "[]" 而不是 null。转成 null 会被 updateById 跳过，
     * 结果是「清空技术栈」永远不生效且不报错。
     *
     * @param projectUpdateRequest 更新请求，不允许为 null；id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功；项目不存在时抛异常而不是返回 false
     * @throws BusinessException 参数不合法、状态非法、技术栈超长（A0401）；项目或队长不存在（A0402）
     */
    boolean updateProject(ProjectUpdateRequest projectUpdateRequest);

    /**
     * 删除项目（逻辑删除，并在同一事务内清理关联表）
     *
     * 顺序是先清关联、再删主表：反过来写的话，主表删掉、清理失败就会留下悬空关联；
     * 而现在的顺序下，第二步失败会让整个事务回滚，不会出现「项目还在、成员关联却没了」。
     *
     * @param id 项目 ID，必须大于 0
     * @return true 表示删除成功；只代表 UPDATE 命中了行，数据并没有被真的抹掉
     * @throws BusinessException 参数不合法（A0401）、项目不存在（A0402）
     */
    boolean deleteProject(long id);

    /**
     * 按 id 查项目详情（管理端视图）
     *
     * 查不到时抛 A0402 而不是返回 null：返回 null 会让前端收到「code=0 但 data 为空」，
     * 分不清是「确实没有」还是「接口出错了」。
     *
     * 坑：这个 VO 带 content 正文大字段，只能用于单条详情，拿它当列表元素会让每行都回传整篇正文。
     *
     * @param id 项目 ID，必须大于 0
     * @return 含 content、leaderId、sortOrder 与审计字段的项目信息，永不为 null
     * @throws BusinessException id 不合法（A0401）、项目不存在或已逻辑删除（A0402）
     */
    ProjectVO getProjectById(long id);

    /**
     * 管理端分页查询（筛选：title 模糊 / status 精确 / leaderId 精确）
     *
     * 分页参数做兜底纠正而不报错，但 status 是闭集，非法值必须报 A0401——
     * 静默返回空列表会把排查方向带偏。
     *
     * 坑：默认排序 sort_order DESC → created_at DESC → id DESC，与官网轮播口径一致。
     * 只改这里的排序而不改官网，后台显示的排名就会是假的。排序字段还必须走白名单。
     *
     * @param projectQueryRequest 查询条件，允许整体为 null（等价于全部、第一页）
     * @return 分页结果，记录为 ProjectVO；无数据时 records 为空列表、total 为 0
     * @throws BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest);

    // ==================== C 端（游客可访问）====================

    /**
     * C 端分页查询项目列表（筛选：title 模糊 / status 精确；忽略排序参数）
     *
     * 返回的 ProjectFrontVO 结构上没有 content 字段：它是 Markdown 正文大字段，
     * 列表带上会让每行都回传整篇正文。排序刻意不接请求参数——官网顺序属于产品定义。
     *
     * 坑：这个接口匿名可访问，pageSize 必须有上限。
     * 无上限的列表接口等于把「拉全表」的能力开放给任何人不限次数使用。
     *
     * @param projectQueryRequest 查询条件，允许为空
     * @return 分页结果（pageSize 上限 50），记录为脱敏 VO
     * @throws BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest);

    /**
     * C 端查询项目详情（含 content 正文与参与成员）
     *
     * 详情单独一个 VO，而不是给列表 VO 加一个「要不要正文」的开关：开关式设计会让同一个接口
     * 在不同参数下返回结构不同的数据，前端的类型定义、缓存、监控都会跟着变复杂。
     *
     * 坑：装配成员是固定 2 次查询，不是 N+1。若改成「循环里查成员」，
     * 这个接口的耗时就会随成员数线性增长。
     *
     * @param id 项目 ID，必须大于 0
     * @return 脱敏详情 VO（列表字段 + content + 参与成员），永不为 null
     * @throws BusinessException id 不合法（A0401）、项目不存在（A0402）
     */
    ProjectFrontDetailVO getFrontProjectById(long id);

    /**
     * 实体转管理端 VO（含 tech_stack 解析）
     *
     * 坑：BeanUtils 对「名字相同、类型不同」的属性会静默跳过，所以 techStack 必须排除后手动赋值。
     * 漏掉排除参数的话，接口返回的 techStack 会永远是 null，而且一条异常都没有。
     *
     * @param project 项目实体，允许为 null
     * @return VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectVO getProjectVO(StudioProject project);

    /**
     * 实体转 C 端列表 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段）
     *
     * 脱敏靠「目标 VO 结构上没有这些字段」实现，结构上不存在的东西不会被拷出去。
     * 若有人给 ProjectFrontVO 加上 content，脱敏会在零代码改动的情况下失效。
     *
     * @param project 项目实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectFrontVO getProjectFrontVO(StudioProject project);
}
