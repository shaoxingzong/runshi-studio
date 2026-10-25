package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.constant.QuizConstant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * 刷题选题器：三种模式的「从候选里挑出本轮题目」策略
 *
 * 为什么单独抽成一个 Manager，而不是写在 Service 里：
 * 选题是本模块最容易出边界问题的地方（刷完、题量不足、错题本为空、随机不重复），
 * 而它本身是纯函数——不查库、不认识 HTTP、不写库。
 * 抽出来之后，「本章刷完该返回什么」「题量不足要不要补」这类问题
 * 可以用纯单测覆盖，不必启动 Spring、不必连数据库。
 * 与 ClientIpManager 是同一条分层纪律。
 *
 * 三种模式的职责必须不重叠（否则用户会在两个入口看到同一批题）：
 * - 顺序：候选已由 Service 排除「已作答」的题，本类只负责截断；
 * - 错题：候选已由 Service 按「待复习 + 最久没复习」排好序，本类只负责截断；
 * - 随机：候选是范围内全部启用题，本类负责洗牌后截断。
 * 也就是说：排序与过滤在 Service，截断与随机在本类。
 * 这个分工让本类不依赖任何数据库状态，也让 Service 的查询条件可以各自走索引。
 *
 * 为什么「不重叠」是硬要求而不是优化项：三个模式对应三个入口（顺序刷题、
 * 错题重刷、随机刷题）。如果一道题既能从顺序入口刷到、又能从错题入口刷到，
 * 用户会认为系统在重复给他同一道题，而错题本的「消灭」语义也就不成立了——
 * 因为「已作答」的题在顺序模式里被排除，恰恰是把错题留给错题本去处理。
 *
 * @author shaoshing
 */
public final class QuizQuestionSampler {

