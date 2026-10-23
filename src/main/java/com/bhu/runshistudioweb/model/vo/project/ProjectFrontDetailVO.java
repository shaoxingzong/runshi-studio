package com.bhu.runshistudioweb.model.vo.project;

import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

// 项目视图对象（C 端详情使用，游客可获取）
//
// 为什么列表与详情分成两个 VO： content 是 text 大字段
// （Markdown 正文，同时是 RAG 切分的数据源）。列表查询若带上它，
// 每一行都要把整篇正文传回来；而详情一次只查一条。
// 所以列表 VO（ ProjectFrontVO）结构上就不含 content，只有详情才取。
//
// 为什么用继承（ extends ProjectFrontVO）而不是再抄一遍字段：
// 详情的语义就是「列表字段 + content」。用继承把这个关系写进类型系统，
// 将来给列表加展示字段时详情自动跟随，不会出现「列表有、详情忘了加」的不一致。
//
// 同样继承来的还有脱敏边界：父类没有 leaderId / sortOrder / 审计字段，
// 因此详情也不会泄露它们——游客能看到的仍然只是「愿意公开」的那部分。
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectFrontDetailVO extends ProjectFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 项目详情正文（Markdown）
    // 前端按 Markdown 渲染；它同时是 RAG 知识库的切分数据源
    private String content;

    // 参与该项目的成员列表（脱敏， 新增字段）
    //
    // 向后兼容：本次只新增字段，列表与详情原有的字段名、类型、含义都没变，
    // 前端不加处理也能照常渲染（多出来的字段会被忽略）。
    //
    // 队长必然在列表里： studio_project.leader_id 是权威源，
    // 新增/修改项目时由 Service 在同一事务内同步进成员-项目关联表（DESIGN 2.3），
    // 因此官网「项目成员」区域天然包含队长，不需要前端额外拼一个。
    //
    // 用 com.bhu.runshistudioweb.model.vo.member.MemberFrontVO 而不是 MemberVO：后者含 userId / sortOrder
    // 与审计字段，放到匿名接口上就是泄露。
    private List<MemberFrontVO> members;
}
