package com.bhu.runshistudioweb.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;

import java.security.SecureRandom;
import java.util.HashMap;

/**
 * 密码加密与校验工具（基于 Hutool BCrypt）
 *
 * 用 BCrypt 而不是 MD5 / SHA：后两者为速度设计，GPU 每秒能算几十亿次，库一旦泄露，
 * 彩虹表或暴力破解的成本极低；BCrypt 自带随机盐（同一明文每次结果都不同），
 * 且计算耗时可调。哈希串里已内含算法版本、cost 与盐，因此不需要额外的 salt 字段。
 *
 * 哈希串形如 {@code $2a$10$VYOi4TBr/...}，固定 60 个字符，对应 DDL 的 varchar(128)。
 *
 * 校验必须走 matches，不能用 equals 比较哈希串：每次加密的盐不同，同一明文两次结果并不相等。
 *
 * @author shaoshing
 */
public class PasswordUtils {

    /** 私有构造：纯静态工具类，禁止外部 new（避免被当成实例工具类使用） */
    private PasswordUtils() {
    }

    /**
     * 随机密码字符集：大小写字母 + 数字，剔除易混淆字符
     *
     * 剔除 0 / O / 1 / l / I 是刻意的：初始密码需要管理员看一眼再转告本人，
     * 这几个字符在绝大多数字体里几乎无法区分，「0 还是 O」的电话沟通成本
     * 远比多去掉 5 个字符值钱。字符集共 57 个字符。
     */
    private static final String RANDOM_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** 随机数源：SecureRandom 而非 Random——口令生成不需要可预测性，需要不可预测性 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成一串随机初始密码，用于管理员新增用户时未指定密码的场景
     *
     * 固定默认口令意味着任何知道本系统存在的人都能登录所有尚未改密的账号，
     * 安全性完全寄托在「所有人都会立刻改密」上，而现实里总有人不会。
     *
     * 长度校验必须在生成之前：小于 8 位的口令即使随机也挡不住暴力破解，
     * 不能让它有机会入库。
     *
     * @param length 密码长度，必须不小于 8；小于 8 时抛参数错误（A0401），
     *               建议传 10~16：既够复杂度，又不至于在电话转告时听错
     * @return 指定长度的随机明文密码，字符取自去掉易混淆字符后的 57 个字符
     * @throws com.bhu.runshistudioweb.exception.BusinessException 长度小于 8 时抛出（A0401）
     */
    public static String generateRandomPassword(int length) {
        // ① 先校验长度：必须在循环之前，避免为一个注定失败的结果白跑一遍
        ThrowUtils.throwIf(length < 8, ErrorCode.PARAMS_ERROR, "随机密码长度不能少于 8 位");
        StringBuilder sb = new StringBuilder(length);
        // ② 按长度逐位生成：每一位都在字符集长度范围内取随机下标
        for (int i = 0; i < length; i++) {
            // nextInt(字符集长度) 保证下标一定落在合法区间内，不会越界
            int index = SECURE_RANDOM.nextInt(RANDOM_PASSWORD_CHARS.length());
            sb.append(RANDOM_PASSWORD_CHARS.charAt(index));
        }
        // ③ 返回明文：调用方负责展示一次并提示管理员转交本人，绝不能写日志
        return sb.toString();
    }

    /**
     * 把明文密码加密成 BCrypt 哈希串
     *
     * 只负责「怎么加密」，不做密码强度校验：长度、大小写、特殊字符属于业务策略，
     * 与具体接口绑定且变化频繁，应由接口层 DTO 上的注解完成，写进工具类会让规则散落多处。
     *
     * 先挡空值：BCrypt 对 null 会直接抛异常，这个异常会穿透成 500，
     * 用户看到的是「系统内部异常」而不是「密码不能为空」。
     *
     * @param rawPassword 明文密码，不能为 null、空串或纯空格；
     *                    建议长度不超过 72 字节，超出部分会被静默丢弃
     * @return 60 位 BCrypt 哈希串（含算法版本、强度与盐），可直接写入 sys_user.user_password
     * @throws com.bhu.runshistudioweb.exception.BusinessException 明文为空或纯空白时抛出（A0401）
     */
    public static String encrypt(String rawPassword) {
        // ① 先挡空值：BCrypt 对 null 会抛异常，会穿透成 500 而不是可读的参数提示
        ThrowUtils.throwIf(StrUtil.isBlank(rawPassword), ErrorCode.PARAMS_ERROR, "密码不能为空");
        // ② 不显式传盐：hashpw 内部会用 SecureRandom 生成随机盐并拼进结果
        // 注意：BCrypt 只取明文前 72 个字节，更长的部分会被静默忽略，
        // 所以上层若放开密码长度上限，需要一并卡在 72 字节以内
        return BCrypt.hashpw(rawPassword);
        HashMap
    }
    /**
     * 校验用户输入的明文密码与库里的哈希串是否匹配
     *
     * 唯一正确的比对方式：把库里的哈希串整个交给 checkpw，它从中取出盐与强度，
     * 用同样的参数把明文再算一遍。把输入加密一遍再 equals 会永远失败——
     * 每次加密的盐都不同。
     *
     * 非法哈希串返回 false 而不是抛异常：库里的 user_password 若因手工改库、
     * 迁移截断而不再是合法 BCrypt 格式，checkpw 会抛 IllegalArgumentException，
     * 用户看到的会是 500 而不是「密码错误」。登录失败本该是 200 响应体里的业务结果。
     *
     * @param rawPassword 用户输入的明文密码，可为 null / 空串（此时直接返回 false）
     * @param encryptedPassword 库中存的 BCrypt 哈希串，可为 null / 空串 / 格式非法
     *                          （这些情况一律返回 false，不抛异常）
     * @return 匹配返回 true；密码为空、哈希为空或哈希格式非法时返回 false。
     * 调用方无需判空，也不需要包 try-catch
     */
    public static boolean matches(String rawPassword, String encryptedPassword) {
        // ① 先挡空值：checkpw 对空串会抛异常，而「没填密码」是再正常不过的输入
        if (StrUtil.isBlank(rawPassword) || StrUtil.isBlank(encryptedPassword)) {
            return false;
        }
        // ② 交给 checkpw 完成「取盐 + 重算 + 比较」，调用方不需要 try-catch
        return BCrypt.checkpw(rawPassword, encryptedPassword);
    }
}
