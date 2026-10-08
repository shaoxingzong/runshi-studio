package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberProjectMapper;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;
import com.bhu.runshistudioweb.service.MemberProjectService;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

/**
 * 成员-项目关联服务实现
 *
 * author: shaoshing
 *
 * <p><b>依赖方向铁律（见接口注释）</b>：本类<b>只注入 Mapper</b>
 * （{@link StudioProjectMapper} / {@link StudioMemberMapper}）与容器里的 {@code JsonMapper}，
 * <b>绝不注入</b> {@code StudioProjectService}——它反过来依赖本类做队长同步与级联清理，
 * 一旦这里再依赖回去就是循环依赖，**启动直接失败**（不是偶发运行问题）。
 * 代价是 {@code MemberVO / ProjectVO} 的转换在本类各写几行
 * {@code BeanUtils.copyProperties}、tech_stack 的解析再写一遍（同 的取舍）。
 *
 * <p><b>查询策略：两步查询而不是 JOIN</b>——与 完全同套路：
 * 先从关联表取 ID 列表（走 {@code uk_member_proj} / {@code idx_proj_member} 的最左前缀，
 * 只 select 一列，属于覆盖索引不回表），空集合直接返回，
 * 再 IN 查主表（MP 自动追加 {@code deleted_at = 0}，于是「主表已被逻辑删除」的悬空关联
 * 被天然过滤掉，不需要我们记得写条件）。
 *
 * <p><b>队长不变量</b>：{@code studio_project.leader_id} 是权威源，
 * 关联表里的队长行是同步结果（由 {@code StudioProjectServiceImpl} 在同一事务写入）。
 * 本类的 {@code unbindMember} 负责守卫它——当前队长不允许被解绑。
 */
