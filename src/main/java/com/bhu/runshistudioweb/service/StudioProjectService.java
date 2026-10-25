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
 * 不返回错误码、不返回 null。这样 Controller 里就没有 if-else 分支，
 * 所有失败路径都由全局异常处理器统一转成标准响应。
 *
 * 本模块独有的两个考点：
 * - leader_id 是队长唯一的权威数据源，写入时必须同步关联表：
 * addProject 与 updateProject（换队长时）都要在同一事务内
 * 调 MemberProjectService#ensureMemberInProject，
 * 保证「队长必然出现在参与成员列表中」。用幂等的 ensure 而不是 bind——
 * 重复保存项目是正常路径，不该报 A0401；
 * - tech_stack 的 JSON 边界：数据库存 varchar(256) 快照，接口对外是
 * List<String>。序列化后超过 256 必须抛 A0401，
 * 否则会撞数据库的字符串截断错误；读回时解析失败返回空列表，
 * 绝不因为一条脏数据让列表接口 500。
 *
 * 事务边界：addProject、updateProject、deleteProject 都是
 * 「主表写 + 关联表写」两次写，必须在同一事务内，
 * 否则会出现「有项目没队长」或「项目已删、关联还在」的悬空状态。
 * 接口本身不加注解，注解写在实现类的方法上——Spring 的 @Transactional
 * 是运行期由代理织入的，放在接口上对基于类代理的配置不生效。
 *
 * @author shaoshing
 */
public interface StudioProjectService extends IService<StudioProject> {

    // ==================== 管理端 ====================

    /**
     * 新增项目，并在同一事务内把队长同步进关联表
     *
     * <p>为什么这样设计：项目主表只记一个 leader_id，而官网要展示参与成员，
     * 名单存在关联表里。两次写必须同生共死——否则会出现「项目建好了、队长却没绑上」
     * 的半成品，而且不会报任何错。这里不用「先写主表、失败再补偿」的方案，
     * 因为补偿逻辑本身也会失败，把问题推得更远。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验必填项（title / description / leaderId）与状态取值，
     *         校验必须排在写库之前，才能给出可读的参数提示；</li>
     *     <li>② 落库主表；</li>
     *     <li>③ 用 ensure 把队长写进关联表——必须排在落库之后，
     *         因为关联表要写项目 ID，而这个 ID 是插入那一刻才生成的雪花 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步若换成 bindMember，管理员重复保存同一个项目时
     * 会收到 A0401「该成员已参与此项目」。它看起来像 bug，
     * 实际是「显式添加」与「保证在里面」两种语义被混用了。
     *
     * @param projectAddRequest 新增请求，不允许为 null；title、description、leaderId 必填
     * @return 新项目 ID（19 位 Long，接口层序列化成字符串以防前端丢精度）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、状态不在枚举内、
     * 技术栈超长时抛出（A0401）；队长不存在或已逻辑删除时抛出（A0402）
     */
    long addProject(ProjectAddRequest projectAddRequest);

    /**
     * 更新项目（部分更新：字段为 null 表示不修改；传了 leaderId 即换队长）
     *
     * <p>为什么这样设计：换队长时只 ensure 新队长进关联表，不移除旧队长——
     * 卸任不等于退出项目，他大概率仍在参与。自动踢人等于用代码替管理员做业务决定；
     * 真要移除由管理员走解绑接口，而解绑守卫保证「当前队长不可被移除」。
     * 另外更新字段是服务端写死的白名单，不做「DTO 转实体整体 updateById」，
     * 否则前端能顺手改 deletedAt 与审计字段。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先查一次确认项目存在：updateById 失败只返回 false，
     *         前端分不清「不存在」与「没改成」；</li>
     *     <li>② 校验传了的字段（null 表示不修改，空串表示想清空 → 拒绝），
     *         校验全部排在写库之前；</li>
     *     <li>③ updateById 写入白名单字段；</li>
     *     <li>④ 传了 leaderId 才 ensure 新队长，必须排在写主表之后，
     *         因为这一步是在「项目已经存在且状态正确」的前提下做的同步。</li>
     * </ol>
     *
     * <p>容易踩的坑：techStack 传空数组时要序列化成 "[]" 而不是 null。
     * 转成 null 会被 updateById 跳过，结果是「清空技术栈」永远不生效且不报错。
     *
     * @param projectUpdateRequest 更新请求，不允许为 null；id 必填，
     *                             其余字段为 null 表示保持原值
     * @return true 表示更新成功；项目不存在时抛异常而不是返回 false
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、状态非法、
     * 技术栈超长时抛出（A0401）；项目不存在、队长不存在时抛出（A0402）
     */
    boolean updateProject(ProjectUpdateRequest projectUpdateRequest);

