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
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

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
 * <p>已知待办：按 DESIGN.md 2.2，主表逻辑删除时应**在同一事务内级联清理**
 * {@code studio_member_certificate} 关联表，否则会留下悬空关联；
 * 这一步等关联表功能落地时补上（本类先不做，避免引入跨表事务的复杂度）。
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

    // ==================== 管理端 ====================

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

    @Override
    public boolean deleteCertificate(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    @Override
    public CertificateVO getCertificateById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate certificate = this.getById(id);
        // 查不到给明确的 40400，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(certificate == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");
        return this.getCertificateVO(certificate);
    }

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

    @Override
    public List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest) {
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        // 固定排序：置顶权重倒序 + 获奖日期倒序（与后台默认排序一致，保证官网与运营预期相同）。
        // 刻意不接 sortField/sortOrder：排序规则由产品定义，不由请求方决定
        wrapper.orderByDesc(StudioCertificate::getSortOrder).orderByDesc(StudioCertificate::getAwardDate);

        // searchCount=false 省掉一次 COUNT 查询；pageSize 上限防止匿名接口被刷
        Page<StudioCertificate> page = this.page(new Page<>(1, FRONT_LIST_MAX_SIZE, false), wrapper);
        return page.getRecords().stream().map(this::getCertificateFrontVO).toList();
    }

    // ==================== VO 转换 ====================

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
            // 默认排序：置顶权重倒序 + 获奖日期倒序（考点④，与 idx_type_date / idx_level_date 同向）
            default -> wrapper.orderByDesc(StudioCertificate::getSortOrder)
                    .orderByDesc(StudioCertificate::getAwardDate);
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
