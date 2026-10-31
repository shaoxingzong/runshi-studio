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
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;
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
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class MemberProjectServiceImpl extends ServiceImpl<StudioMemberProjectMapper, StudioMemberProject>
        implements MemberProjectService {

    private final StudioProjectMapper studioProjectMapper;

    private final StudioMemberMapper studioMemberMapper;

    /** 用于 tech_stack（JSON 字符串）的解析。 */
    private final JsonMapper jsonMapper;

    // 写操作

    /** 幂等地把成员加入项目（已存在则什么都不做） */
    @Override
    public void ensureMemberInProject(long projectId, long memberId) {
        // 两个 id 都必须是正数：雪花 ID 恒为正，0 / 负数一定是调用方传错或探测请求
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
            // 乐观检查只是优化，真正兜底的是唯一索引 uk_member_proj
            this.save(relation);
        } catch (DuplicateKeyException e) {
        }
    }

    /** 绑定成员到项目（管理员显式添加） */
    @Override
    public boolean bindMember(long projectId, long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
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
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已参与此项目");
        }
    }

    /** 解绑成员（会拦住「移除当前队长」） */
    @Override
    public boolean unbindMember(long projectId, long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // 先确认关系存在：不存在要明确说「未参与」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(projectId, memberId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未参与此项目");

        StudioProject project = studioProjectMapper.selectById(projectId);
        if (project != null && Objects.equals(project.getLeaderId(), memberId)) {
            // 提示要给出下一步动作：管理员需要先去「更换队长」，而不是自己猜为什么删不掉
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队长不能从参与成员中移除，请先更换队长");
        }

        // 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(projectId, memberId));
    }

    // 读操作

    /** 项目的参与成员（管理端视图） */
    @Override
    public List<MemberVO> listMembersByProject(long projectId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // 先校验项目存在：否则「项目不存在」会被伪装成「这个项目还没有成员」
        assertProjectExists(projectId);
        // 取数逻辑与管理端、C 端共用，保证两端看到的成员完全一致
        return selectMembersOfProject(projectId).stream().map(this::toMemberVO).toList();
    }

    /** 成员参与的项目（管理端视图） */
    @Override
    public List<ProjectVO> listProjectsByMember(long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // 先校验成员存在：否则「成员不存在」会被伪装成「这个人没有项目」
        assertMemberExists(memberId);
        // 与管理端同一条取数逻辑，只是转换成管理端 VO
        return selectProjectsOfMember(memberId).stream().map(this::toProjectVO).toList();
    }

    // C 端：脱敏视图（供官网详情页装配）

    /** 成员参与的项目（门户脱敏视图） */
    @Override
    public List<ProjectFrontVO> listFrontProjectsByMember(long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // 先校验成员存在：这是 C 端接口，更不能把「不存在」伪装成「空列表」
        assertMemberExists(memberId);
        return selectProjectsOfMember(memberId).stream().map(this::toProjectFrontVO).toList();
    }

    /** 项目的参与成员（门户脱敏视图，项目详情页用） */
    @Override
    public List<MemberFrontVO> listFrontMembersByProject(long projectId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // 先校验项目存在：否则「项目不存在」会被伪装成「这个项目没有成员」
        assertProjectExists(projectId);
        // 队长必然在结果里（leader_id 同步不变量），官网项目详情的成员区域不用再自己拼
        return selectMembersOfProject(projectId).stream().map(this::toMemberFrontVO).toList();
    }

    // 两步查询（管理端与 C 端共用，保证两端语义一致）

    /** 两步查询：取参与某项目的全部成员实体 */
    private List<StudioMember> selectMembersOfProject(long projectId) {
        // 第一步：只取 member_id 一列，走 idx_proj_member 的覆盖索引，不必回表读整行
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

    /** 两步查询：取某成员参与的全部项目实体 */
    private List<StudioProject> selectProjectsOfMember(long memberId) {
        // 第一步：只取 project_id 一列，走 uk_member_proj 的最左前缀
        LambdaQueryWrapper<StudioMemberProject> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberProject::getProjectId)
                .eq(StudioMemberProject::getMemberId, memberId);
        List<Long> projectIds = this.list(relationWrapper).stream()
                .map(StudioMemberProject::getProjectId)
                .toList();

        // 空集合直接返回：IN () 是非法 SQL，不能交给 MP 去拼
        if (projectIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<StudioProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioProject::getId, projectIds);
        wrapper.orderByDesc(StudioProject::getSortOrder)
                .orderByDesc(StudioProject::getCreatedAt)
                .orderByDesc(StudioProject::getId);
        return studioProjectMapper.selectList(wrapper);
    }

    // 级联清理

    /** 删除项目时清理其全部关联行（关联表是物理删除） */
    @Override
    public long removeByProjectId(long projectId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId));
    }

    /** 删除成员时清理其全部关联行（关联表是物理删除） */
    @Override
    public long removeByMemberId(long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // 与 removeByProjectId 同一写法：baseMapper.delete 返回行数，remove 只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getMemberId, memberId));
    }

    // 私有工具方法

    /** 按 (project_id, member_id) 构造关联查询条件（走唯一索引 uk_member_proj） */
    private LambdaQueryWrapper<StudioMemberProject> relationWrapper(long projectId, long memberId) {
        // 两个条件缺一不可：少一个就会把「该成员在其他项目的关联」也算进来
        return new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId)
                .eq(StudioMemberProject::getMemberId, memberId);
    }

    /** 校验项目存在（含「已逻辑删除视为不存在」） */
    private void assertProjectExists(long projectId) {
        ThrowUtils.throwIf(studioProjectMapper.selectById(projectId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目不存在");
    }

    /** 校验成员存在（含「已逻辑删除视为不存在」） */
    private void assertMemberExists(long memberId) {
        // 与 assertProjectExists 同理：逻辑删除的成员不该再被绑定或查询
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /**
     * 实体转管理端成员 VO
     *
     * 本类不能注入 StudioMemberService（那会构成循环依赖），所以这几行拷贝是刻意重复的。
     */
    private MemberVO toMemberVO(StudioMember member) {
        MemberVO memberVO = new MemberVO();
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }

    /** 实体转管理端项目 VO（含 tech_stack 的 JSON → List 解析） */
    private ProjectVO toProjectVO(StudioProject project) {
        ProjectVO projectVO = new ProjectVO();
        BeanUtils.copyProperties(project, projectVO, "techStack");
        projectVO.setTechStack(parseTechStack(project.getTechStack()));
        return projectVO;
    }

    /** 实体转 C 端成员 VO（脱敏：目标 VO 结构上没有 userId / sortOrder / 审计字段） */
    private MemberFrontVO toMemberFrontVO(StudioMember member) {
        MemberFrontVO frontVO = new MemberFrontVO();
        // 脱敏靠「目标 VO 没有这些字段」实现：结构上不可能带出去
        BeanUtils.copyProperties(member, frontVO);
        return frontVO;
    }

    /** 实体转 C 端项目 VO（脱敏：目标 VO 没有 content / leaderId / sortOrder / 审计字段） */
    private ProjectFrontVO toProjectFrontVO(StudioProject project) {
        ProjectFrontVO frontVO = new ProjectFrontVO();
        BeanUtils.copyProperties(project, frontVO, "techStack");
        frontVO.setTechStack(parseTechStack(project.getTechStack()));
        return frontVO;
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
}
