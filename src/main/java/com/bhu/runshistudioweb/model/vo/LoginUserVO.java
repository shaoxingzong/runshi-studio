package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录用户视图对象（VO）
 *
 * author: shaoshing
 *
 * <p>为什么要有 VO，不直接把 {@code SysUser} 返回给前端：
 * <ul>
 *     <li><b>脱敏</b>：实体里有 {@code userPassword}（BCrypt 哈希串），
 *     直接返回等于把密码送到前端，哈希串一旦泄露就可离线爆破；VO 里根本没有这个字段，
 *     从结构上杜绝了「不小心返回密码」；</li>
 *     <li><b>解耦</b>：接口契约与数据库表结构分离，加字段、改列名不会直接影响前端。</li>
 * </ul>
 *
 * <p>注意：VO 只用于「往外返回」，不要拿它接收前端参数；接收参数请用 DTO。
 */
@Data
public class LoginUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID（雪花算法，19 位）
     *
     * <p>这里显式声明 {@link JsonSerialize}（注意导入的是 {@code tools.jackson}，
     * 即 Jackson 3 的注解）：
     * <ul>
     *     <li>19 位 Long 超出 JS Number 的安全整数范围（2^53-1），以数字返回会被前端截断（末位变 0）；</li>
     *     <li>全局 {@code JsonConfig} 已把 Long 统一转字符串，这里再标一次是「字段级兜底」，
     *     保证即使哪天全局规则被调整，用户 ID 也不会退化成数字；</li>
     *     <li><b>坑</b>：早先这里写的是 {@code com.fasterxml.jackson.databind.annotation.JsonSerialize}
     *     （Jackson 2 的注解），在 Boot 4 的 Jackson 3 下会被**静默忽略**，等于没写。</li>
     * </ul>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 用户账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户角色（user / member / admin）
     * <p>与 {@code @SaCheckRole} 的比较值完全一致，取值定义见
     * {@link com.bhu.runshistudioweb.model.enums.UserRoleEnum}；
     * 前端按角色渲染菜单时，请用这里的原始值判断，不要自己写死「admin」之类的中文或大小写变体
     */
    private String userRole;

    /**
     * 用户状态（0-正常，1-封禁）
     * <p>虽然封禁用户无法登录，但前端拿到状态位可以给出更准确的提示
     */
    private Integer userStatus;

    /**
     * AI 查询次数
     */
    private Integer aiQueryCount;

    /**
     * 登录凭证 Token：只在登录接口返回，后续请求由前端放在请求头 {@code satoken} 上带回
     * <p>获取当前用户信息等接口不会再返回 token（前端已有，重复下发只会增加泄露面）
     */
    private String token;

    /**
     * 创建时间
     * <p>由 JsonConfig 统一序列化为 {@code yyyy-MM-dd HH:mm:ss}，前端无需再处理 ISO 的 T
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
