package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.report.ReportAddRequest;
import com.bhu.runshistudioweb.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端举报接口
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/post")
@Tag(name = "内容举报", description = "登录用户举报已公开的帖子或评论")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 提交举报
     *
     * @param request targetType（post/comment）、targetId（字符串形式的雪花 ID）、reason（≤256）
     * @return 新建举报的 ID
     */
    @PostMapping("/report")
    @Operation(summary = "举报帖子或评论", description = "仅可举报已公开内容；同一内容重复举报会被拒绝")
    public BaseResponse<Long> report(@RequestBody @Valid ReportAddRequest request) {
        // @Valid 不覆盖「请求体整体为 null」，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(reportService.submit(request));
    }
}
