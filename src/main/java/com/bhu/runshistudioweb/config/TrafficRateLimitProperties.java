package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流量埋点限流配置（对应 application.yml 的 studio.traffic.rate-limit 段）
 *
 * 为什么埋点也要限流：/traffic/report 是**匿名可写**接口，每次请求都 INSERT 一行。
 * 没有限流时，一个脚本循环调用即可在几分钟内把 studio_page_visit 写成几百万行——
 * 后果不只是统计失真，还有表变大后管理端统计查询变慢、磁盘与备份成本上升，
 * 而这些动作不需要任何账号，注册门槛都拦不住。
 *
 * 阈值必须比真实用户行为宽松：埋点是「每次页面跳转一次」，正常人分钟级访问
 * 也就几次到十几次；60 次/分钟足够覆盖连点、前进后退、多标签同时打开的极端情况，
 * 又不至于让脚本「刷」出有意义的数据量。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.traffic.rate-limit")
public class TrafficRateLimitProperties {

    /**
     * 总开关（默认 true）
     *
     * 测试环境必须能关：MockMvc 下所有请求的来源 IP 都是 127.0.0.1，
     * 而 TrafficManageTest 会在同一分钟内连续上报十几次，
     * 不关就会在中途集体 429，那是假失败。限流行为由专项用例覆盖（见 surefire 配置）。
     */
    private Boolean enabled = true;

    /**
     * 每 IP 每分钟的上报上限，默认 60
     *
     * 这个值刻意比真实浏览行为高一个数量级：一次页面跳转只有一次上报，
     * 60 次/分钟只可能是脚本。
     */
    private Integer minuteLimit = 60;

    /**
     * 每 IP 每天的上报上限，默认 3000
     *
     * 分钟窗口挡不住「每分钟 50 次、持续一整天」的慢速刷量，日窗口是第二道闸。
     * 3000 足以覆盖重度读者（一天浏览几百个页面），又能把单 IP 的写入量封在可控范围。
     * 存在 NAT 出口共享 IP 的场景（工作室 / 校园网），因此不宜再低。
     */
    private Integer dailyLimit = 3000;
}
