package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAddRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAssignRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemQueryRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemStatusRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioBoardItem;
import com.bhu.runshistudioweb.model.vo.board.BoardItemOverviewVO;
import com.bhu.runshistudioweb.model.vo.board.BoardItemVO;

/**
 * 工作室内部事项看板服务
 *
 * <p>权限边界在 Service 里统一判定，Controller 只负责粗粒度登录/角色拦截：
 * 管理员可新建/编辑/下线/指派/改任意状态；在队成员只能看，且只能推进「指派给自己」的事项。
 *
 * @author shaoshing
 */
public interface BoardItemService extends IService<StudioBoardItem> {

    /** 新增事项（仅管理员） */
    Long add(BoardItemAddRequest request);

    /** 编辑事项（仅管理员）；字段为 null 表示不修改 */
    void update(BoardItemUpdateRequest request);

    /** 下线事项（仅管理员，逻辑删除） */
    void delete(Long id);

    /** 指派 / 改派 / 清空负责人（仅管理员） */
    void assign(BoardItemAssignRequest request);

    /**
     * 流转状态：管理员可改任意事项；成员只能改指派给自己的那条。
     * 传了 expectedStatus 时按乐观锁校验，防止两人同时改同一条互相覆盖。
     */
    void changeStatus(BoardItemStatusRequest request);

    /** 管理端分页列表（含负责人筛选与逾期筛选） */
    Page<BoardItemVO> page(BoardItemQueryRequest request);

    /** 成员看板：三列数据 + 顶部统计，一次请求返回 */
    BoardItemOverviewVO board();
}
