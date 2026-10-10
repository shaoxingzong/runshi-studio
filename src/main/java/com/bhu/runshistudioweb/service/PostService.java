package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.vo.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.PostVO;

/**
 * 帖子服务接口
 *
 * author: shaoshing
 *
 * <p><b>准入规则</b>：只有<b>在队的工作室成员</b>能发帖（与考勤同一套判定，
 * 认 {@code studio_member} 表而不认角色标签）；浏览帖子对<b>游客开放</b>。
 *
 * <p><b>先审后发</b>：发布后一律先进「待审」，仅作者本人在「我的发帖」里可见；
 * 通过后才会出现在公开列表。因此「发出去却搜不到」是预期行为，
 * 作者要靠 {@link #listMyByPage} 看状态。
 *
 * <p><b>分页参数的兜底纠正放在实现里</b>（页码 < 1 视为 1、pageSize 收敛到上限），
 * 不抛异常：传错页码没必要让整个查询失败（与项目其它模块一致）。
 *
 * <p>约定：参数不合法 / 业务不允许时抛 {@code BusinessException}，不返回错误码、不返回 null。
 */
public interface PostService extends IService<StudioPost> {

    /**
     * 成员发布帖子
     *
     * <p>三个校验顺序不能乱：
     * <ol>
     *     <li>登录态 + 在队成员；</li>
     *     <li>正文长度；</li>
     *     <li>正文图片必须是站内路径。</li>
     * </ol>
     * 通过后落库为「待审」，随后由后台异步交给 AI 初判（见 {@code ContentAuditManager}）；
     * 摘要与封面为空时由服务端兜底生成。
     *
     * @param request 发布请求（标题、正文必填）
     * @return 新帖子 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、非成员、
     *         正文过长、含外链图片、入库失败时抛出
     */
    long addPost(PostAddRequest request);

    /**
     * 公开列表：仅已通过，置顶优先 + 时间倒序（游客可调用）
     *
     * @param current  页码（< 1 时按 1 处理）
     * @param pageSize 每页条数（收敛到上限）
     * @return 分页结果
     */
    Page<PostFrontVO> listApprovedByPage(long current, long pageSize);

    /**
     * 公开详情：仅已通过（游客可调用），并原子自增浏览量
     *
     * @param id 帖子 ID
     * @return 详情（含 Markdown 正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    PostFrontDetailVO getApprovedDetail(long id);

    /**
     * 我的发帖：作者看自己的，含待审与已驳回（驳回时带理由）
     *
     * @param current  页码
     * @param pageSize 每页条数
     * @return 分页结果
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
    Page<PostVO> listMyByPage(long current, long pageSize);

    /**
     * 待审队列（管理员）：仅「待审」，按提交时间<b>先进先出</b>
     *
     * <p>先进先出不只是公平，更是为了避免老内容被积压——
     * 先审后发的模式下，作者提交后看不到自己的内容，
     * 若队列按其它顺序排，最早提交的可能永远沉底。
     *
     * @param current  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    Page<PostVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * <p>三条约束：
     * <ul>
     *     <li>驳回<b>必须</b>填理由——它要给作者看，没理由等于让作者猜；</li>
     *     <li>只对待审内容生效——已通过/已驳回的不应被重复处理（幂等保护）；</li>
     *     <li>一次 SQL 更新整批，不逐条 update。</li>
     * </ul>
     *
     * @param request 审核请求（ids、action、驳回理由）
     * @return 实际处理的条数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 动作非法、驳回缺理由时抛出
     */
    int audit(AuditRequest request);
}
