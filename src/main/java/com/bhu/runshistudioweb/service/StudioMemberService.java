package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

/**
 * 工作室成员服务接口
 *
 * <p>本接口是成员档案对调用方的契约，实现类只解释「为什么这么实现」。
 *
 * <p>业务规则：团队成员不对外展示——本接口如今只有管理端方法：
 * addMember、updateMember、getMemberById、deleteMember、
 * listMemberByPage，调用方必须已通过 {@code @SaCheckRole("admin")} 校验，
 * 返回含内部字段的 MemberVO。
 * 本接口不含 C 端方法（官网成员列表 / 详情）：成员档案只作为后台内部数据。
 *
 * <p>getMemberFrontVO（脱敏转换）仍然保留：唯一的对外出处是项目详情
 * 里的「参与成员」（ /project/detail，产品确认保留），
 * 由 MemberProjectService#listFrontMembersByProject 使用。
 * 绝不允许把管理端的 MemberVO 用到对外接口上（会公开 user_id 等内部字段）——
 * 两个 VO 的字段差异就是这条边界的物理实现，靠的是「目标类里没有这个字段」，
 * 而不是「转换时记得置空」。
 *
 * <p>成员身份的判定权威：{@code studio_member} 表里存在 user_id 命中且
 * member_status = 0 的档案，而不是 {@code sys_user.user_role}。
 * 角色只是权限标签，档案才是业务事实，两者可能不同步——考勤与刷题两个模块
 * 都靠 getActiveMemberByUserId 做准入，因此本接口的语义必须与它们一致。
 *
 * <p>约定：所有方法在「参数不合法 / 业务不允许」时抛 BusinessException，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应
 * （例外：getMemberVO、getMemberFrontVO 与 getActiveMemberByUserId 是纯转换 / 纯查询方法，
 * 各自的 null 语义写在方法注释里）。
 *
 * @author shaoshing
 */
