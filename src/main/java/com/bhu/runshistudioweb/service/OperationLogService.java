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
 * @author shaoshing
 */
public interface OperationLogService {

    /**
     * 记录一条管理员操作日志
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
     * @param action 动作码
     * @param targets 目标列表（ID + 标题快照），空列表时什么都不做
     * @param detail 这批处置共用的细节（如驳回理由）
     */
    void recordBatch(OperationTypeEnum action, List<LogTarget> targets, String detail);

    /**
     * 管理端分页查询（按时间倒序）
     *
     * @param request 查询条件，允许为 null（等价于「第一页、默认条数」）
     * @return 分页结果；无数据时返回空记录分页，不会返回 null
     */
    Page<OperationLogVO> listByPage(OperationLogQueryRequest request);

    /**
     * 动作字典（供前端下拉与标签文案）
     *
     * @return 全部动作选项，顺序与枚举声明一致
     */
    List<OperationActionOptionVO> actionOptions();

    /**
     * 日志目标：ID 可空，标题为写入时的快照
     *
     * @param id 对象 ID，可为 null
     * @param title 对象标题快照，可为 null
     */
    record LogTarget(Long id, String title) {
    }
}
