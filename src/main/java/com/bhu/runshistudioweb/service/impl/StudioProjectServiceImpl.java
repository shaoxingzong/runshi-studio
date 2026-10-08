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
import com.bhu.runshistudioweb.model.vo.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;
import com.bhu.runshistudioweb.service.MemberProjectService;
import com.bhu.runshistudioweb.service.StudioProjectService;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 项目案例服务实现
 *
 * author: shaoshing
 *
 * <p><b>三处必须想清楚的地方（本模块的核心）</b>：
 * <ol>
 *     <li><b>队长同步用 ensure，不用 bind</b>：{@link #addProject} 与 {@link #updateProject}
 *     调的是幂等的 {@code ensureMemberInProject}——「已存在」直接返回、并发撞唯一索引静默成功。
 *     若误用 {@code bindMember}（重复报 A0401），管理员每次重复保存项目都会收到
 *     「该成员已参与此项目」，而这一切看起来像 bug 实则是设计错误；</li>
 *     <li><b>事务边界</b>：新增 / 更新（换队长）/ 删除都是「主表写 + 关联表写」两次写，
 *     统一加 {@code @Transactional(rollbackFor = Exception.class)}。
 *     分属两个事务的话，会出现「有项目没队长」或「项目已删、关联还在」的悬空状态；
 *     {@code rollbackFor} 必须写 Exception：Spring 默认只回滚 RuntimeException；</li>
 *     <li><b>tech_stack 的 JSON 边界</b>：库里是 varchar(256) 快照，接口层是 List。
 *     写：序列化后超长抛 A0401（否则撞数据库截断错误）；
 *     读：解析失败返回空列表（一条脏数据不能让整个列表 500）。</li>
 * </ol>
 *
 * <p><b>依赖方向</b>：本类 → {@code MemberProjectService}（队长同步 + 级联清理）、
 * {@code StudioMemberMapper}（校验队长存在）。反方向不存在依赖
 * （{@code MemberProjectServiceImpl} 只注入 Mapper），因此不会循环依赖。
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

    /**
     * tech_stack 序列化后的长度上限，与 DDL 的 {@code varchar(256)} 对齐。
     * 不改数据库类型而是先在这里拦住，是因为「标签太多」是入参问题，
     * 应该返回 A0401 让前端提示，而不是让数据库抛截断 / 报错
     */
    private static final int TECH_STACK_MAX_LENGTH = 256;

    /**
     * 校验「队长是否存在」需要查 studio_member，故注入它的 Mapper。
     * 注意查的是成员主表而不是关联表：{@code leader_id} 是权威源
     */
    private final StudioMemberMapper studioMemberMapper;

    /**
     * 成员-项目关联服务：负责队长同步与级联清理
     * <p>依赖方向单向（本类 → 关联服务），不构成循环依赖
     */
    private final MemberProjectService memberProjectService;

    /**
     * 用于 tech_stack 的 JSON 序列化 / 反序列化。
     * <p>用容器里的实例（Boot 4 自动配置 + JsonConfig 定制），不自己 new
     */
    private final JsonMapper jsonMapper;

    // ==================== 管理端 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addProject(ProjectAddRequest projectAddRequest) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);

        // title / description 是 NOT NULL 列，必须在入口拦住；
        // trim 后再判空，避免「   」这种全空格的值入库
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
        project.setContent(projectAddRequest.getContent());
        // 序列化 + 长度校验（超长抛 A0401）都在这里完成
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

        // 关键：同一事务内把队长同步进关联表（DESIGN 2.3），
        // 保证「队长必然出现在参与成员列表中」。
        // 用幂等的 ensure 而不是 bind：重复保存是正常路径，不该报冲突
        memberProjectService.ensureMemberInProject(project.getId(), project.getLeaderId());
        return project.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProject(ProjectUpdateRequest projectUpdateRequest) {
        ThrowUtils.throwIf(projectUpdateRequest == null || projectUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        Long id = projectUpdateRequest.getId();

        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // 传了就必须有实际内容（null 表示不修改，空串表示想清空——后者不允许）：
        // 否则官网会出现「没有名字的项目」
        if (projectUpdateRequest.getTitle() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getTitle()),
                    ErrorCode.PARAMS_ERROR, "项目名称不能为空");
        }
        if (projectUpdateRequest.getDescription() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getDescription()),
                    ErrorCode.PARAMS_ERROR, "项目摘要不能为空");
        }

        // 状态：传了才校验（非必填时兜底值传 null，表示「不修改」）
        ProjectStatusEnum status = resolveStatus(projectUpdateRequest.getStatus(), null);

        // 换队长：先确认新队长存在，再在同一事务里同步进关联表（下面 updateById 之后）
        Long newLeaderId = projectUpdateRequest.getLeaderId();
        if (newLeaderId != null) {
            ThrowUtils.throwIf(newLeaderId <= 0, ErrorCode.PARAMS_ERROR, "项目队长 id 不合法");
            assertLeaderExists(newLeaderId);
        }

        // 只 set 允许修改的字段：这是服务端写死的白名单，
        // 绝不能直接把 DTO 转成实体 updateById（那样前端能改 deletedAt、审计字段）
        StudioProject update = new StudioProject();
        update.setId(id);
        update.setTitle(projectUpdateRequest.getTitle() == null
                ? null : projectUpdateRequest.getTitle().trim());
        update.setDescription(projectUpdateRequest.getDescription() == null
                ? null : projectUpdateRequest.getDescription().trim());
        update.setCoverImage(projectUpdateRequest.getCoverImage());
        update.setContent(projectUpdateRequest.getContent());
        // techStack 传了即整体替换快照；传空数组表示清空（序列化成 "[]"，
        // 不能用 null——updateById 会跳过 null 字段，等于「清不掉」）
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

        // 换队长：同一事务内 ensure 新队长进关联表。
        // 注意**不**自动移除旧队长——卸任 ≠ 退出项目（他大概率仍在参与），
        // 真要移除由管理员调解绑接口，而解绑守卫保证「当前队长不可被移除」
        if (newLeaderId != null) {
            memberProjectService.ensureMemberInProject(id, newLeaderId);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProject(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // 同一事务内先清关联，再逻辑删除主表（DESIGN 2.2）：
        // 反过来的话，主表删除失败就会留下「项目还在、成员关联却没了」的静默数据丢失
        memberProjectService.removeByProjectId(id);

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    @Override
    public ProjectVO getProjectById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject project = this.getById(id);
        // 查不到给明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");
        return this.getProjectVO(project);
    }

    @Override
    public Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest) {
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // 状态是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
        resolveStatus(query.getStatus(), null);

        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectVOPage(entityPage);
    }

    // ==================== C 端 ====================

    @Override
    public Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest) {
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // 与后台同一条口径：状态非法 → A0401（闭集不静默）
        resolveStatus(query.getStatus(), null);

        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        // 公开接口固定排序：置顶权重倒序 → 创建时间倒序 → id 倒序兜底稳定次序。
        // 刻意不接 sortField/sortOrder：排序规则由产品定义，不由请求方决定
        wrapper.orderByDesc(StudioProject::getSortOrder)
                .orderByDesc(StudioProject::getCreatedAt)
                .orderByDesc(StudioProject::getId);

        // searchCount 保持默认 true（分页组件需要 total）；
        // pageSize 上限 50 由 newPageWithDefaults 收敛，防匿名接口拉全表
        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectFrontVOPage(entityPage);
    }

    @Override
    public ProjectFrontDetailVO getFrontProjectById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject project = this.getById(id);
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        ProjectFrontDetailVO detailVO = toProjectFrontDetailVO(project);
        // 装配参与成员：只有 2 次查询（项目本体 + 关联表两步查询），没有 N+1。
        // 队长必然在结果里——它是 leader_id 同步不变量，所以官网不用再单独渲染一个「队长」头像
        detailVO.setMembers(memberProjectService.listFrontMembersByProject(id));
        return detailVO;
    }

    // ==================== VO 转换 ====================

    @Override
    public ProjectVO getProjectVO(StudioProject project) {
        if (project == null) {
            return null;
        }
        ProjectVO projectVO = new ProjectVO();
        // techStack 在实体里是 String、在 VO 里是 List，BeanUtils 对类型不一致的属性会静默跳过，
        // 因此先排除它再手动赋值——否则接口返回的 techStack 永远是 null 且不报错
        BeanUtils.copyProperties(project, projectVO, "techStack");
        projectVO.setTechStack(parseTechStack(project.getTechStack()));
        return projectVO;
    }

    @Override
    public ProjectFrontVO getProjectFrontVO(StudioProject project) {
        if (project == null) {
            return null;
        }
        ProjectFrontVO frontVO = new ProjectFrontVO();
        // 脱敏靠「目标 VO 没有这些字段」实现：content / leaderId / sortOrder / 审计字段
        // 结构上就不可能被拷贝出去
        BeanUtils.copyProperties(project, frontVO, "techStack");
        frontVO.setTechStack(parseTechStack(project.getTechStack()));
        return frontVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 实体转 C 端详情 VO（列表字段 + content）
     *
     * @param project 项目实体
     * @return 详情 VO
     */
    private ProjectFrontDetailVO toProjectFrontDetailVO(StudioProject project) {
        ProjectFrontDetailVO detailVO = new ProjectFrontDetailVO();
        BeanUtils.copyProperties(project, detailVO, "techStack");
        detailVO.setTechStack(parseTechStack(project.getTechStack()));
        return detailVO;
    }

    /**
     * 构建筛选条件（管理端与 C 端共用，保证两边筛选语义完全一致）
     *
     * <p>刻意<b>不支持</b> techStack 筛选：它是 JSON 字符串列，
     * 按它筛选只能写 {@code LIKE '%Java%'}，既不走索引又会误匹配（命中 JavaScript）。
     * DESIGN 3.3 的取舍是「不筛、不建索引」；将来要按技术栈筛选应拆标签表。
     *
     * @param query 查询条件
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioProject> buildQueryWrapper(ProjectQueryRequest query) {
        LambdaQueryWrapper<StudioProject> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioProject::getId, query.getId());
        // 模糊查询：MP 用占位符传参，不存在 SQL 注入；
        // 用户输入的 % 与 _ 仍会被当作通配符（LIKE 的固有语义，这里不额外转义）
        wrapper.like(StrUtil.isNotBlank(query.getTitle()), StudioProject::getTitle, query.getTitle());
        // 精确匹配：status 走 idx_status_sort_time 最左前缀，leaderId 走 idx_leader
        wrapper.eq(query.getStatus() != null, StudioProject::getStatus, query.getStatus());
        wrapper.eq(query.getLeaderId() != null, StudioProject::getLeaderId, query.getLeaderId());
        return wrapper;
    }

    /**
     * 应用排序规则（白名单映射，仅管理端调用）
     *
     * @param wrapper   查询包装类
     * @param sortField 排序字段名，可为空
     * @param sortOrder 排序方向 asc / desc，可为空
     */
    private void applySort(LambdaQueryWrapper<StudioProject> wrapper, String sortField, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder) || "ascend".equalsIgnoreCase(sortOrder);
        switch (sortField == null ? "" : sortField) {
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
            // 默认排序：置顶权重倒序 → 创建时间倒序 → id 倒序。
            // 与 idx_status_sort_time (status, sort_order DESC, created_at DESC) 同向，
            // 按状态筛选时索引能直接提供有序结果
            default -> wrapper.orderByDesc(StudioProject::getSortOrder)
                    .orderByDesc(StudioProject::getCreatedAt)
                    .orderByDesc(StudioProject::getId);
        }
    }

    /**
     * 构造分页对象：页码与每页条数兜底纠正（管理端与 C 端共用同一套规则）
     *
     * @param query 查询条件
     * @return 分页对象（searchCount 保持默认 true）
     */
    private Page<StudioProject> newPageWithDefaults(ProjectQueryRequest query) {
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, pageSize);
    }

    /**
     * 解析并校验项目状态
     *
     * @param status        前端传入的状态，可为空
     * @param defaultStatus 为空时的兜底值（新增传 ONLINE，更新传 null 表示不修改）
     * @return 状态枚举；兜底值为 null 且未传时返回 null
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

    /**
     * 校验队长存在（含「已逻辑删除视为不存在」）
     *
     * @param leaderId 队长（成员）ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出（A0402）
     */
    private void assertLeaderExists(Long leaderId) {
        // selectById 会被 MP 自动追加 deleted_at = 0：已注销/已删除的成员同样视为不存在。
        // 这里的提示特意写明「队长」而不是「成员」，因为调用方传的是 leaderId 字段
        ThrowUtils.throwIf(studioMemberMapper.selectById(leaderId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目队长不存在");
    }

    /**
     * 序列化技术栈标签为 JSON 快照，并校验长度不超过列宽
     *
     * @param techStack 标签列表，可为 null
     * @return JSON 字符串；入参为 null 时返回 null（表示不修改 / 无标签），
     *         空列表返回 {@code "[]"}（表示「清空」——必须是非 null 值，
     *         否则 updateById 会跳过该字段，等于清不掉）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 序列化后长度超过 256 时抛出（A0401）
     */
    private String serializeTechStack(List<String> techStack) {
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
        // 长度必须在入参侧拦住：否则会撞 varchar(256) 的截断 / 报错，
        // 前端拿到的是数据库异常而不是「标签过多」的明确提示
        ThrowUtils.throwIf(json.length() > TECH_STACK_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "技术栈标签过长或过多");
        return json;
    }

    /**
     * 解析 tech_stack 的 JSON 快照
     *
     * <p>解析失败（脏数据、手工改库改坏的）一律返回空列表，<b>绝不抛异常</b>——
     * 一条坏数据不能让整个列表接口 500。
     *
     * @param techStackJson 数据库里的 JSON 字符串，可为空
     * @return 标签列表；为空或解析失败时返回空列表
     */
    private List<String> parseTechStack(String techStackJson) {
        if (StrUtil.isBlank(techStackJson)) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(techStackJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 实体分页结果转管理端 VO 分页结果
     *
     * @param entityPage 实体分页
     * @return VO 分页（保留 total/current/size，前端分页组件依赖这三个值）
     */
    private Page<ProjectVO> toProjectVOPage(Page<StudioProject> entityPage) {
        Page<ProjectVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getProjectVO).toList());
        return voPage;
    }

    /**
     * 实体分页结果转 C 端 VO 分页结果（转的是脱敏 VO，不是管理端 VO）
     *
     * @param entityPage 实体分页
     * @return C 端 VO 分页
     */
    private Page<ProjectFrontVO> toProjectFrontVOPage(Page<StudioProject> entityPage) {
        Page<ProjectFrontVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getProjectFrontVO).toList());
        return voPage;
    }
}