    /**
     * 删除项目（逻辑删除，并在同一事务内清理关联表）
     *
     * <p>为什么这样设计：顺序是「先清关联、再删主表」，且两次写必须在同一事务内。
     * 反过来写的话，主表删掉、清理失败就会留下悬空关联；
     * 而现在的顺序下，第二步失败会让整个事务回滚，不会出现
     * 「项目还在、成员关联却没了」的静默数据丢失。
     * 主表用逻辑删除（deleted_at 写时间戳，行仍在库里）保留可追溯性，
     * 关联表用物理删除，因为「谁参与了一个已不存在的项目」没有保留价值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查存在性，不存在给 A0402；</li>
     *     <li>② 清关联表，必须排在删主表之前；</li>
     *     <li>③ 逻辑删除主表，失败会连带回滚第 ② 步。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回 true 只代表「UPDATE 命中了行」，
     * 不代表数据被真的抹掉。想彻底清理要走归档脚本，不要在业务代码里改成物理删除。
     *
     * @param id 项目 ID，必须大于 0
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法时抛出（A0401）；
     * 项目不存在时抛出（A0402）
     */
    boolean deleteProject(long id);

    /**
     * 按 id 查项目详情（管理端视图）
     *
     * <p>为什么这样设计：查不到时抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code=0 但 data 为空」的响应，
     * 它分不清是「确实没有」还是「接口出错了」，只能靠猜决定展示空态还是错误提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 查实体，逻辑删除的项目会被自动过滤；</li>
     *     <li>③ 转成含内部字段的 VO，同时把 tech_stack 解析成 List。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个 VO 带 content 正文大字段，只能用于单条详情。
     * 拿它当列表元素会让每一行都回传整篇 Markdown。
     *
     * @param id 项目 ID，必须大于 0
     * @return 含 content、leaderId、sortOrder 与审计字段的项目信息，永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法（A0401）、
     * 项目不存在或已逻辑删除（A0402）时抛出
     */
    ProjectVO getProjectById(long id);

