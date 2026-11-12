package com.bhu.runshistudioweb.model.dto.board;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 流转事项状态请求
 *
 * <p>管理员可改任意事项；普通成员只能改「指派给自己」的那条。
 *
 * @author shaoshing
 */
@Data
public class BoardItemStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "事项 ID 不能为空")
    private Long id;

    /** 目标状态：0-待办, 1-进行中, 2-已完成 */
    @NotNull(message = "目标状态不能为空")
    private Integer targetStatus;

    /**
     * 期望的当前状态（乐观锁）：传了就要求库中状态必须等于它，否则报「状态已变更」。
     * 前端拖拽/点按钮时带上打开页面时看到的状态，可避免两个成员同时改同一条互相覆盖。
     */
    private Integer expectedStatus;
}
