package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 刷题模块配置（对应 application.yml 的 studio.quiz 段）
 *
 * 配置项全部给默认值、不做必填校验：刷题是附加能力，配置没写全时应用必须能正常启动。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.quiz")
public class QuizProperties {

    /** 一轮的默认题量（前端不传 size 时用它），默认 20 */
    private int defaultSize = 20;

    /** 一轮题量的上限，默认 100 */
    private int maxSize = 100;

    /** 错题消解阈值：连续答对几次算「已掌握」，默认 2 */
    private int resolveStreak = 2;
}
