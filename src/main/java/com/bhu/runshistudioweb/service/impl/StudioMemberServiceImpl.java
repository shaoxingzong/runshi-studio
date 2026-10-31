package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberSelfUpdateRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.TeamPositionEnum;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import com.bhu.runshistudioweb.service.MemberProjectService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员服务实现（成员档案的增删改查，以及「这个账号是不是在队成员」的唯一判据）
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class StudioMemberServiceImpl extends ServiceImpl<StudioMemberMapper, StudioMember>
        implements StudioMemberService {

    /** 入学年份合理区间，必须与 MemberAddRequest / MemberUpdateRequest 上的 @Min/@Max 保持一致 */
    private static final int GRADE_YEAR_MIN = 1950;
    private static final int GRADE_YEAR_MAX = 2100;

    /** 分页每页条数的默认值与上限 */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 置顶权重不传时的默认值（0 表示不置顶，与 DDL 默认值一致） */
    private static final int DEFAULT_SORT_ORDER = 0;

    /** 系统用户（sys_user）的 Mapper */
    private final SysUserMapper sysUserMapper;

    /** 成员-证书关联服务：删除成员时要级联清理关联关系 */
    private final MemberCertificateService memberCertificateService;

    /** 成员-项目关联服务：删除成员时同样要级联清理 */
    private final MemberProjectService memberProjectService;

    // 管理端：调用方必须已通过 @SaCheckRole("admin")

    /** 新增一条成员档案（管理员操作，调用方已由 @SaCheckRole("admin") 拦过一道） */
    @Override
    public long addMember(MemberAddRequest memberAddRequest) {
        ThrowUtils.throwIf(memberAddRequest == null || StrUtil.isBlank(memberAddRequest.getName()),
                ErrorCode.PARAMS_ERROR, "成员姓名不能为空");

        if (memberAddRequest.getUserId() == null) {
            log.warn("新增成员未绑定登录账号，该档案不会对任何账号生效 | name={}",
                    memberAddRequest.getName());
        }

        assertGradeYearValid(memberAddRequest.getGradeYear());
        // 职务与状态：空值取默认，非空必须是枚举内取值（不把取值写死在注解上，枚举一改注解就过期）
        TeamPositionEnum teamPosition = resolveTeamPosition(memberAddRequest.getTeamPosition(),
                TeamPositionEnum.MEMBER);
        MemberStatusEnum memberStatus = resolveMemberStatus(memberAddRequest.getMemberStatus(),
                MemberStatusEnum.IN_TEAM);
        assertUserBindable(memberAddRequest.getUserId(), null);

        StudioMember member = new StudioMember();
        // 姓名入库前 trim：前端的输入框很容易带首尾空格，而官网列表会把它们一起渲染出来
        member.setName(memberAddRequest.getName().trim());
        member.setAvatar(memberAddRequest.getAvatar());
        member.setGradeYear(memberAddRequest.getGradeYear());
        member.setMajor(memberAddRequest.getMajor());
        member.setDirection(memberAddRequest.getDirection());
        member.setTeamPosition(teamPosition.getValue());
        member.setMemberStatus(memberStatus.getValue());
        member.setGithubUrl(memberAddRequest.getGithubUrl());
        member.setSummary(memberAddRequest.getSummary());
        // 置顶权重不传按 0：显式赋值比依赖 DDL 默认值更直观，避免以后 DDL 变更时行为漂移
        member.setSortOrder(memberAddRequest.getSortOrder() == null
                ? DEFAULT_SORT_ORDER : memberAddRequest.getSortOrder());
        // userId 可为 null（历史导入 / 先建档后开号），此时该档案不会对任何账号生效
        member.setUserId(memberAddRequest.getUserId());

        boolean saveResult;
        try {
            saveResult = this.save(member);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增成员失败，数据库异常");
        return member.getId();
    }

    /** 更新一条成员档案（部分更新：传 null 表示这一项不改） */
    @Override
    public boolean updateMember(MemberUpdateRequest memberUpdateRequest) {
        ThrowUtils.throwIf(memberUpdateRequest == null || memberUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        Long id = memberUpdateRequest.getId();

        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        if (memberUpdateRequest.getGradeYear() != null) {
            assertGradeYearValid(memberUpdateRequest.getGradeYear());
        }
        if (StrUtil.isNotBlank(memberUpdateRequest.getTeamPosition())) {
            resolveTeamPosition(memberUpdateRequest.getTeamPosition(), TeamPositionEnum.MEMBER);
        }
        if (memberUpdateRequest.getMemberStatus() != null) {
            resolveMemberStatus(memberUpdateRequest.getMemberStatus(), MemberStatusEnum.IN_TEAM);
        }
        // 改绑账号：排除自身再判唯一性（把成员绑到「已属于自己」的账号是合法的重复提交）
        assertUserBindable(memberUpdateRequest.getUserId(), id);
        if (memberUpdateRequest.getName() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(memberUpdateRequest.getName()),
                    ErrorCode.PARAMS_ERROR, "成员姓名不能为空");
        }

        StudioMember update = new StudioMember();
        update.setId(id);
        update.setName(memberUpdateRequest.getName());
        update.setAvatar(memberUpdateRequest.getAvatar());
        update.setGradeYear(memberUpdateRequest.getGradeYear());
        update.setMajor(memberUpdateRequest.getMajor());
        update.setDirection(memberUpdateRequest.getDirection());
        update.setTeamPosition(memberUpdateRequest.getTeamPosition());
        update.setMemberStatus(memberUpdateRequest.getMemberStatus());
        update.setGithubUrl(memberUpdateRequest.getGithubUrl());
        update.setSummary(memberUpdateRequest.getSummary());
        update.setSortOrder(memberUpdateRequest.getSortOrder());
        update.setUserId(memberUpdateRequest.getUserId());

        // updateById 默认跳过 null 字段，这正是「传 null 表示不修改」语义成立的基础
        try {
            return this.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
    }

    /** 成员自助编辑本人档案：只更新展示字段，账号/届别/职务/状态/排序均不在此路径内 */
    @Override
    public MemberFrontVO updateMyProfile(long userId, MemberSelfUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "资料内容不能为空");

        // 认档案不认角色：普通 user 角色但绑定了在队档案的账号同样是成员
        StudioMember existing = getActiveMemberByUserId(userId);
        ThrowUtils.throwIf(existing == null, ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以编辑成员资料");

        // 姓名是必展示字段：允许不传（不修改），但传了空白等于把名字清掉，必须拒绝
        if (request.getName() != null) {
            String name = StrUtil.trim(request.getName());
            ThrowUtils.throwIf(StrUtil.isBlank(name), ErrorCode.PARAMS_ERROR, "成员姓名不能为空");
            existing.setName(name);
        }

        // 其余为可选展示字段：null = 不修改；空串 = 清空（与管理端 updateById 的落库行为一致）
        if (request.getAvatar() != null) {
            existing.setAvatar(StrUtil.trim(request.getAvatar()));
        }
        if (request.getMajor() != null) {
            existing.setMajor(StrUtil.trim(request.getMajor()));
        }
        if (request.getDirection() != null) {
            existing.setDirection(StrUtil.trim(request.getDirection()));
        }
        if (request.getGithubUrl() != null) {
            existing.setGithubUrl(StrUtil.trim(request.getGithubUrl()));
        }
        if (request.getSummary() != null) {
            existing.setSummary(StrUtil.trim(request.getSummary()));
        }

        // 只带 id 与上面的展示字段：updateById 跳过 null，账号/届别/职务/状态/排序不会被覆盖
        StudioMember update = new StudioMember();
        update.setId(existing.getId());
        update.setName(request.getName() == null ? null : existing.getName());
        update.setAvatar(request.getAvatar() == null ? null : existing.getAvatar());
        update.setMajor(request.getMajor() == null ? null : existing.getMajor());
        update.setDirection(request.getDirection() == null ? null : existing.getDirection());
        update.setGithubUrl(request.getGithubUrl() == null ? null : existing.getGithubUrl());
        update.setSummary(request.getSummary() == null ? null : existing.getSummary());

        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.SYSTEM_ERROR, "资料更新失败，数据库异常");
        return getMemberFrontVO(existing);
    }

    /** 按 id 查一条成员档案（管理端视图，含 user_id 等内部字段） */
    @Override
    public MemberVO getMemberById(long id) {
        // 按主键取档案：@TableLogic 会自动追加 deleted_at = 0，已逻辑删除的等同于不存在
        StudioMember member = this.getById(id);
        // 查不到要给出明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(member == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        // 转成管理端 VO 返回
        return this.getMemberVO(member);
    }

    /** 删除一条成员档案（逻辑删除主表，并先清掉两张关联表） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMember(long id) {
        // 先确认存在：对不存在的 id 做两次删除没有意义，也拿不到明确的错误提示
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        memberCertificateService.removeByMemberId(id);
        memberProjectService.removeByMemberId(id);

        return this.removeById(id);
    }

    /** 管理端分页查询成员档案（支持按 id、姓名、方向、届别、职务、状态筛选） */
    @Override
    public Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest) {
        MemberQueryRequest query = memberQueryRequest == null ? new MemberQueryRequest() : memberQueryRequest;

        LambdaQueryWrapper<StudioMember> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        Page<StudioMember> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toMemberVOPage(entityPage);
    }

    // 列表聚合的性能纪律

    /** 实体转「管理端」VO（含全部字段，只给管理员看） */
    @Override
    public MemberVO getMemberVO(StudioMember member) {
        if (member == null) {
            return null;
        }
        // 新建 VO 并按同名属性拷贝
        MemberVO memberVO = new MemberVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx；管理端 VO 含全部字段，风险是「多给」而不是「给错」
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }

    /** 实体转「C 端」VO（游客可见的脱敏视图） */
    @Override
    public MemberFrontVO getMemberFrontVO(StudioMember member) {
        // 入参为 null 直接返回 null
        if (member == null) {
            return null;
        }
        // 新建 VO 并按同名属性拷贝——目标类没有的字段会被自动跳过
        MemberFrontVO memberFrontVO = new MemberFrontVO();
        BeanUtils.copyProperties(member, memberFrontVO);
        return memberFrontVO;
    }

    // 考勤模块依赖：按登录账号查在队成员

    /** 按绑定的登录账号查「在队」成员档案（判断某人是不是工作室成员的唯一入口） */
    @Override
    public StudioMember getActiveMemberByUserId(Long userId) {
        // userId 为 null（未登录 / 无绑定）直接返回 null，连 SQL 都不必发
        if (userId == null) {
            return null;
        }
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioMember::getUserId, userId)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());

        return this.getOne(wrapper, false);
    }

    // 私有工具方法

    /** 构建筛选条件（管理端各列表入口共用同一份，保证筛选语义只有一套） */
    private LambdaQueryWrapper<StudioMember> buildQueryWrapper(MemberQueryRequest query) {
        // 逐个拼条件，每个条件都自带「开关」判断，传 null / 空白即跳过
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不会拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioMember::getId, query.getId());
        wrapper.like(StrUtil.isNotBlank(query.getName()), StudioMember::getName, query.getName());
        wrapper.like(StrUtil.isNotBlank(query.getDirection()), StudioMember::getDirection, query.getDirection());
        // 精确匹配：届别、职务、状态是数值/枚举值域，精确匹配才能命中索引、结果也才可预期
        wrapper.eq(query.getGradeYear() != null, StudioMember::getGradeYear, query.getGradeYear());
        wrapper.eq(StrUtil.isNotBlank(query.getTeamPosition()), StudioMember::getTeamPosition, query.getTeamPosition());
        wrapper.eq(query.getMemberStatus() != null, StudioMember::getMemberStatus, query.getMemberStatus());
        // 返回包装器，由调用方继续追加排序与分页
        return wrapper;
    }

    /** 应用排序规则（前端字段名 → 实体字段引用的白名单映射） */
    private void applySort(LambdaQueryWrapper<StudioMember> wrapper, String sortField, String sortOrder) {
        // 只有明确的 asc 才算升序：拼错的方向不该被猜成升序，按降序更符合列表的使用习惯
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        // 按字段名分支拼接；认不出的字段名走 default，落到产品约定的默认排序
        switch (sortField == null ? "" : sortField) {
            // 主键已经唯一，不需要再追加次级排序键
            case "id" -> wrapper.orderBy(true, isAsc, StudioMember::getId);
            case "gradeYear" -> wrapper.orderBy(true, isAsc, StudioMember::getGradeYear)
                    .orderByDesc(StudioMember::getId);
            case "memberStatus" -> wrapper.orderBy(true, isAsc, StudioMember::getMemberStatus)
                    .orderByDesc(StudioMember::getId);
            case "createdAt" -> wrapper.orderBy(true, isAsc, StudioMember::getCreatedAt)
                    .orderByDesc(StudioMember::getId);
            case "sortOrder" -> wrapper.orderBy(true, isAsc, StudioMember::getSortOrder)
                    .orderByDesc(StudioMember::getId);
            default -> wrapper.orderByDesc(StudioMember::getSortOrder)
                    .orderByDesc(StudioMember::getId);
        }
    }

    /** 校验入学年份落在合理区间内 */
    private void assertGradeYearValid(Integer gradeYear) {
        ThrowUtils.throwIf(gradeYear == null || gradeYear < GRADE_YEAR_MIN || gradeYear > GRADE_YEAR_MAX,
                ErrorCode.PARAMS_ERROR, "入学年份不合法，需在 " + GRADE_YEAR_MIN + "-" + GRADE_YEAR_MAX + " 之间");
    }

    /** 解析团队职务：空值取兜底职务，非法值直接报错 */
    private TeamPositionEnum resolveTeamPosition(String teamPosition, TeamPositionEnum defaultPosition) {
        // 空白直接返回兜底职务（不传 = 用默认值）
        if (StrUtil.isBlank(teamPosition)) {
            return defaultPosition;
        }
        // 按取值查枚举（大小写敏感），查不到即抛 A0401
        TeamPositionEnum position = TeamPositionEnum.of(teamPosition);
        // 提示里列出全部合法取值：否则管理员只能靠猜，猜错还看不出哪里错
        ThrowUtils.throwIf(position == null, ErrorCode.PARAMS_ERROR,
                "团队职务不合法，仅支持 " + TeamPositionEnum.valuesText());
        // 返回解析出的枚举
        return position;
    }

    /** 解析成员状态：空值取兜底状态，非法值直接报错 */
    private MemberStatusEnum resolveMemberStatus(Integer memberStatus, MemberStatusEnum defaultStatus) {
        if (memberStatus == null) {
            return defaultStatus;
        }
        // 按取值查枚举，查不到即抛 A0401
        MemberStatusEnum status = MemberStatusEnum.of(memberStatus);
        ThrowUtils.throwIf(status == null, ErrorCode.PARAMS_ERROR,
                "成员状态不合法，仅支持 " + MemberStatusEnum.valuesText());
        return status;
    }

    /** 校验账号可绑定：账号必须存在，且没有被其他成员档案占用 */
    private void assertUserBindable(Long userId, Long selfMemberId) {
        // userId 为 null 直接通过——不绑定账号是允许的（历史导入 / 先建档后开号）
        if (userId == null) {
            return;
        }

        // selectById 会被 MP 自动追加 deleted_at = 0：绑定已注销（逻辑删除）的账号同样视为「不存在」
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "待绑定的账号不存在或已被注销");

        // 唯一性校验用 count 而不是 selectOne：只需要判断存在性，避免把整行数据查回内存
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioMember::getUserId, userId);
        wrapper.ne(selfMemberId != null, StudioMember::getId, selfMemberId);
        long count = this.count(wrapper);

        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定");
    }

    /** 构造分页对象：页码与每页条数做兜底纠正（而不是抛异常） */
    private Page<StudioMember> newPageWithDefaults(MemberQueryRequest query) {
        // 页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        // 每页条数为 null 或小于 1 时按默认值
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时，一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, pageSize);
    }

    /** 实体分页结果转 VO 分页结果 */
    private Page<MemberVO> toMemberVOPage(Page<StudioMember> entityPage) {
        // 用原分页的 current / size / total 建一个空 VO 分页
        Page<MemberVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        // 把记录逐条转成 VO 后整体塞回去；空记录时得到的是空列表，total 仍是真实的 0
        voPage.setRecords(entityPage.getRecords().stream().map(this::getMemberVO).toList());
        return voPage;
    }

}