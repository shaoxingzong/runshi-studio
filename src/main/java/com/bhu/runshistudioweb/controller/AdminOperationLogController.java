package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.log.OperationLogQueryRequest;
import com.bhu.runshistudioweb.model.vo.log.OperationActionOptionVO;
import com.bhu.runshistudioweb.model.vo.log.OperationLogVO;
import com.bhu.runshistudioweb.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 操作日志查询接口（管理端）
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/log")
@Tag(name = "操作日志", description = "管理员操作留痕查询：按时间、动作、操作人、对象类型筛选")
@RequiredArgsConstructor
public class AdminOperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 操作日志分页（时间倒序）
     *
     * @param request 五个筛选条件全部选填；分页缺省 1 / 10，上限在 Service 收敛
     * @return 分页结果（含动作与对象类型的中文标签）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】操作日志分页", description = "按时间范围/动作/操作人/对象类型筛选，倒序")
    public BaseResponse<Page<OperationLogVO>> listByPage(@Valid OperationLogQueryRequest request) {
        // GET 绑定允许整体为 null（一个参数都不传），先挡掉再谈条件
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 条件拼装与分页收敛都在 Service：Controller 只负责转发
        return ResultUtils.success(operationLogService.listByPage(request));
    }

    /**
     * 动作字典
     *
     * @return 动作选项列表（code / label / targetType / targetTypeLabel）
     */
    @GetMapping("/actions")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】操作动作字典", description = "前端下拉与标签文案，与后端校验同源")
    public BaseResponse<List<OperationActionOptionVO>> actionOptions() {
        return ResultUtils.success(operationLogService.actionOptions());
    }
}
