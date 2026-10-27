package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录用户视图对象（VO）
 *
 * 用 VO 而不直接返回 SysUser：一是脱敏，实体里有 userPassword（BCrypt 哈希串），
 * 直接返回等于把密码送到前端，哈希串一旦泄露就可离线爆破，VO 里根本没有这个字段；
 * 二是解耦，接口契约与数据库表结构分离，加字段、改列名不会直接影响前端。
 *
 * 注意：VO 只用于往外返回，不要拿它接收前端参数；接收参数请用 DTO。
 *
 * @author shaoshing
 */
@Data
public class LoginUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID（雪花算法，19 位）
     *
     * 显式声明 JsonSerialize（注意导入的是 tools.jackson，即 Jackson 3 的注解）：
     * 19 位 Long 超出 JS 的安全整数范围，以数字返回会被前端截断；全局 JsonConfig 已统一转字符串，
     * 这里再标一次是字段级兜底。
     *
     * 坑：不能导入 com.fasterxml.jackson.databind.annotation.JsonSerialize（Jackson 2 的注解），
     * 在 Boot 4 的 Jackson 3 下会被静默忽略，等于没写。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户账号 */
    private String userAccount;

    /** 用户昵称 */
    private String userName;

    /** 用户头像 */
    private String userAvatar;

    /**
     * 用户角色（user / member / admin）
     * 与 @SaCheckRole 的比较值完全一致，取值见 UserRoleEnum；
     * 前端按角色渲染菜单时请用这里的原始值，不要自己写死「admin」之类的大小写变体
     */
    private String userRole;

    /**
     * 用户状态（0-正常，1-封禁）
     * 虽然封禁用户无法登录，但前端拿到状态位可以给出更准确的提示
     */
    private Integer userStatus;

    /** AI 查询次数 */
    private Integer aiQueryCount;

    /**
     * 登录凭证 Token：只在登录接口返回，后续请求由前端放在请求头 satoken 上带回
     * 获取当前用户信息等接口不会再返回 token（前端已有，重复下发只会增加泄露面）
     */
    private String token;

    /**
     * 创建时间
     * 由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss，前端无需再处理 ISO 的 T
     */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
