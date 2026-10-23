package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量删除题目请求
 *
 * 为什么单独一个类而不是复用 DeleteRequest：
 * 后者是「单条删除」的通用契约（`id` 字段），批量删除需要 `ids` 列表，
 * 塞进同一个类会让两种语义共用一个字段名，读代码时要靠上下文猜。
 * 项目里 DeleteRequest 的注释也写了「将来要扩展批量删除，
 * 只需把 id 换成 idList」——这里按那个约定单独建一个类，
 * 既保留了单条删除的清晰度，也没有破坏已有契约。
 */
@Data
public class QuizQuestionBatchDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待删除的题目 ID 列表（必填，非空）
     *
     * 逐条走「逻辑删除 + 清理错题条目」，同事务提交；
     * 某条不存在时不中断（返回实际删除条数），因为批量删除的语义是
     * 「把这些都删掉」，其中一条已被别人删掉不该让整批失败。
     */
    @NotNull(message = "题目 id 列表不能为空")
    private List<Long> ids;
}
