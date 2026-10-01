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
import com.bhu.runshistudioweb.model.vo.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.service.CertificateService;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 荣誉证书服务实现
 *
 * author: shaoshing
 *
 * <p>继承 {@code ServiceImpl<StudioCertificateMapper, StudioCertificate>} 后单表 CRUD 已由
 * MyBatis-Plus 提供，本类只写「业务动作」：校验、排序、VO 转换。
 *
 * <p><b>本模块四个考点在实现里的落点</b>：
 * <ol>
 *     <li><b>级别与类型正交</b>：两个字段分别用 {@link CertificateLevelEnum} /
 *     {@link CertificateTypeEnum} 校验，绝不接收「国家级竞赛」这种合并写法（ADR-5）；</li>
 *     <li><b>获奖日期</b>：非空 + 不得晚于今天（{@code isAfter(LocalDate.now())} 即拒绝）；</li>
 *     <li><b>证书图片</b>：非空校验（DDL 中 image_url 是 NOT NULL，不校验会直接抛 SQL 错误）；</li>
 *     <li><b>默认排序</b>：{@code sort_order 倒序 + award_date 倒序}，
 *     与 idx_type_date / idx_level_date 的 {@code (维度列, award_date DESC)} 索引结构同向。</li>
 * </ol>
 *
 * <p>事务说明：{@link #deleteCertificate} 是「清理关联表 + 逻辑删除主表」两次写，
 * 已加 {@code @Transactional(rollbackFor = Exception.class)}（DESIGN.md 2.2）；
 * 新增与更新是一次写，不加事务。写 {@code rollbackFor = Exception.class} 的原因：
 * Spring 默认只回滚 RuntimeException，显式声明才能覆盖受检异常。
 */
@Service
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
     * <p>依赖方向单向（本类 → 关联服务），不构成循环依赖：
     * {@code MemberCertificateServiceImpl} 只注入 Mapper，不依赖本类。
     */
    @Resource
    private MemberCertificateService memberCertificateService;

    // ==================== 管理端 ====================

    /**
     * 新增证书（契约见 {@link CertificateService}）
     *
     * <p>实现要点：四个必填项其实对应四种「不拦住就会出事」的情况——
     * 其中 {@code imageUrl} 非空校验是为了把<b>数据库报错</b>提前成<b>参数提示</b>：
     * DDL 里该列是 NOT NULL，不校验的话用户只会看到 50000「系统错误」。
     *
     * <p>{@code sortOrder} 不传时显式置 0 而不是留 null：显示赋值比依赖 DDL 默认值更直观，
     * 将来 DDL 改默认值时行为也不会漂移。
     */
    @Override
    public long addCertificate(CertificateAddRequest certificateAddRequest) {
        // 请求体整体为 null 时 @Valid 不会触发，这里兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        String title = certificateAddRequest.getTitle();

        // 标题：trim 后再判空，避免"   "这种全空格的标题入库
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "证书名称不能为空");
        // 两个维度：必须是枚举内的合法取值（ADR-5 要求拆开存，各自独立校验）
        CertificateLevelEnum level = resolveLevel(certificateAddRequest.getAwardLevel(), true);
        CertificateTypeEnum type = resolveType(certificateAddRequest.getAwardType(), true);
        // 获奖日期：非空 + 不得晚于今天
        LocalDate awardDate = resolveAwardDate(certificateAddRequest.getAwardDate(), true);
        // 证书图片：DDL 里 image_url 是 NOT NULL，必须在这里拦住而不是等数据库报错
        ThrowUtils.throwIf(StrUtil.isBlank(certificateAddRequest.getImageUrl()),
                ErrorCode.PARAMS_ERROR, "证书图片不能为空");

        StudioCertificate certificate = new StudioCertificate();
        certificate.setTitle(title.trim());
        certificate.setAwardLevel(level.getValue());
        certificate.setAwardType(type.getValue());
        certificate.setAwardDate(awardDate);
        certificate.setImageUrl(certificateAddRequest.getImageUrl().trim());
        // 置顶权重不传按 0：显式赋值比依赖 DDL 默认值更直观
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
     * <p>两个刻意的处理：
     * <ul>
     *     <li><b>先查存在再更新</b>：{@code updateById} 失败时只返回 false，
     *     前端只能看到「更新失败」；先查一次才能给出 40400「证书不存在」；</li>
     *     <li><b>传了才校验</b>（包括"传空串要拦住"）：不传表示不改，
     *     传了空串是想清空——NOT NULL 列不允许，必须在这里拦成 40000。</li>
     * </ul>
     */
    @Override
    public boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest) {
        ThrowUtils.throwIf(certificateUpdateRequest == null || certificateUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        Long id = certificateUpdateRequest.getId();

        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // 传了才校验（null 表示不修改）——合法取值判定与新增走同一套枚举，避免两套标准
        CertificateLevelEnum level = resolveLevel(certificateUpdateRequest.getAwardLevel(), false);
        CertificateTypeEnum type = resolveType(certificateUpdateRequest.getAwardType(), false);
        LocalDate awardDate = resolveAwardDate(certificateUpdateRequest.getAwardDate(), false);
        // 图片：传了就必须是非空值（NOT NULL 列不允许清空，也不允许改成空串）
        if (certificateUpdateRequest.getImageUrl() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(certificateUpdateRequest.getImageUrl()),
                    ErrorCode.PARAMS_ERROR, "证书图片不能为空");
        }
        // 名称：传了就必须有实际内容。
        // 只做 trim 而不判空的话，"   " 会被清成空串写进库——
        // 官网列表里就会出现一条「没有名字的证书」，且前端无从判断该怎么展示
        if (certificateUpdateRequest.getTitle() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(certificateUpdateRequest.getTitle()),
                    ErrorCode.PARAMS_ERROR, "证书名称不能为空");
        }

        // 只 set 允许修改的字段：这是服务端写死的白名单，绝不能直接把 DTO 转成实体 updateById
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
     * 删除证书（逻辑删除 + 级联清理关联表，接口契约见 CertificateService）
     *
     * <p>两次写必须在<b>同一事务</b>内：先清关联再删主表。
     * 反过来的话，一旦主表删除失败，就会出现「证书还在、关联却没了」的静默数据丢失。
     *
     * @param id 证书 ID
     * @return true 表示删除成功
     */
    /**
     * 删除证书（逻辑删除 + <b>同一事务内</b>清理成员-证书关联）
     *
     * <p>顺序是「先清关联、再删主表」：反过来一旦主表删成功而清理失败，
     * 就会留下指向已删除证书的悬空关联（DESIGN.md 2.2）。
     *
     * <p>{@code rollbackFor = Exception.class} 必须写：Spring 默认只回滚
     * RuntimeException，漏了它等于「事务声明了一半」。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteCertificate(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // 同一事务内先清关联（DESIGN.md 2.2）：证书被删后，
        // 「某成员持有该证书」的绑定关系必须一并消失，否则会留下悬空数据
        memberCertificateService.removeByCertificateId(id);

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    /**
     * 按 id 查证书（管理端视图）
     *
     * <p>查不到要抛 40400 而不是返回 null：返回 null 会让前端收到
     * 「code=0 但 data 为空」——它分不清这是"没有数据"还是"出错了"。
     */
    @Override
    public CertificateVO getCertificateById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate certificate = this.getById(id);
        // 查不到给明确的 40400，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(certificate == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");
        return this.getCertificateVO(certificate);
    }

    /**
     * 管理端分页查询
     *
     * <p>分页参数在这里做<b>兜底纠正</b>而不是抛异常（页码 &lt;1 视为 1、pageSize 收敛到 50）：
     * 传错页码没必要让整个查询失败，收敛到合理区间继续查体验更好。
     *
     * <p>排序字段走白名单 switch：ORDER BY 位置<b>无法用占位符参数化</b>，
     * 把前端字符串直接拼进去就是最典型的 SQL 注入入口，只能靠白名单。
     */
    @Override
    public Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest) {
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        // 分页兜底纠正（而不是报错）：页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        Page<StudioCertificate> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toCertificateVOPage(entityPage);
    }

    // ==================== C 端 ====================

    /**
     * C 端公开列表（游客可访问）
     *
     * <p>与管理端分页的三点不同，都是公开接口的必要约束：
     * <ul>
     *     <li>返回<b>脱敏 VO</b>（不含 sortOrder 与审计字段）；</li>
     *     <li><b>固定排序</b>，忽略请求里的 sortField/sortOrder——排序由产品定义，不由请求方决定；</li>
     *     <li><b>强制上限</b>：游客流量不可控，无上限的 list 等于把整表拉回内存。</li>
     * </ul>
     */
    @Override
    public List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest) {
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        // 固定排序：置顶权重倒序 + 获奖日期倒序 + id 倒序兜底
        // （与后台默认排序一致，保证官网与运营预期相同）。
        // 追加 id 作为最终排序键：前两个键都相同时（权重同为默认 0、同一天获奖都很常见），
        // 缺少稳定次序的翻页会出现「同一行出现在两页 / 某行被跳过」。
        // 刻意不接 sortField/sortOrder：排序规则由产品定义，不由请求方决定
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);

        // searchCount=false 省掉一次 COUNT 查询；pageSize 上限防止匿名接口被刷
        Page<StudioCertificate> page = this.page(new Page<>(1, FRONT_LIST_MAX_SIZE, false), wrapper);
        return page.getRecords().stream().map(this::getCertificateFrontVO).toList();
    }

    // ==================== VO 转换 ====================

    /**
     * 实体转管理端 VO
     *
     * <p>用 {@code BeanUtils.copyProperties} 而不是手写一长串 setXxx：
     * 漏一个字段就是线上 bug，而字段新增时还要记得回来补。
     * 代价是<b>类型不一致的属性会被静默跳过</b>——所以两边字段名与类型必须严格对应
     * （例如 {@code awardDate} 都用 {@code LocalDate}，用错类型会表现为"日期永远是 null"且不报错）。
     */
    @Override
    public CertificateVO getCertificateVO(StudioCertificate certificate) {
        if (certificate == null) {
            return null;
        }
        CertificateVO certificateVO = new CertificateVO();
        // 用属性名自动拷贝：两边字段名与类型一致（awardDate 都是 LocalDate）才能拷成功
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    /**
     * 实体转 C 端 VO
     *
     * <p>脱敏靠「<b>目标 VO 没有这些字段</b>」实现：sortOrder 与审计字段在
     * {@code CertificateFrontVO} 里根本不存在，copyProperties 也就拷不过去。
     * 比"拷完再手动置空"可靠——以后实体新增敏感字段时，只要不加进 VO 就自动被挡住。
     */
    @Override
    public CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate) {
        if (certificate == null) {
            return null;
        }
        CertificateFrontVO frontVO = new CertificateFrontVO();
        // 脱敏靠「目标 VO 没有这些字段」实现：sortOrder 与审计字段结构上就不可能被拷贝出去
        BeanUtils.copyProperties(certificate, frontVO);
        return frontVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 解析并校验级别取值
     *
     * @param awardLevel 级别值
     * @param required   是否必填（新增必填、更新非必填）
     * @return 级别枚举；非必填且未传时返回 null
     */
    private CertificateLevelEnum resolveLevel(String awardLevel, boolean required) {
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
     * @param awardType 类型值
     * @param required  是否必填
     * @return 类型枚举；非必填且未传时返回 null
     */
    private CertificateTypeEnum resolveType(String awardType, boolean required) {
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
     * @param awardDate 获奖日期
     * @param required  是否必填
     * @return 日期；非必填且未传时返回 null
     */
    private LocalDate resolveAwardDate(LocalDate awardDate, boolean required) {
        if (awardDate == null) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "获奖日期不能为空");
            return null;
        }
        // 还没发生的获奖日期一定是填错了
        ThrowUtils.throwIf(awardDate.isAfter(LocalDate.now()), ErrorCode.PARAMS_ERROR, "获奖日期不能晚于今天");
        return awardDate;
    }

    /**
     * 构建筛选条件（管理端与 C 端共用，保证两边筛选语义一致）
     *
     * <p>用 {@code LambdaQueryWrapper} 而不是字符串版 {@code QueryWrapper}：
     * 字段名写错在编译期就能发现，重构字段时也不会漏改 SQL 条件。
     *
     * @param query 查询条件
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioCertificate> buildQueryWrapper(CertificateQueryRequest query) {
        LambdaQueryWrapper<StudioCertificate> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不拼进 SQL，省去手写 if 判断
        wrapper.eq(query.getId() != null, StudioCertificate::getId, query.getId());
        wrapper.like(StrUtil.isNotBlank(query.getTitle()), StudioCertificate::getTitle, query.getTitle());
        wrapper.eq(StrUtil.isNotBlank(query.getAwardType()), StudioCertificate::getAwardType, query.getAwardType());
        wrapper.eq(StrUtil.isNotBlank(query.getAwardLevel()), StudioCertificate::getAwardLevel, query.getAwardLevel());
        return wrapper;
    }

    /**
     * 应用排序规则（白名单映射）
     *
     * <p>关键点：前端只能传「字段名」，由这里映射成实体字段引用。
     * 如果把前端字符串直接拼进 ORDER BY，就是最典型的 SQL 注入入口——
     * ORDER BY 位置无法用占位符参数化，只能靠白名单。
     *
     * @param wrapper   查询包装类
     * @param sortField 排序字段名，可为空
     * @param sortOrder 排序方向 asc / descend，可为空
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
            // （考点④，前两个键与 idx_type_date / idx_level_date 的 (维度列, award_date DESC) 同向）
            default -> wrapper.orderByDesc(StudioCertificate::getSortOrder)
                    .orderByDesc(StudioCertificate::getAwardDate)
                    .orderByDesc(StudioCertificate::getId);
        }
    }

    /**
     * 实体分页转 VO 分页（保留 total/size/current，前端分页组件依赖这三个值）
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
