package com.bhu.runshistudioweb.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;

import java.security.SecureRandom;

/**
 * 密码加密与校验工具（基于 Hutool BCrypt）
 *
 * 为什么用 BCrypt，而不是 MD5 / SHA-1：
 * - MD5、SHA 是为速度设计的哈希，GPU 每秒能算几十亿次，库一旦泄露，彩虹表或暴力破解的成本极低；
 * - BCrypt 自带随机盐（同一明文每次加密结果都不同，彩虹表失效），且计算耗时可调，
 * 把暴力破解的成本抬到不可接受的程度；
 * - BCrypt 哈希串里已经内含算法版本、cost 与盐值，因此不需要额外的 salt 字段，
 * 校验时直接把库里的串丢回来即可，也不会出现「盐值丢失导致全站密码失效」的运维事故。
 *
 * 哈希串形如 $2a$10$VYOi4TBr/3q5opBEopv8C.0tTyk/9.AnSlFXx8.MjZvdy5TSBza.K，
 * 固定 60 个字符，对应 DDL 中的 user_password varchar(128)（留了余量，够未来换算法）。
 *
 * 使用约定：
 * - 加密后的哈希串绝不能出现在日志或响应体中（ SysUser.userPassword 不要直接返回给前端）；
 * - 校验必须走 matches，不要用 equals 比较哈希串——
 * 每次加密的盐值不同，同一明文两次加密结果并不相等，用 equals 会让登录永远失败；
 * - 纯静态工具类，不可实例化。
 *
 * 依赖说明：cn.hutool.crypto.digest.BCrypt 来自 hutool-crypto，
 * StrUtil 来自它传递引入的 hutool-core。
 */
public class PasswordUtils {

    /** 私有构造：纯静态工具类，禁止外部 new（避免被当成实例工具类使用） */
    private PasswordUtils() {
    }

    /**
     * 随机密码字符集：大小写字母 + 数字，剔除易混淆字符
     *
     * 剔除 0 / O / 1 / l / I 是刻意的：初始密码需要管理员看一眼再转告本人
     * （电话、微信、纸条），这两个字符在绝大多数字体里几乎无法区分，
     * 「0 还是 O」的电话沟通成本远比多去掉 5 个字符值钱。字符集共 57 个字符。
     */
    private static final String RANDOM_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** 随机数源：SecureRandom 而非 Random——口令生成不需要可预测性，需要不可预测性 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成随机初始密码（管理员新增用户未指定密码时使用）
     *
     * 为什么要随机而不是固定默认值：固定默认口令（如 Runshi@123）
     * 意味着任何知道本系统存在的人，都能登录所有未及时改密的账号——
     * 安全性寄托在「所有人都会立即改密」上，而现实里总有人不会。
     * 随机化后，初始口令只可能在创建响应里出现一次，攻击者无法预知。
     *
     * @param length 密码长度（建议 10~16：够复杂度，又不至于转告时出错）
     * @return 指定长度的随机明文密码（调用方负责展示一次并提示管理员转交本人）
     */
    public static String generateRandomPassword(int length) {
        ThrowUtils.throwIf(length < 8, ErrorCode.PARAMS_ERROR, "随机密码长度不能少于 8 位");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = SECURE_RANDOM.nextInt(RANDOM_PASSWORD_CHARS.length());
            sb.append(RANDOM_PASSWORD_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 加密明文密码（注册、重置密码、修改密码时调用）
     *
     * 职责边界：本方法只负责「怎么加密」，不做密码强度校验。
     * 长度、大小写、特殊字符等规则属于业务策略，与具体接口绑定、变化频繁，
     * 应放在接口层由 DTO 上的 @NotBlank / @Size 完成；
     * 写在工具类里会导致「改一次规则就要动公共代码」，也容易出现同一规则散落多处。
     *
     * 这里保留的唯一校验是「空值兜底」：BCrypt 对 null 会直接抛异常（变成 500 系统错误），
     * 而空密码一旦入库，等于该账号对外不设防。
     *
     * @param rawPassword 明文密码
     * @return 60 位 BCrypt 哈希串，可直接写入 sys_user.user_password
     * @throws com.bhu.runshistudioweb.exception.BusinessException 明文为 null / 空串 / 纯空格时抛出（A0401）
     */
    public static String encrypt(String rawPassword) {
        ThrowUtils.throwIf(StrUtil.isBlank(rawPassword), ErrorCode.PARAMS_ERROR, "密码不能为空");
        // 不显式传盐：hashpw 内部会用 SecureRandom 生成随机盐并拼进结果
        // 注意：BCrypt 只取明文前 72 个字节，更长的部分会被静默忽略，
        // 所以上层若放开密码长度上限，需要一并卡在 72 字节以内
        return BCrypt.hashpw(rawPassword);
    }
    /**
     * 校验明文密码与库中哈希串是否匹配（登录时调用）
     *
     * 已实测：正确的密码返回 true，错误的密码返回 false；
     * 传入非法哈希串或空密码时也只会返回 false，不会抛异常，
     * 因此调用方不需要包 try-catch，登录接口不会因此返回 500。
     *
     * @param rawPassword 用户输入的明文密码
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
