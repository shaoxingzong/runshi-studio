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
     * 剔除 0 / O / 1 / l / I 是刻意的：初始密码需要管理员看一眼再转告本人
     * （电话、微信、纸条），这两个字符在绝大多数字体里几乎无法区分，
     * 「0 还是 O」的电话沟通成本远比多去掉 5 个字符值钱。字符集共 57 个字符。
     */
    private static final String RANDOM_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** 随机数源：SecureRandom 而非 Random——口令生成不需要可预测性，需要不可预测性 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成一串随机初始密码，用于管理员新增用户时未指定密码的场景
     *
     * <p>为什么这样设计：固定默认口令（例如统一发 Runshi@123）意味着
     * 任何知道本系统存在的人，都能登录所有尚未改密的账号——安全性完全寄托在
     * 「所有人都会立刻改密」上，而现实里总有人不会。随机化之后，
     * 初始口令只会在创建响应里出现一次，攻击者无法预知，也无法批量尝试。
     *
     * <p>为什么先校验长度再生成：长度是本方法唯一的入参，也是最容易被
     * 调用方传错的地方（例如从配置里读到一个没配好的 0）。若先生成再校验，
     * 校验失败时已经白跑了一遍循环；更重要的是，长度小于 8 的口令
     * 即使随机也挡不住暴力破解，必须在生成之前就拒绝，不能让它有机会入库。
     *
     * <p>为什么用 SecureRandom 而不是 Random：Random 的种子可预测，
     * 攻击者只要观察到足够多的历史口令就能推出下一个。这里需要的是
     * 「不可预测」而不是「看起来随机」，SecureRandom 才满足。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先校验长度，太短直接抛参数错误——必须在循环之前，
     *     避免为一个注定失败的结果白跑一遍；</li>
     *     <li>按长度逐位生成，每一位都在字符集长度范围内取随机下标；</li>
     *     <li>拼成字符串返回，调用方负责展示一次并提示管理员转交本人。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法生成的是明文，只应出现在「创建用户」的响应里，
     * 绝不能写进日志——日志的访问范围往往比数据库更大，写进去等于把口令公开。
     * 另外这里的随机只保证「不可预测」，不保证「一定同时含大小写与数字」：
     * 极端情况下可能连续取到同一类字符，若业务要求强制包含各类字符，
     * 需要额外做一次校验并重新生成，不能假定随机结果天然满足复杂度要求。
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
     * <p>为什么这样设计：本方法只负责「怎么加密」，不做密码强度校验。
     * 长度、大小写、特殊字符这些规则属于业务策略，与具体接口绑定且变化频繁，
     * 应当放在接口层由 DTO 上的注解完成；写进工具类会导致「改一次规则就要动公共代码」，
     * 也会让同一套规则散落在多个地方，最终各处松紧不一。
     *
     * <p>为什么每次加密同一明文得到的哈希串都不一样：BCrypt 在内部用
     * SecureRandom 生成随机盐，并把盐直接编进结果字符串（形如
     * {@code $2a$10$...}，其中 {@code 2a} 是算法版本、{@code 10} 是计算强度、后面是盐与哈希）。
     * 这正是它能抵御彩虹表的原因，也决定了校验时不能用 equals 比较两个哈希串——
     * 必须用 {@link #matches(String, String)} 让 BCrypt 从库里的串中取出盐再算一遍。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡空值：BCrypt 对 null 会直接抛异常，而这个异常会穿透成 500，
     *     用户看到的是「系统内部异常」而不是「密码不能为空」；</li>
     *     <li>调用 hashpw 完成加密，不显式传盐——由它内部生成随机盐并拼进结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：BCrypt 只使用明文的前 72 个字节，超出的部分被静默忽略。
     * 也就是说「前 72 字节相同的两个密码」会被判定为同一个密码，
     * 而且不会报任何错。如果将来放开密码长度上限，必须一并卡在 72 字节以内
     * （注意是字节不是字符，一个中文字符占 3 个字节）。
     * 另外返回的哈希串绝不能出现在日志或响应体里，它等价于密码本身。
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
    }
    /**
     * 校验用户输入的明文密码与库里的哈希串是否匹配
     *
     * <p>为什么这样设计：这是唯一正确的密码比对方式。BCrypt 每次加密都会用新的随机盐，
     * 同一明文两次加密的结果并不相等，因此绝不能把用户输入加密一遍再和库里的串
     * 用 equals 比较——那样登录会永远失败，而且失败得毫无线索。
     * 正确的做法是把库里的哈希串整个交给 checkpw，它从中取出盐与强度，
     * 用同样的参数把明文再算一遍，再比较结果。
     *
     * <p>为什么遇到非法哈希串返回 false 而不是抛异常：这是登录接口最核心的一条纪律。
     * 库里的 user_password 若因为手工改库、迁移截断或算法升级而变成一段
     * 不是合法 BCrypt 格式的字符串，checkpw 会抛 IllegalArgumentException。
     * 若让它抛出去，用户看到的是 500「系统内部异常」，运维会去查服务是否挂了，
     * 而真正的原因是某一行数据有问题。返回 false 则语义明确：这次登录不成功。
     * 登录失败本来就该是 200 响应体里的一个业务结果，不是系统故障。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡空密码与空哈希：两者都直接返回 false。空哈希通常意味着
     *     该账号是第三方登录或数据不完整，本就不该走密码校验这条路；</li>
     *     <li>交给 checkpw 完成「取盐 + 重算 + 比较」，一步到位。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只回答「密码对不对」，不回答「账号是否存在」。
     * 调用方必须自己保证「账号不存在」与「密码错误」返回给前端的是同一句提示，
     * 否则攻击者可以用它当账号枚举工具：提示不同就说明账号存在。
     * 另外 BCrypt 的计算是刻意设计得慢的（这是安全特性不是性能缺陷），
     * 登录接口不要在高并发路径上反复调用它。
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
