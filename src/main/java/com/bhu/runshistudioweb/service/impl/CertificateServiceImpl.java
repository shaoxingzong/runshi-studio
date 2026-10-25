package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateAddRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateQueryRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.service.CertificateService;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 荣誉证书服务实现
 *
 * 继承 ServiceImpl<StudioCertificateMapper, StudioCertificate> 后单表 CRUD 已由
 * MyBatis-Plus 提供，本类只写「业务动作」：校验、排序、VO 转换。
 *
 * 本模块四个设计点在实现里的落点：
 * - 级别与类型正交：两个字段分别用 CertificateLevelEnum /
 * CertificateTypeEnum 校验，绝不接收「国家级竞赛」这种合并写法。
 * 合并成一个字段后，想查「所有国家级证书」只能写 LIKE '%national%'，
 * 既走不了组合索引，也会误匹配；两个维度拆开后才能各自独立筛选与统计；
 * - 获奖日期：非空 + 不得晚于今天（isAfter(LocalDate.now()) 即拒绝）；
 * 字段类型是 LocalDate 而不是 LocalDateTime——它只表达「哪一天」，
 * 用 LocalDateTime 会凭空多出 00:00:00 的时分秒，前端还得再截断；
 * - 证书图片：非空校验（DDL 中 image_url 是 NOT NULL，不校验会直接抛 SQL 错误）；
 * - 默认排序：sort_order 倒序 + award_date 倒序，
 * 与 idx_type_date / idx_level_date 的 (维度列, award_date DESC) 索引结构同向。
 *
 * 事务说明：deleteCertificate 是「清理关联表 + 逻辑删除主表」两次写，
 * 加了 @Transactional(rollbackFor = Exception.class)；
 * 新增与更新只是一次写，数据库自身的原子性足够，不加事务。
 * 写 rollbackFor = Exception.class 的原因：Spring 默认只回滚 RuntimeException，
 * 显式声明才能覆盖受检异常。
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class CertificateServiceImpl extends ServiceImpl<StudioCertificateMapper, StudioCertificate>
        implements CertificateService {

    /** 分页每页条数的默认值与上限（上限防「一次拉全表」） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** C 端公开列表的最大返回行数：匿名接口必须设上限，游客流量不可控 */
    private static final long FRONT_LIST_MAX_SIZE = 200L;

    /** 置顶权重不传时的默认值（0 表示不置顶，与 DDL 默认值一致） */
    private static final int DEFAULT_SORT_ORDER = 0;

    /**
     * 成员-证书关联服务：删除证书时要级联清理关联关系
     *
     * 为什么注入 Service 而不是在这里自己操作关联表 Mapper：
     * 「删证书要清哪些关联」这条规则只在关联服务里实现一次。
     * 依赖方向单向（本类 → 关联服务），不构成循环依赖：
     * MemberCertificateServiceImpl 只注入 Mapper，不依赖本类。
     */
    private final MemberCertificateService memberCertificateService;

    // ==================== 管理端 ====================

    /**
     * 新增证书
     *
     * <p>为什么这样设计：四个必填项对应四种「不拦住就会出事」的情况。
     * 其中 imageUrl 的非空校验尤其重要——DDL 里该列是 NOT NULL，
     * 不在这里拦的话，用户看到的会是数据库抛出的 B0001「系统错误」，
     * 既不知道是哪个字段错了，也不知道该补什么。
     * 级别与类型是两个正交维度，必须各自独立校验：合并成「国家级竞赛」这种
     * 单字段写法后，任一维度都无法单独筛选与统计。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体：@Valid 只在真的有请求体时生效，整体为 null 时它什么也不做，
     *         不兜这一层，后面拆箱就会变成 500；</li>
     *     <li>② 校验标题、级别、类型、获奖日期、图片五项：
     *         全部排在组装实体之前，任何一项不合法都不该产生一次数据库往返；</li>
     *     <li>③ 组装实体并落库：sortOrder 不传时显式置 0，而不是留给 DDL 默认值。</li>
     * </ol>
     *
     * <p>容易踩的坑：sortOrder 显式赋值 0 而不是依赖数据库默认值。
     * 依赖默认值意味着「数据库改了默认值，Java 侧行为就跟着漂移」，
     * 而这种漂移不会在任何代码里留下痕迹，排查时很难想到。
     *
     * @param certificateAddRequest 新增请求，不允许为 null；名称、级别、类型、
     *                              获奖日期、图片五项必填
     * @return 新证书 ID（19 位 Long，接口层序列化成字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为空、必填项为空、
     *         级别或类型不在枚举内、获奖日期晚于今天时抛出（A0401）；
     *         落库失败时抛出（B0001）
     */
    @Override
    public long addCertificate(CertificateAddRequest certificateAddRequest) {
        // ① 请求体整体为 null 时 @Valid 不会触发，这里兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        String title = certificateAddRequest.getTitle();

        // ② 标题：trim 后再判空，避免「   」这种全空格的标题入库
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "证书名称不能为空");
        // 两个维度：必须是枚举内的合法取值（拆开存，各自独立校验，互不牵连）
        CertificateLevelEnum level = resolveLevel(certificateAddRequest.getAwardLevel(), true);
        CertificateTypeEnum type = resolveType(certificateAddRequest.getAwardType(), true);
        // 获奖日期：非空 + 不得晚于今天（还没发生的获奖日期一定是填错了）
        LocalDate awardDate = resolveAwardDate(certificateAddRequest.getAwardDate(), true);
        // 证书图片：DDL 里 image_url 是 NOT NULL，必须在这里拦住而不是等数据库报错
        ThrowUtils.throwIf(StrUtil.isBlank(certificateAddRequest.getImageUrl()),
                ErrorCode.PARAMS_ERROR, "证书图片不能为空");

        // ③ 组装实体：只 set 请求里允许出现的字段，deletedAt 与审计字段一律不碰
        StudioCertificate certificate = new StudioCertificate();
        certificate.setTitle(title.trim());
        certificate.setAwardLevel(level.getValue());
        certificate.setAwardType(type.getValue());
        certificate.setAwardDate(awardDate);
        certificate.setImageUrl(certificateAddRequest.getImageUrl().trim());
        // 置顶权重不传按 0：显式赋值比依赖 DDL 默认值更直观，DDL 改默认值时行为也不会漂移
        certificate.setSortOrder(certificateAddRequest.getSortOrder() == null
                ? DEFAULT_SORT_ORDER : certificateAddRequest.getSortOrder());

        boolean saveResult = this.save(certificate);
        // save 返回 false 说明没插进去，必须显式判断，否则会把「没存进去」当成新增成功
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增证书失败，数据库异常");
        return certificate.getId();
    }

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * <p>为什么这样设计：两个刻意的处理。
     * 第一，先查存在再更新——updateById 失败时只返回 false，
     * 前端只能看到「更新失败」，先查一次才能给出 A0402「证书不存在」。
     * 第二，传了才校验（包括「传空串要拦住」）——不传表示不改，
     * 传了空串是想清空，而 NOT NULL 列不允许清空，必须在这里拦成 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并先查一次，确认目标存在；</li>
     *     <li>② 对「传了的字段」逐一校验，与新增走同一套枚举与日期规则，
     *         避免新增能过、更新不能过的两套标准；</li>
     *     <li>③ 组装只含白名单字段的更新对象并 updateById。</li>
     * </ol>
     *
     * <p>容易踩的坑：updateById 默认跳过 null 字段，这正是
     * 「传 null 表示不修改」语义成立的基础。所以千万不要为了「统一处理」
     * 给字段填默认值，否则「不修改」会变成「改成默认值」。
     *
     * @param certificateUpdateRequest 更新请求，不允许为 null；id 必填，
     *                                 其余字段为 null 表示保持原值
     * @return true 表示更新成功；证书不存在时抛异常而不是返回 false
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 为空、标题或图片传空串、
     *         级别或类型非法、获奖日期晚于今天时抛出（A0401）；证书不存在时抛出（A0402）
     */
    @Override
    public boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest) {
        // ① 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        ThrowUtils.throwIf(certificateUpdateRequest == null || certificateUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        Long id = certificateUpdateRequest.getId();

        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // ② 传了才校验（null 表示不修改）——合法取值判定与新增走同一套枚举，避免两套标准
        CertificateLevelEnum level = resolveLevel(certificateUpdateRequest.getAwardLevel(), false);
        CertificateTypeEnum type = resolveType(certificateUpdateRequest.getAwardType(), false);
        LocalDate awardDate = resolveAwardDate(certificateUpdateRequest.getAwardDate(), false);
        // 图片：传了就必须是非空值（NOT NULL 列不允许清空，也不允许改成空串）
        if (certificateUpdateRequest.getImageUrl() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(certificateUpdateRequest.getImageUrl()),
                    ErrorCode.PARAMS_ERROR, "证书图片不能为空");
        }
        // 名称：传了就必须有实际内容。
        // 只做 trim 而不判空的话，「   」会被清成空串写进库——
        // 官网列表里就会出现一条「没有名字的证书」，且前端无从判断该怎么展示
        if (certificateUpdateRequest.getTitle() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(certificateUpdateRequest.getTitle()),
                    ErrorCode.PARAMS_ERROR, "证书名称不能为空");
        }

        // ③ 只 set 允许修改的字段：这是服务端写死的白名单，绝不能直接把 DTO 转成实体 updateById
        StudioCertificate update = new StudioCertificate();
        update.setId(id);
        update.setTitle(certificateUpdateRequest.getTitle() == null
                ? null : certificateUpdateRequest.getTitle().trim());
        update.setAwardLevel(level == null ? null : level.getValue());
        update.setAwardType(type == null ? null : type.getValue());
        update.setAwardDate(awardDate);
        update.setImageUrl(certificateUpdateRequest.getImageUrl() == null
                ? null : certificateUpdateRequest.getImageUrl().trim());
        update.setSortOrder(certificateUpdateRequest.getSortOrder());

        // updateById 默认跳过 null 字段，这正是「传 null 表示不修改」语义成立的基础
        return this.updateById(update);
    }

    /**
     * 删除证书（逻辑删除 + 同一事务内清理成员-证书关联）
     *
     * <p>为什么这样设计：顺序是「先清关联、再删主表」，且两次写必须在同一事务内。
     * 反过来（先删主表）一旦清理失败，就会留下指向已删除证书的悬空关联；
     * 而现在的顺序下，主表删除失败会让整个事务回滚，
     * 不会出现「证书还在、关联却没了」的静默数据丢失。
     * 主表用逻辑删除保留可追溯性，关联表用物理删除——
     * 「谁持有了一张已不存在的证书」没有保留价值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查一次，不存在给 A0402；</li>
     *     <li>② 清关联表，必须排在删主表之前；</li>
     *     <li>③ 逻辑删除主表，失败会连带回滚第 ② 步。</li>
     * </ol>
     *
     * <p>容易踩的坑：@Transactional 的 rollbackFor 必须显式写 Exception.class。
     * Spring 默认只回滚 RuntimeException，漏了它等于「事务声明了一半」：
     * 遇到受检异常时两次写只回滚一次，悬空数据照样会出现。
     *
     * @param id 证书 ID，必须大于 0
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         证书不存在时抛出（A0402）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteCertificate(long id) {
        // ① 先查存在性：不存在要给出 A0402，而不是让 removeById 静默返回 false
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // ② 同一事务内先清关联：证书被删后，
        // 「某成员持有该证书」的绑定关系必须一并消失，否则会留下悬空数据
        memberCertificateService.removeByCertificateId(id);

        // ③ 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    /**
     * 按 id 查证书（管理端视图）
     *
     * <p>为什么这样设计：查不到要抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code=0 但 data 为空」——它分不清这是
     * 「没有数据」还是「出错了」，只能靠猜决定展示空态还是错误提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数，省掉一次必然失败的数据库往返；</li>
     *     <li>② 查实体，逻辑删除的记录被自动过滤；</li>
     *     <li>③ 转管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只返回单条，所以可以放心带全部字段；
     * 列表接口不要复用它，管理端 VO 虽然不含正文，但字段越多，
     * 一页十条的响应体也越大。
     *
     * @param id 证书 ID，必须大于 0
     * @return 含图片、置顶权重与审计字段的证书信息，永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         证书不存在或已逻辑删除时抛出（A0402）
     */
    @Override
    public CertificateVO getCertificateById(long id) {
        // ① 0 与负数一定不是合法主键，先挡掉
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // ② getById 会被 MP 自动追加 deleted_at = 0，已逻辑删除的证书等同不存在
        StudioCertificate certificate = this.getById(id);
        // 查不到给明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(certificate == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");
        // ③ 转 VO：纯字段拷贝，没有类型不一致的属性
        return this.getCertificateVO(certificate);
    }

    /**
     * 管理端分页查询
     *
     * <p>为什么这样设计：分页参数在这里做兜底纠正而不是抛异常——
     * 页码小于 1 视为 1、pageSize 收敛到 50。传错页码没必要让整个查询失败，
     * 收敛到合理区间继续查体验更好；但上限必须真的收住，
     * 否则一个 pageSize=999999 就能把整表拉进内存。
     * 排序字段走白名单 switch：ORDER BY 位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是最典型的 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时换空对象，后续不用到处判空；</li>
     *     <li>② 纠正分页参数；</li>
     *     <li>③ 拼筛选条件并应用白名单排序；</li>
     *     <li>④ 分页查询并转 VO，保留 total / current / size 供前端分页器使用。</li>
     * </ol>
     *
     * <p>容易踩的坑：分页对象不要关掉 searchCount。关掉能省一次 COUNT，
     * 但前端分页器拿不到 total 就画不出总页数，表现为「能翻页但页码永远是 0」。
     *
     * @param certificateQueryRequest 查询条件，允许为 null（等价于无条件查第一页）
     * @return 分页结果，记录为管理端 VO；无数据时 records 为空列表、total 为 0
     */
    @Override
    public Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest) {
        // ① 请求为 null 时换空对象，后续代码就不用到处判空
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        // ② 分页兜底纠正（而不是报错）：页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        // ③ 筛选与排序都收在私有方法里，管理端与 C 端共用同一套语义
        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // ④ searchCount 保持默认 true：前端分页器需要 total
        Page<StudioCertificate> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toCertificateVOPage(entityPage);
    }

    // ==================== C 端 ====================

    /**
     * C 端公开列表（游客可访问）
     *
     * <p>为什么这样设计：与管理端分页有三点不同，都是公开接口的必要约束。
     * 一是返回脱敏 VO（不含 sortOrder 与审计字段），靠「目标 VO 没有这些字段」
     * 实现脱敏；二是固定排序，忽略请求里的 sortField / sortOrder——
     * 排序由产品定义，不由请求方决定；三是强制上限，
     * 游客流量不可控，无上限的 list 等于把整表拉回内存。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时换空对象；</li>
     *     <li>② 拼筛选条件；</li>
     *     <li>③ 固定排序：置顶权重 → 获奖日期 → id 倒序；</li>
     *     <li>④ 查一页（不数总数）并逐条转成脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序的第三个键 id 不能省。置顶权重同为默认 0、
     * 同一天获奖都很常见，前两个键完全相同时，缺少稳定次序会让翻页出现
     * 「同一行出现在两页」或「某行被跳过」——这类问题只在数据量上来后才暴露。
     *
     * @param certificateQueryRequest 查询条件，允许为 null；可按级别、类型、标题筛选
     * @return 脱敏后的证书列表，最多 200 条；无数据时返回空列表而不是 null
     */
    @Override
    public List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest) {
        // ① 请求为 null 时换空对象
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        // ② 固定排序：置顶权重倒序 + 获奖日期倒序 + id 倒序兜底
        // （与后台默认排序一致，保证官网与运营预期相同）。
        // 追加 id 作为最终排序键：前两个键都相同时（权重同为默认 0、同一天获奖都很常见），
        // 缺少稳定次序的翻页会出现「同一行出现在两页 / 某行被跳过」。
        // 刻意不接 sortField/sortOrder：排序规则由产品定义，不由请求方决定
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);

        // ③ searchCount=false 省掉一次 COUNT 查询（这里只需要一页数据，不画分页器）；
        // pageSize 上限防止匿名接口被刷
        Page<StudioCertificate> page = this.page(new Page<>(1, FRONT_LIST_MAX_SIZE, false), wrapper);
        // ④ 逐条转脱敏 VO：转换里没有手写的字段过滤，脱敏靠 VO 结构本身
        return page.getRecords().stream().map(this::getCertificateFrontVO).toList();
    }

    // ==================== VO 转换 ====================

    /**
     * 实体转管理端 VO
     *
     * <p>为什么这样设计：用 BeanUtils.copyProperties 而不是手写一长串 setXxx。
     * 手写的话，实体加一个字段就要记得回来补一行，漏一个字段就是一个线上 bug；
     * 自动拷贝不会漏。代价是类型不一致的属性会被静默跳过，
     * 所以两边的字段名与类型必须严格对应。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null，纯转换方法不制造异常；</li>
     *     <li>② 按属性名自动拷贝。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里能自动拷贝成功，前提是 awardDate 在实体与 VO 里
     * 都是 LocalDate。若一边写成 LocalDateTime，结果不是报错，而是
     * 「这个日期永远是 null」——不报错的 bug 最难查。
     *
     * @param certificate 证书实体，允许为 null
     * @return 管理端 VO；入参为 null 时返回 null，调用方需要判空
     */
    @Override
    public CertificateVO getCertificateVO(StudioCertificate certificate) {
        // ① 纯转换方法：入参为 null 就返回 null
        if (certificate == null) {
            return null;
        }
        CertificateVO certificateVO = new CertificateVO();
        // ② 用属性名自动拷贝：两边字段名与类型一致（awardDate 都是 LocalDate）才能拷成功
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    /**
     * 实体转 C 端 VO
     *
     * <p>为什么这样设计：脱敏靠「目标 VO 没有这些字段」实现——sortOrder 与审计字段在
     * CertificateFrontVO 里根本不存在，copyProperties 也就拷不过去。
     * 这比「拷完再手动置空」可靠：以后实体新增敏感字段时，
     * 只要没人把它加进 VO，它就自动被挡住，不需要有人记得来改这个方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null；</li>
     *     <li>② 自动拷贝到脱敏 VO，多出来的字段自然被丢弃。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「什么都没做」就是正确的脱敏。
     * 若为了「字段完整」给 CertificateFrontVO 加上 sortOrder，
     * 脱敏会在零代码改动的情况下失效。
     *
     * @param certificate 证书实体，允许为 null
     * @return 脱敏 VO；入参为 null 时返回 null，调用方需要判空
     */
    @Override
    public CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate) {
        // ① 纯转换方法：入参为 null 就返回 null
        if (certificate == null) {
            return null;
        }
        CertificateFrontVO frontVO = new CertificateFrontVO();
        // ② 脱敏靠「目标 VO 没有这些字段」实现：sortOrder 与审计字段结构上就不可能被拷贝出去
        BeanUtils.copyProperties(certificate, frontVO);
        return frontVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 解析并校验级别取值
     *
     * 级别与类型各自一个方法而不是合成一个「校验两个维度」的方法：
     * 两边的枚举不同、提示文案不同，合起来只会让 required 分支互相纠缠。
     *
     * @param awardLevel 级别值，可为空
     * @param required 是否必填（新增必填、更新非必填）
     * @return 级别枚举；非必填且未传时返回 null
     */
    private CertificateLevelEnum resolveLevel(String awardLevel, boolean required) {
        // 先判空再查枚举：否则 required=false 时会把「不修改」误判成「取值非法」
        if (StrUtil.isBlank(awardLevel)) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "证书级别不能为空");
            return null;
        }
        CertificateLevelEnum level = CertificateLevelEnum.of(awardLevel);
        // 非法值一旦入库，官网按级别筛选就会静默漏数据，所以必须在入口拦住
        ThrowUtils.throwIf(level == null, ErrorCode.PARAMS_ERROR,
                "非法的证书级别，仅支持 " + CertificateLevelEnum.valuesText());
        return level;
    }

    /**
     * 解析并校验类型取值
     *
     * 与 resolveLevel 结构完全对称：类型是独立维度，不能借用级别的校验逻辑，
     * 否则两边枚举一改就会互相牵连。
     *
     * @param awardType 类型值，可为空
     * @param required 是否必填
     * @return 类型枚举；非必填且未传时返回 null
     */
    private CertificateTypeEnum resolveType(String awardType, boolean required) {
        // 与 resolveLevel 同样的顺序：先判空，再查枚举
        if (StrUtil.isBlank(awardType)) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "证书类型不能为空");
            return null;
        }
        CertificateTypeEnum type = CertificateTypeEnum.of(awardType);
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR,
                "非法的证书类型，仅支持 " + CertificateTypeEnum.valuesText());
        return type;
    }

    /**
     * 校验获奖日期：非空（必填时）+ 不得晚于今天
     *
     * 为什么用 LocalDate 而不是 LocalDateTime：获奖日期表达的是「哪一天」，
     * 不含时刻。用 LocalDateTime 会让库里凭空多出 00:00:00，
     * 前端还要自己截断；而且「晚于今天」的判定也得先归一化到天，反而更绕。
     *
     * @param awardDate 获奖日期，可为空
     * @param required 是否必填
     * @return 日期；非必填且未传时返回 null
     */
    private LocalDate resolveAwardDate(LocalDate awardDate, boolean required) {
        if (awardDate == null) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "获奖日期不能为空");
            return null;
        }
        // 还没发生的获奖日期一定是填错了（注意用 isAfter 而不是 isBefore，
        // 写成 isBefore 会把「今天」也判成非法）
        ThrowUtils.throwIf(awardDate.isAfter(LocalDate.now()), ErrorCode.PARAMS_ERROR, "获奖日期不能晚于今天");
        return awardDate;
    }

    /**
     * 构建筛选条件（管理端与 C 端共用，保证两边筛选语义一致）
     *
     * 用 LambdaQueryWrapper 而不是字符串版 QueryWrapper：
     * 字段名写错在编译期就能发现，重构字段时也不会漏改 SQL 条件。
     *
     * @param query 查询条件，调用方保证非 null
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioCertificate> buildQueryWrapper(CertificateQueryRequest query) {
        LambdaQueryWrapper<StudioCertificate> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioCertificate::getId, query.getId());
        // 标题模糊：MP 用占位符传参，不存在 SQL 注入
        wrapper.like(StrUtil.isNotBlank(query.getTitle()), StudioCertificate::getTitle, query.getTitle());
        // 两个维度各自精确匹配：级别走 idx_level_date、类型走 idx_type_date 的最左前缀
        wrapper.eq(StrUtil.isNotBlank(query.getAwardType()), StudioCertificate::getAwardType, query.getAwardType());
        wrapper.eq(StrUtil.isNotBlank(query.getAwardLevel()), StudioCertificate::getAwardLevel, query.getAwardLevel());
        return wrapper;
    }

    /**
     * 应用排序规则（白名单映射）
     *
     * 关键点：前端只能传「字段名」，由这里映射成实体字段引用。
     * 如果把前端字符串直接拼进 ORDER BY，就是最典型的 SQL 注入入口——
     * ORDER BY 位置无法用占位符参数化，只能靠白名单。
     *
     * @param wrapper 查询包装类
     * @param sortField 排序字段名，可为空（走默认排序）
     * @param sortOrder 排序方向 asc / ascend，其余一律按倒序处理
     */
    private void applySort(LambdaQueryWrapper<StudioCertificate> wrapper, String sortField, String sortOrder) {
        // 兼容前端常见的 "ascend / descend" 与后端常见的 "asc / desc" 两种写法
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder) || "ascend".equalsIgnoreCase(sortOrder);
        switch (sortField == null ? "" : sortField) {
            case "id" -> wrapper.orderBy(true, isAsc, StudioCertificate::getId);
            case "title" -> wrapper.orderBy(true, isAsc, StudioCertificate::getTitle);
            case "awardLevel" -> wrapper.orderBy(true, isAsc, StudioCertificate::getAwardLevel);
            case "awardType" -> wrapper.orderBy(true, isAsc, StudioCertificate::getAwardType);
            case "awardDate" -> wrapper.orderBy(true, isAsc, StudioCertificate::getAwardDate);
            case "sortOrder" -> wrapper.orderBy(true, isAsc, StudioCertificate::getSortOrder);
            // 默认排序：置顶权重倒序 + 获奖日期倒序 + id 倒序兜底
            // （前两个键与 idx_type_date / idx_level_date 的 (维度列, award_date DESC) 同向，
            // 按维度筛选时索引能直接提供有序结果，省掉一次 filesort）
            default -> wrapper.orderByDesc(StudioCertificate::getSortOrder)
                    .orderByDesc(StudioCertificate::getAwardDate)
                    .orderByDesc(StudioCertificate::getId);
        }
    }

    /**
     * 实体分页转 VO 分页（保留 total/size/current，前端分页组件依赖这三个值）
     *
     * 新建一个 Page 而不是在原对象上改：泛型不同，无法就地转换。
     * 三个分页元数据必须显式传过去，漏了 total 前端就画不出总页数。
     *
     * @param entityPage 实体分页
     * @return VO 分页
     */
    private Page<CertificateVO> toCertificateVOPage(Page<StudioCertificate> entityPage) {
        Page<CertificateVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getCertificateVO).toList());
        return voPage;
    }
}
