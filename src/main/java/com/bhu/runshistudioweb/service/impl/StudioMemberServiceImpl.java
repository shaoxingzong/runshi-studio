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
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.TeamPositionEnum;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import com.bhu.runshistudioweb.service.MemberProjectService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员服务实现
 *
 * author: shaoshing
 *
 * <p>沿用了 {@link UserServiceImpl} 的三条范式，先读那一份再读这里会更快：
 * <ol>
 *     <li>校验统一走 {@code ThrowUtils.throwIf}，条件成立即抛，本类不写 try-catch
 *     （唯一例外是唯一索引冲突，见下）；</li>
 *     <li>分页参数兜底纠正（页码 &lt; 1 视为 1、pageSize 上限 50），而不是抛异常；</li>
 *     <li>排序字段走白名单 switch 映射，绝不把前端字符串拼进 SQL。</li>
 * </ol>
 *
 * <p><b>本模块特有的四个考点</b>（面试高频，逐条看注释）：
 * <ul>
 *     <li><b>user_id 可选绑定</b>：非空时必须「账号存在」且「未被其他成员绑定」；
 *     并发竞态靠唯一索引 {@code uk_userid_deleted} 兜底 —— 所以 seed 里必须 catch
 *     {@link DuplicateKeyException} 并换成可读提示，否则前端只会看到 50000「系统错误」；</li>
 *     <li><b>grade_year 是 smallint</b>：用 Integer + 区间校验（1950~2100），
 *     拒绝「2026级」这类字符串输入（Jackson 反序列化阶段就会失败）；</li>
 *     <li><b>team_position / member_status 走枚举校验</b>：非法值入库会让筛选与渲染静默出错；</li>
 *     <li><b>默认排序是 sort_order 倒序</b>（官网置顶权重），不是 id 倒序；
 *     并追加 id 倒序作为最终排序键，保证 sort_order 相同的行在分页时次序稳定。</li>
 * </ul>
 *
 * <p>事务说明：单条 insert / update 是一次写，数据库自身的原子性已足够，因此不加事务；
 * 而 {@link #deleteMember} 是「清理关联表 + 逻辑删除主表」两次写，
 * 已加 {@code @Transactional(rollbackFor = Exception.class)}（DESIGN.md 2.2）。
 * 写 {@code rollbackFor = Exception.class} 的原因：Spring 默认只回滚 RuntimeException，
 * 受检异常（如 SQLException 的包装）不回滚，显式声明才能覆盖全部情况。
 */
@Service
public class StudioMemberServiceImpl extends ServiceImpl<StudioMemberMapper, StudioMember>
        implements StudioMemberService {

    /** 入学年份合理区间，必须与 MemberAddRequest / MemberUpdateRequest 上的 @Min/@Max 保持一致 */
    private static final int GRADE_YEAR_MIN = 1950;
    private static final int GRADE_YEAR_MAX = 2100;

    /** 分页每页条数的默认值与上限（上限用来防「一次拉全表」） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 置顶权重不传时的默认值（0 表示不置顶，与 DDL 默认值一致） */
    private static final int DEFAULT_SORT_ORDER = 0;

    /**
     * 成员档案与登录账号的绑定关系维护在 studio_member.user_id 上，
     * 校验「账号是否存在」需要查 sys_user，故注入它的 Mapper
     */
    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * 成员-证书关联服务：删除成员时要级联清理关联关系
     *
     * <p>依赖方向是单向的（本类 → 关联服务），不会构成循环依赖：
     * {@code MemberCertificateServiceImpl} 只注入 Mapper，不依赖本类。
     */
    @Resource
    private MemberCertificateService memberCertificateService;

    /**
     * 成员-项目关联服务：删除成员时同样要级联清理
     *
     * <p>与上面同一个道理：{@code MemberProjectServiceImpl} 只注入 Mapper，
     * 它自己不会被本类依赖回去，因此不构成循环依赖。
     */
    @Resource
    private MemberProjectService memberProjectService;

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 新增成员档案（接口契约见 StudioMemberService）
     *
     * <p>这里补充三件**接口文档之外**的实现要点：
     * <ul>
     *     <li>入学年份、职务、状态三处校验与更新接口走同一套规则，杜绝两套标准；</li>
     *     <li>绑定账号必须过 {@code assertUserBindable}：先确认账号存在、再确认没被别的档案占用；</li>
     *     <li><b>并发兜底</b>：两个请求同时通过上面的校验时，后到的会被唯一索引拦下抛
     *     {@code DuplicateKeyException}，这里必须翻成可读提示，否则会落到全局兜底变成 50000。</li>
     * </ul>
     *
     * @param memberAddRequest 新增请求（姓名必填）
     * @return 新成员档案 ID
     */
    @Override
    public long addMember(MemberAddRequest memberAddRequest) {
        ThrowUtils.throwIf(memberAddRequest == null || StrUtil.isBlank(memberAddRequest.getName()),
                ErrorCode.PARAMS_ERROR, "成员姓名不能为空");

        // 入学年份：DTO 的 @NotNull/@Min/@Max 已拦一道，这里再拦一道——
        // Service 也可能被其他入口（定时任务、测试）直接调用，不能只依赖 Web 层校验
        assertGradeYearValid(memberAddRequest.getGradeYear());
        // 职务与状态：空值取默认，非空必须是枚举内取值（不用 @Pattern 把取值写死在注解上）
        TeamPositionEnum teamPosition = resolveTeamPosition(memberAddRequest.getTeamPosition(),
                TeamPositionEnum.MEMBER);
        MemberStatusEnum memberStatus = resolveMemberStatus(memberAddRequest.getMemberStatus(),
                MemberStatusEnum.IN_TEAM);
        // 绑定账号校验：非空时必须「账号存在」且「未被其他成员占用」
        assertUserBindable(memberAddRequest.getUserId(), null);

        StudioMember member = new StudioMember();
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
        // userId 可为 null（表示尚未开通登录账号），直接赋值即可
        member.setUserId(memberAddRequest.getUserId());

        boolean saveResult;
        try {
            saveResult = this.save(member);
        } catch (DuplicateKeyException e) {
            // 并发竞态兜底：两个请求同时通过上面的「未被绑定」校验时，
            // 后到的那条会被唯一索引 uk_userid_deleted 拦下并抛 DuplicateKeyException。
            // 必须在这里换成可读提示，否则会落到全局兜底变成 50000「系统错误」，
            // 前端既不知道原因、也没法给出「该账号已被绑定」的引导
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增成员失败，数据库异常");
        return member.getId();
    }

    /**
     * 更新成员档案（部分更新，接口契约见 StudioMemberService）
     *
     * <p><b>安全要点</b>：更新字段是**服务端写死的白名单**，只 set 允许修改的字段，
     * 绝不接收前端实体整体更新——否则前端能顺手改动 deletedAt、审计字段等内部数据。
     *
     * <p>与新增的两处差异：① 改绑账号时要「排除自身」再判唯一性；
     * ② 同样要接住 {@code DuplicateKeyException} 竞态。
     *
     * @param memberUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     */
    @Override
    public boolean updateMember(MemberUpdateRequest memberUpdateRequest) {
        ThrowUtils.throwIf(memberUpdateRequest == null || memberUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        Long id = memberUpdateRequest.getId();

        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        // 传了就校验（不传表示不修改）——这三段与新增走的是同一套规则，避免两套标准
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
        // 姓名：传了就必须有实际内容。name 是 NOT NULL 列，
        // 只做 trim 而不判空的话，"   " 会被清成空串写进库，
        // 官网成员列表里就会出现一个「没有名字的成员」
        if (memberUpdateRequest.getName() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(memberUpdateRequest.getName()),
                    ErrorCode.PARAMS_ERROR, "成员姓名不能为空");
        }

        // 只 set 允许修改的字段：id / userId 之外不允许改的字段（deletedAt、审计字段）
        // 一律不出现在这里——更新接口的字段白名单必须由服务端写死，
        // 绝不能直接把前端 DTO 转成实体 updateById（那样前端能改任何字段）
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
            // 与新增同一类竞态：把账号改绑到刚被别人绑走的账号上，由唯一索引兜底
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
    }

    /**
     * 按 id 查成员详情（返回管理端 VO，接口契约见 StudioMemberService）
     *
     * @param id 成员 ID
     * @return 成员信息；不存在时抛 40400，不返回 null
     */
    @Override
    public MemberVO getMemberById(long id) {
        StudioMember member = this.getById(id);
        // 查不到要给出明确的 40400，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(member == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        return this.getMemberVO(member);
    }

    /**
     * 删除成员（逻辑删除 + 级联清理关联表，接口契约见 StudioMemberService）
     *
     * <p>两次写（清理关联表 + 逻辑删除主表）必须在<b>同一事务</b>内完成：
     * 如果先清关联、主表删除失败，成员还在但证书关系没了（数据静默丢失）；
     * 如果先删主表、清理失败，就留下指向已删除成员的悬空关联（DESIGN.md 2.2 明确禁止）。
     *
     * @param id 成员 ID
     * @return true 表示删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMember(long id) {
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        // 同一事务内先清关联，再逻辑删除主表（DESIGN.md 2.2）。
        // 注意这里是**两张**关联表：成员-证书与成员-项目，
        // 必须都清——只清一张就会留下指向已删除成员的悬空关联，
        // 表现为「某个已删除的成员还出现在项目参与人列表里」。
        // 两个 removeByXxxId 都是物理删除：关联表没有 deleted_at，也不该有
        memberCertificateService.removeByMemberId(id);
        memberProjectService.removeByMemberId(id);

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，
        // 数据可追溯；且该成员绑定过的账号会被释放（唯一索引带 deleted_at），可重新绑定新档案
        return this.removeById(id);
    }

    /**
     * 管理端分页查询（接口契约见 StudioMemberService）
     *
     * <p>分页参数在这里做兜底纠正（页码 < 1 视为 1、每页条数收敛到上限），
     * 而不是靠 DTO 校验报错：传错页码没必要让整个查询失败，
     * 而且 GET 参数校验失败抛的是 BindException，会落到全局兜底变成 50000。
     *
     * @param memberQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    @Override
    public Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest) {
        MemberQueryRequest query = memberQueryRequest == null ? new MemberQueryRequest() : memberQueryRequest;

        LambdaQueryWrapper<StudioMember> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // 分页参数的兜底收敛与 C 端共用同一个方法，保证两边规则一致（页码 < 1 视为 1、pageSize 上限 50）
        Page<StudioMember> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toMemberVOPage(entityPage);
    }

    // ==================== C 端：游客可访问 ====================

    // ==================== C 端：已随「团队成员不对外展示」删除 ====================

    // 原 listFrontMembers(...)（官网成员列表）与 getFrontMemberDetail(...)（官网成员详情聚合）
    // 两个实现已删除，配套的接口方法、白名单路径与 MemberDetailFrontVO 同步移除。
    // 成员档案此后只由管理端方法读取（listMemberByPage / getMemberById）。
    //
    // 这里保留一条原来的性能纪律（对将来任何列表聚合都适用）：
    // 若要让「成员列表」显示每人证书数，绝不能写成
    // {@code for (成员 m : 列表) { countByMember(m.id) }}（标准 N+1，一页 50 条 = 50 次查询），
    // 正确做法是一次 {@code SELECT member_id, COUNT(*) ... WHERE member_id IN (...) GROUP BY member_id}，
    // 用 {@code selectMaps} 拿回 Map 后在内存里拼。

    /**
     * 实体转管理端 VO（含全部字段，只给管理员看）
     *
     * @param member 成员实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    @Override
    public MemberVO getMemberVO(StudioMember member) {
        if (member == null) {
            return null;
        }
        MemberVO memberVO = new MemberVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx；管理端 VO 含全部字段，风险是「多给」而不是「给错」
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }

    /**
     * 实体转 C 端展示 VO（游客可见）
     *
     * <p>脱敏靠**目标 VO 的字段结构**实现：VO 里没有 userId / sortOrder / createdBy /
     * updatedBy / deletedAt 这些字段，所以 copyProperties 天然拷不过去。
     * 比「拷完再手动置空某些字段」可靠得多——新增敏感字段时不会被漏掉。
     *
     * @param member 成员实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    @Override
    public MemberFrontVO getMemberFrontVO(StudioMember member) {
        if (member == null) {
            return null;
        }
        MemberFrontVO memberFrontVO = new MemberFrontVO();
        // 关键安全点：目标 VO 中没有 userId / sortOrder / createdBy / updatedBy / deletedAt 字段，
        // 因此即使实体里有这些内部信息，copyProperties 也**不可能**把它们带出去——
        // 这是「结构上脱敏」，比在同一个 VO 里写 if 判断字段更不容易出错
        BeanUtils.copyProperties(member, memberFrontVO);
        return memberFrontVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 构建筛选条件（管理端与 C 端共用，保证两边筛选语义完全一致）
     *
     * @param query 查询条件
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioMember> buildQueryWrapper(MemberQueryRequest query) {
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不会拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioMember::getId, query.getId());
        // 模糊查询：MP 用占位符传参，不存在 SQL 注入；
        // 但用户输入的 % 与 _ 仍会被当作通配符（属于 LIKE 的固有语义，这里不额外转义）
        wrapper.like(StrUtil.isNotBlank(query.getName()), StudioMember::getName, query.getName());
        wrapper.like(StrUtil.isNotBlank(query.getDirection()), StudioMember::getDirection, query.getDirection());
        // 精确匹配：届别、职务、状态是数值/枚举值域，精确匹配才能命中索引、结果也才可预期
        wrapper.eq(query.getGradeYear() != null, StudioMember::getGradeYear, query.getGradeYear());
        wrapper.eq(StrUtil.isNotBlank(query.getTeamPosition()), StudioMember::getTeamPosition, query.getTeamPosition());
        wrapper.eq(query.getMemberStatus() != null, StudioMember::getMemberStatus, query.getMemberStatus());
        return wrapper;
    }

    /**
     * 应用排序规则（白名单映射）
     *
     * <p>关键点：前端只能传「字段名」，由这里映射成实体字段引用。
     * 如果把前端字符串直接拼进 SQL 的 ORDER BY，就是最典型的 SQL 注入入口，
     * 而且 ORDER BY 位置无法用占位符参数化，只能靠白名单。
     *
     * @param wrapper   查询包装类
     * @param sortField 排序字段名，可为空
     * @param sortOrder 排序方向 asc/desc，可为空
     */
    private void applySort(LambdaQueryWrapper<StudioMember> wrapper, String sortField, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortField == null ? "" : sortField) {
            case "id" -> wrapper.orderBy(true, isAsc, StudioMember::getId);
            case "gradeYear" -> wrapper.orderBy(true, isAsc, StudioMember::getGradeYear)
                    .orderByDesc(StudioMember::getId);
            case "memberStatus" -> wrapper.orderBy(true, isAsc, StudioMember::getMemberStatus)
                    .orderByDesc(StudioMember::getId);
            case "createdAt" -> wrapper.orderBy(true, isAsc, StudioMember::getCreatedAt)
                    .orderByDesc(StudioMember::getId);
            case "sortOrder" -> wrapper.orderBy(true, isAsc, StudioMember::getSortOrder)
                    .orderByDesc(StudioMember::getId);
            // 默认按 sort_order 倒序（官网置顶权重，数值越大越靠前）。
            // 注意不能沿用用户模块的「id 倒序」默认值：成员列表是**运营排序**，
            // 置顶是产品明确要求的能力，id 倒序（≈创建时间倒序）只作为次级稳定键
            default -> wrapper.orderByDesc(StudioMember::getSortOrder)
                    .orderByDesc(StudioMember::getId);
        }
    }

    /**
     * 校验入学年份在合理区间
     *
     * @param gradeYear 入学年份
     */
    private void assertGradeYearValid(Integer gradeYear) {
        // grade_year 是 smallint 列：这里挡的是「1800」「3200」这类业务上不可能的值；
        // 「2026级」这类字符串在 JSON 反序列化阶段就已失败（40000），不会走到这里
        ThrowUtils.throwIf(gradeYear == null || gradeYear < GRADE_YEAR_MIN || gradeYear > GRADE_YEAR_MAX,
                ErrorCode.PARAMS_ERROR, "入学年份不合法，需在 " + GRADE_YEAR_MIN + "-" + GRADE_YEAR_MAX + " 之间");
    }

    /**
     * 解析团队职务：空值用兜底职务，非法值直接报错
     *
     * @param teamPosition    前端传入的职务，可为空
     * @param defaultPosition 兜底职务
     * @return 职务枚举
     */
    private TeamPositionEnum resolveTeamPosition(String teamPosition, TeamPositionEnum defaultPosition) {
        if (StrUtil.isBlank(teamPosition)) {
            return defaultPosition;
        }
        TeamPositionEnum position = TeamPositionEnum.of(teamPosition);
        ThrowUtils.throwIf(position == null, ErrorCode.PARAMS_ERROR,
                "团队职务不合法，仅支持 " + TeamPositionEnum.valuesText());
        return position;
    }

    /**
     * 解析成员状态：空值用兜底状态，非法值直接报错
     *
     * @param memberStatus    前端传入的状态，可为空
     * @param defaultStatus   兜底状态
     * @return 状态枚举
     */
    private MemberStatusEnum resolveMemberStatus(Integer memberStatus, MemberStatusEnum defaultStatus) {
        if (memberStatus == null) {
            return defaultStatus;
        }
        MemberStatusEnum status = MemberStatusEnum.of(memberStatus);
        ThrowUtils.throwIf(status == null, ErrorCode.PARAMS_ERROR,
                "成员状态不合法，仅支持 " + MemberStatusEnum.valuesText());
        return status;
    }

    /**
     * 校验账号可绑定：必须存在，且未被其他成员档案占用
     *
     * @param userId       待绑定的账号 id，为 null 表示不绑定（直接通过）
     * @param selfMemberId 当前成员 id，新增时传 null；更新时传自身的 id（用于排除自己）
     */
    private void assertUserBindable(Long userId, Long selfMemberId) {
        if (userId == null) {
            return;
        }

        // selectById 会被 MP 自动追加 deleted_at = 0：绑定已注销（逻辑删除）的账号同样视为「不存在」
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "待绑定的账号不存在或已被注销");

        // 唯一性校验用 count 而不是 selectOne：只需要判断存在性，避免把整行数据查回内存
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioMember::getUserId, userId);
        // 更新场景排除自身：把成员绑到「已属于自己」的账号上是合法的重复提交，
        // 不排除的话重复保存同一个绑定会被误判为「已被其他成员绑定」
        wrapper.ne(selfMemberId != null, StudioMember::getId, selfMemberId);
        long count = this.count(wrapper);

        // 这里是「先查后写」的乐观检查：它挡不住并发竞态（两个请求同时查到 0 条），
        // 最终一致性由数据库唯一索引 uk_userid_deleted 兜底 —— 所以上面两处写操作
        // 都 catch 了 DuplicateKeyException。二者缺一不可：应用层校验负责「友好提示」，
        // 唯一索引负责「绝对不出现脏数据」
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定");
    }

    /**
     * 构造分页对象：页码与每页条数做兜底纠正（而不是抛异常）
     *
     * <p>管理端与 C 端共用这一份规则，避免出现「后台一页 10 条、官网一页 20 条」的两套标准。
     *
     * @param query 查询条件
     * @return 分页对象（searchCount 保持默认 true，即会执行 COUNT 查询）
     */
    private Page<StudioMember> newPageWithDefaults(MemberQueryRequest query) {
        // 页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时，一个 pageSize=100000 就能把整表读进内存。
        // 匿名接口尤其重要——它的调用方不可控
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, pageSize);
    }

    /**
     * 实体分页结果转 VO 分页结果
     *
     * @param entityPage 实体分页
     * @return VO 分页（保留 total/current/size，前端分页组件依赖这三个值）
     */
    private Page<MemberVO> toMemberVOPage(Page<StudioMember> entityPage) {
        Page<MemberVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getMemberVO).toList());
        return voPage;
    }

}