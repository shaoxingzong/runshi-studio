package com.bhu.runshistudioweb.model.vo.board;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 成员看板聚合视图：三列数据 + 顶部统计
 *
 * <p>一次请求返回完整看板，避免前端按状态发三次请求。
 *
 * @author shaoshing
 */
@Data
public class BoardItemOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待办列 */
    private List<BoardItemVO> todo = new ArrayList<>();

    /** 进行中列 */
    private List<BoardItemVO> doing = new ArrayList<>();

    /** 已完成列（只保留最近的若干条，避免看板被历史淹没） */
    private List<BoardItemVO> done = new ArrayList<>();

    /** 未完成总数（待办 + 进行中） */
    private Integer pendingCount;

    /** 逾期未完成数 */
    private Integer overdueCount;

    /** 指派给当前登录人且未完成的数量 */
    private Integer myPendingCount;

    /** 已完成总数（含未展示在 done 列里的历史） */
    private Integer doneCount;
}