    /**
     * 从已排好序的候选里取前 N 个（顺序模式与错题重刷模式共用）
     *
     * <p>为什么这样设计：排序语义由调用方的查询决定——顺序模式按题序、
     * 错题模式按「最久没复习」，两者完全不同。如果这里再排一次（比如按 id 排），
     * 两种模式就会失去各自的语义：顺序模式不再「从第一章往后走」，
     * 错题模式也不再「先复习最久没碰的」。所以本方法只截断、不重排，
     * 这也是它能被两种模式共用的前提。
     * 题量不足时按实际数量返回，不补题也不报错：「本章只剩 3 题」是正常状态，
     * 补题会把别的章节的题混进来（用户会以为串章了），报错则会让用户以为系统坏了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 三个前置条件一次判断：候选为 null、候选为空、期望题量非正数，
     *     都直接给空列表。合并判断是为了让「没有题」只有一条出口，
     *     调用方只需判 isEmpty，不必分辨是哪一种「没有」；</li>
     *     <li>② 按 min(size, 候选数) 预分配容量：避免 ArrayList 反复扩容，
     *     也让「最多取多少」在创建时就确定下来；</li>
     *     <li>③ 按候选的迭代顺序依次加入，凑够 size 立即 break：
     *     用 break 而不是 subList，是为了兼容任何 Collection 实现——
     *     并不是所有集合都支持按下标随机访问。</li>
     * </ol>
     *
     * <p>容易踩的坑：size 传 0 或负数时返回的是空列表，而不是「全部」。
     * 调用方若把「前端没传条数」当成 0 直接传进来，会得到「本轮没有题目」，
     * 因此题量的兜底纠正必须在此之前用 resolveSize 完成。
     *
     * @param ordered 已按各自模式排好序的候选 ID，可为 null；
     *                顺序即最终展示顺序，本方法不会改动它
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @return 截断后的 ID 列表，长度不超过 size；候选为空或 size 非正数时返回空列表（不是 null）
     */
    public static List<Long> takeOrdered(Collection<Long> ordered, int size) {
        // ① 三种「没有题」合成一条出口，调用方只判 isEmpty
        if (ordered == null || ordered.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        // ② 预分配容量：最多取 min(size, 候选数) 个，不必反复扩容
        List<Long> result = new ArrayList<>(Math.min(size, ordered.size()));
        for (Long id : ordered) {
            // ③ 凑够就停：保留候选原有的相对顺序，不做任何重排
            if (result.size() >= size) {
                break;
            }
            result.add(id);
        }
        return result;
    }

    /**
     * 从候选里随机取前 N 个（随机模式）
     *
     * <p>为什么这样设计：随机选题最常见的两种写法都有代价。
     * 一是 `ORDER BY RAND()`：数据库要对整张题表算一遍随机值再排序，题量上万时
     * 是一次明显的慢查询，而且它只返回前 N 行，白算了后面的。
     * 二是「循环随机取下标」：很容易写出重复抽取（同一题在一轮里出现两次），
     * 而用户对「这道题刚做过」非常敏感。
     * 这里走第三条路——Service 只查 ID 列（覆盖索引，几千个 long 在内存里可以忽略），
     * 本方法在内存里整体洗牌后截断。洗牌天然保证一轮内不重复，也只需要一次 O(n) 遍历。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 前置条件判断与 takeOrdered 保持一致：null、空、非正数都返回空列表，
     *     让两种模式的「没有题」在调用方眼里是同一个形态；</li>
     *     <li>② 先复制一份再洗牌：绝不能直接改调用方传进来的集合，
     *     Service 可能还要拿原集合做别的判断，就地洗牌会让那些判断莫名其妙地失效；</li>
     *     <li>③ 按 random 是否为 null 选择洗牌重载：传 null 走无 seed 的生产路径，
     *     传了就用调用方的随机源，保证单元测试可复现；</li>
     *     <li>④ 截取前 min(size, 候选数) 个并复制成新列表：subList 返回的是原列表的视图，
     *     直接返回会让调用方拿到一个仍指向洗牌后列表的引用，后续任何改动都会互相影响。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里「随机」的粒度是「整批洗牌后取前 N」，不是「每题独立按概率抽」。
     * 因此同一批候选在两次调用里抽到的题几乎一定不同，这是期望行为；
     * 但如果将来要做「同一天同一用户抽到同一批」，就不能依赖这个方法，
     * 需要由调用方传入固定的随机源或提前固化结果。
     *
     * @param candidates 候选 ID（顺序无关），可为 null
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @param random 随机源；传 null 时用默认实现（生产路径），传固定 seed 的实例可复现结果
     * @return 洗牌后的前 size 个；候选为空或 size 非正数时返回空列表（不是 null）
     */
    public static List<Long> takeRandom(Collection<Long> candidates, int size, Random random) {
        // ① 与 takeOrdered 同一套前置判断，两种模式的「空」形态一致
        if (candidates == null || candidates.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        // ② 先复制再洗牌：不能改动调用方的集合（Service 可能还要拿它做别的判断）
        List<Long> shuffled = new ArrayList<>(candidates);
        // ③ random 为 null 走生产路径；非 null 时用调用方的随机源，单测才能复现
        if (random == null) {
            Collections.shuffle(shuffled);
        } else {
            Collections.shuffle(shuffled, random);
        }
        // ④ subList 只是视图，必须复制成新列表再返回
        return new ArrayList<>(shuffled.subList(0, Math.min(size, shuffled.size())));
    }

    /**
     * 从候选里剔除「已作答」的题（只用于顺序模式）
     *
     * <p>为什么这样设计：剔除的口径是「已作答」而不是「已掌握」，这是三种模式
     * 职责不重叠的关键。顺序模式的语义是「从本章第一题往后走一遍，走过的跳过」，
     * 它负责覆盖；答错的题不在这里反复出现，因为它们归错题重刷管，那个模式负责消灭。
     * 如果顺序模式也排除「已掌握」，那些答错、还没掌握的题就会同时出现在两个入口，
     * 用户会困惑「怎么又是这道」；如果顺序模式什么都不排除，章节永远刷不完。
     * 另一个刻意的选择是「跳过不算作答」：跳过只是「这题我现在不想做」，
     * 它不该让这道题从顺序模式里消失，否则用户一跳过就等于把它漏掉了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 候选为空直接给空列表：没有候选时谈不上「剔除」，也不必往下走；</li>
     *     <li>② 已作答集合为空时直接复制候选返回：这是最常见的情况（刚开始刷），
     *     省掉建集合与差集的开销，也避免无谓的对象分配；</li>
     *     <li>③ 用 LinkedHashSet 装候选再 removeAll：LinkedHashSet 同时提供
     *     集合语义（去重、O(1) 删除）与顺序保证（保留题序），
     *     这样剔除之后不需要再排一次序；</li>
     *     <li>④ 转回 ArrayList 返回：调用方要做下标访问与截断，
     *     而 Set 没有顺序下标的概念。</li>
     * </ol>
     *
     * <p>容易踩的坑：如果改用 HashSet 装候选，返回顺序会变成哈希顺序，
     * 顺序模式立刻退化成「随机模式」——而且这个错误不会报错，只会让用户觉得
     * 「怎么跳着出题」。另外 answered 传 null 与传空集合必须等价处理，
     * 否则「一次都没作答」的新用户会拿到空列表。
     *
     * @param candidates 已按题序排好的候选 ID，可为 null
     * @param answered 已作答（结果为正确或错误，不含跳过）的题目 ID，可为 null
     * @return 剔除后的列表，保留候选原有顺序；候选为空时返回空列表（不是 null）
     */
    public static List<Long> excludeAnswered(Collection<Long> candidates, Collection<Long> answered) {
        // ① 没有候选就没什么可剔除的
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        // ② 新用户最常见的路径：一次都没作答，直接复制，省掉差集运算
        if (answered == null || answered.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        // ③ LinkedHashSet 兼顾「集合语义」与「保留题序」，剔完不用再排
        LinkedHashSet<Long> remaining = new LinkedHashSet<>(candidates);
        remaining.removeAll(answered);
        // ④ 调用方要做截断，必须换成有下标的 List
        return new ArrayList<>(remaining);
    }

    /**
     * 把前端请求的题量收敛到配置允许的区间 [1, maxSize]
     *
     * <p>为什么这样设计：题量来自前端，可能是 null、0、负数，也可能是有人
     * 手改请求传了 100000。三种情况都不该让请求失败——分页参数传错没必要让
     * 整个查询失败，这是项目里统一的口径。因此这里只做「收敛」不做「报错」：
     * 没传或传了非正数就用配置的默认值，超过上限就压到上限。
     * 用「压到上限」而不是「拒绝」，是因为用户想多刷几题这件事本身是合理的，
     * 只是服务端不允许一次拉太多；悄悄少给几题比弹一个错误提示体验更好。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先把上限抬到至少 1：配置写错（比如 maxSize 配成 0）时不能让
     *     后续的 Math.min 把结果压成 0，否则所有模式都会「没有题」；</li>
     *     <li>② 再把默认值夹进 [1, 上限]：默认值来自配置文件，同样可能被配错，
     *     这里兜住它，保证 fallback 一定是一个可用的题量；</li>
     *     <li>③ 请求为空或非正数时用 fallback：null 表示前端没传，
     *     0 与负数通常是前端把「不限」错传成了 0，都按「没指定」处理；</li>
     *     <li>④ 其余情况压到上限：Math.min 保证返回值永远不会超过 maxSize。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只保证返回值落在 [1, maxSize]，不保证「有那么多题」。
     * 实际能出几题由候选数量决定，题量不足时 takeOrdered 会按实际数量返回，
     * 不会因为这里给了 20 就去凑 20 道题。
     *
     * @param requested 前端请求的题量，可为 null；非正数一律视为「没指定」
     * @param defaultSize 配置里的默认题量，允许被配成非法值（本方法会纠正）
     * @param maxSize 配置里的上限，允许被配成非法值（本方法会纠正）
     * @return 实际使用的题量，保证落在 [1, max(1, maxSize)] 区间内
     */
    public static int resolveSize(Integer requested, int defaultSize, int maxSize) {
        // ① 上限至少为 1：配置成 0 时不能让结果被压成 0
        int max = Math.max(1, maxSize);
        // ② 默认值也夹进合法区间，保证 fallback 一定可用
        int fallback = Math.min(Math.max(1, defaultSize), max);
        // ③ null / 0 / 负数都按「没指定」处理
        if (requested == null || requested <= 0) {
            return fallback;
        }
        // ④ 超上限压到上限，不报错
        return Math.min(requested, max);
    }

    /**
     * 首页错题本预览条数
     *
     * <p>为什么这样设计：它看起来只是一个常量，放在 Controller 里直接写数字也能跑。
     * 但「首页展示多少条」是业务口径而不是接口细节——它和「一轮刷多少题」属于同一类
     * 决定，都要受同一份配置约束。放在这里，将来调整时只需改一处；
     * 若散落在 Controller 里，就会出现「首页 5 条、错题本页 10 条」这种
     * 谁都没写错、但产品上不自洽的配置。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接返回常量值：这里刻意不做可配置化，因为首页预览条数变化时
     *     前端的布局也要跟着调，写死反而能让改动被显式地发现。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法每次调用都返回同一个值，不要把它当成「随机或可变的」而
     * 在循环里反复调用——虽然结果一样，但会让读者误以为它有副作用。
     *
     * @return 固定为 QuizConstant.HOME_WRONG_PREVIEW 的值
     */
    public static int homePreviewSize() {
        return QuizConstant.HOME_WRONG_PREVIEW;
    }

    private QuizQuestionSampler() {
    }
}
