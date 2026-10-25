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
 * 本类的三条通用纪律，读每个方法时都会用到：
 * - 校验统一走 ThrowUtils.throwIf（条件成立即抛），业务分支里不写 try-catch；
 * 唯一的例外是数据库唯一索引冲突——它抛的是英文 SQL 异常，不翻成中文提示
 * 就会落到全局兜底变成 B0001「系统错误」，前端既不知道原因、也给不出引导；
 * - 分页参数兜底纠正（页码 < 1 视为 1、pageSize 上限 50），而不是抛异常——
 * 传错页码没必要让整个列表打不开；
 * - 排序字段走白名单 switch 映射，绝不把前端字符串拼进 SQL
 * （ORDER BY 的位置无法用占位符参数化，拼字符串就是最典型的注入入口）。
 *
 * 成员身份的权威来源就是本表：判断「一个人是不是工作室成员」看 studio_member 里
 * 有没有他 user_id 的在队档案，而不是看 sys_user.user_role 是不是 member。
 * 角色只是权限标签，可能与档案不同步（有人被移出名录但角色没改），
 * 按角色放行会绕开真正的准入规则。
 *
 * 本模块特有的四个考点（逐条看方法注释）：
 * - user_id 可选绑定：非空时必须「账号存在」且「未被其他成员绑定」；
 * 应用层查重只管友好提示，并发竞态靠唯一索引 uk_userid_deleted 兜底 ——
 * 所以新增与更新两处写操作都必须 catch DuplicateKeyException 并换成可读提示，
 * 否则前端只会看到 B0001「系统错误」；
 * - grade_year 是 smallint：用 Integer + 区间校验（1950~2100），
 * 拒绝「2026级」这类字符串输入（Jackson 反序列化阶段就会失败）；
 * - team_position / member_status 走枚举校验：非法值入库会让筛选与渲染静默出错；
 * - 默认排序是 sort_order 倒序（官网置顶权重），不是 id 倒序；
 * 并追加 id 倒序作为最终排序键，保证 sort_order 相同的行在分页时次序稳定
 * （排序键不唯一时，翻页会出现「同一条记录出现两次、另一条却看不到」）。
 *
 * 事务说明：单条 insert / update 是一次写，数据库自身的原子性已足够，因此不加事务；
 * 而 deleteMember 是「清理关联表 + 逻辑删除主表」两次写，
 * 加了 @Transactional(rollbackFor = Exception.class)。
 * 写 rollbackFor = Exception.class 的原因：Spring 默认只回滚 RuntimeException，
 * 受检异常（如 SQLException 的包装）不回滚，显式声明才能覆盖全部情况。
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
     * <p>为什么这样设计：成员档案是「这个人是不是工作室成员」的唯一权威来源，
     * 所以建档时必须同时把三件事定下来——人是谁（姓名、届别）、
     * 在不在队（member_status）、绑不绑账号（user_id）。
     * 其中「绑账号」刻意允许为空：历史数据导入、先建档后开号都需要它，
     * 但空绑定的档案对任何账号都不生效，因此这里留一条 warn 日志——
     * 出问题时能顺着日志找到「为什么这个人登录后看不到成员功能」。
     * 真正的防线在授予角色那一侧：给账号加 member / admin 角色时会要求档案已存在，
     * 那才是「有角色没档案」这种坏状态的入口。
     * 校验不依赖 Web 层：DTO 上的 @NotNull / @Min / @Max 只拦得住 HTTP 入口，
     * 定时任务、数据迁移脚本、单测都可能直接调本方法，所以服务端再校验一遍。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应）：
     * <ol>
     *     <li>① 校验请求体与姓名——姓名是 NOT NULL 列，空值必须在写库之前挡住，
     *     否则报错会以数据库异常的形式抛出来，落到全局兜底变成 B0001；</li>
     *     <li>② 未绑定账号时记一条 warn——不拦，但要留下可追溯的痕迹；</li>
     *     <li>③ 校验入学年份区间、解析职务与状态——放在组装实体之前，
     *     这样后面 set 进去的一定是合法值，不必在赋值处再判一次；</li>
     *     <li>④ 校验账号可绑定（存在 + 未被别的档案占用）——必须排在写库之前，
     *     否则脏数据已经落库，只能回头删；</li>
     *     <li>⑤ 组装实体并落库，同时把唯一索引冲突翻成中文提示。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ④ 步是「先查后写」的乐观检查，它挡不住并发——
     * 两个管理员同时把同一个账号绑到两条新档案上，两边都会查到「没人占用」。
     * 真正保证不出脏数据的是唯一索引 uk_userid_deleted，所以第 ⑤ 步的 catch 不能省。
     * 二者缺一不可：应用层校验负责「友好提示」，唯一索引负责「绝对不出现重复绑定」。
     *
     * @param memberAddRequest 新增请求：姓名必填（最长 64 字符）、入学年份必填（1950~2100）、
     * user_id 选填（为空则该档案不会对任何账号生效）、职务与状态选填（为空分别按
     * 成员 member 与在队 0 处理）；不能为 null，为 null 抛 A0401
     * @return 新成员档案的主键 ID（雪花算法生成的 19 位长整型，不是数据库自增主键；
     * 序列化成 JSON 时由全局配置转成字符串，前端不会精度丢失）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 姓名为空、入学年份越界、
     * 职务 / 状态取值非法、待绑定账号不存在或已被占用时抛出（A0401）；
     * 入库失败时抛出（B0001）
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
     * <p>为什么这样设计：管理端的编辑表单常常只改一两项（例如只换头像），
     * 若要求前端回传整行数据，前端漏一个字段就会把它误清空——这种 bug 极难发现，
     * 因为接口返回成功、页面也正常，只是某个字段悄悄没了。
     * 因此这里采用「传 null 表示不修改」，靠 updateById 默认跳过 null 字段实现。
     * 代价是这套接口无法把字段清空（例如解除账号绑定），那需要语义明确的专用接口，
     * 而不是用传空串这种模糊写法来表达。
     * 更新字段是服务端写死的白名单：绝不接收前端实体整体更新，
     * 否则前端能顺手改动 deletedAt、审计字段这些内部数据。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应）：
     * <ol>
     *     <li>① 校验 id——没有 id 就不知道该改谁，此时 updateById 只会返回 false，
     *     前端只看到「更新失败」而不知道原因；</li>
     *     <li>② 先查目标是否存在——这一步不能省，updateById 对不存在的 id 只是影响 0 行，
     *     不查就分不清「记录不存在」与「字段值没变化」；</li>
     *     <li>③ 逐个校验传了的字段（届别 / 职务 / 状态）——与新增走同一套规则，
     *     避免出现「新增拦得住、编辑拦不住」的两套标准；</li>
     *     <li>④ 校验账号可绑定，并排除自身——把成员绑到「已属于自己」的账号上是合法的重复提交；</li>
     *     <li>⑤ 校验姓名（传了就不能是纯空白），再只 set 白名单字段并更新，
     *     同时把唯一索引冲突翻成中文提示。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步的几段校验都必须先判「传了没有」再校验。
     * 若写成无条件校验，前端只想改头像时也会因为 gradeYear 为 null 而被拒——
     * 那样「部分更新」的语义就名存实亡了。
     * 另外第 ④ 步的 selfMemberId 必须传 id：漏掉它，把同一个账号重复保存一次
     * 就会被自己的旧记录判成「已被其他成员绑定」，表现为「不改账号却保存不了」。
     *
     * @param memberUpdateRequest 更新请求：id 必填；其余字段为 null 表示不修改；
     * 姓名若传则不能是纯空白（name 是 NOT NULL 列）；不能为 null，为 null 抛 A0401
     * @return true 表示更新成功；false 说明没有命中任何行（典型情况是记录在
     * 「查存在」与「执行更新」之间被并发删除了）。调用方不要把它当成「字段值是否变化」
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 为空、姓名纯空白、
     * 届别越界、职务 / 状态非法、待绑定账号不存在或已被占用时抛出（A0401）；
     * 成员不存在时抛出（A0402）
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
     * <p>为什么这样设计：查不到时抛 A0402 而不是返回 null。
     * 返回 null 的话接口仍然是「成功」，前端拿到 data 为空只能自己猜——
     * 是这个人不存在、还是后端漏查了？把它变成明确的错误码，
     * 前端的处理路径就唯一了：提示「成员不存在」并刷新列表。
     * 反过来说，成员功能里的「不是成员」不该走这个方法——那是一个正常的查询结果，
     * 用 getActiveMemberByUserId 返回 null 表达，两者的语义完全不同。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按主键取档案（@TableLogic 会自动追加 deleted_at = 0，
     *     已逻辑删除的档案等同于不存在）；</li>
     *     <li>② 查不到就抛 A0402；</li>
     *     <li>③ 转成管理端 VO 返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要顺手在这里补「顺便把关联的证书也查出来」。
     * 那会让一次详情查询变成多次查询，而且详情接口会被用在编辑弹窗里反复调用；
     * 证书与项目各有自己的列表接口，需要时由前端按需再取。
     *
     * @param id 成员档案 ID（雪花 ID，必须大于 0；传 0 或负数会直接落到 A0402，
     * 因为主键不可能取到这两类值）
     * @return 管理端 VO，含 userId / sortOrder / 时间字段；不含密码等账号信息
     * @throws com.bhu.runshistudioweb.exception.BusinessException 档案不存在或已被逻辑删除时抛出（A0402）
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
     * <p>为什么这样设计：两次写（清理关联表 + 逻辑删除主表）必须在同一事务里完成。
     * 如果先清关联、主表删除失败，成员还在但证书关系没了——数据静默丢失，
     * 而且没有任何报错；如果先删主表、清理失败，就留下指向已删除成员的悬空关联，
     * 表现为「某个已删除的成员还出现在项目参与人列表里」。
     * 主表用逻辑删除（只把 deleted_at 标成非 0，行仍留在表里，查询时由框架
     * 自动追加 deleted_at = 0 过滤）而不是物理删除：成员档案涉及考勤、帖子等历史数据，
     * 物理删掉会让这些数据失去归属。逻辑删除还有个副作用是想要的——
     * 唯一索引 uk_userid_deleted 带上 deleted_at，档案删掉后它绑定过的账号就被释放了，
     * 可以重新绑定到新档案上。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 先确认档案存在——不存在就抛 A0402，避免对不存在的 id 做两次无意义的删除；</li>
     *     <li>② 清两张关联表（成员-证书、成员-项目）。必须排在删主表之前：
     *     主表一旦先删掉，再清关联时若失败，就没人能发现这些行已经是孤儿了；</li>
     *     <li>③ 最后逻辑删除主表——它是这个事务的「主动作」，
     *     放最后才能保证「主表没了，关联一定已经清干净」。</li>
     * </ol>
     *
     * <p>容易踩的坑：两张关联表必须都清，只清一张就会留下悬空关联，
     * 而这种脏数据不会报错、只会在某个列表页里悄悄出现。
     * 另外两个 removeByXxxId 是物理删除（关联表没有 deleted_at 列，也不该有）——
     * 若它们改成逻辑删除，唯一索引会被已删除的行占住，
     * 「先解绑、再重新绑定」这个正常操作就会一直冲突。
     *
     * @param id 成员档案 ID，必须大于 0；档案不存在（含已逻辑删除）时抛 A0402
     * @return true 表示主表删除成功。注意：返回 true 不代表「之前一定存在关联」——
     * 关联清理返回 0 行只是说明这个人本来就没绑证书 / 项目，不是失败
     * @throws com.bhu.runshistudioweb.exception.BusinessException 档案不存在时抛出（A0402）
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
     * <p>为什么这样设计：分页参数在这里做兜底纠正（页码 < 1 视为 1、每页条数收敛到上限），
     * 而不是靠 DTO 上的注解校验报错。两个理由：传错页码没必要让整个列表打不开，
     * 纠正后继续查对使用者更友好；而且 GET 请求的参数校验失败抛的是 BindException，
     * 会落到全局兜底变成 B0001「系统错误」，前端会把「页码写错了」显示成「服务器故障」。
     * 筛选条件与排序规则都抽成了私有方法，与其它列表入口共用同一份实现——
     * 两处各写一遍，迟早会演变成「后台筛得出、官网筛不出」这种难查的差异。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 请求体为 null 时兜一个空对象——「不传条件」等价于「无条件查第一页」，
     *     这样后面取字段不必到处判空；</li>
     *     <li>② 拼筛选条件与排序。排序必须在分页查询之前拼进同一个 wrapper：
     *     分页是 SQL 层做的，先查回来再在内存里排序，排的只是当前这一页，没有意义；</li>
     *     <li>③ 分页查询并转成 VO 分页。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验登录态与管理员角色——这两道在 Controller 上
     * 由 @SaCheckRole("admin") 拦下。成员档案含 user_id、置顶权重等内部字段，
     * 换给普通用户调用就是一次内部数据泄露，所以不能把本方法暴露成匿名接口。
     * 另外排序字段必须走 applySort 的白名单，不能直接拿 query.getSortField()
     * 拼进 SQL——那是注入入口，而且 ORDER BY 的位置无法用占位符参数化。
     *
     * @param memberQueryRequest 查询条件，允许整体为 null（等价于无条件查第一页）；
     * 各字段为 null 表示「该条件不参与筛选」，不是「查该字段为空的行」
     * @return 分页结果（记录为管理端 VO，含内部字段）；查不到时返回空记录分页，
     * 不会返回 null，调用方不必判空
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
     * <p>为什么这样设计：管理端要能核对账号绑定、置顶权重这些内部配置，
     * 所以 MemberVO 是「宽」的——它含 userId、sortOrder、时间字段。
     * 转换靠 BeanUtils.copyProperties 按属性名自动拷贝，不手写一长串 setXxx：
     * 手写的话，实体加一个字段就得记得来这里补一行，漏了不报错、只是永远显示空。
     * 这里的风险方向是「多给」而不是「给错」——多给的前提是调用方是管理员，
     * 由 Controller 上的 @SaCheckRole 保证；给游客看的版本是另一个方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 直接返回 null——本方法要能安全地处理「查不到」，
     *     否则调用方每次都得先判空；</li>
     *     <li>② 新建 VO 并按同名属性拷贝。</li>
     * </ol>
     *
     * <p>容易踩的坑：copyProperties 只拷「两边都存在且类型兼容」的属性，
     * 它不会报错也不会警告。所以给 VO 加字段后，要确认实体里确实有同名的源字段，
     * 否则会得到一个恒为 null 的属性，而代码看起来一切正常。
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
     * <p>为什么这样设计：脱敏靠目标 VO 的字段结构实现，而不是「拷完再手动置空几个字段」。
     * MemberFrontVO 里根本没有 userId / sortOrder / createdBy / updatedBy / deletedAt
     * 这些属性，copyProperties 自然带不出去——这是「结构上脱敏」。
     * 手动置空那种写法的问题在于：将来实体新增一个内部字段、VO 又恰好同名时，
     * 它会静默泄露，而且没有任何编译期提示。
     * 两条路线（一个 VO 加开关 vs 两个 VO）之间选后者，理由也是同一个：
     * 字段级的 if 判断只要写错一处就泄露，分两个类则是「结构上不可能泄露」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 直接返回 null；</li>
     *     <li>② 新建 VO 并按同名属性拷贝——目标类没有的字段会被自动跳过。</li>
     * </ol>
     *
     * <p>容易踩的坑：脱敏效果完全取决于 MemberFrontVO 的字段清单。
     * 将来给它补字段前，先问一句「这个字段愿意给所有游客看吗」——
     * 一旦加进去，所有公开接口都会立刻开始返回它，没有任何拦截点。
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
     * <p>为什么这样设计：准入判定问的是「这个登录账号有没有在队档案」，
     * 答案必须来自 studio_member 表，而不是 sys_user.user_role 是不是 member。
     * 角色只是权限标签，可能与档案不同步（有人被移出名录但角色没改），
     * 按角色放行等于绕开真正的准入规则。这也是发帖、签到等接口共用的判据：
     * 同一套规则集中在一处，才不会出现「发帖说你是成员、签到说你不是」。
     * 查询条件有两条，缺一不可：user_id 相等（档案必须绑定了这个账号，
     * 未绑定的档案 user_id 是 NULL）、member_status = 在队（毕业离队的成员
     * 不再有成员功能，但他们不该报错、也不该被静默放行——这个条件就是那道门槛）。
     * 第三重条件 deleted_at = 0 由 @TableLogic 自动追加，不需要手写。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① userId 为 null 直接返回 null——未登录 / 无绑定时连 SQL 都不必发；</li>
     *     <li>② 按「user_id + member_status = 在队」查一条；</li>
     *     <li>③ 返回查到的档案或 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 null 是正常结果，不是异常——普通注册用户就是这个结果，
     * 真正的拦截（A0301）发生在发帖、打卡这些业务接口里。让查询接口抛异常，
     * 会把「查询」和「鉴权」两件事混在一起，前端也没法用它来决定要不要显示入口。
     *
     * @param userId 登录账号 ID（sys_user.id），为 null 时直接返回 null（未登录 / 无绑定）
     * @return 在队的成员档案；账号未绑定任何档案、档案已毕业离队、档案已删除时返回 null，
     * 调用方必须判空——不能假定「登录了就一定有档案」
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
     * <p>为什么这样设计：条件重载的第一个参数是一个布尔开关——传 false 时该条件
     * 不会拼进 SQL。这样就不必为每个可选筛选手写 if 块，条件多了也不会把方法撑成一片 if。
     * 模糊与精确的选择有明确依据：姓名与方向是自由文本，用户习惯用关键词搜，
     * 所以用 like；届别、职务、状态是数值或枚举值域，精确匹配才能命中索引，
     * 结果也才可预期（用 like 匹配状态，'1' 会连 '10' 一起命中）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 逐个拼条件，每个条件都自带「开关」判断，传 null / 空白即跳过；</li>
     *     <li>② 返回包装器，由调用方继续追加排序与分页。</li>
     * </ol>
     *
     * <p>容易踩的坑：like 用的是占位符传参，不存在 SQL 注入；但用户输入里的
     * % 与 _ 仍会被当成通配符（这是 LIKE 的固有语义，这里刻意不转义）——
     * 也就是说搜「a%b」会命中「a 与 b 之间夹任意字符」的结果。
     * 另外这里只拼「传了才生效」的条件，不传不等于「查该字段为空的行」，
     * 这两种语义千万别混。
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
     * <p>为什么这样设计：前端只能传「字段名」这样的字符串，由这里映射成实体字段引用。
     * 绝不能把前端字符串直接拼进 SQL 的 ORDER BY——那是最典型的 SQL 注入入口，
     * 而且 ORDER BY 的位置无法用占位符参数化，白名单是唯一可靠的防线。
     * 每个分支都在主排序键之后追加了 id 倒序，这不是装饰：
     * 排序键不唯一时（例如一批 sort_order 都是 0 的成员），数据库返回的顺序是不确定的，
     * 翻页就会出现「同一条记录出现两次、另一条却看不到」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先把排序方向归一成布尔值——只有明确的 asc 才算升序，其余（含拼错、不传）都按降序；</li>
     *     <li>② 按字段名分支拼接；认不出的字段名走 default，落到产品约定的默认排序。</li>
     * </ol>
     *
     * <p>容易踩的坑：默认排序是 sort_order 倒序（官网置顶权重，数值越大越靠前），
     * 而不是 id 倒序。id 倒序在这里只作为次级稳定键——成员列表是运营排序，
     * 置顶是产品明确要求的能力，用 id 倒序当默认会让置顶功能看起来完全没生效。
     *
     * @param wrapper 查询包装器，本方法只往里追加排序，不碰筛选条件
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
     * <p>为什么这样设计：grade_year 是 smallint 列，数据库能存下 1800 与 3200，
     * 但那两个值在业务上不可能出现，存进去之后按届别筛选、按届分组都会得到怪结果。
     * 区间上下限用常量而不是把数字写在判断里，是为了和 DTO 上的 @Min / @Max 保持同一个来源。
     * 「2026级」这类字符串不需要在这里处理——它在 JSON 反序列化阶段就失败了，
     * 由全局异常处理器统一转成 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 判空——新增场景必填，传 null 说明调用方漏了校验；</li>
     *     <li>② 判断是否越界，越界即抛 A0401。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里把 null 也当成不合法，是因为本方法只服务于「新增 / 修改时传了值」
     * 两种场景。若将来要支持「传 null 表示不修改」，必须在调用处先判空再进来，
     * 而不是把 null 在这里放行——那会让新增接口能建出一条没有届别的档案。
     *
     * @param gradeYear 入学年份，可为 null（null 视为不合法）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 为 null 或不在
     * 1950~2100 之间时抛出（A0401）
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
     * <p>为什么这样设计：职务取值定义在枚举里，但校验放在 Service 而不是 DTO 注解上。
     * 把取值写死在 @Pattern 里的话，枚举一加新职务，注解就过期了——
     * 而这种过期不会报错，只会表现为「新职务怎么都存不进去」。
     * 非法值直接报错而不是静默兜底成默认职务：静默兜底会让管理员以为自己设置成功了，
     * 直到某天发现某人的职务莫名其妙变成了「成员」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空白直接返回兜底职务（不传 = 用默认值）；</li>
     *     <li>② 按取值查枚举，查不到即抛 A0401，错误文案里列出全部合法取值。</li>
     * </ol>
     *
     * <p>容易踩的坑：枚举查找是大小写敏感的（member 不等于 Member）。
     * 若前端传来首字母大写的写法，会被判成非法值——这是刻意的，
     * 因为入库的值必须与 DDL 注释里约定的字面量完全一致。
     *
     * @param teamPosition 前端传入的职务，可为空（为空返回兜底职务）
     * @param defaultPosition 兜底职务，调用方按场景指定（新增时是「成员」）
     * @return 解析出的职务枚举，不会为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 取值不在枚举内时抛出（A0401）
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
     * <p>为什么这样设计：与职务解析同一套思路——枚举是取值的唯一来源，
     * 校验放在 Service 里而不是把数字写死在注解上。非法值必须报错，
     * 因为成员状态直接决定这个人能不能用成员功能：静默兜底成「在队」，
     * 等于把一个本该离队的人悄悄放回准入名单里。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① null 直接返回兜底状态（不传 = 用默认值）；</li>
     *     <li>② 按取值查枚举，查不到即抛 A0401。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里只认 null，不认「空串转数字」这类模糊输入——
     * 请求体里的 memberStatus 是 Integer，非数字会在反序列化阶段就失败（A0401），
     * 不会走到这里；而 0 是一个合法取值，不能用「假值判断」代替 null 判断，
     * 否则传 0（在队）会被当成「没传」。
     *
     * @param memberStatus 前端传入的状态，可为 null（null 返回兜底状态）
     * @param defaultStatus 兜底状态，调用方按场景指定（新增时是「在队」）
     * @return 解析出的状态枚举，不会为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 取值不在枚举内时抛出（A0401）
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
     * <p>为什么这样设计：成员档案与登录账号是可选的一对一关系，
     * 一个账号被两条档案同时绑定，会让「这个人是谁」出现两个答案——
     * 按账号查档案时返回哪一条都说不清，成员功能的行为会变得不可预测。
     * 所以这里做两道检查：账号存在（且未被逻辑删除）、没有被别的档案占用。
     * 唯一性用 count 而不是 selectOne：只关心「有没有」，没必要把整行数据查回内存。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① userId 为 null 直接通过——不绑定账号是允许的（历史导入 / 先建档后开号）；</li>
     *     <li>② 查账号，查不到即抛错（selectById 会自动追加 deleted_at = 0，
     *     已注销的账号等同于不存在）；</li>
     *     <li>③ 统计「绑定了这个账号的其它档案」条数，大于 0 即抛错。</li>
     * </ol>
     *
     * <p>容易踩的坑：这是「先查后写」的乐观检查，它挡不住并发——两个请求可能同时查到 0 条，
     * 然后各自插入成功。真正保证不出现重复绑定的是数据库唯一索引 uk_userid_deleted，
     * 所以新增与更新两处写操作都必须 catch DuplicateKeyException。
     * 二者缺一不可：应用层校验负责「友好提示」，唯一索引负责「绝对不出现脏数据」。
     *
     * @param userId 待绑定的账号 id，为 null 表示不绑定（直接通过）
     * @param selfMemberId 当前成员 id，新增时传 null；更新时传自身 id（用于排除自己）。
     * 更新时漏传会让「把同一个账号重复保存一次」被误判成「已被其他成员绑定」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 账号不存在或已被注销、
     * 已被其他成员档案绑定时抛出（A0401）
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
     * <p>为什么这样设计：分页参数来自前端，传 0、传负数、传一个巨大的 pageSize 都是常见的。
     * 对前两种，抛异常让整个列表打不开并不划算，纠正后继续查更友好；
     * 对第三种必须设上限——不设上限时一个 pageSize=100000 就能把整表读进内存，
     * 请求成本完全由调用方决定。这与「参数非法就抛 BusinessException」的总体约定
     * 是刻意的不一致，理由就是「页码错了没必要让查询失败」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 页码为 null 或小于 1 时按 1 处理；</li>
     *     <li>② 每页条数为 null 或小于 1 时按默认值；</li>
     *     <li>③ 再把每页条数收敛到上限，最后构造分页对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ② 步与第 ③ 步的顺序不能反——先取 min 再判「小于 1」，
     * 会让传入 0 时得到 0 条，查询直接返回空页，看起来像「这个筛选条件没有数据」。
     * 另外 searchCount 保持默认的 true：前端分页组件要靠 total 算总页数。
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
     * <p>为什么这样设计：必须新建一个 VO 分页并把 total / current / size 一起搬过去，
     * 不能只把 records 取出来返回一个 List——前端的分页组件要靠 total 算总页数，
     * 丢了它就只能显示「一页」。
     * 逐条转换直接复用 getMemberVO，而不是在这里再写一遍属性拷贝：
     * 转换规则只留一份，将来给 VO 加字段时不会出现「详情接口有、列表接口没有」的错位。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用原分页的 current / size / total 建一个空 VO 分页；</li>
     *     <li>② 把记录逐条转成 VO 后整体塞回去。</li>
     * </ol>
     *
     * <p>容易踩的坑：空记录时也要走同一条路径——records 是空列表，转换结果仍是空列表，
     * total 仍然是真实的 0。不要为「空结果」写一条提前返回的分支，
     * 那种分支很容易漏掉 total，导致前端在空列表上显示「共 1 页」。
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