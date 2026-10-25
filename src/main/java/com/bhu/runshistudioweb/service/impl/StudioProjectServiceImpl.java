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
 * 继承 ServiceImpl<StudioProjectMapper, StudioProject> 之后，单表增删改查已由
 * MyBatis-Plus 提供，本类只写「业务动作」：校验、事务编排、VO 转换。
 *
 * 三处必须先想清楚的地方（本模块的全部难点）：
 * - 队长同步用 ensure，不用 bind：addProject 与 updateProject 调的都是幂等的
 * ensureMemberInProject——「已存在」直接返回、并发撞唯一索引也静默成功。
 * 若误用 bindMember（重复即报 A0401），管理员每次重复保存项目都会收到
 * 「该成员已参与此项目」，而这一切看起来像 bug，实际是选错了方法；
 * - 事务边界：新增、更新（换队长）、删除都是「主表写 + 关联表写」两次写，
 * 统一加 @Transactional(rollbackFor = Exception.class)。
 * 分属两个事务的话，会出现「有项目没队长」或「项目已删、关联还在」的悬空状态；
 * rollbackFor 必须显式写 Exception——Spring 默认只回滚 RuntimeException，
 * 漏了它等于「事务只声明了一半」；
 * - tech_stack 的 JSON 边界：库里是 varchar(256) 快照，接口层是 List。
 * 写：序列化后超长抛 A0401（否则撞数据库的字符串截断错误）；
 * 读：解析失败返回空列表（一条脏数据不能让整个列表接口 500）。
 *
 * 依赖方向：本类 → MemberProjectService（队长同步 + 级联清理）、
 * StudioMemberMapper（校验队长是否存在）。反方向不存在依赖
 * （MemberProjectServiceImpl 只注入 Mapper），因此不会循环依赖。
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

    /**
     * 首页轮播展示的项目数
     *
     * 必须与门户 ProjectSpotlight.MAX_COUNT 保持一致：
     * 后台按它计算「首页 N」，门户按它决定渲染几条，两边对不上时
     * 后台显示的排名就是假的。
     */
    private static final long HOME_SPOTLIGHT_COUNT = 6L;

    /**
     * tech_stack 序列化后的长度上限，与 DDL 的 varchar(256) 对齐。
     * 不改数据库类型而是先在这里拦住，是因为「标签太多」是入参问题，
     * 应该返回 A0401 让前端提示，而不是让数据库抛截断 / 报错
     */
    private static final int TECH_STACK_MAX_LENGTH = 256;

    /**
     * 校验「队长是否存在」需要查 studio_member，故注入它的 Mapper。
     * 注意查的是成员主表而不是关联表：leader_id 是权威源
     */
    private final StudioMemberMapper studioMemberMapper;

    /**
     * 成员-项目关联服务：负责队长同步与级联清理
     *
     * 为什么注入 Service 而不是在这里直接写关联表的 Mapper：
     * 「队长必须在参与成员列表里」这条不变量只由关联服务实现一次，
     * 若本类自己拼 SQL，规则就有两处实现，改一处漏一处。
     * 依赖方向单向（本类 → 关联服务），不构成循环依赖。
     */
    private final MemberProjectService memberProjectService;

    /**
     * 用于 tech_stack 的 JSON 序列化 / 反序列化。
     * 用容器里的实例（Spring Boot 自动配置 + JsonConfig 定制），不自己 new：
     * 自建实例不共享全局配置，时间格式与 null 策略都可能和接口不一致
     */
    private final JsonMapper jsonMapper;

    // ==================== 管理端 ====================

    /**
     * 新增一个项目案例，并把队长同步进「成员-项目」关联表
     *
     * <p>为什么这样设计：项目主表只存一个 leader_id，而官网要展示「参与成员」，
     * 这份名单存在关联表里。两份数据必须在同一次数据库事务里落库——
     * 分成两个事务的话，第一个成功、第二个失败就会留下「有项目、没队长」的半成品：
     * 后台查得到项目，官网的成员区域却是空的，而且全程没有任何报错。
     * 所以这里用 @Transactional(rollbackFor = Exception.class) 兜住两次写，
     * 而不是「先写主表、失败再手工补一条」的事后补偿。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体：@Valid 只在真的有请求体时才生效，整体为 null 时它什么也不做，
     *         不兜这一层，后面的 getXxx() 会抛空指针，用户看到的是 B0001「系统错误」；</li>
     *     <li>② 校验 title / description / leaderId：三者都是 NOT NULL 列，
     *         必须在入参侧拦住；先 trim 再判空，是为了挡掉「   」这种全空格值；</li>
     *     <li>③ 解析状态并校验队长存在：状态不传按 1（已上线）；
     *         队长不存在时连项目都不该建，所以这两步排在写库之前；</li>
     *     <li>④ 组装实体并落库：只 set 白名单字段，正文先过 XSS 清洗、
     *         技术栈先序列化并校验长度，都是「写进数据库之前」能给出可读提示的最后时机；</li>
     *     <li>⑤ 同一事务内 ensure 队长进关联表：必须排在 save 之后，
     *         因为关联表要写项目 ID，而这个 ID 是雪花算法（分布式自增 ID 算法，
     *         生成 19 位 Long，不依赖数据库自增）在插入那一刻才产生的。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里调的是幂等的 ensureMemberInProject，不是 bindMember。
     * 幂等指「重复调用结果一致」——队长早就在成员列表里时 ensure 直接返回，
     * 换成 bindMember 就会抛 A0401「该成员已参与此项目」。
     * 而管理员只是保存了一次项目，看起来像 bug，实际是选错了方法。
     *
     * @param projectAddRequest 新增请求，不允许为 null；title、description、leaderId 必填，
     *                          传空串或全空格按 A0401 拒绝
     * @return 新项目的 ID；19 位 Long，接口层会序列化成字符串，避免前端丢精度
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为空、必填项为空、
     *         状态不在枚举内、技术栈序列化后超长时抛出（A0401）；
     *         队长不存在或已逻辑删除时抛出（A0402）；落库失败时抛出（B0001）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addProject(ProjectAddRequest projectAddRequest) {
        // ① 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);

        // ② title / description 是 NOT NULL 列，必须在入口拦住；
        // trim 后再判空，避免「   」这种全空格的值入库
        ThrowUtils.throwIf(StrUtil.isBlank(projectAddRequest.getTitle()),
                ErrorCode.PARAMS_ERROR, "项目名称不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(projectAddRequest.getDescription()),
                ErrorCode.PARAMS_ERROR, "项目摘要不能为空");
        ThrowUtils.throwIf(projectAddRequest.getLeaderId() == null || projectAddRequest.getLeaderId() <= 0,
                ErrorCode.PARAMS_ERROR, "项目队长不能为空");

        // ③ 状态：不传按 DDL 默认值 1（已上线）；传了必须是枚举内的合法取值
        ProjectStatusEnum status = resolveStatus(projectAddRequest.getStatus(), ProjectStatusEnum.ONLINE);
        // 队长必须存在（含「已逻辑删除视为不存在」）：否则会写出一个没有队长的项目
        assertLeaderExists(projectAddRequest.getLeaderId());

        // ④ 只 set 请求里允许出现的字段：这是服务端写死的白名单，
        // 绝不能把 DTO 直接反射成实体（那样前端能顺手改 deletedAt、审计字段）
        StudioProject project = new StudioProject();
        project.setTitle(projectAddRequest.getTitle().trim());
        project.setDescription(projectAddRequest.getDescription().trim());
        project.setCoverImage(projectAddRequest.getCoverImage());
        // XSS 兜底：正文入库前做白名单清洗（与帖子同一策略，见 MarkdownSanitizer）。
        // 管理员录入的内容同样不该成为非本前端消费方的存储型 XSS 载体
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

        // ⑤ 关键：同一事务内把队长同步进关联表，
        // 保证「队长必然出现在参与成员列表中」。
        // 用幂等的 ensure 而不是 bind：重复保存是正常路径，不该报冲突。
        // 位置必须在 save 之后——关联表要写 project.getId()，ID 是插入时才生成的
        memberProjectService.ensureMemberInProject(project.getId(), project.getLeaderId());
        return project.getId();
    }

    /**
     * 修改项目（部分更新：字段为 null 表示不修改；传了 leaderId 即换队长）
     *
     * <p>为什么这样设计：换队长时只把新队长 ensure 进关联表，不移除旧队长。
     * 卸任不等于退出项目——前任队长大概率仍然在参与这个项目，
     * 自动踢人属于「用代码替管理员做业务决定」。真要移除他，由管理员走解绑接口，
     * 而解绑接口有守卫：当前队长不允许被移除。两处配合起来，
     * 「队长必然在参与成员列表里」这条不变量才守得住。
     * 另外，更新字段是服务端写死的白名单，不用「DTO 转实体整体 updateById」，
     * 否则前端能顺手改 deletedAt 与审计字段。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并先查一次：updateById 失败只返回 false，
     *         前端只能看到「更新失败」；先查一次才能给出 A0402「项目不存在」；</li>
     *     <li>② 校验「传了就必须有内容」的字段：null 表示不修改，
     *         空串表示想清空，而 NOT NULL 列不允许清空，必须在这里拦成 A0401；</li>
     *     <li>③ 解析状态与新队长：都只在传了的时候校验，
     *         这一步必须排在写库之前，否则会先写脏数据再抛异常；</li>
     *     <li>④ 组装只含白名单字段的更新对象并 updateById：
     *         它默认跳过 null 字段，正是「传 null 表示不修改」语义成立的基础；</li>
     *     <li>⑤ 传了 leaderId 才在同一事务内 ensure 新队长：
     *         必须排在 updateById 之后，因为新队长的外键语义要求项目本身已经改好。</li>
     * </ol>
     *
     * <p>容易踩的坑：techStack 传空数组时序列化成 "[]" 而不是 null。
     * 若图省事把空列表转成 null，updateById 会跳过该字段，
     * 结果就是「清空技术栈」这个操作永远不生效，而且不报任何错。
     *
     * @param projectUpdateRequest 更新请求，不允许为 null；id 必填，
     *                             其余字段为 null 表示保持原值
     * @return true 表示更新成功；项目不存在时不会返回 false，而是直接抛异常
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 为空、
     *         标题或摘要传了空串、状态非法、队长 id 非法、技术栈超长时抛出（A0401）；
     *         项目或队长不存在时抛出（A0402）；更新失败时抛出（B0001）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProject(ProjectUpdateRequest projectUpdateRequest) {
        // ① 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        ThrowUtils.throwIf(projectUpdateRequest == null || projectUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        Long id = projectUpdateRequest.getId();

        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // ② 传了就必须有实际内容（null 表示不修改，空串表示想清空——后者不允许）：
        // 否则官网会出现「没有名字的项目」
        if (projectUpdateRequest.getTitle() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getTitle()),
                    ErrorCode.PARAMS_ERROR, "项目名称不能为空");
        }
        if (projectUpdateRequest.getDescription() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(projectUpdateRequest.getDescription()),
                    ErrorCode.PARAMS_ERROR, "项目摘要不能为空");
        }

        // ③ 状态：传了才校验（兜底值传 null 表示「不修改」，而不是「改成 null」）
        ProjectStatusEnum status = resolveStatus(projectUpdateRequest.getStatus(), null);

        // 换队长：先确认新队长存在，再在同一事务里同步进关联表（排在 updateById 之后）
        Long newLeaderId = projectUpdateRequest.getLeaderId();
        if (newLeaderId != null) {
            ThrowUtils.throwIf(newLeaderId <= 0, ErrorCode.PARAMS_ERROR, "项目队长 id 不合法");
            assertLeaderExists(newLeaderId);
        }

        // ④ 只 set 允许修改的字段：这是服务端写死的白名单，
        // 绝不能直接把 DTO 转成实体 updateById（那样前端能改 deletedAt、审计字段）
        StudioProject update = new StudioProject();
        update.setId(id);
        update.setTitle(projectUpdateRequest.getTitle() == null
                ? null : projectUpdateRequest.getTitle().trim());
        update.setDescription(projectUpdateRequest.getDescription() == null
                ? null : projectUpdateRequest.getDescription().trim());
        update.setCoverImage(projectUpdateRequest.getCoverImage());
        // XSS 兜底：与新增同一策略。null 表示「不修改」，sanitize 对 null 原样返回
        update.setContent(MarkdownSanitizer.sanitize(projectUpdateRequest.getContent()));
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

        // ⑤ 换队长：同一事务内 ensure 新队长进关联表。
        // 注意不自动移除旧队长——卸任不等于退出项目（他大概率仍在参与），
        // 真要移除由管理员走解绑接口，而解绑守卫保证「当前队长不可被移除」
        if (newLeaderId != null) {
            memberProjectService.ensureMemberInProject(id, newLeaderId);
        }
        return true;
    }

    /**
     * 删除项目：同一事务内先清关联表，再逻辑删除主表
     *
     * <p>为什么这样设计：顺序必须是「先清关联、再删主表」，不能反。
     * 反过来写的话，主表删除成功、清理关联失败时，会留下一批指向已删项目的悬空关联行；
     * 而现在的顺序下，第二步失败会让整个事务回滚，第一步的清理也一并撤销，
     * 不会出现「项目还在、成员关联却没了」这种静默数据丢失。
     * 主表用逻辑删除（只把 deleted_at 写成毫秒时间戳，行还在库里）而不是物理删除，
     * 是为了让误删可追溯、也让历史关联有据可查；关联表则用物理删除，
     * 因为「谁参与了这个已不存在的项目」没有保留价值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查一次：不存在时给 A0402，而不是让 removeById 静默返回 false；</li>
     *     <li>② 先清关联表：必须排在删主表之前，理由见上一段；</li>
     *     <li>③ 再逻辑删除主表：这一步失败会连带回滚第 ② 步，两次写要么都成、要么都不成。</li>
     * </ol>
     *
     * <p>容易踩的坑：removeById 走的是 @TableLogic，实际执行的是
     * UPDATE studio_project SET deleted_at = ... 而不是 DELETE。
     * 因此它返回 true 只代表「更新到了一行」，不代表数据被真的抹掉；
     * 想彻底清理需要另外的归档脚本，不要在业务代码里改成物理删除。
     *
     * @param id 项目 ID，必须大于 0；传 0 或负数按 A0401 拒绝
     * @return true 表示两次写都成功；项目不存在时抛异常而不是返回 false
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         项目不存在时抛出（A0402）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProject(long id) {
        // ① 先查存在性：不存在要给出 A0402，而不是让 removeById 静默返回 false
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // ② 同一事务内先清关联，再逻辑删除主表：
        // 反过来的话，主表删除失败就会留下「项目还在、成员关联却没了」的静默数据丢失
        memberProjectService.removeByProjectId(id);

        // ③ 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    /**
     * 按 id 查项目详情（管理端视图，含内部字段）
     *
     * <p>为什么这样设计：查不到时抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code=0 但 data 为空」的响应，
     * 它分不清这是「这个项目确实没有」还是「接口出错了」，
     * 只能靠猜来决定展示空状态还是错误提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数：0 与负数一定不是合法主键，属于探测请求；</li>
     *     <li>② getById 查实体：MyBatis-Plus 会自动追加 deleted_at = 0，
     *         因此已逻辑删除的项目在这里等同不存在；</li>
     *     <li>③ 转成管理端 VO：这一步才把 tech_stack 的 JSON 字符串解析成 List。</li>
     * </ol>
     *
     * <p>容易踩的坑：管理端 VO 里有 content 正文大字段，列表接口不能复用它。
     * 这里只有一个项目，返回正文没有成本；一旦把它用到列表上，
     * 每一行都会回传整篇 Markdown，响应体会膨胀几十倍。
     *
     * @param id 项目 ID，必须大于 0
     * @return 含 content、leaderId、sortOrder 与审计字段的管理端 VO，永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         项目不存在或已逻辑删除时抛出（A0402）
     */
    @Override
    public ProjectVO getProjectById(long id) {
        // ① 0 与负数一定不是合法主键，先挡掉，省一次数据库往返
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // ② getById 会被 MP 自动追加 deleted_at = 0，已逻辑删除的项目等同不存在
        StudioProject project = this.getById(id);
        // 查不到给明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");
        // ③ 转换时把 tech_stack 从 JSON 字符串解析成 List
        return this.getProjectVO(project);
    }

    /**
     * 管理端项目分页列表（全状态，可按标题、状态、队长筛选）
     *
     * <p>为什么这样设计：筛选条件与 C 端共用同一个 buildQueryWrapper，
     * 保证「后台筛出 3 条、官网筛出 3 条」这种一致性不靠人工核对。
     * 分页参数做兜底纠正而不是抛异常：页码传错没必要让整个查询失败，
     * 收敛到合理区间继续查体验更好；但状态是闭集（取值只有枚举那几个），
     * 非法值必须报 A0401——静默返回空列表会把排查方向带偏。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时换一个空对象：后续代码就不用到处判空；</li>
     *     <li>② 先校验状态取值：闭集不静默，非法值在这里就报错，不浪费一次查询；</li>
     *     <li>③ 拼筛选条件并应用白名单排序：排序字段必须走 switch 映射，
     *         前端字符串不能直接进 ORDER BY；</li>
     *     <li>④ 分页查询并转 VO：转换后还会补「首页轮播排名」。</li>
     * </ol>
     *
     * <p>容易踩的坑：分页对象的 searchCount 保持默认 true，因为前端分页组件
     * 需要 total 才能画出总页数。若为了省一次 COUNT 关掉它，
     * 列表能出来但翻页器会显示「共 0 页」。
     *
     * @param projectQueryRequest 查询条件，允许整体为 null（等价于「全部、第一页」）；
     *                            status 传枚举外的值会被拒绝
     * @return 分页结果，记录为管理端 VO（含 content）；无数据时 records 为空列表、total 为 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException status 不在枚举内时抛出（A0401）
     */
    @Override
    public Page<ProjectVO> listProjectByPage(ProjectQueryRequest projectQueryRequest) {
        // ① 请求为 null 时换一个空对象，后续代码就不用到处判空
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // ② 状态是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
        resolveStatus(query.getStatus(), null);

        // ③ 筛选条件与排序都收在私有方法里，管理端与 C 端复用同一套语义
        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // ④ searchCount 保持默认 true：分页组件需要 total，关掉它翻页器会显示「共 0 页」
        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectVOPage(entityPage);
    }

    // ==================== C 端 ====================

    /**
     * 门户项目列表（游客可访问，只含脱敏字段）
     *
     * <p>为什么这样设计：脱敏靠「目标 VO 里根本没有这个字段」实现，
     * 而不是靠「拷完再记得手动置空」。少一个字段就少一处泄露风险，
     * 而且以后实体新增敏感字段时，只要没人把它加进 VO，它就自动被挡住。
     * 排序也刻意不接前端传的 sortField / sortOrder：
     * 官网的顺序属于产品定义，不能由请求方决定，
     * 否则任何人换个参数就能把某个项目顶到第一位。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时换空对象，与后台同一套兜底写法；</li>
     *     <li>② 校验状态取值：与后台同一口径，闭集不静默；</li>
     *     <li>③ 拼筛选条件后固定排序：置顶权重倒序、创建时间倒序、id 倒序；
     *         最后那个 id 是稳定次序的兜底，缺了它翻页会出现「同一行出现两次」；</li>
     *     <li>④ 分页查询并转成脱敏 VO：pageSize 上限 50，防匿名接口被刷。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是 ProjectFrontVO，结构上没有 content 正文。
     * 如果为了「少写一个 VO」把详情 VO 拿来当列表项，列表响应会带上整篇 Markdown，
     * 一页十条就是十篇正文——这是最容易在联调时被忽略、上线后才发现的性能问题。
     *
     * @param projectQueryRequest 查询条件，允许为 null；title 模糊、status 精确，
     *                            其余字段即使传了也不参与筛选
     * @return 分页结果，记录为脱敏 VO（无 content、无 leaderId、无审计字段）；
     *         无数据时 records 为空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException status 不在枚举内时抛出（A0401）
     */
    @Override
    public Page<ProjectFrontVO> listFrontProjects(ProjectQueryRequest projectQueryRequest) {
        // ① 与后台同一套兜底写法：null 换空对象，避免后续到处判空
        ProjectQueryRequest query = projectQueryRequest == null ? new ProjectQueryRequest() : projectQueryRequest;

        // ② 与后台同一条口径：状态非法 → A0401（闭集不静默）
        resolveStatus(query.getStatus(), null);

        LambdaQueryWrapper<StudioProject> wrapper = buildQueryWrapper(query);
        // ③ 公开接口固定排序：置顶权重倒序 → 创建时间倒序 → id 倒序兜底稳定次序。
        // 刻意不接 sortField/sortOrder：排序规则由产品定义，不由请求方决定
        wrapper.orderByDesc(StudioProject::getSortOrder)
                .orderByDesc(StudioProject::getCreatedAt)
                .orderByDesc(StudioProject::getId);

        // ④ searchCount 保持默认 true（分页组件需要 total）；
        // pageSize 上限 50 由 newPageWithDefaults 收敛，防匿名接口拉全表
        Page<StudioProject> entityPage = this.page(newPageWithDefaults(query), wrapper);
        return toProjectFrontVOPage(entityPage);
    }

    /**
     * 门户项目详情（游客可访问，含 Markdown 正文与参与成员）
     *
     * <p>为什么这样设计：参与成员是本项目唯一对外暴露的成员信息出口，
     * 属于产品确认保留的例外——官网要展示「这个项目是谁做的」。
     * 装配成员时只多两次查询（关联表一次、成员主表一次），
     * 不是 N+1（N+1 指「先查 N 条主记录、再为每条各发一次查询」，共 N+1 次），
     * 因此不需要引入缓存或批处理优化。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查项目本体：查不到直接 A0402，不必继续查成员；</li>
     *     <li>② 先转成详情 VO：把 tech_stack 解析成 List，与其他接口保持同形；</li>
     *     <li>③ 再装配参与成员：这一步排在 VO 转换之后，
     *         是因为成员列表挂在 VO 上，VO 不存在就没有装配的载体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不额外校验「项目是否已上线」。
     * 详情接口按 ID 精确访问，草稿项目只要没人把链接发出去就看不到；
     * 反过来若在这里加状态过滤，后台预览草稿的能力也会一起丢掉。
     * 是否对外可访问，应该由「列表接口只列已上线」来保证，而不是靠详情接口兜。
     *
     * @param id 项目 ID，必须大于 0
     * @return 脱敏详情 VO（列表字段 + content + 参与成员列表），永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         项目不存在或已逻辑删除时抛出（A0402）
     */
    @Override
    public ProjectFrontDetailVO getFrontProjectById(long id) {
        // ① 先查项目本体：查不到就没必要再查成员，少一次数据库往返
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        StudioProject project = this.getById(id);
        ThrowUtils.throwIf(project == null, ErrorCode.NOT_FOUND_ERROR, "项目不存在");

        // ② 先转 VO：tech_stack 的 JSON 解析在这一步完成
        ProjectFrontDetailVO detailVO = toProjectFrontDetailVO(project);
        // ③ 装配参与成员：只有 2 次查询（项目本体 + 关联表两步查询），没有 N+1。
        // 队长必然在结果里——它是 leader_id 同步不变量，所以官网不用再单独渲染一个「队长」头像
        detailVO.setMembers(memberProjectService.listFrontMembersByProject(id));
        return detailVO;
    }

    // ==================== VO 转换 ====================

    /**
     * 实体转管理端 VO
     *
     * <p>为什么这样设计：用 BeanUtils.copyProperties 而不是手写一长串 setXxx，
     * 是为了避免「实体加了字段、VO 忘了补」这种漏字段 bug；
     * 代价是类型不一致的属性会被静默跳过，所以 techStack 必须显式排除后手动赋值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时直接返回 null：这是纯转换方法，
     *         调用方（例如分页里的 stream.map）遇到空元素时不该炸；</li>
     *     <li>② 按属性名自动拷贝，但排除 techStack：实体里是 String、VO 里是 List，
     *         类型不一致时 BeanUtils 不会报错，只会悄悄跳过；</li>
     *     <li>③ 手动把 JSON 字符串解析成 List 塞进去。</li>
     * </ol>
     *
     * <p>容易踩的坑：如果漏掉第 ② 步的排除参数，接口返回的 techStack 会永远是 null，
     * 而且日志里一条异常都没有——这是最典型的「静默失败」。
     *
     * @param project 项目实体，允许为 null
     * @return 管理端 VO；入参为 null 时返回 null，调用方需要判空
     */
    @Override
    public ProjectVO getProjectVO(StudioProject project) {
        // ① 纯转换方法：入参为 null 就返回 null，不抛异常
        if (project == null) {
            return null;
        }
        ProjectVO projectVO = new ProjectVO();
        // ② techStack 在实体里是 String、在 VO 里是 List，BeanUtils 对类型不一致的属性会静默跳过，
        // 因此先排除它再手动赋值——否则接口返回的 techStack 永远是 null 且不报错
        BeanUtils.copyProperties(project, projectVO, "techStack");
        // ③ 解析失败时 parseTechStack 返回空列表，不会让整条记录转换失败
        projectVO.setTechStack(parseTechStack(project.getTechStack()));
        return projectVO;
    }

    /**
     * 实体转门户脱敏 VO
     *
     * <p>为什么这样设计：脱敏不靠「拷完再手动置空」，而是靠目标 VO 里没有这些字段。
     * 结构上不存在的东西，copyProperties 不可能拷过去，也就不会因为
     * 「某次改动忘了加一行 setNull」而泄露。这比逐字段判断更可靠。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null：纯转换方法不制造异常；</li>
     *     <li>② 自动拷贝时排除 techStack：类型不一致必须手动处理；</li>
     *     <li>③ 手动解析 tech_stack。</li>
     * </ol>
     *
     * <p>容易踩的坑：ProjectFrontVO 里没有 content、leaderId、sortOrder，
     * 所以这里「什么都没做」就是正确的脱敏。若有人为了图方便给它加上 content，
     * 这个方法的脱敏能力会在没有任何代码改动的情况下失效。
     *
     * @param project 项目实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null，调用方需要判空
     */
    @Override
    public ProjectFrontVO getProjectFrontVO(StudioProject project) {
        // ① 纯转换方法：入参为 null 就返回 null
        if (project == null) {
            return null;
        }
        ProjectFrontVO frontVO = new ProjectFrontVO();
        // ② 脱敏靠「目标 VO 没有这些字段」实现：content / leaderId / sortOrder / 审计字段
        // 结构上就不可能被拷贝出去
        BeanUtils.copyProperties(project, frontVO, "techStack");
        // ③ techStack 类型不一致，只能手动赋值
        frontVO.setTechStack(parseTechStack(project.getTechStack()));
        return frontVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 实体转 C 端详情 VO（列表字段 + content）
     *
     * 详情 VO 比列表 VO 多两个字段：content 正文与参与成员列表。
     * 前者在这里拷贝，后者由调用方装配（它需要额外查询关联表）。
     *
     * @param project 项目实体，调用方保证非 null
     * @return 详情 VO
     */
    private ProjectFrontDetailVO toProjectFrontDetailVO(StudioProject project) {
        ProjectFrontDetailVO detailVO = new ProjectFrontDetailVO();
        // 同样要排除 techStack：实体是 String、VO 是 List，类型不一致会被静默跳过
        BeanUtils.copyProperties(project, detailVO, "techStack");
        detailVO.setTechStack(parseTechStack(project.getTechStack()));
        return detailVO;
    }

    /**
     * 构建筛选条件（管理端与 C 端共用，保证两边筛选语义完全一致）
     *
     * 刻意不支持 techStack 筛选：它是 JSON 字符串列，
     * 按它筛选只能写 LIKE '%Java%'，既不走索引又会误匹配（命中 JavaScript）。
     * 取舍是「不筛、不建索引」；将来要按技术栈筛选，正确做法是拆出标签表。
     *
     * @param query 查询条件，调用方保证非 null
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
     * ORDER BY 的位置无法用占位符参数化，把前端字符串直接拼进去
     * 就是最典型的 SQL 注入入口，所以这里只接受几个固定字段名。
     *
     * @param wrapper 查询包装类
     * @param sortField 排序字段名，可为空（走默认排序）
     * @param sortOrder 排序方向 asc / ascend，其余一律按倒序处理
     */
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
     * 非法值收敛而不是报错：页码传错不该让整个查询失败。
     * 但上限必须真的收住——否则匿名接口一个 pageSize=999999 就能把整表拉进内存。
     *
     * @param query 查询条件，调用方保证非 null
     * @return 分页对象（searchCount 保持默认 true，前端分页器需要 total）
     */
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
     * 非法值必须在入口拦住：一旦入库，官网按状态筛选就会静默漏数据，
     * 而且没有任何报错，只能靠人肉比对发现。
     *
     * @param status 前端传入的状态，可为 null
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
        // selectById 会被 MP 自动追加 deleted_at = 0：已注销、已逻辑删除的成员同样视为不存在。
        // 这里的提示特意写明「队长」而不是「成员」，因为调用方传的是 leaderId 字段
        ThrowUtils.throwIf(studioMemberMapper.selectById(leaderId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目队长不存在");
    }

    /**
     * 序列化技术栈标签为 JSON 快照，并校验长度不超过列宽
     *
     * @param techStack 标签列表，可为 null
     * @return JSON 字符串；入参为 null 时返回 null（表示不修改 / 无标签），
     * 空列表返回 "[]"（表示「清空」——必须是非 null 值，
     * 否则 updateById 会跳过该字段，等于清不掉）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 序列化后长度超过 256 时抛出（A0401）
     */
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
        // 长度必须在入参侧拦住：否则会撞 varchar(256) 的截断 / 报错，
        // 前端拿到的是数据库异常而不是「标签过多」的明确提示
        ThrowUtils.throwIf(json.length() > TECH_STACK_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "技术栈标签过长或过多");
        return json;
    }

    /**
     * 解析 tech_stack 的 JSON 快照
     *
     * 解析失败（脏数据、手工改库改坏的）一律返回空列表，绝不抛异常——
     * 一条坏数据不能让整个列表接口 500。
     *
     * @param techStackJson 数据库里的 JSON 字符串，可为空
     * @return 标签列表；为空或解析失败时返回空列表
     */
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

    /**
     * 实体分页结果转管理端 VO 分页结果
     *
     * @param entityPage 实体分页
     * @return VO 分页（保留 total/current/size，前端分页组件依赖这三个值）
     */
    private Page<ProjectVO> toProjectVOPage(Page<StudioProject> entityPage) {
        Page<ProjectVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getProjectVO).toList());
        // 补「首页轮播排名」：列表顺序恰好与官网轮播口径一致，运营看到的名次就是首页上的名次
        fillHomeRank(voPage.getRecords());
        return voPage;
    }

    /**
     * 给管理端 VO 补「首页轮播排名」
     *
     * 首页轮播（ProjectSpotlight）取的是官网口径前 6 条，
     * 而官网口径是 sort_order DESC → created_at DESC → id DESC——
     * 与管理端列表的默认排序恰好一致，所以「列表顺序 = 首页顺序」，
     * 运营翻列表时看到的名次就是首页上的名次。
     *
     * 排名按全局计算，不按当前筛选结果：管理员按标题 / 状态筛选时，
     * 本页只是全量的一个子集，用子集内的名次会严重误导
     * （筛选结果里的「第 1 名」全局可能排第 20）。
     *
     * 这里是固定 1 次额外查询（只取前 6 条），不会随本页记录数增长，
     * 不属于 N+1（N+1 指按每条记录各发一次查询）。
     *
     * HOME_SPOTLIGHT_COUNT 必须与门户 ProjectSpotlight.MAX_COUNT
     * 保持一致：两边对不上时，后台显示的「首页 N」会是假的。
     *
     * @param records 本页的管理端 VO，允许为空列表
     */
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
