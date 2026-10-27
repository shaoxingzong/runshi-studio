package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateAddRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateQueryRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;

import java.util.List;

/**
 * 荣誉证书服务接口
 *
 * 方法分「管理端（需 admin）」与「C 端（匿名）」两组，职责不混用。
 * 参数不合法 / 目标不存在时抛 BusinessException，不返回错误码、不返回 null
 * （例外：两个 VO 转换方法是纯转换，入参为 null 时返回 null）。
 *
 * 级别（国家级 / 省级 / 市级）与类型（竞赛 / 软著 / 专利 / 论文）是正交的两个维度，
 * 必须分列存储：合并成一个字段后，想查「所有国家级证书」只能做字符串模糊匹配，
 * 既走不了组合索引又会误匹配。
 *
 * @author shaoshing
 */
public interface CertificateService extends IService<StudioCertificate> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole ====================

    /**
     * 新增证书
     *
     * 五个校验点各自对应一种「不拦住就会出事」：标题为空会让官网出现无名证书；
     * 级别或类型不在枚举内会让筛选静默漏数据；获奖日期晚于今天一定是填错了；
     * 图片为空则直接撞 DDL 的 NOT NULL 约束，用户只会看到 B0001。
     *
     * 坑：级别与类型必须分两次校验、分两列存储。只校验一边，另一边的脏值会静默入库，
     * 表现为「按类型筛选时这条记录时有时无」。
     *
     * @param certificateAddRequest 新增请求，不允许为 null；五项必填
     * @return 新证书 ID（19 位 Long，接口层序列化成字符串）
     * @throws BusinessException 参数不合法时抛出（A0401）
     */
    long addCertificate(CertificateAddRequest certificateAddRequest);

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * 部分更新依赖 MyBatis-Plus 的 updateById 默认跳过 null 字段，
     * 因此绝不能给未传的字段填默认值，否则「不修改」会悄悄变成「改成默认值」。
     * 传空串与不传必须区别对待：不传是不改，传空串是想清空，而 NOT NULL 列必须拦成 A0401。
     *
     * 坑：更新字段由服务端写死成白名单，不能把 DTO 直接转实体整体 updateById——
     * 那样前端能顺手改 deletedAt 与审计字段。
     *
     * @param certificateUpdateRequest 更新请求，不允许为 null；id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功；证书不存在时抛异常而不是返回 false
     * @throws BusinessException 证书不存在（A0402）或参数非法（A0401）
     */
    boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest);

    /**
     * 删除证书（逻辑删除主表 + 物理清理成员-证书关联）
     *
     * 两次写必须在同一事务内，且先清关联再删主表——反过来一旦清理失败，
     * 就会留下指向已删除证书的悬空关联，且不会有任何报错。
     *
     * @param id 证书 ID，必须大于 0
     * @return true 表示删除成功；只代表 UPDATE 命中了行，数据并没有从库里消失
     * @throws BusinessException 证书不存在时抛出（A0402）
     */
    boolean deleteCertificate(long id);

    /**
     * 按 id 查证书详情（返回管理端 VO）
     *
     * 不存在时抛 A0402 而不是返回 null：返回 null 会让前端收到「code=0 但 data 为空」，
     * 分不清是没有数据还是接口出错。
     *
     * @param id 证书 ID，必须大于 0
     * @return 证书信息（全字段）；不存在时抛 A0402，不返回 null
     */
    CertificateVO getCertificateById(long id);

    /**
     * 管理端分页查询
     *
     * 分页与排序参数的非法值由实现侧兜底纠正，调用方无需预处理。
     *
     * 坑：排序字段必须走白名单。ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * @param certificateQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest);

    // ==================== C 端：匿名可访问 ====================

    /**
     * C 端证书列表（官网「荣誉墙」，匿名可访问）
     *
     * 返回脱敏 VO，靠 CertificateFrontVO 结构上没有置顶权重与审计字段实现，
     * 而不是拷完再逐个置空。实现侧固定排序并强制条数上限：这是公开接口，游客流量不可控。
     *
     * 坑：排序参数在 C 端一律忽略。允许请求方指定排序，等于允许任何人把某条证书顶到官网第一位。
     *
     * @param certificateQueryRequest 查询条件，允许为 null
     * @return 脱敏后的证书列表，最多 200 条；无数据时返回空列表而不是 null
     */
    List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest);

    // ==================== VO 转换 ====================

    /**
     * 实体转管理端 VO（字段齐全，含置顶权重与审计时间）
     *
     * 坑：BeanUtils 自动拷贝对类型不一致的属性会静默跳过、不报错，
     * 所以实体与 VO 的字段名与类型必须严格对应（例如 awardDate 两边都必须是 LocalDate）。
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateVO getCertificateVO(StudioCertificate certificate);

    /**
     * 实体转 C 端展示 VO（只保留官网展示字段）
     *
     * 脱敏不是一段逻辑，而是 VO 的结构：CertificateFrontVO 里没有 sortOrder 与审计字段，
     * 拷贝时自然被丢弃。若给它补上 sortOrder，脱敏会在零代码改动的情况下失效。
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate);
}
