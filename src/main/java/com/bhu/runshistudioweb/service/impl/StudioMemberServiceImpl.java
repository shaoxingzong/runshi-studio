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
 * 成员身份的权威来源是本表：看 studio_member 里有没有他 user_id 的在队档案，
 * 而不是看 sys_user.user_role——角色只是权限标签，可能与档案不同步。
 *
 * 唯一索引冲突必须 catch DuplicateKeyException 并翻成中文，否则前端只看到 B0001；
 * 排序字段走白名单 switch，ORDER BY 无法参数化，拼前端字符串就是注入入口。
 * deleteMember 是两次写，加了 @Transactional(rollbackFor = Exception.class)。
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

    /**
     * 分页每页条数的默认值与上限
     *
     * 上限不是性能优化，而是安全边界：不设上限时一个 pageSize=100000
     * 就能把整张成员表读进内存，请求成本由调用方决定，服务器只能被动承受。
     */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /**
     * 置顶权重不传时的默认值（0 表示不置顶，与 DDL 默认值一致）
     *
     * 在代码里显式赋这个值，而不是依赖数据库列默认值：DDL 一改，
     * 行为会跟着悄悄变化，而代码里写的常量改没改一眼就能看出来。
     */
    private static final int DEFAULT_SORT_ORDER = 0;

    /**
     * 系统用户（sys_user）的 Mapper
     *
     * 成员档案与登录账号的绑定关系维护在 studio_member.user_id 上，
     * 校验「账号是否存在」需要查 sys_user，故注入它的 Mapper。
     * 这里只需要「按 id 取一行」这种单表能力，没有必要绕一层 UserService——
     * 那样反而会把成员模块和用户模块的依赖缠在一起。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * 成员-证书关联服务：删除成员时要级联清理关联关系
     *
     * 依赖方向是单向的（本类 → 关联服务），不会构成循环依赖：
     * MemberCertificateServiceImpl 只注入 Mapper，不依赖本类。
     * 这条铁律不能破——本项目全部使用构造器注入，一旦成环，
     * 症状是应用启动直接失败，而不是运行期偶发问题。
     */
    private final MemberCertificateService memberCertificateService;

    /**
     * 成员-项目关联服务：删除成员时同样要级联清理
     *
     * 与成员-证书服务同一个道理：MemberProjectServiceImpl 只注入 Mapper，
     * 它自己不会被本类依赖回去，因此不构成循环依赖。
     */
    private final MemberProjectService memberProjectService;

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 新增一条成员档案（管理员操作，调用方已由 @SaCheckRole("admin") 拦过一道）
     *
     * 建档时必须同时定下三件事：人是谁、在不在队、绑不绑账号。绑账号刻意允许为空，
     * 历史导入与「先建档后开号」都需要它，因此这里只留一条 warn 日志便于事后追溯。
     *
     * 坑：校验账号可绑定是「先查后写」的乐观检查，挡不住并发——两个管理员同时绑同一个账号，
     * 两边都会查到「没人占用」。真正保证不出脏数据的是唯一索引，所以写库处的 catch 不能省。
     *
     * @param memberAddRequest 新增请求：姓名必填（最长 64 字符）、入学年份必填（1950~2100）、
     * user_id 选填（为空则该档案不会对任何账号生效）、职务与状态选填
     * @return 新成员档案的主键 ID（雪花算法生成，序列化时由全局配置转成字符串）
     * @throws BusinessException 姓名为空、年份越界、账号不可绑定抛 A0401
     */
    @Override
    public long addMember(MemberAddRequest memberAddRequest) {
        // ① 姓名是 NOT NULL 列：空值必须在写库之前挡住，
        //    否则报错会以数据库异常的形式抛出来，被全局兜底成 B0001
        ThrowUtils.throwIf(memberAddRequest == null || StrUtil.isBlank(memberAddRequest.getName()),
                ErrorCode.PARAMS_ERROR, "成员姓名不能为空");

        // ② userId 允许为空（历史导入 / 先建档后开号，见 db/user.sql 的注释），
        //    但 user_id 为 NULL 的档案永远不会生效（准入判据是「档案 user_id 命中当前账号」），
        //    所以这里留一条 warn：出问题时能顺着日志找到「为什么这个人用不了功能」。
        //    真正的防线在 UserServiceImpl#updateUser——授予 member/admin 角色时要求档案必须存在
        if (memberAddRequest.getUserId() == null) {
            log.warn("新增成员未绑定登录账号，该档案不会对任何账号生效 | name={}",
                    memberAddRequest.getName());
        }

        // ③ 入学年份：DTO 的 @NotNull/@Min/@Max 已拦一道，这里再拦一道——
        //    Service 也可能被其他入口（定时任务、测试）直接调用，不能只依赖 Web 层校验
        assertGradeYearValid(memberAddRequest.getGradeYear());
        // 职务与状态：空值取默认，非空必须是枚举内取值（不把取值写死在注解上，枚举一改注解就过期）
        TeamPositionEnum teamPosition = resolveTeamPosition(memberAddRequest.getTeamPosition(),
                TeamPositionEnum.MEMBER);
        MemberStatusEnum memberStatus = resolveMemberStatus(memberAddRequest.getMemberStatus(),
                MemberStatusEnum.IN_TEAM);
        // ④ 绑定账号校验：非空时必须「账号存在」且「未被其他成员占用」。
        //    第二个参数传 null：新增场景没有「自身」要排除
        assertUserBindable(memberAddRequest.getUserId(), null);

        // ⑤ 组装实体：职务与状态存枚举的 value 而不是枚举名，
        //    这样列里就是 DDL 约定的取值，换枚举类名也不影响已入库的数据
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
            // 并发竞态兜底：两个请求同时通过上面的「未被绑定」校验时，
            // 后到的那条会被唯一索引 uk_userid_deleted 拦下并抛 DuplicateKeyException。
            // 必须在这里换成可读提示，否则会落到全局兜底变成 B0001「系统错误」，
            // 前端既不知道原因、也没法给出「该账号已被绑定」的引导
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增成员失败，数据库异常");
        return member.getId();
    }

    /**
     * 更新一条成员档案（部分更新：传 null 表示这一项不改）
     *
     * 若要求前端回传整行数据，前端漏一个字段就会把它误清空，而接口返回成功、页面也正常。
     * 因此靠 updateById 默认跳过 null 字段实现「传 null 表示不修改」，代价是这套接口无法把字段清空。
     * 更新字段是服务端写死的白名单，绝不接收前端实体整体更新，否则前端能顺手改 deletedAt 与审计字段。
     *
     * 坑：校验必须先判「传了没有」，写成无条件校验会让「只改头像」因为 gradeYear 为 null 而被拒；
     * selfMemberId 必须传 id，漏掉它，把同一个账号重复保存一次就会被自己的旧记录判成「已被占用」。
     *
     * @param memberUpdateRequest 更新请求：id 必填；其余字段为 null 表示不修改；姓名若传则不能是纯空白
     * @return true 表示更新成功；false 说明没有命中任何行（典型情况是查存在与执行更新之间被并发删除）
     * @throws BusinessException id 为空、字段非法、账号不可绑定抛 A0401；成员不存在抛 A0402
     */
    @Override
    public boolean updateMember(MemberUpdateRequest memberUpdateRequest) {
        // ① id 是必填项：没有它 updateById 只会返回 false，
        //    前端只能看到「更新失败」，看不出到底是参数问题还是记录不存在
        ThrowUtils.throwIf(memberUpdateRequest == null || memberUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        Long id = memberUpdateRequest.getId();

        // ② 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        // ③ 传了就校验（不传表示不修改）——这三段与新增走的是同一套规则，避免两套标准。
        //    注意每段都包在「字段非 null」里：无条件校验会让「只改头像」的请求被拒
        if (memberUpdateRequest.getGradeYear() != null) {
            assertGradeYearValid(memberUpdateRequest.getGradeYear());
        }
        if (StrUtil.isNotBlank(memberUpdateRequest.getTeamPosition())) {
            resolveTeamPosition(memberUpdateRequest.getTeamPosition(), TeamPositionEnum.MEMBER);
        }
        if (memberUpdateRequest.getMemberStatus() != null) {
            resolveMemberStatus(memberUpdateRequest.getMemberStatus(), MemberStatusEnum.IN_TEAM);
        }
        // ④ 改绑账号：排除自身再判唯一性（把成员绑到「已属于自己」的账号是合法的重复提交）
        assertUserBindable(memberUpdateRequest.getUserId(), id);
        // 姓名：传了就必须有实际内容。name 是 NOT NULL 列，
        // 只做 trim 而不判空的话，"   " 会被清成空串写进库，
        // 官网成员列表里就会出现一个「没有名字的成员」
        if (memberUpdateRequest.getName() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(memberUpdateRequest.getName()),
                    ErrorCode.PARAMS_ERROR, "成员姓名不能为空");
        }

        // ⑤ 只 set 允许修改的字段：deletedAt、审计字段一律不出现在这里——
        //    更新接口的字段白名单必须由服务端写死，绝不能直接把前端 DTO 转成实体 updateById
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
            // 与新增同一类竞态：把账号改绑到刚被别人绑走的账号上，由唯一索引兜底。
            // 文案必须与新增一致，否则同一件事在不同入口会给出两种提示
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定，请刷新后重试");
        }
    }

    /**
     * 按 id 查一条成员档案（管理端视图，含 user_id 等内部字段）
     *
     * 查不到时抛 A0402 而不是返回 null：返回 null 时接口仍然是「成功」，
     * 前端拿到空 data 只能猜是这个人不存在还是后端漏查了。
     * 「不是成员」不走本方法，那是正常查询结果，用 getActiveMemberByUserId 返回 null 表达。
     *
     * @param id 成员档案 ID，必须大于 0（传 0 或负数会直接落到 A0402）
     * @return 管理端 VO，含 userId / sortOrder / 时间字段；不含密码等账号信息
     * @throws BusinessException 档案不存在或已被逻辑删除时抛出（A0402）
     */
    @Override
    public MemberVO getMemberById(long id) {
        // ① 按主键取档案：@TableLogic 会自动追加 deleted_at = 0，已逻辑删除的等同于不存在
        StudioMember member = this.getById(id);
        // ② 查不到要给出明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(member == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");
        // ③ 转成管理端 VO 返回
        return this.getMemberVO(member);
    }

    /**
     * 删除一条成员档案（逻辑删除主表，并先清掉两张关联表）
     *
     * 两次写必须在同一事务里。先清关联、主表删除失败，成员还在但证书关系没了；
     * 先删主表、清理失败，就留下指向已删除成员的悬空关联，只会在某个列表页里悄悄出现。
     * 主表用逻辑删除：档案涉及考勤、帖子等历史数据，物理删掉会让这些数据失去归属；
     * 副作用也是想要的——uk_userid_deleted 带上 deleted_at，档案删掉后账号就被释放了。
     *
     * 坑：两张关联表必须都清；关联表用物理删除，若改成逻辑删除，唯一索引会被已删除的行占住，
     * 「先解绑、再重新绑定」这个正常操作就会一直冲突。
     *
     * @param id 成员档案 ID，必须大于 0；档案不存在（含已逻辑删除）时抛 A0402
     * @return true 表示主表删除成功；关联清理返回 0 行只说明这个人本来就没绑证书 / 项目
     * @throws BusinessException 档案不存在时抛出（A0402）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMember(long id) {
        // ① 先确认存在：对不存在的 id 做两次删除没有意义，也拿不到明确的错误提示
        StudioMember existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        // ② 同一事务内先清关联，再逻辑删除主表。
        //    注意这里是两张关联表：成员-证书与成员-项目，
        //    必须都清——只清一张就会留下指向已删除成员的悬空关联，
        //    表现为「某个已删除的成员还出现在项目参与人列表里」。
        //    两个 removeByXxxId 都是物理删除：关联表没有 deleted_at，也不该有
        memberCertificateService.removeByMemberId(id);
        memberProjectService.removeByMemberId(id);

        // ③ 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，
        //    数据可追溯；且该成员绑定过的账号会被释放（唯一索引带 deleted_at），可重新绑定新档案。
        //    删值用毫秒时间戳而非固定 1，是为了让同一账号被反复删除时不撞唯一索引
        return this.removeById(id);
    }

    /**
     * 管理端分页查询成员档案（支持按 id、姓名、方向、届别、职务、状态筛选）
     *
     * 分页参数做兜底纠正而不是报错：GET 请求的校验失败抛的是 BindException，
     * 会落到全局兜底变成 B0001，前端会把「页码写错了」显示成「服务器故障」。
     * 筛选与排序抽成私有方法，与其它列表入口共用——两处各写一遍迟早演变成「后台筛得出、官网筛不出」。
     *
     * 坑：本方法不校验登录态与管理员角色，那两道在 Controller 上；档案含 user_id 等内部字段，
     * 不能暴露成匿名接口。排序必须在分页查询之前拼进同一个 wrapper，否则排的只是当前这一页。
     *
     * @param memberQueryRequest 查询条件，允许整体为 null（等价于无条件查第一页）；
     * 各字段为 null 表示「该条件不参与筛选」，不是「查该字段为空的行」
     * @return 分页结果（记录为管理端 VO，含内部字段）；查不到时返回空记录分页，不返回 null
     */
    @Override
    public Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest) {
        // ① 请求体为 null 时兜一个空对象：把「不传条件」统一成「无条件」，
        //    后面取字段就不用到处判空
        MemberQueryRequest query = memberQueryRequest == null ? new MemberQueryRequest() : memberQueryRequest;

        // ② 筛选与排序都拼进同一个 wrapper。排序必须在分页之前交给 SQL：
        //    分页是数据库层做的，先查回来再在内存里排，排的只是当前这一页
        LambdaQueryWrapper<StudioMember> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // ③ 分页参数的兜底收敛与其它列表入口共用同一个方法，
        //    保证规则一致（页码 < 1 视为 1、pageSize 上限 50）
        Page<StudioMember> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toMemberVOPage(entityPage);
    }

    // ==================== 列表聚合的性能纪律 ====================

    // 本类没有「官网成员列表 / 官网成员详情」的实现：
    // 成员档案只由管理端方法读取（listMemberByPage / getMemberById）。
    // 对外唯一会露出成员信息的地方是项目详情里的「参与成员」，
    // 由 MemberProjectService 按项目维度组装，不经过本类。
    //
    // 但下面这条纪律对任何列表聚合都适用：
    // 若要让「成员列表」显示每人证书数，绝不能写成
    //  for (成员 m : 列表) { countByMember(m.id) }（标准 N+1：查 1 次列表，
    //  再对每条记录各查 1 次；一页 50 条就是 50 次查询），
    // 正确做法是一次 SELECT member_id, COUNT(*) ... WHERE member_id IN (...) GROUP BY member_id，
    // 用 selectMaps 拿回 Map 后在内存里拼。

    /**
     * 实体转「管理端」VO（含全部字段，只给管理员看）
     *
     * 管理端要核对账号绑定与置顶权重，所以 MemberVO 是「宽」的。转换靠 copyProperties
     * 按属性名自动拷贝：手写 setXxx 的话，实体加字段就得记得来补一行，漏了不报错、只是永远显示空。
     * 这里的风险方向是「多给」而不是「给错」，前提是调用方是管理员。
     *
     * 坑：copyProperties 只拷「两边都存在且类型兼容」的属性，不会报错也不会警告，
     * 给 VO 加字段后要确认实体里有同名的源字段。
     *
     * @param member 成员实体，允许为 null
     * @return 管理端 VO；入参为 null 时返回 null，调用方需判空
     */
    @Override
    public MemberVO getMemberVO(StudioMember member) {
        // ① 入参为 null 直接返回 null：本方法要能安全地处理「查不到」，
        //    否则调用方每次都得先判空
        if (member == null) {
            return null;
        }
        // ② 新建 VO 并按同名属性拷贝
        MemberVO memberVO = new MemberVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx；管理端 VO 含全部字段，风险是「多给」而不是「给错」
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }

    /**
     * 实体转「C 端」VO（游客可见的脱敏视图）
     *
     * 脱敏靠目标 VO 的字段结构实现，而不是「拷完再手动置空几个字段」：MemberFrontVO 里没有
     * userId / sortOrder / 审计字段，copyProperties 自然带不出去。手动置空的问题在于
     * 将来实体新增一个内部字段、VO 又恰好同名时，它会静默泄露，且没有编译期提示。
     *
     * 坑：脱敏效果完全取决于 MemberFrontVO 的字段清单，给它补字段前先问一句
     * 「这个字段愿意给所有游客看吗」——加进去就会立刻被所有公开接口返回。
     *
     * @param member 成员实体，允许为 null
     * @return 脱敏后的 VO（不含账号绑定关系与审计字段）；入参为 null 时返回 null
     */
    @Override
    public MemberFrontVO getMemberFrontVO(StudioMember member) {
        // ① 入参为 null 直接返回 null
        if (member == null) {
            return null;
        }
        // ② 新建 VO 并按同名属性拷贝——目标类没有的字段会被自动跳过
        MemberFrontVO memberFrontVO = new MemberFrontVO();
        // 关键安全点：目标 VO 中没有 userId / sortOrder / createdBy / updatedBy / deletedAt 字段，
        // 因此即使实体里有这些内部信息，copyProperties 也不可能把它们带出去——
        // 这是「结构上脱敏」，比在同一个 VO 里写 if 判断字段更不容易出错
        BeanUtils.copyProperties(member, memberFrontVO);
        return memberFrontVO;
    }

    // ==================== 考勤模块依赖：按登录账号查在队成员 ====================

    /**
     * 按绑定的登录账号查「在队」成员档案（判断某人是不是工作室成员的唯一入口）
     *
     * 答案必须来自 studio_member，而不是 sys_user.user_role 是不是 member：角色可能与档案不同步，
     * 按角色放行等于绕开真正的准入规则。发帖、签到等接口共用这个判据，
     * 规则集中在一处才不会出现「发帖说你是成员、签到说你不是」。
     *
     * 坑：返回 null 是正常结果，不是异常——普通注册用户就是这个结果，真正的拦截（A0301）
     * 发生在业务接口里。让查询接口抛异常会把「查询」与「鉴权」混在一起。
     *
     * @param userId 登录账号 ID，为 null 时直接返回 null（连 SQL 都不发）
     * @return 在队的成员档案；账号未绑定、档案已离队或已删除时返回 null，调用方必须判空
     */
    @Override
    public StudioMember getActiveMemberByUserId(Long userId) {
        // ① userId 为 null（未登录 / 无绑定）直接返回 null，连 SQL 都不必发
        if (userId == null) {
            return null;
        }
        // ② 两个条件缺一不可：绑定了这个账号、且仍在队。
        //    少 user_id 会查出别人的档案，少 member_status 会把已毕业的人放回准入名单
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioMember::getUserId, userId)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());

        // ③ getOne 的第二个参数传 false：命中多条时不抛 TooManyResultsException，取第一条返回。
        // 唯一索引 uk_userid_deleted 本已保证「同一账号最多一条未删除档案」，
        // 这里只是防御「手工改库造成脏数据」时接口直接 500——宁可返回一条，
        // 也要比整个签到功能挂掉好
        return this.getOne(wrapper, false);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 构建筛选条件（管理端各列表入口共用同一份，保证筛选语义只有一套）
     *
     * 每个条件都带一个布尔开关，传 false 时不拼进 SQL，省掉成片的 if。模糊与精确有明确依据：
     * 姓名与方向是自由文本，用 like；届别、职务、状态是数值或枚举值域，精确匹配才能命中索引。
     *
     * 坑：like 用占位符传参不存在注入，但用户输入里的 % 与 _ 仍会被当成通配符（刻意不转义）；
     * 不传不等于「查该字段为空的行」，这两种语义千万别混。
     *
     * @param query 查询条件，调用方保证非 null（无条件的场景由上层兜一个空对象）
     * @return 已拼好筛选条件、尚未拼排序与分页的查询包装器
     */
    private LambdaQueryWrapper<StudioMember> buildQueryWrapper(MemberQueryRequest query) {
        // ① 逐个拼条件，每个条件都自带「开关」判断，传 null / 空白即跳过
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
        // ② 返回包装器，由调用方继续追加排序与分页
        return wrapper;
    }

    /**
     * 应用排序规则（前端字段名 → 实体字段引用的白名单映射）
     *
     * 绝不能把前端字符串直接拼进 ORDER BY——那是最典型的注入入口，而 ORDER BY 的位置
     * 无法用占位符参数化，白名单是唯一可靠的防线。
     * 每个分支都在主排序键之后追加了 id 倒序：排序键不唯一时数据库返回顺序不确定，
     * 翻页就会出现「同一条记录出现两次、另一条却看不到」。
     *
     * 坑：默认排序是 sort_order 倒序而不是 id 倒序，用 id 当默认会让置顶功能看起来完全没生效。
     *
     * @param wrapper 查询包装器，本方法只往里追加排序
     * @param sortField 排序字段名，可为空；只认 id / gradeYear / memberStatus / createdAt / sortOrder
     * @param sortOrder 排序方向 asc / desc，可为空（为空按 desc 处理）
     */
    private void applySort(LambdaQueryWrapper<StudioMember> wrapper, String sortField, String sortOrder) {
        // ① 只有明确的 asc 才算升序：拼错的方向不该被猜成升序，按降序更符合列表的使用习惯
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        // ② 按字段名分支拼接；认不出的字段名走 default，落到产品约定的默认排序
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
            // 默认按 sort_order 倒序（官网置顶权重，数值越大越靠前）。
            // 不能沿用「id 倒序」当默认：成员列表是运营排序，置顶是产品明确要求的能力，
            // id 倒序（≈创建时间倒序）只作为次级稳定键
            default -> wrapper.orderByDesc(StudioMember::getSortOrder)
                    .orderByDesc(StudioMember::getId);
        }
    }

    /**
     * 校验入学年份落在合理区间内
     *
     * grade_year 是 smallint，数据库存得下 1800 与 3200，但这两个值在业务上不可能出现，
     * 存进去之后按届别筛选、按届分组都会得到怪结果。上下限用常量，与 DTO 上的 @Min / @Max 同源。
     *
     * 坑：这里把 null 也当成不合法，因为它只服务于「传了值」的场景；要支持「null 表示不修改」，
     * 必须在调用处先判空，而不是在这里放行，否则新增接口能建出一条没有届别的档案。
     *
     * @param gradeYear 入学年份，可为 null（null 视为不合法）
     * @throws BusinessException 为 null 或不在 1950~2100 之间时抛出（A0401）
     */
    private void assertGradeYearValid(Integer gradeYear) {
        // ① 判空 + ② 判断是否越界，任一成立即抛 A0401。
        // grade_year 是 smallint 列：这里挡的是「1800」「3200」这类业务上不可能的值；
        // 「2026级」这类字符串在 JSON 反序列化阶段就已失败（A0401），不会走到这里
        ThrowUtils.throwIf(gradeYear == null || gradeYear < GRADE_YEAR_MIN || gradeYear > GRADE_YEAR_MAX,
                ErrorCode.PARAMS_ERROR, "入学年份不合法，需在 " + GRADE_YEAR_MIN + "-" + GRADE_YEAR_MAX + " 之间");
    }

    /**
     * 解析团队职务：空值取兜底职务，非法值直接报错
     *
     * 校验放在 Service 而不是 DTO 的 @Pattern 上：把取值写死在注解里，枚举一加新职务注解就过期，
     * 而这种过期不报错，只表现为「新职务怎么都存不进去」。
     * 非法值不静默兜底，否则管理员以为自己设置成功了。
     *
     * 坑：枚举查找大小写敏感，前端传首字母大写的写法会被判成非法值——这是刻意的，
     * 入库值必须与 DDL 注释里约定的字面量完全一致。
     *
     * @param teamPosition 前端传入的职务，可为空（为空返回兜底职务）
     * @param defaultPosition 兜底职务，调用方按场景指定（新增时是「成员」）
     * @return 解析出的职务枚举，不会为 null
     * @throws BusinessException 取值不在枚举内时抛出（A0401）
     */
    private TeamPositionEnum resolveTeamPosition(String teamPosition, TeamPositionEnum defaultPosition) {
        // ① 空白直接返回兜底职务（不传 = 用默认值）
        if (StrUtil.isBlank(teamPosition)) {
            return defaultPosition;
        }
        // ② 按取值查枚举（大小写敏感），查不到即抛 A0401
        TeamPositionEnum position = TeamPositionEnum.of(teamPosition);
        // 提示里列出全部合法取值：否则管理员只能靠猜，猜错还看不出哪里错
        ThrowUtils.throwIf(position == null, ErrorCode.PARAMS_ERROR,
                "团队职务不合法，仅支持 " + TeamPositionEnum.valuesText());
        // ③ 返回解析出的枚举
        return position;
    }

    /**
     * 解析成员状态：空值取兜底状态，非法值直接报错
     *
     * 与职务解析同一套思路。非法值必须报错：成员状态直接决定这个人能不能用成员功能，
     * 静默兜底成「在队」等于把一个本该离队的人悄悄放回准入名单里。
     *
     * 坑：这里只认 null，不能用「假值判断」——0（在队）是合法取值，用真假判断会把 0 当成没传。
     *
     * @param memberStatus 前端传入的状态，可为 null（null 返回兜底状态）
     * @param defaultStatus 兜底状态，调用方按场景指定（新增时是「在队」）
     * @return 解析出的状态枚举，不会为 null
     * @throws BusinessException 取值不在枚举内时抛出（A0401）
     */
    private MemberStatusEnum resolveMemberStatus(Integer memberStatus, MemberStatusEnum defaultStatus) {
        // ① null 直接返回兜底状态（不传 = 用默认值）。
        //    判的是 null 而不是「假值」：0（在队）是合法取值，用 == 0 之类的判断会把它误当成没传
        if (memberStatus == null) {
            return defaultStatus;
        }
        // ② 按取值查枚举，查不到即抛 A0401
        MemberStatusEnum status = MemberStatusEnum.of(memberStatus);
        ThrowUtils.throwIf(status == null, ErrorCode.PARAMS_ERROR,
                "成员状态不合法，仅支持 " + MemberStatusEnum.valuesText());
        return status;
    }

    /**
     * 校验账号可绑定：账号必须存在，且没有被其他成员档案占用
     *
     * 一个账号被两条档案同时绑定，「这个人是谁」就有两个答案，按账号查档案时返回哪一条都说不清。
     * 唯一性用 count 而不是 selectOne：只关心「有没有」，没必要把整行查回内存。
     *
     * 坑：这是「先查后写」的乐观检查，挡不住并发。真正保证不出现重复绑定的是唯一索引，
     * 所以新增与更新两处写操作都必须 catch DuplicateKeyException。
     *
     * @param userId 待绑定的账号 id，为 null 表示不绑定（直接通过）
     * @param selfMemberId 当前成员 id，新增时传 null；更新时传自身 id，漏传会让「重复保存一次」
     * 被误判成「已被其他成员绑定」
     * @throws BusinessException 账号不存在或已被注销、已被其他档案绑定时抛出（A0401）
     */
    private void assertUserBindable(Long userId, Long selfMemberId) {
        // ① userId 为 null 直接通过——不绑定账号是允许的（历史导入 / 先建档后开号）
        if (userId == null) {
            return;
        }

        // ② selectById 会被 MP 自动追加 deleted_at = 0：绑定已注销（逻辑删除）的账号同样视为「不存在」
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "待绑定的账号不存在或已被注销");

        // ③ 唯一性校验用 count 而不是 selectOne：只需要判断存在性，避免把整行数据查回内存
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioMember::getUserId, userId);
        // 更新场景排除自身：把成员绑到「已属于自己」的账号上是合法的重复提交，
        // 不排除的话重复保存同一个绑定会被误判为「已被其他成员绑定」
        wrapper.ne(selfMemberId != null, StudioMember::getId, selfMemberId);
        long count = this.count(wrapper);

        // 这里是「先查后写」的乐观检查：它挡不住并发竞态（两个请求同时查到 0 条），
        // 最终一致性由数据库唯一索引 uk_userid_deleted 兜底 —— 所以新增与更新两处写操作
        // 都 catch 了 DuplicateKeyException。二者缺一不可：应用层校验负责「友好提示」，
        // 唯一索引负责「绝对不出现脏数据」
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该账号已被其他成员绑定");
    }

    /**
     * 构造分页对象：页码与每页条数做兜底纠正（而不是抛异常）
     *
     * 页码传错没必要让整个列表打不开；但上限必须真的收住，否则一个 pageSize=100000
     * 就能把整表读进内存，请求成本完全由调用方决定。
     *
     * 坑：必须先判「小于 1 取默认值」再压上限，顺序反了会让传入 0 时得到 0 条，
     * 查询直接返回空页，看起来像「这个筛选条件没有数据」。
     *
     * @param query 查询条件，调用方保证非 null
     * @return 分页对象；current 至少为 1，size 在 1~50 之间
     */
    private Page<StudioMember> newPageWithDefaults(MemberQueryRequest query) {
        // ① 页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        // ② 每页条数为 null 或小于 1 时按默认值
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // ③ 上限收敛：不设上限时，一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, pageSize);
    }

    /**
     * 实体分页结果转 VO 分页结果
     *
     * 必须把 total / current / size 一起搬过去，只返回 records 会让前端分页组件算不出总页数。
     * 逐条转换复用 getMemberVO，转换规则只留一份，不会出现「详情有、列表没有」的错位。
     *
     * 坑：不要为「空结果」写提前返回的分支，那种分支很容易漏掉 total，
     * 让前端在空列表上显示「共 1 页」。
     *
     * @param entityPage 实体分页，记录可为空
     * @return 同 total / current / size 的 VO 分页，不会返回 null
     */
    private Page<MemberVO> toMemberVOPage(Page<StudioMember> entityPage) {
        // ① 用原分页的 current / size / total 建一个空 VO 分页
        Page<MemberVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        // ② 把记录逐条转成 VO 后整体塞回去；空记录时得到的是空列表，total 仍是真实的 0
        voPage.setRecords(entityPage.getRecords().stream().map(this::getMemberVO).toList());
        return voPage;
    }

}