package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO;

/**
 * 统计服务接口
 *
 * 本模块刻意做得极小：只有一个方法、没有入参、没有写操作，服务于官网首页大盘。
 *
 * 三条强制口径：一律排除逻辑删除的数据（由 {@code @TableLogic} 自动追加，
 * 它「看不见」，改统计口径时必须去 SQL 日志确认它还在）；枚举外的脏值忽略不统计、不报错；
 * 空库或某维度为空时返回完整的 key 集合且值为 0。后两条是因为统计接口是首页的锦上添花，
 * 不该因为一条脏数据让整个首页挂掉，而前端图表按固定维度渲染，key 缺失会导致渲染报错。
 *
 * 刻意不含成员人数：本接口挂在匿名路径上，对外暴露「团队有多少人」与
 * 「团队成员不对外展示」的规则冲突。管理端需要时应另开一个要求 admin 的接口。
 *
 * @author shaoshing
 */
public interface StatisticService {

    /**
     * 查询官网首页大盘数据
     *
     * 首页必须任何情况下都能渲染，因此「统计不出来」一律收敛成 0 而不是异常——
     * 与业务接口刚好相反：业务接口查不到数据要报 A0402，首页没有数据就是「还没有数据」。
     *
     * 坑：两个分布的 key 由枚举决定而非库里的实际取值，「枚举有、库里没有」的 key 会以 0 出现，
     * 脏值被静默丢弃。计数用 Integer 不用 Long——全局把 Long 序列化成字符串，
     * 前端拿到的 "12" 会让排序与加法按字典序算。
     *
     * @return 大盘统计，永不为 null；空库时返回全 0 且分布 key 完整（顺序与枚举一致）
     */
    StatisticOverviewVO getOverview();
}
