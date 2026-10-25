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
 * 与 UserService、StudioMemberService 保持同一套约定：
 * - 方法分「管理端（需 admin）」与「C 端（匿名）」两组，职责不混用；
 * - 参数不合法 / 目标不存在时抛 BusinessException，不返回错误码、不返回 null，
 * 由全局异常处理器统一转成标准响应
 * （例外：两个 VO 转换方法是纯转换，入参为 null 时返回 null）；
 * - 不暴露 QueryWrapper：查询条件的拼装是实现细节，
 * 放到接口上等于允许调用方自己拼 SQL，会把「排序字段改成注入字符串」这类风险扩散出去。
 *
 * 本模块的两个维度必须先分清：级别（国家级 / 省级 / 市级）与
 * 类型（竞赛 / 软著 / 专利 / 论文）是正交的两件事。
 * 合并成一个「国家级竞赛」字段后，想单独查「所有国家级证书」只能做字符串模糊匹配，
 * 既走不了组合索引，又会误匹配；拆成两个字段才能各自筛选、各自统计、自由组合。
 *
 * @author shaoshing
 */
public interface CertificateService extends IService<StudioCertificate> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole ====================

    /**
     * 新增证书
     *
     * <p>为什么这样设计：四个校验点各自对应一种「不拦住就会出事」的情况。
     * 标题为空会让官网出现没有名字的证书；级别或类型不在枚举内会让筛选静默漏数据；
     * 获奖日期晚于今天一定是填错了；图片为空则直接撞 DDL 的 NOT NULL 约束，
     * 用户只会看到 B0001「系统错误」，既不知道哪个字段错了，也不知道该补什么。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体（@Valid 对整体为 null 的请求体不生效）；</li>
     *     <li>② 依次校验标题、级别、类型、获奖日期、图片，
     *         全部排在写库之前，任何一项不合法都不该产生一次数据库往返；</li>
     *     <li>③ 落库并返回新 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：级别与类型必须分两次校验、分两列存储。
     * 若图省事只校验其中一边，另一边的脏值会静默入库，
     * 表现为「按类型筛选时这条记录时有时无」。
     *
     * @param certificateAddRequest 新增请求，不允许为 null；五项必填
     * @return 新证书 ID（19 位 Long，接口层序列化成字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法时抛出（A0401）
     */
    long addCertificate(CertificateAddRequest certificateAddRequest);

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * <p>为什么这样设计：部分更新语义让前端「只改名称」时不必回传整行数据。
     * 它成立的前提是 MyBatis-Plus 的 updateById 默认跳过 null 字段——
     * 所以这里绝不能给未传的字段填默认值，否则「不修改」会悄悄变成「改成默认值」。
     * 同时，传了空串与不传必须区别对待：不传是不改，传空串是想清空，
     * 而 NOT NULL 列不允许清空，必须拦成 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先查一次确认证书存在，否则 updateById 失败只返回 false，
     *         前端分不清「不存在」与「没改成」；</li>
     *     <li>② 只对「传了的字段」校验，与新增共用同一套枚举与日期规则；</li>
     *     <li>③ 只把白名单字段放进更新对象，再 updateById。</li>
     * </ol>
     *
     * <p>容易踩的坑：更新字段必须由服务端写死成白名单，
     * 不能把 DTO 直接转成实体整体 updateById——那样前端能顺手改
     * deletedAt 与审计字段，等于把内部数据的所有权交了出去。
     *
     * @param certificateUpdateRequest 更新请求，不允许为 null；id 必填，
     *                                 其余字段为 null 表示保持原值
     * @return true 表示更新成功；证书不存在时抛异常而不是返回 false
     * @throws com.bhu.runshistudioweb.exception.BusinessException 证书不存在（A0402）
     * 或参数非法（A0401）时抛出
     */
    boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest);

    /**
     * 删除证书（逻辑删除，并清理成员-证书关联）
     *
     * <p>为什么这样设计：删除是「主表写 + 关联表写」两次操作，必须在同一事务内。
     * 顺序是先清关联、再删主表——反过来一旦清理失败，
     * 就会留下指向已删除证书的悬空关联，而且不会有任何报错。
     * 主表用逻辑删除（deleted_at 写时间戳，行仍在库里）保留可追溯性；
     * 关联表用物理删除，因为「谁持有了一张已不存在的证书」没有保留价值。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并查存在性，不存在给 A0402；</li>
     *     <li>② 清关联表；</li>
     *     <li>③ 逻辑删除主表。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回 true 只代表 UPDATE 命中了行，
     * 数据并没有从库里消失。想彻底清理要走归档脚本，
     * 不要在业务代码里改成物理删除——那会让「误删可恢复」这个前提消失。
     *
     * @param id 证书 ID，必须大于 0
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 证书不存在时抛出（A0402）
     */
    boolean deleteCertificate(long id);

    /**
     * 按 id 查证书详情（返回管理端 VO）
     *
     * <p>为什么这样设计：不存在时抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code=0 但 data 为空」，分不清是没有数据还是接口出错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 查实体（逻辑删除的自动过滤）；</li>
     *     <li>③ 转管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把这个方法复用到列表上。
     * 它返回的是全字段 VO，单条无所谓，一页十条就会明显放大响应体。
     *
     * @param id 证书 ID，必须大于 0
     * @return 证书信息；不存在时抛 A0402，不返回 null
     */
    CertificateVO getCertificateById(long id);

    /**
     * 管理端分页查询
     *
     * <p>为什么这样设计：分页与排序参数的非法值由实现侧兜底纠正，调用方无需预处理。
     * 页码传错没必要让整个查询失败，收敛到合理区间继续查体验更好；
     * 但排序字段必须走白名单——ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 纠正分页参数（页码下限 1、pageSize 上限 50）；</li>
     *     <li>② 拼筛选条件与应用白名单排序；</li>
     *     <li>③ 分页查询并转 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要关掉 searchCount。关掉能省一次 COUNT，
     * 但前端分页器拿不到 total 就画不出总页数。
     *
     * @param certificateQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest);

    // ==================== C 端：匿名可访问 ====================

    /**
     * C 端证书列表（官网「荣誉墙」，匿名可访问）
     *
     * <p>为什么这样设计：返回的是脱敏后的 C 端 VO，不含置顶权重与审计字段。
     * 脱敏靠「目标 VO 没有这些字段」实现，而不是「拷完再逐个置空」——
     * 结构上不存在的东西不会被拷出去，以后实体新增敏感字段也不会自动泄露。
     * 实现侧还固定排序、并强制返回条数上限：这是公开接口，游客流量不可控。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拼筛选条件（级别、类型、标题）；</li>
     *     <li>② 固定排序（置顶权重 → 获奖日期 → id 倒序）；</li>
     *     <li>③ 取一页（最多 200 条）并转成脱敏 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序参数在 C 端一律忽略。允许请求方指定排序，
     * 等于允许任何人把某条证书顶到官网第一位。
     *
     * @param certificateQueryRequest 查询条件，允许为 null
     * @return 脱敏后的证书列表，最多 200 条；无数据时返回空列表而不是 null
     */
    List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest);

    // ==================== VO 转换 ====================

    /**
     * 实体转管理端 VO（字段齐全，含置顶权重与审计时间）
     *
     * <p>为什么这样设计：用 BeanUtils 自动拷贝避免漏字段。
     * 代价是类型不一致的属性会被静默跳过，所以实体与 VO 的字段名、
     * 类型必须严格对应（例如 awardDate 两边都必须是 LocalDate）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null；</li>
     *     <li>② 按属性名自动拷贝。</li>
     * </ol>
     *
     * <p>容易踩的坑：类型写错不会报错，只会让那个字段永远是 null。
     * 这类问题只能在联调时靠「字段怎么没出来」发现，排查成本很高。
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateVO getCertificateVO(StudioCertificate certificate);

    /**
     * 实体转 C 端展示 VO（只保留官网展示字段）
     *
     * <p>为什么这样设计：脱敏不是一段逻辑，而是 VO 的结构——
     * CertificateFrontVO 里没有 sortOrder 与审计字段，拷贝时自然被丢弃。
     * 这比「拷完再手动置空」可靠，也不需要在每次实体加字段时回来维护。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 入参为 null 时返回 null；</li>
     *     <li>② 自动拷贝到脱敏 VO，多出来的字段被丢弃。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「什么都没做」就是正确的脱敏。
     * 若给 CertificateFrontVO 补上 sortOrder，脱敏会在零代码改动的情况下失效。
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate);
}
