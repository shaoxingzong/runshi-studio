package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAddRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAssignRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemQueryRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemStatusRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemUpdateRequest;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.vo.board.BoardItemOverviewVO;
import com.bhu.runshistudioweb.model.vo.board.BoardItemVO;
import com.bhu.runshistudioweb.service.BoardItemService;
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
 * 工作室内部事项看板接口
 *
 * <p>粗粒度权限在注解，细粒度权限（成员只能改指派给自己的事项）在 Service。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/board/item")
@Tag(name = "内部事项看板", description = "仅在队成员可见的 Kanban：三列查询、管理员维护、状态流转")
@RequiredArgsConstructor
public class BoardItemController {

    private final BoardItemService boardItemService;

    /**
     * 成员看板：一次返回三列 + 统计（仅登录 + 在队成员）
     */
    @GetMapping("/board")
    @SaCheckLogin
    @Operation(summary = "我的事项看板", description = "待办 / 进行中 / 已完成三列，含逾期与我负责的统计")
    public BaseResponse<BoardItemOverviewVO> board() {
        return ResultUtils.success(boardItemService.board());
    }

    /** 管理端分页列表（支持标题、状态、负责人、仅看逾期筛选） */
    @GetMapping("/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】事项分页列表", description = "每页最多 50 条；可按负责人筛选未交的人")
    public BaseResponse<Page<BoardItemVO>> page(@Valid BoardItemQueryRequest request) {
        return ResultUtils.success(boardItemService.page(request));
    }

    /** 新增事项（仅管理员） */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增事项", description = "可指定负责人与截止日期；requestId 为一次性幂等令牌")
    public BaseResponse<Long> add(@RequestBody @Valid BoardItemAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(boardItemService.add(request));
    }

    /** 编辑事项（仅管理员）；null 字段表示不修改 */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】编辑事项", description = "负责人改派走 /assign，状态流转走 /status")
    public BaseResponse<Boolean> update(@RequestBody @Valid BoardItemUpdateRequest request) {
        boardItemService.update(request);
        return ResultUtils.success(true);
    }

    /** 下线事项（仅管理员，逻辑删除） */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】下线事项", description = "逻辑删除，可从操作日志追溯")
    public BaseResponse<Boolean> delete(@RequestBody @Valid DeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boardItemService.delete(request.getId());
        return ResultUtils.success(true);
    }

    /** 指派 / 改派 / 清空负责人（仅管理员） */
    @PostMapping("/assign")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】指派负责人", description = "负责人须为在队成员；传 null 表示清空指派")
    public BaseResponse<Boolean> assign(@RequestBody @Valid BoardItemAssignRequest request) {
        boardItemService.assign(request);
        return ResultUtils.success(true);
    }

    /**
     * 流转状态：管理员可改任意事项；成员只能改指派给自己的那条。
     * 只做登录拦截，细粒度归属校验在 Service（管理员可以在门户与后台共用这个接口）。
     */
    @PostMapping("/status")
    @SaCheckLogin
    @Operation(summary = "流转事项状态", description = "成员仅能推进指派给自己的事项；expectedStatus 用于乐观锁")
    public BaseResponse<Boolean> changeStatus(@RequestBody @Valid BoardItemStatusRequest request) {
        boardItemService.changeStatus(request);
        return ResultUtils.success(true);
    }
}