@Service
@RequiredArgsConstructor
public class MemberProjectServiceImpl extends ServiceImpl<StudioMemberProjectMapper, StudioMemberProject>
        implements MemberProjectService {

    private final StudioProjectMapper studioProjectMapper;

    private final StudioMemberMapper studioMemberMapper;

    /**
     * 用于 tech_stack（JSON 字符串）的解析。
     *
     * <p>用容器里的 {@code JsonMapper}（Boot 4 Jackson 3 自动配置，且已被 {@code JsonConfig} 定制），
     * 不自己 {@code new} 一个：自建的实例不共享全局配置，时间格式、null 策略都可能与接口不一致。
     */
    private final JsonMapper jsonMapper;

    // ==================== 写操作 ====================

    @Override
    public void ensureMemberInProject(long projectId, long memberId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // 先查一次（乐观检查）：绝大多数情况（重复保存项目、队长没变）在这里就返回了
        if (this.count(relationWrapper(projectId, memberId)) > 0) {
            return;
        }

        StudioMemberProject relation = new StudioMemberProject();
        relation.setProjectId(projectId);
        relation.setMemberId(memberId);
        try {
            this.save(relation);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求已经插进去了：对「保证在列表里」这个语义而言，
            // 这正是我们想要的结果，因此**静默成功**，绝不抛异常。
            // 这也是 ensure 与 bind 的关键差别——bind 在这个分支必须抛 A0401
        }
    }

    @Override
    public boolean bindMember(long projectId, long memberId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        assertProjectExists(projectId);
        assertMemberExists(memberId);

        // 应用层查重：命中就给出可读提示（唯一索引抛的是英文 SQL 异常，不能直接给前端）
        ThrowUtils.throwIf(this.count(relationWrapper(projectId, memberId)) > 0,
                ErrorCode.PARAMS_ERROR, "该成员已参与此项目");

        StudioMemberProject relation = new StudioMemberProject();
        relation.setProjectId(projectId);
        relation.setMemberId(memberId);
        try {
            return this.save(relation);
        } catch (DuplicateKeyException e) {
            // 并发竞态兜底：文案与上面完全一致——对调用方而言，
            // 「先查出来的重复」与「并发撞唯一索引的重复」是同一件事
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已参与此项目");
        }
    }

    @Override
    public boolean unbindMember(long projectId, long memberId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // 先确认关系存在：不存在要明确说「未参与」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(projectId, memberId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未参与此项目");

        // 队长守卫：leader_id 是权威源，且「队长必然出现在参与成员列表」是本模块的不变量。
        // 若允许移除队长，就会出现「项目有队长、但成员列表里没有他」的破窗状态
        StudioProject project = studioProjectMapper.selectById(projectId);
        if (project != null && Objects.equals(project.getLeaderId(), memberId)) {
            // 提示要给出下一步动作：管理员需要先去「更换队长」，而不是自己猜为什么删不掉
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队长不能从参与成员中移除，请先更换队长");
        }

        // 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(projectId, memberId));
    }

    // ==================== 读操作 ====================

    @Override
    public List<MemberVO> listMembersByProject(long projectId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        assertProjectExists(projectId);
        return selectMembersOfProject(projectId).stream().map(this::toMemberVO).toList();
    }

    @Override
    public List<ProjectVO> listProjectsByMember(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        assertMemberExists(memberId);
        return selectProjectsOfMember(memberId).stream().map(this::toProjectVO).toList();
    }

    // ==================== C 端：脱敏视图（供官网详情页装配，） ====================

    @Override
    public List<ProjectFrontVO> listFrontProjectsByMember(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        assertMemberExists(memberId);
        // 与管理端同一条查询，只是换成脱敏 VO——
        // 这样两端的筛选与排序规则天然一致，不会出现「后台看到 3 个、官网只显示 2 个」
        return selectProjectsOfMember(memberId).stream().map(this::toProjectFrontVO).toList();
    }

    @Override
    public List<MemberFrontVO> listFrontMembersByProject(long projectId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        assertProjectExists(projectId);
        // 队长必然在结果里（leader_id 同步不变量），官网项目详情的成员区域不用再自己拼
        return selectMembersOfProject(projectId).stream().map(this::toMemberFrontVO).toList();
    }

    // ==================== 两步查询（管理端与 C 端共用，保证两端语义一致） ====================

    /**
     * 两步查询：取参与某项目的全部成员实体
     *
     * <p>第一步走 {@code idx_proj_member (project_id, member_id)} 的覆盖索引（只 select 一列、不回表）；
     * 第二步 IN 查主表时，MP 会自动追加 {@code deleted_at = 0}，
     * 于是「关联还在、但成员已被逻辑删除」的悬空数据被天然过滤（查询侧防御）。
     *
     * @param projectId 项目 ID
     * @return 成员实体列表（已排序）
     */
    private List<StudioMember> selectMembersOfProject(long projectId) {
        LambdaQueryWrapper<StudioMemberProject> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberProject::getMemberId)
                .eq(StudioMemberProject::getProjectId, projectId);
        List<Long> memberIds = this.list(relationWrapper).stream()
                .map(StudioMemberProject::getMemberId)
                .toList();

        // 空集合必须直接返回：拿空集合拼 IN () 是语法错误（MySQL 直接报语法错）
        if (memberIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getId, memberIds);
        // 成员侧沿用成员模块的默认排序：置顶权重倒序 + id 倒序兜底
        wrapper.orderByDesc(StudioMember::getSortOrder).orderByDesc(StudioMember::getId);
        return studioMemberMapper.selectList(wrapper);
    }

    /**
     * 两步查询：取某成员参与的全部项目实体
     *
     * <p>第一步走 {@code uk_member_proj (member_id, project_id)} 的最左前缀；
     * 第二步 IN 查主表时自动带上 {@code deleted_at = 0}，
     * 「项目已被删除但关联还在」的悬空数据会被过滤掉。
     *
     * @param memberId 成员 ID
     * @return 项目实体列表（已排序）
     */
    private List<StudioProject> selectProjectsOfMember(long memberId) {
        LambdaQueryWrapper<StudioMemberProject> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberProject::getProjectId)
                .eq(StudioMemberProject::getMemberId, memberId);
        List<Long> projectIds = this.list(relationWrapper).stream()
                .map(StudioMemberProject::getProjectId)
                .toList();

        if (projectIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<StudioProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioProject::getId, projectIds);
        // 项目侧默认排序：sort_order 倒序 → created_at 倒序 → id 倒序
        // （与 idx_status_sort_time 的 (status, sort_order DESC, created_at DESC) 同向）
        wrapper.orderByDesc(StudioProject::getSortOrder)
                .orderByDesc(StudioProject::getCreatedAt)
                .orderByDesc(StudioProject::getId);
        return studioProjectMapper.selectList(wrapper);
    }

    // ==================== 级联清理 ====================

    @Override
    public long removeByProjectId(long projectId) {
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // 用 baseMapper.delete() 而不是 Service 的 remove()：前者返回被删除的行数（便于日志核对），
        // 后者只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId));
    }

    @Override
    public long removeByMemberId(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getMemberId, memberId));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 按 (project_id, member_id) 构造关联查询条件（走唯一索引 uk_member_proj）
     *
     * @param projectId 项目 ID
     * @param memberId  成员 ID
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioMemberProject> relationWrapper(long projectId, long memberId) {
        return new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId)
                .eq(StudioMemberProject::getMemberId, memberId);
    }

    /**
     * 校验项目存在（含「已逻辑删除视为不存在」）
     *
     * @param projectId 项目 ID
     */
    private void assertProjectExists(long projectId) {
        ThrowUtils.throwIf(studioProjectMapper.selectById(projectId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目不存在");
    }

    /**
     * 校验成员存在（含「已逻辑删除视为不存在」）
     *
     * @param memberId 成员 ID
     */
    private void assertMemberExists(long memberId) {
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /**
     * 实体转管理端成员 VO（写法与 StudioMemberServiceImpl 一致）
     *
     * @param member 成员实体
     * @return 管理端 VO
     */
    private MemberVO toMemberVO(StudioMember member) {
        MemberVO memberVO = new MemberVO();
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }

    /**
     * 实体转管理端项目 VO（含 tech_stack 的 JSON → List 解析）
     *
     * @param project 项目实体
     * @return 管理端 VO
     */
    private ProjectVO toProjectVO(StudioProject project) {
        ProjectVO projectVO = new ProjectVO();
        // 注意 techStack 的类型差异：实体是 String（JSON 快照），VO 是 List<String>。
        // BeanUtils 对类型不一致的属性会**静默跳过**，所以必须手动赋值，
        // 否则接口返回的 techStack 永远是 null，且不报任何错
        BeanUtils.copyProperties(project, projectVO, "techStack");
        projectVO.setTechStack(parseTechStack(project.getTechStack()));
        return projectVO;
    }

    /**
     * 实体转 C 端成员 VO（脱敏：目标 VO 结构上没有 userId / sortOrder / 审计字段）
     *
     * @param member 成员实体
     * @return 脱敏 VO
     */
    private MemberFrontVO toMemberFrontVO(StudioMember member) {
        MemberFrontVO frontVO = new MemberFrontVO();
        // 脱敏靠「目标 VO 没有这些字段」实现：结构上不可能带出去
        BeanUtils.copyProperties(member, frontVO);
        return frontVO;
    }

    /**
     * 实体转 C 端项目 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段）
     *
     * @param project 项目实体
     * @return 脱敏 VO
     */
    private ProjectFrontVO toProjectFrontVO(StudioProject project) {
        ProjectFrontVO frontVO = new ProjectFrontVO();
        // 与 toProjectVO 同样的坑：techStack 在实体里是 String、在 VO 里是 List，
        // 类型不一致时 BeanUtils 会静默跳过，必须先排除再手动赋值
        BeanUtils.copyProperties(project, frontVO, "techStack");
        frontVO.setTechStack(parseTechStack(project.getTechStack()));
        return frontVO;
    }

    /**
     * 解析 tech_stack 的 JSON 快照
     *
     * <p>解析失败（脏数据、手工改库改坏的）一律返回空列表，
     * <b>绝不抛异常</b>——一条坏数据不能让整个列表接口 500。
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
}
