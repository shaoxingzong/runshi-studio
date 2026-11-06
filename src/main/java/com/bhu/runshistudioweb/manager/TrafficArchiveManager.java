package com.bhu.runshistudioweb.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 流量访问记录归档（清理过期行）
 *
 * 为什么需要它：studio_page_visit 只增不改不删，一天正常几百到几千行，
 * 一年就是几十万行。表越大，「近 30 天趋势」「热门页面」和备份都越慢，
 * 而这些历史数据对运营决策的价值随时间快速衰减。
 *
 * 设计取舍：
 *   - 用「分批 DELETE」而不是一次删完：一次删除百万行会长时间锁表，
 *     期间埋点写入排队、管理端查询变慢。每批 5000 行，批间不留 sleep——
 *     清理放在凌晨 3:30，与业务低峰重叠，短时压力可以接受；
 *   - 保留天数可配（studio.traffic.retention-days），设 0 或负数表示不做清理，
 *     便于「先观察再开启」；
 *   - 删除条件只依赖 created_at，因此必须有 idx_created_at 索引；
 *     否则每天凌晨都会做一次全表扫描。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class TrafficArchiveManager {

    /** 单批删除行数：够小才不会长时间锁表，够大才不会产生几千次往返 */
    private static final int BATCH_SIZE = 5000;

    /** 归档执行用的模板：直接 DELETE 走 SQL，不经过 MyBatis-Plus（逻辑删除字段在这里没有意义） */
    private final JdbcTemplate jdbcTemplate;

    /** 保留天数，<= 0 表示关闭清理 */
    private final int retentionDays;

    public TrafficArchiveManager(JdbcTemplate jdbcTemplate,
                                 @Value("${studio.traffic.retention-days:180}") int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    /**
     * 每天 03:30 清理过期访问记录
     *
     * cron 固定写在注解里而不是配置项：清理时间不影响业务语义，
     * 多一个配置项只会多一个「忘了配导致任务没跑」的可能。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void archive() {
        if (retentionDays <= 0) {
            log.debug("流量归档已关闭（retention-days={}），跳过本轮", retentionDays);
            return;
        }

        LocalDateTime cutoff = LocalDate.now().minusDays(retentionDays).atStartOfDay();
        long totalDeleted = 0;
        int deleted;
        do {
            // LIMIT 拼常量：编译期固定值，不来自用户输入，没有注入面；
            // MySQL 支持 DELETE ... LIMIT，分批删才能避免长事务与长时间锁表
            deleted = jdbcTemplate.update(
                    "DELETE FROM studio_page_visit WHERE created_at < ? LIMIT " + BATCH_SIZE, cutoff);
            totalDeleted += deleted;
        } while (deleted == BATCH_SIZE);

        if (totalDeleted > 0) {
            log.info("流量归档完成 | 截止时间={} | 保留天数={} | 删除行数={}",
                    cutoff, retentionDays, totalDeleted);
        }
    }
}