public interface StudioMemberService extends IService<StudioMember> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 管理员新增成员档案
     *
     * <p>为什么这样设计：成员档案是「谁有资格用考勤与刷题」的唯一凭据，
     * 因此它的写入比普通 CRUD 多两道校验。这两道各自对应一种「不拦住就会出事」的情况：
     * 一是绑定了不存在或已被别人占用的账号（会导致一个人拥有两份档案，
     * 或档案指向一个登不进来的号）；二是入学年份写成了明显不合理的值
     * （smallint 列存不下「2026级」这类字符串，而脏值会让届别筛选静默漏人）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验姓名非空。姓名是官网展示的主字段，空名字会让列表出现空白行；</li>
     *     <li>② 校验入学年份区间（1950~2100）。DTO 上的 {@code @Min/@Max} 已拦一道，
     *     这里再拦一道，因为 Service 也可能被定时任务或测试直接调用，不能只依赖 Web 层；</li>
     *     <li>③ 解析职务与状态：空值取默认，非空必须是枚举内取值。
     *     不用 {@code @Pattern} 把取值写死在注解上——枚举一改，注解就过期了；</li>
     *     <li>④ 绑定账号校验（userId 非空时）：必须「账号存在」且「未被其他成员绑定」；</li>
     *     <li>⑤ 组装实体并落库，接住唯一索引冲突；</li>
     *     <li>⑥ 返回新成员档案 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：并发竞态靠唯一索引兜底。两个请求同时通过第 ④ 步的「未被绑定」检查时，
     * 后到的那条会被数据库唯一索引拦下并抛 DuplicateKeyException，
     * 这里必须把它翻成可读提示，否则会落到全局兜底变成 B0001「系统错误」，
     * 前端既不知道原因、也没法给出「该账号已被绑定」的引导。
     * 另一个坑是 userId 允许为空（历史导入 / 先建档后开号），
     * 但 user_id 为 NULL 的档案<b>永远不会生效</b>——准入判据是「档案 user_id 命中当前账号」，
     * 这类档案不会报错，只是本人登录后看不到任何成员功能。
     *
     * @param memberAddRequest 新增请求，不能为 null；姓名与入学年份必填；
     *                         userId 选填（非空时必须是存在且未被占用的账号）；
     *                         teamPosition / memberStatus 不传时分别按「成员」「在队」处理
     * @return 新成员档案 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     *         账号不存在或已被绑定（A0401）、入库失败（B0001）时抛出
     */
    long addMember(MemberAddRequest memberAddRequest);

    /**
     * 管理员更新成员档案（部分更新：字段为 null 表示不修改）
     *
     * <p>为什么这样设计：部分更新让前端「只改简介」时不必回传整份档案，
     * 它成立的前提是 MyBatis-Plus 的更新默认跳过 null 字段——
     * 因此这里绝不能给未传的字段填默认值，否则「不修改」会悄悄变成「改成默认值」。
     * 更新字段由服务端写死成白名单，绝不接收前端实体整体更新：
     * 否则前端能顺手改动 deletedAt、审计字段等内部数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 非空，并先查一次确认成员存在。否则更新失败只会返回 false，
     *     前端分不清「不存在」与「没改成」；</li>
     *     <li>② 传了的字段才校验，规则与新增完全一致（同一套常量、同一套枚举），
     *     避免两条入口出现两套标准；</li>
     *     <li>③ 改绑账号时<b>排除自身</b>再校验唯一性；</li>
     *     <li>④ 姓名传了就必须有实际内容：姓名是 NOT NULL 列，
     *     只做 trim 而不判空的话，一串空格会被清成空串写进库；</li>
     *     <li>⑤ 只 set 白名单字段后写库，接住唯一索引冲突。</li>
     * </ol>
     *
     * <p>容易踩的坑：改绑账号时的「排除自身」不能省。把成员 A 绑定到
     * 「已属于 A 自己」的账号是合法的重复提交，不做排除的话，
     * 用户重复保存同一个 userId 会被误判为「已被其他成员绑定」。
     * 另一个坑是返回 true 不代表数据真的变了——什么都没改时也是 true。
     *
     * @param memberUpdateRequest 更新请求，不能为 null；id 必填；
     *                            其余字段为 null 表示保持原值，传了空白的姓名会抛 A0401
     * @return true 表示更新成功；成员不存在时抛异常而不是返回 false
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在（A0402）、
     *         账号不存在或已被其他成员绑定（A0401）、参数不合法（A0401）时抛出
     */
    boolean updateMember(MemberUpdateRequest memberUpdateRequest);

    /**
     * 管理员按 id 查询成员详情
     *
     * <p>为什么这样设计：不存在时抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code=0 但 data 为空」，分不清是没有数据还是接口出错，
     * 这类含糊的成功在排查时最费时间。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按主键查实体。逻辑删除的档案会被自动过滤，查不到即视为不存在；</li>
     *     <li>② 判空并抛 A0402；</li>
     *     <li>③ 转成管理端 VO——它包含 userId、置顶权重与审计时间，
     *     只能给管理员看，不能用于任何匿名接口。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的是<b>管理端</b> VO。若哪天要在官网详情页展示成员，
     * 必须改用 getMemberFrontVO，直接复用这里会把账号绑定关系暴露给游客。
     *
     * @param id 成员 ID
     * @return 含内部字段的成员信息（管理端视图），永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出（A0402）
     */
    MemberVO getMemberById(long id);

    /**
     * 管理员逻辑删除成员档案
     *
     * <p>为什么这样设计：删除是「主表写 + 两张关联表写」三次操作，必须在同一事务内。
     * 顺序是先清关联、再删主表——反过来一旦清理失败，
     * 就会留下指向已删除成员的悬空关联，表现为「某个已删除的成员还出现在项目参与人列表里」，
     * 而且不会有任何报错。主表用逻辑删除（写 deleted_at 毫秒时间戳）保留可追溯性；
     * 关联表用物理删除，因为「谁参与了一个已不存在的成员」没有保留价值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先查一次确认成员存在，不存在给 A0402——否则删除 0 行会被误认为成功；</li>
     *     <li>② 清掉成员-证书关联；</li>
     *     <li>③ 清掉成员-项目关联。两张表都要清，只清一张就会留下悬空关联；</li>
     *     <li>④ 逻辑删除主表。</li>
     * </ol>
     *
     * <p>容易踩的坑：逻辑删除之后，该成员绑定过的账号会被<b>释放</b>——
     * 唯一索引带着 deleted_at，因此同一个账号可以重新绑定到新档案。
     * 这是刻意设计（换人、重建档案都要用到），但也意味着「删除成员」不会阻止
     * 该账号再次成为成员。另外返回 true 只代表 UPDATE 命中了行，
     * 数据并没有从库里消失。
     *
     * @param id 成员 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 成员不存在时抛出（A0402）
     */
    boolean deleteMember(long id);

    /**
     * 管理员分页查询成员列表
     *
     * <p>为什么这样设计：分页与排序参数的兜底纠正在实现里完成，而不是靠 DTO 校验报错。
     * 原因是这些参数走 GET，校验失败抛的是 BindException，
     * 会落到全局兜底变成 B0001「系统错误」——用户只是页码传错了，却看到「服务器故障」。
     * 排序字段还必须走白名单：ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是最典型的 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时兜一个空对象，表示无条件查第一页；</li>
     *     <li>② 拼筛选条件：id / 届别 / 职务 / 状态精确匹配，姓名与方向模糊匹配；</li>
     *     <li>③ 应用排序，默认按 sort_order 倒序（官网置顶权重），而不是 id 倒序；</li>
     *     <li>④ 分页查询并转成管理端 VO 分页对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：默认排序刻意不是 id 倒序。成员列表是运营排序，
     * 「置顶」是产品明确要求的能力，用 id 倒序会让管理员设的置顶权重完全失效；
     * id 倒序只作为次级键，用来保证 sort_order 相同的行在分页时次序稳定。
     * 另一个坑是模糊查询没有转义 % 与 _，用户搜「100%」会匹配到多余结果，
     * 这属于 LIKE 的固有语义，本项目选择不额外转义。
     *
     * @param memberQueryRequest 查询条件，允许整体为 null（表示无条件查第一页）；
     *                           字段为 null 表示「该条件不参与筛选」，不是「查 null 值」
     * @return 分页结果，记录为含内部字段的 MemberVO；
     *         没有匹配数据时 records 为空列表（不是 null）
     */
    Page<MemberVO> listMemberByPage(MemberQueryRequest memberQueryRequest);

    // ==================== C 端：只有一个脱敏转换方法 ====================

    // 本接口刻意没有「官网成员列表 / 官网成员详情」这类匿名方法：
    // 成员档案只经管理端读取（listMemberByPage / getMemberById，均要求 admin）。
    //
    // getMemberFrontVO 是唯一的 C 端出口：项目详情 /project/detail 仍需返回「参与成员」
    // （产品确认的例外），由 MemberProjectService#listFrontMembersByProject 调用。

    /**
     * 实体转「管理端」VO
     *
     * <p>为什么这样设计：用属性名自动拷贝，避免手写一长串 setXxx（漏一个字段就是线上 bug）。
     * 管理端 VO 含全部字段，这里的风险是「多给」而不是「给错」，
     * 因此只要调用方是管理端接口，全字段拷贝是安全的。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null。这是纯转换方法，不承载任何业务判断，
     *     不该为 null 抛异常；</li>
     *     <li>② 按属性名自动拷贝。</li>
     * </ol>
     *
     * <p>容易踩的坑：自动拷贝只按属性名与类型匹配，类型不一致的属性会被<b>静默跳过</b>、
     * 不报任何错。所以给 VO 加字段时，类型必须与实体完全一致
     * （实体是 LocalDateTime，VO 写成 Date 就会永远拿到 null）。
     *
     * @param member 成员实体，允许为 null
     * @return 含内部字段的管理端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberVO getMemberVO(StudioMember member);

    /**
     * 实体转「C 端」VO
     *
     * <p>为什么这样设计：脱敏不是一段逻辑，而是 VO 的结构——
     * MemberFrontVO 里没有 userId、sortOrder 与审计字段，因此自动拷贝时
     * 这些内部信息根本无处可去。这比「拷完再手动置空」可靠得多：
     * 将来给实体新增一个敏感字段时，不会被漏掉。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null，与 getMemberVO 保持同一套 null 语义；</li>
     *     <li>② 自动拷贝到脱敏 VO，目标类里没有的字段自然被丢弃。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「什么都没做」就是正确的脱敏。
     * 若给 MemberFrontVO 补上 userId 或 sortOrder，脱敏会在零代码改动的情况下失效——
     * 加字段前先问「这个字段愿意给所有人看吗」。
     *
     * @param member 成员实体，允许为 null
     * @return 脱敏后的 C 端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberFrontVO getMemberFrontVO(StudioMember member);

    // ==================== 考勤模块依赖：按登录账号查在队成员 ====================

    /**
     * 按绑定的登录账号 ID 查询在队的成员档案
     *
     * <p>为什么考勤要的是这个方法，而不是复用 getMemberById：
     * 考勤的准入规则是「当前登录账号是不是被管理员标记过的工作室成员」，
     * 判定链条是 sys_user.id → studio_member.user_id，
     * 且必须是在队（member_status = 0）状态——毕业 / 离队的成员不再参与考勤。
     * getMemberById 是按成员档案主键查，答不上这个问题。
     *
     * <p>注意它与 sys_user.user_role 的关系：角色里也有 member 取值，
     * 但那是权限标签，可能与档案不同步。成员身份的权威是 studio_member 表，
     * 因此这里查表而不是查角色。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① userId 为 null 时直接返回 null（未登录 / 无绑定），
     *     省掉一次注定查不到的查询；</li>
     *     <li>② 按「user_id 命中 + member_status = 0」查一条。两个条件缺一不可：
     *     少前者会查到别人的档案，少后者会让毕业成员继续拥有考勤资格；</li>
     *     <li>③ 返回第一条。这里刻意不要求「最多一条」——
     *     唯一索引本已保证，但手工改库造出的脏数据不该让整个签到功能挂掉。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回 null 表示「查不到」，这是正常状态而不是错误——
     * 调用方要拿「查不到」这件事来生成 A0301 无权限提示，而不是让它炸掉。
     * 若把「查不到」改成抛异常，非成员调用签到接口时得到的会是 A0402 而不是 A0301，
     * 前端的引导（去登录 / 找管理员）就会走错分支。
     *
     * @param userId 登录账号 ID，为 null 时直接返回 null（未登录 / 无绑定）
     * @return 在队的成员档案；查不到返回 null（不是抛异常——调用方要拿
     *         「查不到」这件事来生成 A0301 无权限提示，而不是让它炸掉）
     */
    StudioMember getActiveMemberByUserId(Long userId);
}
