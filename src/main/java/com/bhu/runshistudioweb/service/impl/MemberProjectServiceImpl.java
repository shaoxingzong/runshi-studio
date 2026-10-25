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
 * 依赖方向铁律：本类只注入 Mapper
 * （StudioProjectMapper / StudioMemberMapper）与容器里的 JsonMapper，
 * 绝不注入 StudioProjectService——它反过来依赖本类做队长同步与级联清理，
 * 一旦这里再依赖回去就是循环依赖，Spring Boot 默认禁止循环引用，启动直接失败
 * （不是偶发运行问题，而是每次启动都失败）。代价是 MemberVO / ProjectVO 的转换
 * 要在本类各写几行 BeanUtils.copyProperties、tech_stack 的解析要再写一遍，
 * 这个代价远小于循环依赖带来的启动失败。
 *
 * 查询策略：两步查询而不是 JOIN。
 * 先从关联表取 ID 列表（走 uk_member_proj / idx_proj_member 的最左前缀，
 * 只 select 一列，属于覆盖索引不回表），空集合直接返回，
 * 再 IN 查主表（MyBatis-Plus 自动追加 deleted_at = 0，于是「主表已被逻辑删除」的
 * 悬空关联被天然过滤掉，不需要我们记得写条件）。
 * 不写 JOIN 的理由：项目目前没有任何 XML Mapper，为了一个 JOIN 引入手写 SQL，
 * 就多了一层「漏写 deleted_at = 0」的风险，而主表是逻辑删除的，
 * 漏掉这个条件就会查出已删除数据的悬空行。
 *
 * 队长不变量：studio_project.leader_id 是权威源，
 * 关联表里的队长行是同步结果（由 StudioProjectServiceImpl 在同一事务写入）。
 * 本类的 unbindMember 负责守卫它——当前队长不允许被解绑。
 *
 * @author shaoshing
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
     * 用容器里的 JsonMapper（Spring Boot 自动配置的 Jackson 实例，且已被 JsonConfig 定制），
     * 不自己 new 一个：自建的实例不共享全局配置，时间格式、null 策略都可能与接口不一致。
     */
    private final JsonMapper jsonMapper;

    // ==================== 写操作 ====================

    /**
     * 幂等地把成员加入项目（已存在则什么都不做）
     *
     * <p>为什么这样设计：新增项目、换队长这两个场景要的是「确保他在里面」，
     * 而不是「再插一条」。如果这里像 bindMember 那样遇到重复就报错，
     * 管理员每次重复保存一个项目都会收到「该成员已参与此项目」——
     * 而管理员只是点了一次保存，这个提示看起来完全像 bug。
     * 所以「显式添加」与「保证在里面」必须是两个方法，语义不同，错误处理也不同。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数：雪花 ID 恒为正，0 与负数一定是调用方传错；</li>
     *     <li>② 先查一次（乐观检查）：绝大多数情况（重复保存、队长没变）在这里就返回了，
     *         省掉一次必然失败的插入；</li>
     *     <li>③ 插入关联行，并捕获唯一索引冲突——并发下另一个请求已经插进去了，
     *         对「保证在列表里」这个语义而言，这正是想要的结果，所以静默成功。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ② 步的查询只是优化，不能当作并发保证。
     * 两个请求可能同时查到「不存在」然后同时插入，真正拦住重复的是
     * 唯一索引 uk_member_proj。所以第 ③ 步的 catch 不能省——
     * 省掉的话，并发场景下会抛一个英文 SQL 异常，前端看到的是 B0001。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 任一 id 不合法时抛出（A0401）
     */
    @Override
    public void ensureMemberInProject(long projectId, long memberId) {
        // ① 两个 id 都必须是正数：雪花 ID 恒为正，0 / 负数一定是调用方传错或探测请求
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // ② 先查一次（乐观检查）：绝大多数情况（重复保存项目、队长没变）在这里就返回了
        if (this.count(relationWrapper(projectId, memberId)) > 0) {
            return;
        }

        StudioMemberProject relation = new StudioMemberProject();
        relation.setProjectId(projectId);
        relation.setMemberId(memberId);
        try {
            // ③ 乐观检查只是优化，真正兜底的是唯一索引 uk_member_proj
            this.save(relation);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求已经插进去了：对「保证在列表里」这个语义而言，
            // 这正是我们想要的结果，因此静默成功，绝不抛异常。
            // 这也是 ensure 与 bind 的关键差别——bind 在这个分支必须抛 A0401
        }
    }

    /**
     * 绑定成员到项目（管理员显式添加）
     *
     * <p>为什么这样设计：这是管理员的显式动作，如果没生效必须让他看见，
     * 所以重复绑定要报 A0401，而不是像 ensure 那样静默返回。
     * 两层防御缺一不可：应用层先查重，是为了给出可读的中文提示；
     * 唯一索引兜住并发，是为了保证绝对不出现重复数据。
     * 单靠唯一索引的话，用户看到的会是一段英文 SQLException。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数；</li>
     *     <li>② 校验项目与成员都存在（逻辑删除的等同不存在），
     *         这一步必须排在插入之前，否则会写出指向不存在实体的关联；</li>
     *     <li>③ 应用层查重，命中就给可读提示；</li>
     *     <li>④ 插入，并捕获并发下的唯一索引冲突，翻成与第 ③ 步完全相同的文案。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ④ 步的文案必须与第 ③ 步一模一样。
     * 对调用方而言，「先查出来的重复」和「并发撞上唯一索引的重复」是同一件事，
     * 给出两种提示只会让人以为是两个不同的问题。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或已参与时抛出（A0401）；
     *         项目 / 成员不存在时抛出（A0402）
     */
    @Override
    public boolean bindMember(long projectId, long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // ② 存在性校验：selectById 会被 MP 自动追加 deleted_at = 0，
        // 因此「已逻辑删除的项目 / 成员」在这里同样按不存在处理
        assertProjectExists(projectId);
        assertMemberExists(memberId);

        // ③ 应用层查重：命中就给出可读提示（唯一索引抛的是英文 SQL 异常，不能直接给前端）
        ThrowUtils.throwIf(this.count(relationWrapper(projectId, memberId)) > 0,
                ErrorCode.PARAMS_ERROR, "该成员已参与此项目");

        StudioMemberProject relation = new StudioMemberProject();
        relation.setProjectId(projectId);
        relation.setMemberId(memberId);
        try {
            return this.save(relation);
        } catch (DuplicateKeyException e) {
            // ④ 并发竞态兜底：文案与上面完全一致——对调用方而言，
            // 「先查出来的重复」与「并发撞唯一索引的重复」是同一件事
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已参与此项目");
        }
    }

    /**
     * 解绑成员（会拦住「移除当前队长」）
     *
     * <p>为什么这样设计：项目表里存着 leader_id，成员名单存在关联表里，
     * 两者必须保持一致——「队长必然出现在参与成员列表」是本模块的不变量。
     * 如果允许把队长从关联表里删掉，就会出现「项目有队长、但成员列表里没有他」
     * 的破窗状态：官网的项目成员区域看不到负责人，而项目本身又确实有负责人。
     * 所以这里先读 leader_id 做守卫；想换人，必须先走「更换队长」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验两个 id 为正数；</li>
     *     <li>② 先确认关联存在：不存在要明确说「未参与」，而不是返回 false 让前端猜；</li>
     *     <li>③ 队长守卫：查项目读 leader_id，命中就拒绝并给出下一步动作；</li>
     *     <li>④ 物理删除关联行。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的守卫读的是 studio_project.leader_id（权威源），
     * 不是关联表里的某一行。若改成「查关联表里有没有 leader 标记」，
     * 就多了一份需要同步的冗余状态，两边一旦不一致，守卫就会失效。
     *
     * @param projectId 项目 ID，必须大于 0
     * @param memberId 成员 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法、
     *         该成员是当前队长时抛出（A0401）；关联不存在时抛出（A0402）
     */
    @Override
    public boolean unbindMember(long projectId, long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");

        // ② 先确认关系存在：不存在要明确说「未参与」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(projectId, memberId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未参与此项目");

        // ③ 队长守卫：leader_id 是权威源，且「队长必然出现在参与成员列表」是本模块的不变量。
        // 若允许移除队长，就会出现「项目有队长、但成员列表里没有他」的破窗状态
        StudioProject project = studioProjectMapper.selectById(projectId);
        if (project != null && Objects.equals(project.getLeaderId(), memberId)) {
            // 提示要给出下一步动作：管理员需要先去「更换队长」，而不是自己猜为什么删不掉
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队长不能从参与成员中移除，请先更换队长");
        }

        // ④ 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(projectId, memberId));
    }

    // ==================== 读操作 ====================

    /**
     * 项目的参与成员（管理端视图）
     *
     * <p>为什么这样设计：先校验项目存在，再查成员。顺序不能反——
     * 反过来的话，项目 ID 根本不存在时也会返回一个空列表，
     * 前端会把它理解成「这个项目还没有成员」，而实际上项目本身就不存在。
     * 用 A0402 明确区分这两种情况，前端才能决定是展示空态还是报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验项目存在，不存在给 A0402；</li>
     *     <li>③ 两步查询取成员实体，再逐条转成管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：队长一定在返回结果里（这是同步不变量），
     * 所以前端不需要再单独调接口取队长，否则同一个人会出现两次。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 成员列表（按置顶权重 → id 倒序）；项目没有成员时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         项目不存在时抛出（A0402）
     */
    @Override
    public List<MemberVO> listMembersByProject(long projectId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // ② 先校验项目存在：否则「项目不存在」会被伪装成「这个项目还没有成员」
        assertProjectExists(projectId);
        // ③ 取数逻辑与管理端、C 端共用，保证两端看到的成员完全一致
        return selectMembersOfProject(projectId).stream().map(this::toMemberVO).toList();
    }

    /**
     * 成员参与的项目（管理端视图）
     *
     * <p>为什么这样设计：与 listMembersByProject 对称——先校验成员存在，
     * 再查项目。不先校验的话，成员 ID 不存在时也会返回空列表，
     * 前端分不清是「这个人没参与任何项目」还是「这个人根本不存在」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验成员存在，不存在给 A0402；</li>
     *     <li>③ 两步查询取项目实体，再逐条转成管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：管理端 VO 带 content 正文大字段，一次查回这个人参与的所有项目，
     * 响应体会随项目数线性增长。参与项目数量通常很少，所以可以接受；
     * 若将来一个成员能参与几十个项目，这里就应该换成列表 VO。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 项目列表（按置顶权重 → 创建时间 → id 倒序）；无项目时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         成员不存在时抛出（A0402）
     */
    @Override
    public List<ProjectVO> listProjectsByMember(long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // ② 先校验成员存在：否则「成员不存在」会被伪装成「这个人没有项目」
        assertMemberExists(memberId);
        // ③ 与管理端同一条取数逻辑，只是转换成管理端 VO
        return selectProjectsOfMember(memberId).stream().map(this::toProjectVO).toList();
    }

    // ==================== C 端：脱敏视图（供官网详情页装配）====================

    /**
     * 成员参与的项目（门户脱敏视图）
     *
     * <p>为什么这样设计：与管理端用同一条查询（selectProjectsOfMember），
     * 只在最后一步换成脱敏 VO。这样两端的筛选与排序规则天然一致，
     * 不会出现「后台看到 3 个、官网只显示 2 个」这种对不上的情况——
     * 这类不一致一旦出现，排查成本极高，因为两边代码看起来都「没问题」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验成员存在，不存在给 A0402；</li>
     *     <li>③ 复用同一套两步查询，再转成脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：脱敏靠 ProjectFrontVO 里没有 content / leaderId /
     * sortOrder / 审计字段实现，不是靠这里手动过滤。
     * 若为了「字段完整」给那个 VO 补字段，这个接口会静默泄露内部数据。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 脱敏项目列表；无项目时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         成员不存在时抛出（A0402）
     */
    @Override
    public List<ProjectFrontVO> listFrontProjectsByMember(long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // ② 先校验成员存在：这是 C 端接口，更不能把「不存在」伪装成「空列表」
        assertMemberExists(memberId);
        // ③ 与管理端同一条查询，只是换成脱敏 VO——
        // 这样两端的筛选与排序规则天然一致，不会出现「后台看到 3 个、官网只显示 2 个」
        return selectProjectsOfMember(memberId).stream().map(this::toProjectFrontVO).toList();
    }

    /**
     * 项目的参与成员（门户脱敏视图，项目详情页用）
     *
     * <p>为什么这样设计：官网项目详情要展示「这个项目是谁做的」，
     * 而参与成员是本项目唯一对外暴露的成员信息出口（属于产品确认保留的例外）。
     * 脱敏靠 MemberFrontVO 的结构实现——它没有 userId、置顶权重与审计字段，
     * 结构上就不可能把账号 ID 带出去。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验项目存在，不存在给 A0402；</li>
     *     <li>③ 复用同一套两步查询，再转成脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回结果里必然包含队长（同步不变量），
     * 官网不需要再单独渲染一个「队长」头像，否则同一个人会出现两次。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 脱敏成员列表；无成员时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         项目不存在时抛出（A0402）
     */
    @Override
    public List<MemberFrontVO> listFrontMembersByProject(long projectId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // ② 先校验项目存在：否则「项目不存在」会被伪装成「这个项目没有成员」
        assertProjectExists(projectId);
        // ③ 队长必然在结果里（leader_id 同步不变量），官网项目详情的成员区域不用再自己拼
        return selectMembersOfProject(projectId).stream().map(this::toMemberFrontVO).toList();
    }

    // ==================== 两步查询（管理端与 C 端共用，保证两端语义一致）====================

    /**
     * 两步查询：取参与某项目的全部成员实体
     *
     * 第一步走 idx_proj_member (project_id, member_id) 的覆盖索引（只 select 一列、不回表）；
     * 第二步 IN 查主表时，MyBatis-Plus 会自动追加 deleted_at = 0，
     * 于是「关联还在、但成员已被逻辑删除」的悬空数据被天然过滤（查询侧防御）。
     *
     * @param projectId 项目 ID，调用方保证已校验
     * @return 成员实体列表（已排序）
     */
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

        // 第二步：IN 查主表，MP 自动追加 deleted_at = 0，已删除的成员被过滤掉
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getId, memberIds);
        // 成员侧沿用成员模块的默认排序：置顶权重倒序 + id 倒序兜底
        wrapper.orderByDesc(StudioMember::getSortOrder).orderByDesc(StudioMember::getId);
        return studioMemberMapper.selectList(wrapper);
    }

    /**
     * 两步查询：取某成员参与的全部项目实体
     *
     * 第一步走 uk_member_proj (member_id, project_id) 的最左前缀；
     * 第二步 IN 查主表时自动带上 deleted_at = 0，
     * 「项目已被删除但关联还在」的悬空数据会被过滤掉。
     *
     * @param memberId 成员 ID，调用方保证已校验
     * @return 项目实体列表（已排序）
     */
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

        // 第二步：IN 查主表，MP 自动追加 deleted_at = 0，已删除的项目被过滤掉
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

    /**
     * 删除项目时清理其全部关联行（关联表是物理删除）
     *
     * <p>为什么这样设计：关联表没有 deleted_at 列，也不该有——
     * 主表已经被逻辑删除，「谁参与了这个已不存在的项目」没有保留价值。
     * 物理删除还让唯一索引保持有效：若是逻辑删除，同一对
     * (project_id, member_id) 会与已删除的行冲突，重复绑定就再也插不进去了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 用 baseMapper.delete 按 project_id 删除，返回被删除的行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法必须在调用方的事务里执行。
     * 它自己不加 @Transactional，因为「先清关联、再删主表」这两步要作为一个整体提交，
     * 在这里另开事务会让两步互相独立，反而破坏原子性。
     *
     * @param projectId 项目 ID，必须大于 0
     * @return 被删除的关联行数（0 表示该项目本来就没有关联成员）
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）
     */
    @Override
    public long removeByProjectId(long projectId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(projectId <= 0, ErrorCode.PARAMS_ERROR, "项目 id 不合法");
        // ② 用 baseMapper.delete() 而不是 Service 的 remove()：前者返回被删除的行数（便于日志核对），
        // 后者只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId));
    }

    /**
     * 删除成员时清理其全部关联行（关联表是物理删除）
     *
     * <p>为什么这样设计：与 removeByProjectId 对称——删成员时也要把
     * 「他参与了哪些项目」一次性清掉，否则会留下指向已删除成员的悬空关联。
     * 同样返回行数而不是 boolean：级联清理是内部调用，行数对排查问题有用
     * （例如在日志里确认「这次清理掉了 3 条」）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 用 baseMapper.delete 按 member_id 删除，返回被删除的行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法同样必须活在调用方的事务里。
     * 它不加 @Transactional 是有意的——事务边界应该由「删主表」的那个方法统一划定。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 被删除的关联行数（0 表示该成员本来就没有参与任何项目）
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）
     */
    @Override
    public long removeByMemberId(long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // ② 与 removeByProjectId 同一写法：baseMapper.delete 返回行数，remove 只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getMemberId, memberId));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 按 (project_id, member_id) 构造关联查询条件（走唯一索引 uk_member_proj）
     *
     * 把条件收在一处：查重、解绑、ensure 的乐观检查都用它，
     * 避免某处少写一个 eq 导致「查到了别的项目的关联」。
     *
     * @param projectId 项目 ID
     * @param memberId 成员 ID
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioMemberProject> relationWrapper(long projectId, long memberId) {
        // 两个条件缺一不可：少一个就会把「该成员在其他项目的关联」也算进来
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
        // selectById 会被 MP 自动追加 deleted_at = 0：已删除的项目同样视为不存在
        ThrowUtils.throwIf(studioProjectMapper.selectById(projectId) == null,
                ErrorCode.NOT_FOUND_ERROR, "项目不存在");
    }

    /**
     * 校验成员存在（含「已逻辑删除视为不存在」）
     *
     * @param memberId 成员 ID
     */
    private void assertMemberExists(long memberId) {
        // 与 assertProjectExists 同理：逻辑删除的成员不该再被绑定或查询
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /**
     * 实体转管理端成员 VO
     *
     * 本类不能注入 StudioMemberService（那会构成循环依赖），
     * 所以这里的几行 BeanUtils 拷贝是刻意重复的，属于「依赖方向」换来的代价。
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
        // BeanUtils 对类型不一致的属性会静默跳过，所以必须手动赋值，
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
}