    /**
     * 管理端分页查询（筛选：title 模糊 / status 精确 / leaderId 精确）
     *
     * <p>为什么这样设计：分页参数做兜底纠正而不报错——页码传错没必要让整个查询失败；
     * 但 status 是闭集，非法值必须报 A0401，静默返回空列表会把排查方向带偏。
     * 排序字段走白名单 switch：ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验状态取值（闭集不静默）；</li>
     *     <li>② 拼筛选条件与应用白名单排序；</li>
     *     <li>③ 分页查询并转 VO，转换时补「首页轮播排名」。</li>
     * </ol>
     *
     * <p>容易踩的坑：默认排序 sort_order DESC → created_at DESC → id DESC，
     * 与官网轮播口径一致，所以「列表顺序 = 首页顺序」。
     * 若只改这里的排序而不改官网，后台显示的排名就会是假的。
     *
     * @param projectQueryRequest 查询条件，允许整体为 null（等价于全部、第一页）
     * @return 分页结果，记录为 ProjectVO；无数据时 records 为空列表、total 为 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest);

    // ==================== C 端（游客可访问）====================

    /**
     * C 端分页查询项目列表（筛选：title 模糊 / status 精确；忽略排序参数）
     *
     * <p>为什么这样设计：返回的 ProjectFrontVO 结构上没有 content 字段。
     * 它是 Markdown 正文大字段，列表带上会让每行都回传整篇正文；
     * 用「VO 里没有这个字段」来脱敏，比「拷完再手动置空」可靠——
     * 以后实体新增敏感字段时，只要不加进 VO 就自动被挡住。
     * 排序也刻意不接请求参数：官网顺序属于产品定义，不能由请求方决定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验状态取值，与后台同一口径；</li>
     *     <li>② 拼筛选条件；</li>
     *     <li>③ 固定排序（置顶权重 → 创建时间 → id 倒序），
     *         最后那个 id 是稳定次序的兜底，缺了它翻页会重复或漏行；</li>
     *     <li>④ 分页查询并转脱敏 VO，pageSize 上限 50。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个接口匿名可访问，pageSize 必须有上限。
     * 无上限的列表接口等于把「拉全表」的能力开放给任何人不限次数使用。
     *
     * @param projectQueryRequest 查询条件，允许为空
     * @return 分页结果（pageSize 上限 50），记录为脱敏 VO
     * @throws com.bhu.runshistudioweb.exception.BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest);

    /**
     * C 端查询项目详情（含 content 正文与参与成员）
     *
     * <p>为什么这样设计：详情单独一个 VO，而不是给列表 VO 加一个
     * 「要不要正文」的开关。开关式的设计会让同一个接口在不同参数下
     * 返回结构不同的数据，前端类型定义、缓存、监控都会跟着变复杂。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查项目本体，不存在直接 A0402；</li>
     *     <li>② 转详情 VO（含 tech_stack 解析）；</li>
     *     <li>③ 装配参与成员，这一步必须排在 VO 转换之后——成员列表挂在 VO 上。</li>
     * </ol>
     *
     * <p>容易踩的坑：装配成员是固定 2 次查询，不是 N+1
     * （N+1 指先查 N 条主记录、再为每条各发一次查询）。
     * 若有人把它改成「循环里查成员」，这个接口的耗时就会随成员数线性增长。
     *
     * @param id 项目 ID，必须大于 0
     * @return 脱敏详情 VO（列表字段 + content + 参与成员），永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法（A0401）、
     * 项目不存在（A0402）时抛出
     */
    ProjectFrontDetailVO getFrontProjectById(long id);

    /**
     * 实体转管理端 VO（含 tech_stack 解析）
     *
     * <p>为什么这样设计：用 BeanUtils 自动拷贝避免漏字段，但它对
     * 「名字相同、类型不同」的属性会静默跳过，所以 techStack 必须排除后手动赋值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null（纯转换方法不制造异常）；</li>
     *     <li>② 自动拷贝并排除 techStack；</li>
     *     <li>③ 手动把 JSON 字符串解析成 List。</li>
     * </ol>
     *
     * <p>容易踩的坑：漏掉排除参数的话，接口返回的 techStack 会永远是 null，
     * 而且一条异常都没有——这是最典型的静默失败。
     *
     * @param project 项目实体，允许为 null
     * @return VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectVO getProjectVO(StudioProject project);

    /**
     * 实体转 C 端列表 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段）
     *
     * <p>为什么这样设计：脱敏靠「目标 VO 结构上没有这些字段」实现，
     * 而不是「拷完再逐个置空」。结构上不存在的东西不会被拷出去，
     * 也就不会因为某次改动忘了一行而泄露。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null；</li>
     *     <li>② 自动拷贝并排除 techStack（类型不一致，会被静默跳过）；</li>
     *     <li>③ 手动解析 tech_stack。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里「什么都没做」就是正确的脱敏。
     * 若有人给 ProjectFrontVO 加上 content，脱敏会在零代码改动的情况下失效。
     *
     * @param project 项目实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectFrontVO getProjectFrontVO(StudioProject project);
}
