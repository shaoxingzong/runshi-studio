package com.bhu.runshistudioweb.model.dto.traffic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 流量埋点上报请求（前端路由变化时调用，匿名可访问）
 *
 * 为什么不收 userId：用户身份由后端从登录态取（未登录为 NULL）。
 * 前端传什么就信什么，等于开一个「伪造他人访问记录」的口子。
 *
 * 不收完整 URL / query：pagePath 只要路径部分。
 * query 里可能带 token（例如分享链接、邮件营销参数），把它原样存进埋点表
 * 等于把凭证写进了一张匿名可写的表里。
 */
@Data
public class TrafficReportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 页面路径（必填，≤256）：如 /posts、/projects/123
     *
     * 由前端在提交前剥掉 query 与 hash；后端仍会再做一次规范化
     * （见 TrafficServiceImpl#normalizePath）——前端可能被旧版本缓存。
     *
     * 必须以 / 开头：本接口匿名可写，不收紧格式的话
     * 任意字符串都能进库，把「热门页面」聚合搞成一锅粥。
     */
    @NotBlank(message = "页面路径不能为空")
    @Size(max = 256, message = "页面路径过长")
    @Pattern(regexp = "^/.*", message = "页面路径必须以 / 开头")
    private String pagePath;

    /**
     * 访客标识（必填，≤64）：前端 localStorage 里的随机 uuid
     *
     * UV 去重的唯一依据。为空时按 A0401 拒绝——没有它这条记录既不能算 PV 也不能算 UV。
     */
    @NotBlank(message = "访客标识不能为空")
    @Size(max = 64, message = "访客标识过长")
    private String visitorId;

    /** 来源域名（选填，≤256）：外站跳转进来才有值；只收域名，不收完整 URL */
    @Size(max = 256, message = "来源域名过长")
    private String referrerHost;

    /** 设备类型（选填，≤16）：pc / mobile */
    @Size(max = 16, message = "设备类型过长")
    private String deviceType;
}
