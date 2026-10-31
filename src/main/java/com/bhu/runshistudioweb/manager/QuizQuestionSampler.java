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
 * @author shaoshing
 */
public final class QuizQuestionSampler {

    /**
     * 从已排好序的候选里取前 N 个（顺序模式与错题重刷模式共用）
     *
     * @param ordered 已按各自模式排好序的候选 ID，可为 null；顺序即最终展示顺序
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @return 截断后的 ID 列表，长度不超过 size；不会为 null
     */
    public static List<Long> takeOrdered(Collection<Long> ordered, int size) {
        // 三种「没有题」合成一条出口，调用方只判 isEmpty
        if (ordered == null || ordered.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        // 预分配容量：最多取 min(size, 候选数) 个，不必反复扩容
        List<Long> result = new ArrayList<>(Math.min(size, ordered.size()));
        for (Long id : ordered) {
            // 凑够就停：保留候选原有的相对顺序，不做任何重排
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
     * @param candidates 候选 ID（顺序无关），可为 null
     * @param size 本轮期望题量；小于等于 0 时返回空列表
     * @param random 随机源；传 null 用默认实现，传固定 seed 可复现
     * @return 洗牌后的前 size 个；不会为 null
     */
    public static List<Long> takeRandom(Collection<Long> candidates, int size, Random random) {
        // 与 takeOrdered 同一套前置判断，两种模式的「空」形态一致
        if (candidates == null || candidates.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        // 先复制再洗牌：不能改动调用方的集合（Service 可能还要拿它做别的判断）
        List<Long> shuffled = new ArrayList<>(candidates);
        // random 为 null 走生产路径；非 null 时用调用方的随机源，单测才能复现
        if (random == null) {
            Collections.shuffle(shuffled);
        } else {
            Collections.shuffle(shuffled, random);
        }
        // subList 只是视图，必须复制成新列表再返回
        return new ArrayList<>(shuffled.subList(0, Math.min(size, shuffled.size())));
    }

    /**
     * 从候选里剔除「已作答」的题（只用于顺序模式）
     *
     * @param candidates 已按题序排好的候选 ID，可为 null
     * @param answered 已作答（不含跳过）的题目 ID，可为 null
     * @return 剔除后的列表，保留候选原有顺序；不会为 null
     */
    public static List<Long> excludeAnswered(Collection<Long> candidates, Collection<Long> answered) {
        // 没有候选就没什么可剔除的
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        // 新用户最常见的路径：一次都没作答，直接复制，省掉差集运算
        if (answered == null || answered.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        // LinkedHashSet 兼顾「集合语义」与「保留题序」，剔完不用再排
        LinkedHashSet<Long> remaining = new LinkedHashSet<>(candidates);
        remaining.removeAll(answered);
        // 调用方要做截断，必须换成有下标的 List
        return new ArrayList<>(remaining);
    }

    /**
     * 把前端请求的题量收敛到配置允许的区间 [1, maxSize]
     *
     * @param requested 前端请求的题量，可为 null；非正数一律视为「没指定」
     * @param defaultSize 配置里的默认题量，允许被配成非法值（本方法会纠正）
     * @param maxSize 配置里的上限，允许被配成非法值（本方法会纠正）
     * @return 实际使用的题量，保证落在 [1, max(1, maxSize)] 区间内
     */
    public static int resolveSize(Integer requested, int defaultSize, int maxSize) {
        // 上限至少为 1：配置成 0 时不能让结果被压成 0
        int max = Math.max(1, maxSize);
        // 默认值也夹进合法区间，保证 fallback 一定可用
        int fallback = Math.min(Math.max(1, defaultSize), max);
        // null / 0 / 负数都按「没指定」处理
        if (requested == null || requested <= 0) {
            return fallback;
        }
        // 超上限压到上限，不报错
        return Math.min(requested, max);
    }

    /**
     * 首页错题本预览条数
     *
     * @return 固定为 QuizConstant.HOME_WRONG_PREVIEW 的值
     */
    public static int homePreviewSize() {
        return QuizConstant.HOME_WRONG_PREVIEW;
    }

    private QuizQuestionSampler() {
    }
}
