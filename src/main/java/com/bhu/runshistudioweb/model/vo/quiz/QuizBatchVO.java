package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

// 开批结果（C 端）
//
// 题目整批返回、服务端不保存「本轮是哪几题」，这是本模块最重要的
// 一个简化：题目 VO 不含答案（见 QuizQuestionVO 的类注释），
// 因此整批交给前端持有不存在泄露风险；而服务端只需要在作答时拿到
// batchId 做幂等，不需要知道这一批到底有哪几题。
//
// 代价是「换设备不能续答」——刷题不是考试，重开一轮的成本是 0，
// 同设备刷新由前端的 sessionStorage 兜住。这个取舍是刻意的。
//
// total 与 questions.size() 恒等，单独给一个字段是为了
// 让前端不必写 questions.length（也便于将来做「只返回第一题 + 总数」
// 的按需加载时，字段语义不变）。
@Data
public class QuizBatchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 批次 ID（雪花 19 位，序列化为字符串）：作答与跳过时都要回传
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    // 模式：0-顺序，1-错题重刷，2-随机（前端用于结果页文案与「再来一轮」）
    private Integer mode;

    // 本轮题量
    private Integer total;

    // 本轮题目（不含答案与解析）
    private List<QuizQuestionVO> questions;
}
