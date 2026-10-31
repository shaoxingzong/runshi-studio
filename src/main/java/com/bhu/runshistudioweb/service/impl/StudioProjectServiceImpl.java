package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.bhu.runshistudioweb.model.dto.project.ProjectAddRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectQueryRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.enums.ProjectStatusEnum;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;
import com.bhu.runshistudioweb.service.MemberProjectService;
import com.bhu.runshistudioweb.service.StudioProjectService;
import com.bhu.runshistudioweb.utils.MarkdownSanitizer;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目案例服务实现
 *
 * 继承 ServiceImpl 后单表增删改查已由 MyBatis-Plus 提供，本类只写业务动作：校验、事务编排、VO 转换。
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class StudioProjectServiceImpl extends ServiceImpl<StudioProjectMapper, StudioProject>
        implements StudioProjectService {

    /** 分页每页条数的默认值与上限（上限防「一次拉全表」） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 置顶权重不传时的默认值（0 表示不置顶，与 DDL 默认值一致） */
    private static final int DEFAULT_SORT_ORDER = 0;

    /** 首页轮播展示的项目数 */
    private static final long HOME_SPOTLIGHT_COUNT = 6L;

    /** tech_stack 序列化后的长度上限，与 DDL 的 varchar(256) 对齐。 */
    private static final int TECH_STACK_MAX_LENGTH = 256;

    /** 校验「队长是否存在」需要查 studio_member，故注入它的 Mapper。注意查的是成员主表而不是关联表：leader_id 是权威源 */
    private final StudioMemberMapper studioMemberMapper;

    /** 成员-项目关联服务：负责队长同步与级联清理 */
    private final MemberProjectService memberProjectService;

    /** 用于 tech_stack 的 JSON 序列化 / 反序列化。 */
    private final JsonMapper jsonMapper;

    // 管理端

    /** 新增一个项目案例，并把队长同步进「成员-项目」关联表 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addProject(ProjectAddRequest projectAddRequest) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);

        ThrowUtils.throwIf(StrUtil.isBlank(projectAddRequest.getTitle()),
                ErrorCode.PARAMS_ERROR, "项目名称不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(projectAddRequest.getDescription()),
                ErrorCode.PARAMS_ERROR, "项目摘要不能为空");
        ThrowUtils.throwIf(projectAddRequest.getLeaderId() == null || projectAddRequest.getLeaderId() <= 0,
                ErrorCode.PARAMS_ERROR, "项目队长不能为空");

        // 状态：不传按 DDL 默认值 1（已上线）；传了必须是枚举内的合法取值
        ProjectStatusEnum status = resolveStatus(projectAddRequest.getStatus(), ProjectStatusEnum.ONLINE);
        // 队长必须存在（含「已逻辑删除视为不存在」）：否则会写出一个没有队长的项目
        assertLeaderExists(projectAddRequest.getLeaderId());

        StudioProject project = new StudioProject();
        project.setTitle(projectAddRequest.getTitle().trim());
        project.setDescription(projectAddRequest.getDescription().trim());
        project.setCoverImage(projectAddRequest.getCoverImage());
        project.setContent(MarkdownSanitizer.sanitize(projectAddRequest.getContent()));
        // 序列化 + 长度校验（超长抛 A0401）都在这一行里完成
        project.setTechStack(serializeTechStack(projectAddRequest.getTechStack()));
        project.setDemoUrl(projectAddRequest.getDemoUrl());
        project.setGithubUrl(projectAddRequest.getGithubUrl());
        project.setLeaderId(projectAddRequest.getLeaderId());
        project.setStatus(status.getValue());
        project.setSortOrder(projectAddRequest.getSortOrder() == null
                ? DEFAULT_SORT_ORDER : projectAddRequest.getSortOrder());

        boolean saveResult = this.save(project);
        // save 返回 false 说明没插进去，必须显式判断，否则会把「没存进去」当成新增成功
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增项目失败，数据库异常");

        memberProjectService.ensureMemberInProject(project.getId(), project.getLeaderId());
        return project.getId();
    }

    /** 修改项目（部分更新：字段为 null 表示不修改；传了 leaderId 即换队长） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProject(ProjectUpdateRequest projectUpdateRequest) {
        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        ThrowUtils.throwIf(projectUpdateRequest == null || projectUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        Long id = projectUpdateRequest.getId();

        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        if (projectUpdateRequest.getTitle() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getTitle()),
                    ErrorCode.PARAMS_ERROR, "项目名称不能为空");
        }
        if (projectUpdateRequest.getDescription() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getDescription()),
                    ErrorCode.PARAMS_ERROR, "项目摘要不能为空");
        }

        ProjectStatusEnum status = resolveStatus(projectUpdateRequest.getStatus(), null);

        // 换队长：先确认新队长存在，再在同一事务里同步进关联表（排在 updateById 之后）
        Long newLeaderId = projectUpdateRequest.getLeaderId();
        if (newLeaderId != null) {
            ThrowUtils.throwIf(newLeaderId <= 0, ErrorCode.PARAMS_ERROR, "项目队长 id 不合法");
            assertLeaderExists(newLeaderId);
        }

        StudioProject update = new StudioProject();
        update.setId(id);
        update.setTitle(projectUpdateRequest.getTitle() == null
                ? null : projectUpdateRequest.getTitle().trim());
        update.setDescription(projectUpdateRequest.getDescription() == null
                ? null : projectUpdateRequest.getDescription().trim());
        update.setCoverImage(projectUpdateRequest.getCoverImage());
        // XSS 兜底：与新增同一策略。null 表示「不修改」，sanitize 对 null 原样返回
        update.setContent(MarkdownSanitizer.sanitize(projectUpdateRequest.getContent()));
        update.setTechStack(projectUpdateRequest.getTechStack() == null
                ? null : serializeTechStack(projectUpdateRequest.getTechStack()));
        update.setDemoUrl(projectUpdateRequest.getDemoUrl());
        update.setGithubUrl(projectUpdateRequest.getGithubUrl());
        update.setLeaderId(newLeaderId);
        update.setStatus(status == null ? null : status.getValue());
        update.setSortOrder(projectUpdateRequest.getSortOrder());

        // updateById 默认跳过 null 字段，这正是「传 null 表示不修改」语义成立的基础
        boolean updateResult = this.updateById(update);
        ThrowUtils.throwIf(!updateResult, ErrorCode.SYSTEM_ERROR, "更新项目失败，数据库异常");

        if (newLeaderId != null) {
            memberProjectService.ensureMemberInProject(id, newLeaderId);
        }
        return true;
    }

    /** 删除项目：同一事务内先清关联表，再逻辑删除主表 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProject(long id) {
        // 先查存在性：不存在要给出 A0402，而不是让 removeById 静默返回 false
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        memberProjectService.removeByProjectId(id);

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    /** 按 id 查项目详情（管理端视图，含内部字段） */
    @Override
    public ProjectVO getProjectById(long id) {
        // 0 与负数一定不是合法主键，先挡掉，省一次数据库往返
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // getById 会被 MP 自动追加 deleted_at = 0，已逻辑删除的项目等同不存在
        StudioProject project = this.getById(id);
        // 查不到给明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");
        // 转换时把 tech_stack 从 JSON 字符串解析成 List
        return this.getProjectVO(project);
    }

    /** 管理端项目分页列表（全状态，可按标题、状态、队长筛选） */
    @Override
    public Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest) {
        // 请求为 null 时换一个空对象，后续代码就不用到处判空
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // 状态是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
        resolveStatus(query.getStatus(), null);

        // 筛选条件与排序都收在私有方法里，管理端与 C 端复用同一套语义
        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // searchCount 保持默认 true：分页组件需要 total，关掉它翻页器会显示「共 0 页」
        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectVOPage(entityPage);
    }

    // C 端

    /** 门户项目列表（游客可访问，只含脱敏字段） */
    @Override
    public Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest) {
        // 与后台同一套兜底写法：null 换空对象，避免后续到处判空
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // 与后台同一条口径：状态非法 → A0401（闭集不静默）
        resolveStatus(query.getStatus(), null);

        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(StudioProject::getSortOrder)
                .orderByDesc(StudioProject::getCreatedAt)
                .orderByDesc(StudioProject::getId);

        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectFrontVOPage(entityPage);
    }

    /** 门户项目详情（游客可访问，含 Markdown 正文与参与成员） */
    @Override
    public ProjectFrontDetailVO getFrontProjectById(long id) {
        // 先查项目本体：查不到就没必要再查成员，少一次数据库往返
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject project = this.getById(id);
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // 先转 VO：tech_stack 的 JSON 解析在这一步完成
        ProjectFrontDetailVO detailVO = toProjectFrontDetailVO(project);
        detailVO.setMembers(memberProjectService.listFrontMembersByProject(id));
        return detailVO;
    }

    // VO 转换

    /** 实体转管理端 VO */
    @Override
    public ProjectVO getProjectVO(StudioProject project) {
        // 纯转换方法：入参为 null 就返回 null，不抛异常
        if (project == null) {
            return null;
        }
        ProjectVO projectVO = new ProjectVO();
        BeanUtils.copyProperties(project, projectVO, "techStack");
        // 解析失败时 parseTechStack 返回空列表，不会让整条记录转换失败
        projectVO.setTechStack(parseTechStack(project.getTechStack()));
        return projectVO;
    }

    /** 实体转门户脱敏 VO */
    @Override
    public ProjectFrontVO getProjectFrontVO(StudioProject project) {
        // 纯转换方法：入参为 null 就返回 null
        if (project == null) {
            return null;
        }
        ProjectFrontVO frontVO = new ProjectFrontVO();
        BeanUtils.copyProperties(project, frontVO, "techStack");
        // techStack 类型不一致，只能手动赋值
        frontVO.setTechStack(parseTechStack(project.getTechStack()));
        return frontVO;
    }

    // 私有工具方法

    /** 实体转 C 端详情 VO（列表字段 + content） */
    private ProjectFrontDetailVO toProjectFrontDetailVO(StudioProject project) {
        ProjectFrontDetailVO detailVO = new ProjectFrontDetailVO();
        // 同样要排除 techStack：实体是 String、VO 是 List，类型不一致会被静默跳过
        BeanUtils.copyProperties(project, detailVO, "techStack");
        detailVO.setTechStack(parseTechStack(project.getTechStack()));
        return detailVO;
    }

    /** 构建筛选条件（管理端与 C 端共用，保证两边筛选语义完全一致） */
    private LambdaQueryWrapper<StudioProject> buildQueryWrapper(ProjectQueryRequest query) {
        LambdaQueryWrapper<StudioProject> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioProject::getId, query.getId());
        wrapper.like(StrUtil.isNotBlank(query.getTitle()), StudioProject::getTitle, query.getTitle());
        // 精确匹配：status 走 idx_status_sort_time 最左前缀，leaderId 走 idx_leader
        wrapper.eq(query.getStatus() != null, StudioProject::getStatus, query.getStatus());
        wrapper.eq(query.getLeaderId() != null, StudioProject::getLeaderId, query.getLeaderId());
        return wrapper;
    }

    /** 应用排序规则（白名单映射，仅管理端调用） */
    private void applySort(LambdaQueryWrapper<StudioProject> wrapper, String sortField, String sortOrder) {
        // 兼容前端常见的 "ascend / descend" 与后端常见的 "asc / desc" 两种写法
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder) || "ascend".equalsIgnoreCase(sortOrder);
        switch (sortField == null ? "" : sortField) {
            // 每个分支都追加 id 倒序：单个排序键相同的行很多，缺少稳定次序时翻页会乱
            case "id" -> wrapper.orderBy(true, isAsc, StudioProject::getId);
            case "title" -> wrapper.orderBy(true, isAsc, StudioProject::getTitle)
                    .orderByDesc(StudioProject::getId);
            case "status" -> wrapper.orderBy(true, isAsc, StudioProject::getStatus)
                    .orderByDesc(StudioProject::getSortOrder)
                    .orderByDesc(StudioProject::getId);
            case "createdAt" -> wrapper.orderBy(true, isAsc, StudioProject::getCreatedAt)
                    .orderByDesc(StudioProject::getId);
            case "sortOrder" -> wrapper.orderBy(true, isAsc, StudioProject::getSortOrder)
                    .orderByDesc(StudioProject::getCreatedAt)
                    .orderByDesc(StudioProject::getId);
            default -> wrapper.orderByDesc(StudioProject::getSortOrder)
                    .orderByDesc(StudioProject::getCreatedAt)
                    .orderByDesc(StudioProject::getId);
        }
    }

    /** 构造分页对象：页码与每页条数兜底纠正（管理端与 C 端共用同一套规则） */
    private Page<StudioProject> newPageWithDefaults(ProjectQueryRequest query) {
        // 页码小于 1 一律按 1：负数页码在 SQL 里是 OFFSET 负数，数据库会直接报错
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 用 Math.min 收住上限，而不是抛异常：这是「防刷」而不是「纠错」，没必要报错
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, pageSize);
    }

    /**
     * 解析并校验项目状态
     *
     * 非法值必须在入口拦住：一旦入库，官网按状态筛选就会静默漏数据，只能靠人肉比对发现。
     */
    private ProjectStatusEnum resolveStatus(Integer status, ProjectStatusEnum defaultStatus) {
        if (status == null) {
            return defaultStatus;
        }
        ProjectStatusEnum resolved = ProjectStatusEnum.of(status);
        // 非法值一旦入库，官网按状态筛选就会静默漏数据，所以必须在入口拦住并列出合法取值
        ThrowUtils.throwIf(resolved == null, ErrorCode.PARAMS_ERROR,
                "项目状态不合法，仅支持 " + ProjectStatusEnum.valuesText());
        return resolved;
    }

    /** 校验队长存在（含「已逻辑删除视为不存在」） */
    private void assertLeaderExists(Long leaderId) {
        ThrowUtils.throwIf(studioMemberMapper.selectById(leaderId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目队长不存在");
    }

    /** 序列化技术栈标签为 JSON 快照，并校验长度不超过列宽 */
    private String serializeTechStack(List<String> techStack) {
        // null 与空列表必须区分开：null 是「别动这个字段」，空列表是「清空」
        if (techStack == null) {
            return null;
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(techStack);
        } catch (Exception e) {
            // 理论上不会发生（List<String> 一定能序列化），兜住避免变成 500
            throw new com.bhu.runshistudioweb.exception.BusinessException(
                    ErrorCode.PARAMS_ERROR, "技术栈标签格式不正确");
        }
        ThrowUtils.throwIf(json.length() > TECH_STACK_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "技术栈标签过长或过多");
        return json;
    }

    /** 解析 tech_stack 的 JSON 快照 */
    private List<String> parseTechStack(String techStackJson) {
        // 空串与 null 都按「没有标签」处理，省掉一次 JSON 解析
        if (StrUtil.isBlank(techStackJson)) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(techStackJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 吞掉异常是有意的：读路径的容错优先级高于「把坏数据暴露出来」
            return List.of();
        }
    }

    /** 实体分页结果转管理端 VO 分页结果 */
    private Page<ProjectVO> toProjectVOPage(Page<StudioProject> entityPage) {
        Page<ProjectVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getProjectVO).toList());
        // 补「首页轮播排名」：列表顺序恰好与官网轮播口径一致，运营看到的名次就是首页上的名次
        fillHomeRank(voPage.getRecords());
        return voPage;
    }

    /** 给管理端 VO 补「首页轮播排名」 */
    private void fillHomeRank(List<ProjectVO> records) {
        // 空页直接返回：没记录就不必多发一次查询
        if (records == null || records.isEmpty()) {
            return;
        }
        List<StudioProject> top = this.page(new Page<>(1, HOME_SPOTLIGHT_COUNT),
                new LambdaQueryWrapper<StudioProject>()
                        .orderByDesc(StudioProject::getSortOrder)
                        .orderByDesc(StudioProject::getCreatedAt)
                        .orderByDesc(StudioProject::getId)).getRecords();

        // 先建「ID → 名次」映射：本页记录要按 ID 反查名次，避免双层循环
        Map<Long, Integer> rankOfId = new HashMap<>();
        for (int i = 0; i < top.size(); i++) {
            rankOfId.put(top.get(i).getId(), i + 1);
        }
        // 不在前 6 名里的记录会拿到 null，前端据此不显示「首页 N」角标
        records.forEach(vo -> vo.setHomeRank(rankOfId.get(vo.getId())));
    }

    /** 实体分页结果转 C 端 VO 分页结果（转的是脱敏 VO，不是管理端 VO） */
    private Page<ProjectFrontVO> toProjectFrontVOPage(Page<StudioProject> entityPage) {
        Page<ProjectFrontVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getProjectFrontVO).toList());
        return voPage;
    }
}
