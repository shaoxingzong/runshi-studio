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
 * 只读：写日志由各业务 Service 在处置动作里调用，不开放任何「手工补一条日志」的接口——
 * 能补写的审计记录等于没有审计记录。
 *
 * 路径另起 /admin/log：与 /admin/post、/admin/quiz 同属管理端，默认拒绝的拦截器规则
 * 已经要求登录，本类再挂 {@code @SaCheckRole(ADMIN)} 确保只有管理员能看全站操作轨迹。
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
     * 不加 @Valid 会漏掉关键词长度校验，加了又可能把「时间格式写错」变成 B0001；
     * 这里保留 @Valid（长度校验值得），时间格式由前端用固定格式提交，
     * 格式非法时 Spring 抛的绑定异常由全局异常处理器统一兜底。
     *
     * @param request 五个筛选条件全部选填；分页缺省 1 / 10，上限在 Service 收敛
     * @return 分页结果（含动作与对象类型的中文标签）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】操作日志分页", description = "按时间范围/动作/操作人/对象类型筛选，倒序")
    public BaseResponse<Page<OperationLogVO>> listByPage(@Valid OperationLogQueryRequest request) {
        // ① GET 绑定允许整体为 null（一个参数都不传），先挡掉再谈条件
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 条件拼装与分页收敛都在 Service：Controller 只负责转发
        return ResultUtils.success(operationLogService.listByPage(request));
    }

    /**
     * 动作字典
     *
     * 前端下拉、表格标签与筛选提交共用这一份数据，避免前后端各写一份映射而漂移。
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
