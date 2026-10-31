package com.bhu.runshistudioweb.model.vo.project;

import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 项目视图对象（C 端详情使用，游客可获取）
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectFrontDetailVO extends ProjectFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目详情正文（Markdown）前端按 Markdown 渲染；它同时是 RAG 知识库的切分数据源 */
    private String content;

    /**
     * 参与该项目的成员列表（脱敏）
     *
     * 用 MemberFrontVO 而不是 MemberVO：后者含 userId / sortOrder 与审计字段，放到匿名接口上就是泄露。
     */
    private List<MemberFrontVO> members;
}
