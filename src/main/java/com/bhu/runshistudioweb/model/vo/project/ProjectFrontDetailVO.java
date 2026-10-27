package com.bhu.runshistudioweb.model.vo.project;

import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 项目视图对象（C 端详情使用，游客可获取）
 *
 * 列表与详情分成两个 VO：content 是 text 大字段（Markdown 正文，同时是 RAG 切分的数据源），
 * 列表若带上它每一行都要传回整篇正文，而详情一次只查一条。所以列表 VO（ProjectFrontVO）结构上就不含 content。
 *
 * 用继承而不是再抄一遍字段：详情的语义就是「列表字段 + content」，继承把这个关系写进类型系统，
 * 将来给列表加展示字段时详情自动跟随。同样继承来的还有脱敏边界：父类没有 leaderId / sortOrder / 审计字段。
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectFrontDetailVO extends ProjectFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目详情正文（Markdown）
     * 前端按 Markdown 渲染；它同时是 RAG 知识库的切分数据源
     */
    private String content;

    /**
     * 参与该项目的成员列表（脱敏）
     *
     * 队长必然在列表里：studio_project.leader_id 是权威源，新增/修改项目时由 Service 在同一事务内
     * 同步进成员-项目关联表，因此官网「项目成员」区域天然包含队长，不需要前端额外拼一个。
     *
     * 用 MemberFrontVO 而不是 MemberVO：后者含 userId / sortOrder 与审计字段，放到匿名接口上就是泄露。
     */
    private List<MemberFrontVO> members;
}
