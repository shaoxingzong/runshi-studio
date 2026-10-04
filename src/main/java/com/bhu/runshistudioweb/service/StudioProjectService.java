package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.project.ProjectAddRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectQueryRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.vo.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;

/**
 * 项目案例服务接口
 *
 * author: shaoshing
 *
 * <p>与成员/证书模块同一套约定：参数不合法或业务不允许时抛 {@code BusinessException}，
 * 不返回错误码、不返回 null。
 *
 * <p><b>本模块独有的两个考点</b>：
 * <ol>
 *     <li><b>leader_id 是权威源，写入时必须同步关联表</b>（DESIGN 2.3）：
 *     {@link #addProject} 与 {@link #updateProject}（换队长时）都要在同一事务内
 *     调 {@code MemberProjectService#ensureMemberInProject}，
 *     保证「队长必然出现在参与成员列表中」。用<b>幂等的 ensure</b> 而不是 bind——
 *     重复保存项目是正常路径，不该报 A0401；</li>
 *     <li><b>tech_stack 的 JSON 边界</b>：数据库存 varchar(256) 快照，接口对外是
 *     {@code List<String>}。序列化后超过 256 必须抛 A0401，
 *     否则会撞数据库的字符串截断错误；读回时解析失败返回空列表，
 *     绝不因为一条脏数据让列表接口 500。</li>
 * </ol>
 *
 * <p><b>事务边界</b>（DESIGN 2.2 / 2.3）：{@link #addProject}、{@link #updateProject}、
 * {@link #deleteProject} 都是「主表写 + 关联表写」两次写，必须在同一事务内，
 * 否则会出现「有项目没队长」或「项目已删、关联还在」的悬空状态。
 */
public interface StudioProjectService extends IService<StudioProject> {

    // ==================== 管理端 ====================

    /**
     * 新增项目（<b>同一事务内把队长同步进关联表</b>）
     *
     * @param projectAddRequest 新增请求（title / description / leaderId 必填）
     * @return 新项目 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     *         队长不存在或已逻辑删除（A0402）、技术栈超长（A0401）时抛出
     */
    long addProject(ProjectAddRequest projectAddRequest);

    /**
     * 更新项目（部分更新：字段为 null 表示不修改；<b>传了 leaderId 即换队长</b>）
     *
     * <p>换队长时只 ensure 新队长进关联表，<b>不移除旧队长</b>：卸任 ≠ 退出项目
     * （他大概率仍在参与），真要移除由管理员调解绑接口，
     * 而解绑守卫保证「当前队长不可被移除」。
     *
     * @param projectUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、项目不存在（A0402）、
     *         队长不存在（A0402）、技术栈超长（A0401）时抛出
     */
    boolean updateProject(ProjectUpdateRequest projectUpdateRequest);

    /**
     * 删除项目（逻辑删除 + <b>同一事务内清理关联表</b>）
     *
     * @param id 项目 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、项目不存在（A0402）时抛出
     */
    boolean deleteProject(long id);

    /**
     * 按 id 查项目详情（管理端视图）
     *
     * @param id 项目 ID
     * @return 含 content / leaderId / sortOrder / 审计字段的项目信息
     * @throws com.bhu.runshistudioweb.exception.BusinessException 项目不存在时抛出（A0402）
     */
    ProjectVO getProjectById(long id);

    /**
     * 管理端分页查询（筛选：title 模糊 / status 精确 / leaderId 精确）
     *
     * <p>默认排序 {@code sort_order DESC → created_at DESC → id DESC}；排序字段走白名单。
     *
     * @param projectQueryRequest 查询条件，允许整体为 null
     * @return 分页结果，记录为 {@link ProjectVO}
     */
    Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest);

    // ==================== C 端（游客可访问） ====================

    /**
     * C 端分页查询项目列表（筛选：title 模糊 / status 精确；<b>忽略排序参数</b>）
     *
     * <p>返回的 {@link ProjectFrontVO} <b>不含 content</b>——它是 text 大字段，
     * 列表带上它会让每行都回传整篇正文。
     *
     * @param projectQueryRequest 查询条件，允许为空
     * @return 分页结果（pageSize 上限 50）
     */
    Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest);

    /**
     * C 端查询项目详情（含 {@code content} 正文）
     *
     * @param id 项目 ID
     * @return 脱敏详情 VO（列表字段 + content）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 项目不存在时抛出（A0402）
     */
    ProjectFrontDetailVO getFrontProjectById(long id);

    /**
     * 实体转管理端 VO（含 tech_stack 解析）
     *
     * @param project 项目实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    ProjectVO getProjectVO(StudioProject project);

    /**
     * 实体转 C 端列表 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段）
     *
     * @param project 项目实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null
     */
    ProjectFrontVO getProjectFrontVO(StudioProject project);
}
