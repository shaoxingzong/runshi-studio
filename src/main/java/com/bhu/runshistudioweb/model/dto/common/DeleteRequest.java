package com.bhu.runshistudioweb.model.dto.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用删除请求（按主键删除）
 *
 * author: shaoshing
 *
 * <p>为什么用「POST + 请求体」而不是「DELETE + 路径参数」：
 * <ul>
 *     <li>部分网关、代理、老版本 HTTP 客户端对带请求体的 DELETE 支持不好，
 *     而删除接口的语义又必须明确（不能退化成 GET，否则会被浏览器/爬虫预取误删）；</li>
 *     <li>用请求体传参，未来要扩展「批量删除」只需把 id 换成 idList，URL 契约不用变。</li>
 * </ul>
 *
 * <p><b>位置说明</b>：本类原放在 {@code dto/user} 下，注释里写明「等成员、证书、项目模块也要用时
 * 再整体挪到 {@code dto/common}」。 的成员删除接口成为第二个使用方，故按约定搬家——
 * 「用户模块的请求类被成员模块复用」会让依赖方向变得可疑，而删除请求本身与业务实体无关，
 * 放在 common 下语义才准确。
 *
 * <p>注意：{@code @Positive} 只是廉价的格式兜底，真正的权限与存在性校验在 Service 里。
 */
@Data
public class DeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待删除记录的主键 id（必填）
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;
}