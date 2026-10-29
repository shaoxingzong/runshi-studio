package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.report.ReportAddRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportHandleRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportQueryRequest;
import com.bhu.runshistudioweb.model.vo.report.ReportVO;

/**
 * 内容举报服务
 *
 * 举报是「免审内容」的兜底通道：被信任用户的内容自动通过，但读者必须有反馈入口。
 * 三个职责分开且都在同一事务里完成：受理提交（防重复）、管理端分页、处置（撤下/删除/驳回举报）。
 *
 * @author shaoshing
 */
public interface ReportService {

    /**
     * 提交一条举报（登录用户）
     *
     * 只允许举报**已通过**的内容：待审/已驳回内容对外不可见，能举报它等于泄露其存在性。
     * 同一人对同一内容的重复举报由唯一键拦下，转成可读的 A0401。
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
     * resolve：撤下内容（转已驳回，保留可追溯）；delete：直接删除内容；dismiss：举报不成立。
     * 处置与内容变更在同一事务里完成，并通知举报人。已处理过的举报不允许重复处置。
     *
     * @param request 举报 id / 动作 / 处置说明
     */
    void handle(ReportHandleRequest request);
}
