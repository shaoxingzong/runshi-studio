package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门户页面访问记录实体（对应 studio_page_visit）
 *
 * 为什么没有逻辑删除与审计列：访问记录只增不改不删，
 * deleted_at / updated_by 对它没有意义，加了只会让每个查询多带条件。
 * （DDL 与表结构注释在 db/user.sql 第 16 节，含隐私口径说明。）
 *
 * 隐私口径：记 visitorId（前端随机 uuid）而不记 IP / UA——
 * UV 去重只需要一个稳定标识，IP 属于个人数据，多存一份多一分泄露面。
 * referrerHost 与 pagePath 都刻意不含 query，避免 token 随埋点外泄。
 */
@Data
@TableName("studio_page_visit")
public class StudioPageVisit implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 页面路径（如 /posts、/projects/123；不含 query） */
    private String pagePath;

    /** 访客标识（前端 localStorage 随机 uuid；UV 去重依据） */
    private String visitorId;

    /** 登录用户 ID（未登录为 NULL，由后端从登录态取，不信前端传的） */
    private Long userId;

    /** 来源域名（外站跳转进来才有值；站内跳转不记） */
    private String referrerHost;

    /** 设备类型：pc / mobile（按 UA 粗分，仅作参考维度） */
    private String deviceType;

    /** 访问时间（插入时由 MyMetaObjectHandler 填充；列无 DB 默认值，少了这个注解会直接 SQL 报错） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 创建人 ID：匿名埋点固定填 0（没有「登录态才有埋点」这回事） */
    private Long createdBy;
}
