package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

// 置顶 / 取消置顶请求
//
// 照 DeleteRequest 的写法：单个 id + 一个要改的状态值，不搞批量——
// 置顶是逐篇的运营决策（「这篇放最上面」），一次操作多篇没有产品意义，
// 将来真需要批量也应设计成带排序权重的接口，而不是复用本类。
//
// pinned 用 Integer 而不是 Boolean：与 studio_post.pinned
// 的 tinyint 列、实体字段 StudioPost#pinned 三方保持一致，
// 避免「布尔 ↔ 0/1」在契约上多一次隐式转换。
@Data
public class PinPostRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    // 目标帖子 ID（必填）
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 必须为正整数")
    private Long id;

    // 是否置顶（必填）：1-置顶, 0-取消置顶
    //
    // 只接受 0 / 1 两个值，Service 里显式校验——
    // 枚举一加取值注解就过期，与项目其它模块的枚举校验方式一致。
    @NotNull(message = "置顶标记不能为空")
    private Integer pinned;
}
