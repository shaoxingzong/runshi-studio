package com.bhu.runshistudioweb.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;

/**
 * 密码加密与校验工具（基于 Hutool BCrypt）
 *
 * author: shaoshing
 *
 * <p>为什么用 BCrypt，而不是 MD5 / SHA-1：
 * <ul>
 *     <li>MD5、SHA 是**为速度设计**的哈希，GPU 每秒能算几十亿次，库一旦泄露，彩虹表或暴力破解的成本极低；</li>
 *     <li>BCrypt 自带随机盐（同一明文每次加密结果都不同，彩虹表失效），且计算耗时可调，
 *     把暴力破解的成本抬到不可接受的程度；</li>
 *     <li>BCrypt 哈希串里已经内含算法版本、cost 与盐值，因此**不需要额外的 salt 字段**，
 *     校验时直接把库里的串丢回来即可，也不会出现「盐值丢失导致全站密码失效」的运维事故。</li>
 * </ul>
 *
 * <p>哈希串形如 {@code $2a$10$VYOi4TBr/3q5opBEopv8C.0tTyk/9.AnSlFXx8.MjZvdy5TSBza.K}，
 * 固定 60 个字符，对应 DDL 中的 {@code user_password varchar(128)}（留了余量，够未来换算法）。
 *
 * <p><b>使用约定</b>：
 * <ol>
 *     <li>加密后的哈希串绝不能出现在日志或响应体中（{@code SysUser.userPassword} 不要直接返回给前端）；</li>
 *     <li>校验必须走 {@link #matches(String, String)}，<b>不要用 equals 比较哈希串</b>——
 *     每次加密的盐值不同，同一明文两次加密结果并不相等，用 equals 会让登录永远失败；</li>
 *     <li>纯静态工具类，不可实例化。</li>
 * </ol>
 *
 * <p>依赖说明：{@code cn.hutool.crypto.digest.BCrypt} 来自 hutool-crypto，
 * {@code StrUtil} 来自它传递引入的 hutool-core。
 */
public class PasswordUtils {

    /** 私有构造：纯静态工具类，禁止外部 new（避免被当成实例工具类使用） */
    private PasswordUtils() {
    }

    /**
     * 加密明文密码（注册、重置密码、修改密码时调用）
     *
     * @param rawPassword 明文密码
     * @return 60 位 BCrypt 哈希串，可直接写入 sys_user.user_password
     * @throws com.bhu.runshistudioweb.exception.BusinessException 明文为空时抛出（40000），
     *         防止把空密码写进库——一旦写入，空密码即可登录
     */
    public static String encrypt(String rawPassword) {
        ThrowUtils.throwIf(StrUtil.isBlank(rawPassword), ErrorCode.PARAMS_ERROR, "密码不能为空");
        // 不显式传盐：hashpw 内部会用 SecureRandom 生成随机盐并拼进结果
        return BCrypt.hashpw(rawPassword);
    }

    /**
     * 校验明文密码与库中哈希串是否匹配（登录时调用）
     *
     * <p>已实测：正确的密码返回 true，错误的密码返回 false；
     * 传入非法哈希串（例如历史遗留的 MD5 值）或空密码时也只会返回 false，不会抛异常，
     * 因此调用方不需要包 try-catch，登录接口不会因此返回 500。
     *
     * @param rawPassword       用户输入的明文密码
     * @param encryptedPassword 库中存的 BCrypt 哈希串
     * @return 匹配返回 true，其余情况一律 false
     */
    public static boolean matches(String rawPassword, String encryptedPassword) {
        if (StrUtil.isBlank(rawPassword) || StrUtil.isBlank(encryptedPassword)) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, encryptedPassword);
    }
}
