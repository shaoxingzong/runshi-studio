package com.bhu.runshistudioweb.model.dto.post;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：帖子全量列表查询请求
 *
 * 与待审队列的区别：待审队列（ /admin/post/pending）只有「待审」一种状态、
 * 也不需要筛选——管理员面对的就是一条先进先出的队列。本类服务于「帖子管理」页：
 * 三种状态都要能查，还要能按标题、作者定位到具体某篇。
 *
 * GET + 对象绑定，与 UserQueryRequest 等既有查询 DTO 同一惯例
 * （分页字段由本类自己声明，Service 转成 MyBatis-Plus 的 Page）。
 *
 * 字段语义：
 * - status 精确匹配（不传 = 全部状态）；
 * - titleKeyword、authorKeyword 模糊匹配；
 * - 字段为 null 表示「该条件不参与筛选」，不是「查 null 值」。
 *
 * 分页与筛选的非法值不在这里报错：这些参数用 GET 传递，校验失败抛的是
 * BindException，会落到全局异常处理器的兜底分支变成 B0001，体验很差；
 * 且「页码传错」没必要让整个查询失败。因此一律由 Service 兜底纠正
 * （页码 < 1 视为 1、pageSize 收敛到上限）。
 */
@Data
public class PostAdminQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审核状态（精确匹配，选填）：0-待审, 1-已通过, 2-已驳回
     *
     * 不传（null）表示查全部状态；取值合法性由 Service 用
     * com.bhu.runshistudioweb.model.enums.AuditStatusEnum 校验。
     */
    private Integer status;

    /** 标题关键词（模糊查询，选填） */
    private String titleKeyword;

    /**
     * 作者关键词（模糊查询，选填）
     *
     * 匹配 sys_user 的昵称与账号（ user_name / user_account）：
     * 管理员记得住的多是「谁谁谁发的」，未必拼得出完整昵称，账号名也要能搜。
     */
    private String authorKeyword;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛到上限（防止一次拉全表） */
    private Long pageSize = 10L;
}
