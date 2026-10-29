package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.log.OperationLogQueryRequest;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.vo.log.OperationActionOptionVO;
import com.bhu.runshistudioweb.model.vo.log.OperationLogVO;

import java.util.List;

/**
 * 管理端操作日志服务（审计留痕）
 *
 * 接口只有「写一条 / 写一批 / 查一页 / 取字典」四件事，刻意不做通用化：
 * 审计的价值在于「埋点位置准确」，而不在于「自动拦截一切」——切面会把
 * 「实际影响了哪几行、驳回理由是什么、改了哪些字段」这类信息丢掉，留下的日志看着齐全却查不出事故。
 *
 * 写成接口而不是直接注入实现，是为了让埋点方只依赖契约；实现内部吞异常、截断长文本、
 * 解析 IP 等细节全部收在一处。
 *
 * @author shaoshing
 */
public interface OperationLogService {

    /**
     * 记录一条管理员操作日志
     *
     * 必须在业务写操作之后、同一事务内调用：业务回滚时日志一起消失，
     * 不会留下「操作成功」的假痕迹。本方法内部吞掉所有异常——
     * 日志表故障绝不能把审核、删帖这类主业务拖垮。
     *
     * @param action 动作码，取值见 OperationTypeEnum；为 null 时直接跳过（埋点漏传不该炸业务）
     * @param targetId 对象 ID，可为 null（章节 / 教材 / 批量导入没有单一对象 ID）
     * @param targetTitle 对象标题快照，可为 null；超过列宽会被截断
     * @param detail 处置细节，可为 null；**调用方不得传入口令、token 或正文**
     */
    void record(OperationTypeEnum action, Long targetId, String targetTitle, String detail);

    /**
     * 同一次批量处置落多行（批量审核 N 篇即 N 行）
     *
     * 为什么不是一行：事故排查的入口通常是「这篇帖子被谁驳回了」，
     * 只有按单条内容拆行，按 target_id 反查才有结果；N 行用一次 saveBatch 落库，不是 N 次往返。
     *
     * @param action 动作码
     * @param targets 目标列表（ID + 标题快照），空列表时什么都不做
     * @param detail 这批处置共用的细节（如驳回理由）
     */
    void recordBatch(OperationTypeEnum action, List<LogTarget> targets, String detail);

    /**
     * 管理端分页查询（按时间倒序）
     *
     * 时间范围、动作、操作人关键词、对象类型全部「传了才生效」；
     * 动作码或对象类型非法时抛参数错误，不静默忽略——忽略会让用户以为筛选生效了。
     *
     * @param request 查询条件，允许为 null（等价于「第一页、默认条数」）
     * @return 分页结果；无数据时返回空记录分页，不会返回 null
     */
    Page<OperationLogVO> listByPage(OperationLogQueryRequest request);

    /**
     * 动作字典（供前端下拉与标签文案）
     *
     * 与后端校验同源：前端不再维护一份「动作码 → 中文」映射，
     * 否则新增动作后会出现「有记录但筛不出来」的静默问题。
     *
     * @return 全部动作选项，顺序与枚举声明一致
     */
    List<OperationActionOptionVO> actionOptions();

    /**
     * 日志目标：ID 可空，标题为写入时的快照
     *
     * 用 record 是因为它纯粹是数据载体：构造后不再变化，且天然不可变，
     * 可以安全地在批量处置里逐条传递。
     *
     * @param id 对象 ID，可为 null
     * @param title 对象标题快照，可为 null
     */
    record LogTarget(Long id, String title) {
    }
}
