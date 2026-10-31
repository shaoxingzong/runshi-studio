package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.report.ReportAddRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportHandleRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportQueryRequest;
import com.bhu.runshistudioweb.model.vo.report.ReportVO;

/**
 * 内容举报服务
 *
 * @author shaoshing
 */
public interface ReportService {

    /**
     * 提交一条举报（登录用户）
     *
     * @param request 举报请求（targetType / targetId / reason）
     * @return 新建举报的 ID
     */
    long submit(ReportAddRequest request);

    /**
     * 管理端分页查询（默认只看待处理，按时间倒序）
     *
     * @param request 状态 / 目标类型 / 分页
     * @return 分页结果（含目标标题与举报人昵称）
     */
    Page<ReportVO> listByPage(ReportQueryRequest request);

    /**
     * 处置一条举报
     *
     * @param request 举报 id / 动作 / 处置说明
     */
    void handle(ReportHandleRequest request);
}
