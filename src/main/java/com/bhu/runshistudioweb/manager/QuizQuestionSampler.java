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
 * author: shaoshing
 *
 * <p><b>为什么单独抽成一个 Manager，而不是写在 Service 里</b>：
 * 选题是本模块最容易出边界问题的地方（刷完、题量不足、错题本为空、随机不重复），
 * 而它本身是<b>纯函数</b>——不查库、不认识 HTTP、不写库。
 * 抽出来之后，「本章刷完该返回什么」「题量不足要不要补」这类问题
 * 可以用纯单测覆盖，不必启动 Spring、不必连数据库。
 * 与 {@code ClientIpManager} 是同一条分层纪律。
 *
 * <p><b>三种模式的职责必须不重叠</b>（否则用户会在两个入口看到同一批题）：
 * <ul>
 *     <li>顺序：候选已由 Service 排除「已作答」的题，本类只负责截断；</li>
 *     <li>错题：候选已由 Service 按「待复习 + 最久没复习」排好序，本类只负责截断；</li>
 *     <li>随机：候选是范围内全部启用题，本类负责洗牌后截断。</li>
 * </ul>
 * 也就是说：<b>排序与过滤在 Service，截断与随机在本类</b>。
 * 这个分工让本类不依赖任何数据库状态，也让 Service 的查询条件可以各自走索引。
 */
public final class QuizQuestionSampler {

    /**
     * 从有序候选里取前 N 个（顺序模式 / 错题模式共用）
     *
     * <p>候选已按各自模式的排序规则排好（顺序按题序、错题按最久未复习），
     * 因此这里<b>只截断、不重排</b>。重排会让两种模式失去各自的语义。
     *
     * <p><b>题量不足时按实际数量返回，不补题、不报错</b>：
     * 「本章只剩 3 题」是正常状态，补题会把别的章节的题混进来（用户会以为串章了），
     * 报错则会让用户以为系统坏了。
     *
     * @param ordered 已排好序的候选 ID，可为 null
     * @param size    本轮期望题量
     * @return 截断后的 ID 列表；候选为空时返回空列表，不返回 null
     */
    public static List<Long> takeOrdered(Collection<Long> ordered, int size) {
        if (ordered == null || ordered.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>(Math.min(size, ordered.size()));
        for (Long id : ordered) {
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
     * <p><b>为什么不用 {@code ORDER BY RAND()}</b>：那会对整张题表做一次排序，
     * 题量上万时是明显的慢查询。这里的做法是 Service 只查 ID 列（索引覆盖，
     * 几千个 long 在内存里可以忽略），本类在内存里洗牌后截断。
     *
     * <p><b>洗牌用 {@link Collections#shuffle}</b> 而不是「随机取下标」：
     * 后者容易写出重复抽取（同一题出现两次），而洗牌天然保证一轮内不重复。
     *
     * <p><b>为什么允许注入 Random</b>：单测要断言「同一 seed 结果可复现」，
     * 否则随机逻辑只能测「不重复」「数量对」这类弱断言。
     *
     * @param candidates 候选 ID（顺序无关），可为 null
     * @param size       本轮期望题量
     * @param random     随机源；传 null 时用默认实现（生产路径）
     * @return 洗牌后的前 size 个；候选为空时返回空列表
     */
    public static List<Long> takeRandom(Collection<Long> candidates, int size, Random random) {
        if (candidates == null || candidates.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        // 先复制再洗牌：不能改动调用方的集合（Service 可能还要拿它做别的判断）
        List<Long> shuffled = new ArrayList<>(candidates);
        if (random == null) {
            Collections.shuffle(shuffled);
        } else {
            Collections.shuffle(shuffled, random);
        }
        return new ArrayList<>(shuffled.subList(0, Math.min(size, shuffled.size())));
    }

    /**
     * 从候选里排除「已作答」的题（顺序模式）
     *
     * <p><b>为什么是「已作答」而不是「已掌握」</b>：顺序模式的语义是
     * 「从第一章往后走一遍，走过的跳过」。答错的题不会在这里反复出现——
     * 它们归错题重刷管。两个模式的职责因此不重叠：
     * 顺序模式负责「覆盖」，错题模式负责「消灭」。
     *
     * <p>用 {@link LinkedHashSet} 保留候选原有顺序（即题序），
     * 这样排除之后不需要再排一次。
     *
     * @param candidates 已按题序排好的候选 ID
     * @param answered   已作答（result 为正确或错误，<b>不含跳过</b>）的题目 ID
     * @return 排除后的列表，保留候选顺序；候选为空时返回空列表
     */
    public static List<Long> excludeAnswered(Collection<Long> candidates, Collection<Long> answered) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (answered == null || answered.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        LinkedHashSet<Long> remaining = new LinkedHashSet<>(candidates);
        remaining.removeAll(answered);
        return new ArrayList<>(remaining);
    }

    /**
     * 收敛本轮题量到配置允许的区间
     *
     * <p>规则：{@code size <= 0} 用默认值；超过上限<b>收敛</b>到上限而不是报错。
     * 与分页参数的处置方式一致（「页码传错没必要让整个查询失败」）。
     *
     * @param requested 前端请求的题量，可为 null
     * @param defaultSize 默认题量
     * @param maxSize     上限
     * @return 实际使用的题量，保证落在 {@code [1, maxSize]}
     */
    public static int resolveSize(Integer requested, int defaultSize, int maxSize) {
        int max = Math.max(1, maxSize);
        int fallback = Math.min(Math.max(1, defaultSize), max);
        if (requested == null || requested <= 0) {
            return fallback;
        }
        return Math.min(requested, max);
    }

    /**
     * 错题本「最近 N 条」在首页的预览数量
     *
     * <p>放在这里而不是 Controller：它是「展示多少条」的业务口径，
     * 与选题同属「取多少」这一类决定。
     *
     * @return 固定为 {@link QuizConstant#HOME_WRONG_PREVIEW}
     */
    public static int homePreviewSize() {
        return QuizConstant.HOME_WRONG_PREVIEW;
    }

    private QuizQuestionSampler() {
    }
}
