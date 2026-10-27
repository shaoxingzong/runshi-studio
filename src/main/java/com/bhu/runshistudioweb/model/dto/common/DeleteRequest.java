package com.bhu.runshistudioweb.model.dto.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用删除请求（按主键删除）
 *
 * 用「POST + 请求体」而不是「DELETE + 路径参数」：部分网关、代理与 HTTP 客户端对带体的
 * DELETE 支持不好，而删除语义又不能退化成 GET（会被浏览器、爬虫预取误删）。
 *
 * 与业务实体无关，成员、证书、项目等模块共用；@Positive 只是廉价的格式兜底，
 * 真正的权限与存在性校验在 Service 里。
 *
 * @author shaoshing
 */
@Data
public class DeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待删除记录的主键 id（必填） */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;
}