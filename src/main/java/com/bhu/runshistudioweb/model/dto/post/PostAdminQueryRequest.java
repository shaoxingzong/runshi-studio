package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：帖子全量列表查询请求
 *
 * 与待审队列的区别：待审队列只有「待审」一种状态、也不需要筛选，
 * 本类服务于「帖子管理」页——三种状态都要能查，还要能按标题、作者定位到具体某篇。
 *
 * status 精确匹配，titleKeyword、authorKeyword 模糊匹配；字段为 null 表示该条件不参与筛选。
 * 分页非法值不在这里报错（GET 传参失败会变成 B0001），由 Service 兜底纠正。
 *
 * @author shaoshing
 */
@Data
public class PostAdminQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审核状态（精确匹配，选填）：0-待审, 1-已通过, 2-已驳回
     *
     * 不传（null）表示查全部状态；取值合法性由 Service 用 AuditStatusEnum 校验。
     */
    private Integer status;

    /** 标题关键词（模糊查询，选填；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String titleKeyword;

    /**
     * 作者关键词（模糊查询，选填）
     *
     * 匹配 sys_user 的昵称与账号：管理员未必拼得出完整昵称，账号名也要能搜。
     */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String authorKeyword;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛到上限（防止一次拉全表） */
    private Long pageSize = 10L;
}
