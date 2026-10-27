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
 * 业务规则：团队成员不对外展示，因此本接口只有管理端方法，调用方必须已通过
 * {@code @SaCheckRole("admin")} 校验，返回含内部字段的 MemberVO。
 *
 * 成员身份的判定权威：{@code studio_member} 表里存在 user_id 命中且 member_status = 0 的档案，
 * 而不是 {@code sys_user.user_role}。角色只是权限标签，档案才是业务事实，两者可能不同步——
 * 考勤与刷题两个模块都靠 getActiveMemberByUserId 做准入，因此本接口的语义必须与它们一致。
 *
 * getMemberFrontVO 是唯一的对外出口，只被项目详情里的「参与成员」使用。
 * 两个 VO 的字段差异就是这条边界的物理实现，靠的是「目标类里没有这个字段」。
 *
 * @author shaoshing
 */
public interface StudioMemberService extends IService<StudioMember> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 管理员新增成员档案
     *
     * 成员档案是「谁有资格用考勤与刷题」的唯一凭据，因此比普通 CRUD 多两道校验：
     * 绑定不存在或已被别人占用的账号（会导致一个人有两份档案，或档案指向登不进来的号）；
     * 入学年份超出合理区间（脏值会让届别筛选静默漏人）。
     *
     * 坑：并发竞态靠唯一索引兜底。两个请求同时通过「未被绑定」检查时，后到的会被
     * 唯一索引拦下并抛 DuplicateKeyException，必须翻成可读提示，否则会变成 B0001。
     * 另一个坑是 userId 允许为空（历史导入 / 先建档后开号），但 user_id 为 NULL 的档案
     * 永远不会生效——准入判据是「档案 user_id 命中当前账号」，这类档案不报错，只是看不到功能。
     *
     * @param memberAddRequest 新增请求，不能为 null；姓名与入学年份必填；
     *                         userId 选填（非空时必须是存在且未被占用的账号）；
     *                         teamPosition / memberStatus 不传时分别按「成员」「在队」处理
     * @return 新成员档案 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws BusinessException 参数不合法、账号不存在或已被绑定（A0401）、入库失败（B0001）
     */
    long addMember(MemberAddRequest memberAddRequest);

    /**
     * 管理员更新成员档案（部分更新：字段为 null 表示不修改）
     *
     * 部分更新依赖 MyBatis-Plus 的更新默认跳过 null 字段，因此绝不能给未传的字段填默认值，
     * 否则「不修改」会悄悄变成「改成默认值」。更新字段由服务端写死成白名单，
     * 绝不接收前端实体整体更新，否则前端能顺手改动 deletedAt 与审计字段。
     *
     * 坑：改绑账号时的「排除自身」不能省。把成员绑定到「已属于他自己」的账号是合法的重复提交，
     * 不排除的话，用户重复保存同一个 userId 会被误判为「已被其他成员绑定」。
     *
     * @param memberUpdateRequest 更新请求，不能为 null；id 必填；其余字段为 null 表示保持原值，
     *                            传了空白的姓名会抛 A0401
     * @return true 表示更新成功；不代表数据真的变了，什么都没改时也是 true
     * @throws BusinessException 成员不存在（A0402）、账号不存在或已被其他成员绑定（A0401）、
     *         参数不合法（A0401）
     */
    boolean updateMember(MemberUpdateRequest memberUpdateRequest);

    /**
     * 管理员按 id 查询成员详情
     *
     * 不存在时抛 A0402 而不是返回 null：返回 null 会让前端收到「code=0 但 data 为空」，
     * 分不清是没有数据还是接口出错，这类含糊的成功在排查时最费时间。
     *
     * 坑：返回的是管理端 VO（含 userId、置顶权重与审计时间）。若要在官网展示成员，
     * 必须改用 getMemberFrontVO，直接复用这里会把账号绑定关系暴露给游客。
     *
     * @param id 成员 ID
     * @return 含内部字段的成员信息（管理端视图），永不为 null
     * @throws BusinessException 成员不存在时抛出（A0402）
     */
    MemberVO getMemberById(long id);

    /**
     * 管理员逻辑删除成员档案
     *
     * 删除是「主表写 + 两张关联表写」三次操作，必须在同一事务内，且先清关联、再删主表——
     * 反过来一旦清理失败，就会留下指向已删除成员的悬空关联，
     * 表现为「已删除的成员还出现在项目参与人列表里」，而且不会有任何报错。
     *
     * 坑：逻辑删除之后该成员绑定过的账号会被释放（唯一索引带着 deleted_at），
     * 同一账号可以重新绑定到新档案。这是刻意设计，但也意味着「删除成员」不会阻止该账号
     * 再次成为成员。返回 true 只代表 UPDATE 命中了行，数据并没有从库里消失。
     *
     * @param id 成员 ID
     * @return true 表示删除成功
     * @throws BusinessException 成员不存在时抛出（A0402）
     */
    boolean deleteMember(long id);

    /**
     * 管理员分页查询成员列表
     *
     * 分页与排序参数在实现里兜底纠正而不是靠 DTO 校验报错：这些参数走 GET，
     * 校验失败抛的 BindException 会落到全局兜底变成 B0001，用户只是页码传错了却看到「服务器故障」。
     *
     * 坑：默认排序是 sort_order 倒序而不是 id 倒序。「置顶」是产品明确要求的能力，
     * 用 id 倒序会让管理员设的置顶权重完全失效；id 只作为次级键保证分页次序稳定。
     * 排序字段还必须走白名单——ORDER BY 的位置无法用占位符参数化，直接拼就是注入入口。
     *
     * @param memberQueryRequest 查询条件，允许整体为 null（表示无条件查第一页）；
     *                           字段为 null 表示该条件不参与筛选，不是「查 null 值」
     * @return 分页结果，记录为含内部字段的 MemberVO；没有匹配数据时 records 为空列表（不是 null）
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
     * 管理端 VO 含全部字段，这里的风险是「多给」而不是「给错」，
     * 因此只要调用方是管理端接口，全字段拷贝是安全的。
     *
     * 坑：自动拷贝只按属性名与类型匹配，类型不一致的属性会被静默跳过、不报任何错。
     * 给 VO 加字段时类型必须与实体完全一致（实体是 LocalDateTime，VO 写成 Date 就永远是 null）。
     *
     * @param member 成员实体，允许为 null
     * @return 含内部字段的管理端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberVO getMemberVO(StudioMember member);

    /**
     * 实体转「C 端」VO
     *
     * 脱敏不是一段逻辑，而是 VO 的结构：MemberFrontVO 里没有 userId、sortOrder 与审计字段，
     * 因此自动拷贝时这些内部信息根本无处可去。这比「拷完再手动置空」可靠——
     * 将来给实体新增敏感字段时不会被漏掉。
     *
     * 坑：本方法「什么都没做」就是正确的脱敏。若给 MemberFrontVO 补上 userId 或 sortOrder，
     * 脱敏会在零代码改动的情况下失效——加字段前先问「这个字段愿意给所有人看吗」。
     *
     * @param member 成员实体，允许为 null
     * @return 脱敏后的 C 端 VO；入参为 null 时返回 null，调用方必须判空
     */
    MemberFrontVO getMemberFrontVO(StudioMember member);

    // ==================== 考勤模块依赖：按登录账号查在队成员 ====================

    /**
     * 按绑定的登录账号 ID 查询在队的成员档案
     *
     * 考勤的准入是「当前登录账号是不是被管理员标记过的工作室成员」，判定链条是
     * sys_user.id → studio_member.user_id，且必须是在队状态——毕业 / 离队的成员不再参与考勤。
     * getMemberById 按档案主键查，答不上这个问题。
     *
     * 坑：返回 null 表示「查不到」，这是正常状态而不是错误。调用方要拿它来生成 A0301 无权限提示；
     * 若把「查不到」改成抛异常，非成员签到得到的会是 A0402 而不是 A0301，前端引导就会走错分支。
     *
     * @param userId 登录账号 ID，为 null 时直接返回 null（未登录 / 无绑定）
     * @return 在队的成员档案；查不到返回 null（不抛异常）
     */
    StudioMember getActiveMemberByUserId(Long userId);
}
