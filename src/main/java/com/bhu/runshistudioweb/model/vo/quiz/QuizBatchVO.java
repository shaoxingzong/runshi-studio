package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 开批结果（C 端）
 *
 * 代价是「换设备不能续答」——刷题不是考试，重开一轮成本为 0，同设备刷新由前端的 sessionStorage 兜住。
 *
 * @author shaoshing
 */
@Data
public class QuizBatchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 批次 ID（雪花 19 位，序列化为字符串）：作答与跳过时都要回传 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    /** 模式：0-顺序，1-错题重刷，2-随机（前端用于结果页文案与「再来一轮」） */
    private Integer mode;

    /** 本轮题量 */
    private Integer total;

    /** 本轮题目（不含答案与解析） */
    private List<QuizQuestionVO> questions;
}
