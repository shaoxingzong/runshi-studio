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

    /** 成员-证书关联服务：删除证书时要级联清理关联关系 */
    private final MemberCertificateService memberCertificateService;

    // 管理端

    /** 新增证书 */
    @Override
    public long addCertificate(CertificateAddRequest certificateAddRequest) {
        // 请求体整体为 null 时 @Valid 不会触发，这里兜一层避免后面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        String title = certificateAddRequest.getTitle();

        // 标题：trim 后再判空，避免「 」这种全空格的标题入库
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "证书名称不能为空");
        // 两个维度：必须是枚举内的合法取值（拆开存，各自独立校验，互不牵连）
        CertificateLevelEnum level = resolveLevel(certificateAddRequest.getAwardLevel(), true);
        CertificateTypeEnum type = resolveType(certificateAddRequest.getAwardType(), true);
        // 获奖日期：非空 + 不得晚于今天（还没发生的获奖日期一定是填错了）
        LocalDate awardDate = resolveAwardDate(certificateAddRequest.getAwardDate(), true);
        // 证书图片：DDL 里 image_url 是 NOT NULL，必须在这里拦住而不是等数据库报错
        ThrowUtils.throwIf(StrUtil.isBlank(certificateAddRequest.getImageUrl()),
                ErrorCode.PARAMS_ERROR, "证书图片不能为空");

        // 组装实体：只 set 请求里允许出现的字段，deletedAt 与审计字段一律不碰
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

    /** 更新证书（部分更新：字段为 null 表示不修改） */
    @Override
    public boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest) {
        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        ThrowUtils.throwIf(certificateUpdateRequest == null || certificateUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        Long id = certificateUpdateRequest.getId();

        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        // 传了才校验（null 表示不修改）——合法取值判定与新增走同一套枚举，避免两套标准
        CertificateLevelEnum level = resolveLevel(certificateUpdateRequest.getAwardLevel(), false);
        CertificateTypeEnum type = resolveType(certificateUpdateRequest.getAwardType(), false);
        LocalDate awardDate = resolveAwardDate(certificateUpdateRequest.getAwardDate(), false);
        if (certificateUpdateRequest.getImageUrl() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(certificateUpdateRequest.getImageUrl()),
                    ErrorCode.PARAMS_ERROR, "证书图片不能为空");
        }
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

    /** 删除证书（逻辑删除 + 同一事务内清理成员-证书关联） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteCertificate(long id) {
        // 先查存在性：不存在要给出 A0402，而不是让 removeById 静默返回 false
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        StudioCertificate existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");

        memberCertificateService.removeByCertificateId(id);

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，数据可追溯
        return this.removeById(id);
    }

    /** 按 id 查证书（管理端视图） */
    @Override
    public CertificateVO getCertificateById(long id) {
        // 0 与负数一定不是合法主键，先挡掉
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // getById 会被 MP 自动追加 deleted_at = 0，已逻辑删除的证书等同不存在
        StudioCertificate certificate = this.getById(id);
        // 查不到给明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(certificate == null, ErrorCode.NOT_FOUND_ERROR, "证书不存在");
        // 转 VO：纯字段拷贝，没有类型不一致的属性
        return this.getCertificateVO(certificate);
    }

    /** 管理端分页查询 */
    @Override
    public Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest) {
        // 请求为 null 时换空对象，后续代码就不用到处判空
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        // 分页兜底纠正（而不是报错）：页码传错没必要让整个查询失败，收敛到合理区间继续查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        // 筛选与排序都收在私有方法里，管理端与 C 端共用同一套语义
        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // searchCount 保持默认 true：前端分页器需要 total
        Page<StudioCertificate> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toCertificateVOPage(entityPage);
    }

    // C 端

    /** C 端公开列表（游客可访问） */
    @Override
    public List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest) {
        // 请求为 null 时换空对象
        CertificateQueryRequest query = certificateQueryRequest == null
                ? new CertificateQueryRequest() : certificateQueryRequest;

        LambdaQueryWrapper<StudioCertificate> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);

        Page<StudioCertificate> page = this.page(new Page<>(1, FRONT_LIST_MAX_SIZE, false), wrapper);
        // 逐条转脱敏 VO：转换里没有手写的字段过滤，脱敏靠 VO 结构本身
        return page.getRecords().stream().map(this::getCertificateFrontVO).toList();
    }

    // VO 转换

    /** 实体转管理端 VO */
    @Override
    public CertificateVO getCertificateVO(StudioCertificate certificate) {
        // 纯转换方法：入参为 null 就返回 null
        if (certificate == null) {
            return null;
        }
        CertificateVO certificateVO = new CertificateVO();
        // 用属性名自动拷贝：两边字段名与类型一致（awardDate 都是 LocalDate）才能拷成功
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    /** 实体转 C 端 VO */
    @Override
    public CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate) {
        // 纯转换方法：入参为 null 就返回 null
        if (certificate == null) {
            return null;
        }
        CertificateFrontVO frontVO = new CertificateFrontVO();
        // 脱敏靠「目标 VO 没有这些字段」实现：sortOrder 与审计字段结构上就不可能被拷贝出去
        BeanUtils.copyProperties(certificate, frontVO);
        return frontVO;
    }

    // 私有工具方法

    /** 解析并校验级别取值 */
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
     * 与 resolveLevel 结构对称：类型是独立维度，不能借用级别的校验逻辑，否则两边枚举一改就互相牵连。
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

    /** 校验获奖日期：非空（必填时）+ 不得晚于今天 */
    private LocalDate resolveAwardDate(LocalDate awardDate, boolean required) {
        if (awardDate == null) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "获奖日期不能为空");
            return null;
        }
        ThrowUtils.throwIf(awardDate.isAfter(LocalDate.now()), ErrorCode.PARAMS_ERROR, "获奖日期不能晚于今天");
        return awardDate;
    }

    /** 构建筛选条件（管理端与 C 端共用，保证两边筛选语义一致） */
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

    /** 应用排序规则（白名单映射） */
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
            default -> wrapper.orderByDesc(StudioCertificate::getSortOrder)
                    .orderByDesc(StudioCertificate::getAwardDate)
                    .orderByDesc(StudioCertificate::getId);
        }
    }

    /** 实体分页转 VO 分页（保留 total/size/current，前端分页组件依赖这三个值） */
    private Page<CertificateVO> toCertificateVOPage(Page<StudioCertificate> entityPage) {
        Page<CertificateVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getCertificateVO).toList());
        return voPage;
    }
}
