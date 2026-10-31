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
 * @author shaoshing
 */
public interface StudioProjectService extends IService<StudioProject> {

    // 管理端

    /**
     * 新增项目，并在同一事务内把队长同步进关联表
     *
     * @param projectAddRequest 新增请求，不允许为 null；title、description、leaderId 必填
     * @return 新项目 ID（19 位 Long，接口层序列化成字符串以防前端丢精度）
     * @throws BusinessException 参数不合法、状态不在枚举内、技术栈超长（A0401）；队长不存在或已逻辑删除（A0402）
     */
    long addProject(ProjectAddRequest projectAddRequest);

    /**
     * 更新项目（部分更新：字段为 null 表示不修改；传了 leaderId 即换队长）
     *
     * @param projectUpdateRequest 更新请求，不允许为 null；id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功；项目不存在时抛异常而不是返回 false
     * @throws BusinessException 参数不合法、状态非法、技术栈超长（A0401）；项目或队长不存在（A0402）
     */
    boolean updateProject(ProjectUpdateRequest projectUpdateRequest);

    /**
     * 删除项目（逻辑删除，并在同一事务内清理关联表）
     *
     * @param id 项目 ID，必须大于 0
     * @return true 表示删除成功；只代表 UPDATE 命中了行，数据并没有被真的抹掉
     * @throws BusinessException 参数不合法（A0401）、项目不存在（A0402）
     */
    boolean deleteProject(long id);

    /**
     * 按 id 查项目详情（管理端视图）
     *
     * @param id 项目 ID，必须大于 0
     * @return 含 content、leaderId、sortOrder 与审计字段的项目信息，永不为 null
     * @throws BusinessException id 不合法（A0401）、项目不存在或已逻辑删除（A0402）
     */
    ProjectVO getProjectById(long id);

    /**
     * 管理端分页查询（筛选：title 模糊 / status 精确 / leaderId 精确）
     *
     * @param projectQueryRequest 查询条件，允许整体为 null（等价于全部、第一页）
     * @return 分页结果，记录为 ProjectVO；无数据时 records 为空列表、total 为 0
     * @throws BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest);

    // C 端（游客可访问）

    /**
     * C 端分页查询项目列表（筛选：title 模糊 / status 精确；忽略排序参数）
     *
     * @param projectQueryRequest 查询条件，允许为空
     * @return 分页结果（pageSize 上限 50），记录为脱敏 VO
     * @throws BusinessException status 不在枚举内时抛出（A0401）
     */
    Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest);

    /**
     * C 端查询项目详情（含 content 正文与参与成员）
     *
     * @param id 项目 ID，必须大于 0
     * @return 脱敏详情 VO（列表字段 + content + 参与成员），永不为 null
     * @throws BusinessException id 不合法（A0401）、项目不存在（A0402）
     */
    ProjectFrontDetailVO getFrontProjectById(long id);

    /**
     * 实体转管理端 VO（含 tech_stack 解析）
     *
     * @param project 项目实体，允许为 null
     * @return VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectVO getProjectVO(StudioProject project);

    /**
     * 实体转 C 端列表 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段）
     *
     * @param project 项目实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null，调用方需要判空
     */
    ProjectFrontVO getProjectFrontVO(StudioProject project);
}
