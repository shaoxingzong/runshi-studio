package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.report.ReportHandleRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportQueryRequest;
import com.bhu.runshistudioweb.model.vo.report.ReportVO;
import com.bhu.runshistudioweb.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报处置接口（管理端）
 *
 * 与用户端提交分开成两个 Controller：鉴权要求不同（管理员 vs 登录用户），
 * 放在一起最容易在某次改动里把 {@code @SaCheckRole} 挪到类上，连带放开了提交接口。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/report")
@Tag(name = "举报管理", description = "管理员查看与处置内容举报")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;

    /**
     * 举报分页（默认只看待处理，时间倒序）
     *
     * @param request status / targetType / 分页；status 不传按待处理处理
     * @return 分页结果（含目标标题与举报人昵称）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】举报分页", description = "默认只看待处理；可按状态与对象类型筛选")
    public BaseResponse<Page<ReportVO>> listByPage(@Valid ReportQueryRequest request) {
        // GET 参数可以一个都不传，Spring 会绑成默认值对象，但显式判空更稳
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(reportService.listByPage(request));
    }

    /**
     * 处置举报
     *
     * resolve=撤下（转已驳回）/ delete=删除 / dismiss=举报不成立；
     * 处置结果会通知举报人，并写入操作日志。
     *
     * @param request 举报 id / 动作 / 处置说明
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/handle")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】处置举报", description = "resolve 撤下内容 / delete 删除内容 / dismiss 举报不成立")
    public BaseResponse<Boolean> handle(@RequestBody @Valid ReportHandleRequest request) {
        // 空 body 时 @Valid 不触发，先挡住避免 Service 拆箱 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        reportService.handle(request);
        return ResultUtils.success(true);
    }
}
