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
 * 抽成纯函数是因为选题最容易出边界问题（刷完、题量不足、错题本为空、随机不重复），
 * 而它不查库、不认识 HTTP，因此能用纯单测覆盖。
 *
 * 分工：排序与过滤在 Service，截断与随机在本类。顺序与错题模式只截断不重排，
 * 各自保留查询赋予的语义；随机模式负责洗牌。三种模式的职责必须不重叠，
 * 否则用户会在两个入口看到同一批题。
 *
 * @author shaoshing
 */
public final class QuizQuestionSampler {

    /**
     * 从已排好序的候选里取前 N 个（顺序模式与错题重刷模式共用）
     *
     * 只截断、不重排：排序语义由调用方的查询决定（顺序模式按题序、错题模式按最久没复习），
     * 这里再排一次两种模式就都失去各自语义了。
     *
     * 题量不足时按实际数量返回，不补题也不报错：补题会把别的章节混进来，报错会让用户以为系统坏了。
     *
     * 坑：size 传 0 或负数时返回空列表而不是「全部」，题量的兜底纠正必须在此之前用
     * {@link #resolveSize} 完成。
     *
     * @param ordered 已按各自模式排好序的候选 ID，可为 null；顺序即最终展示顺序
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @return 截断后的 ID 列表，长度不超过 size；不会为 null
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
     * 不用 `ORDER BY RAND()`：题量上万时是一次明显的慢查询，而且它只返回前 N 行，白算了后面的。
     * 也不用「循环随机取下标」：很容易写出重复抽取，而用户对「这道题刚做过」非常敏感。
     * 这里由 Service 只查 ID 列，本方法在内存里整体洗牌后截断，天然保证一轮内不重复。
     *
     * 坑一：先复制再洗牌，绝不能直接改调用方传进来的集合。
     * 坑二：subList 只是视图，必须复制成新列表再返回。
     *
     * @param candidates 候选 ID（顺序无关），可为 null
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @param random 随机源；传 null 用默认实现，传固定 seed 可复现
     * @return 洗牌后的前 size 个；不会为 null
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
     * 口径是「已作答」而不是「已掌握」：顺序模式负责覆盖，答错的题归错题重刷去消灭。
     * 若也排除「已掌握」，那些题会同时出现在两个入口，用户会困惑「怎么又是这道」。
     * 跳过不算作答——跳过只是「这题我现在不想做」，不该让题从顺序模式里消失。
     *
     * 坑：必须用 LinkedHashSet 装候选。换成 HashSet 后返回顺序变成哈希顺序，
     * 顺序模式立刻退化成「随机模式」，而且不报错，只让用户觉得「怎么跳着出题」。
     *
     * @param candidates 已按题序排好的候选 ID，可为 null
     * @param answered 已作答（不含跳过）的题目 ID，可为 null
     * @return 剔除后的列表，保留候选原有顺序；不会为 null
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
     * 只收敛不报错：分页参数传错没必要让整个查询失败，这是项目里统一的口径。
     * 超限压到上限而不是拒绝——用户想多刷几题本身合理，悄悄少给几题比弹错误提示体验更好。
     *
     * 坑：本方法只保证返回值落在 [1, maxSize]，不保证「有那么多题」，
     * 实际能出几题由候选数量决定。
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
     * 放在这里而不是在 Controller 里直接写数字：「首页展示多少条」是业务口径，
     * 与「一轮刷多少题」属于同一类决定，散落出去就会出现「首页 5 条、错题本页 10 条」
     * 这种谁都没写错、但产品上不自洽的配置。
     *
     * @return 固定为 QuizConstant.HOME_WRONG_PREVIEW 的值
     */
    public static int homePreviewSize() {
        return QuizConstant.HOME_WRONG_PREVIEW;
    }

    private QuizQuestionSampler() {
    }
}
