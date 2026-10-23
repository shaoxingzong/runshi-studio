package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 刷题模块配置（对应 application.yml 的 studio.quiz 段）
 *
 * 与 PostProperties / AttendanceProperties 同一纪律：
 * 配置项全部给默认值，不做必填校验。刷题是附加能力，
 * 配置没写全时应用必须能正常启动；做成必填的话，一个缺失的环境变量会让整个站点起不来。
 *
 * 注意：本类不能把字段声明成 final。
 * @ConfigurationProperties 的绑定依赖 setter 与无参构造，
 * 字段一旦 final 就会让配置绑定彻底失效——这与「依赖注入统一用构造器注入」是两回事。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.quiz")
public class QuizProperties {

    /**
     * 一轮的默认题量（前端不传 size 时用它）
     *
     * 取 20：一屏一题的话大约 15–20 分钟，是「一次坐下来能刷完」的长度。
     * 再长会让用户中途放弃，而中途放弃的批次不会产生任何统计价值。
     */
    private int defaultSize = 20;

    /**
     * 一轮题量的上限，默认 100
     *
     * 防「一次拉全库」：随机模式会把范围内所有题的 id 查出来再洗牌，
     * 不设上限时用户可以传 100000 让服务端为一次请求做十万次洗牌。
     * 超限时收敛到上限而不是报错（与分页参数同一处理方式）。
     */
    private int maxSize = 100;

    /**
     * 错题消解阈值：连续答对几次算「已掌握」，默认 2
     *
     * 为什么不是 1：四选一蒙对的概率是 25%，一次答对就消解会让
     * 「已消解」这个数字失去意义，用户也不会因此真正掌握。
     * 取 2 是「一次可能是蒙的、两次大概率是真会了」的折中。
     *
     * 设为 1 会让错题本变成「答对一次就消失」，设为 3 以上会让用户
     * 觉得「怎么刷都消不掉」——两个极端都不建议。
     */
    private int resolveStreak = 2;
}
