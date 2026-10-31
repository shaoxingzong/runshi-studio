package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeDocQueryRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeManualIngestRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeSyncRequest;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSourceVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSyncAllVO;

import java.util.List;

/**
 * 知识库文档服务（RAG 入库与检索的编排中枢）
 *
 * 职责：把「一段正文」变成「1 行文档 + N 行切分块 + N 个向量」，且幂等、可重建、失败可重试。
 *
 * @author shaoshing
 */
public interface KnowledgeDocService {

    /**
     * 手工录入一篇文档：每次都新建，不参与幂等
     *
     * @param request 录入请求：title 与 content 均必填，正文不超过 20000 字；整个对象为 null 时得到 A0401
     * @return 入库结果：docId、标题、切分块数、状态（1 已索引）、skipped 恒为 false
     * @throws BusinessException 参数不合法（A0401）、向量化失败（C0200）、影响行数不为 1（B0002）
     */
    KnowledgeIngestVO ingestManual(KnowledgeManualIngestRequest request);

    /**
     * 把一条业务数据（项目 / 成员 / 证书）同步进知识库：幂等、可重建
     *
     * @param request 同步请求：sourceType ∈ project / member / certificate（大小写敏感），sourceId 必须是正整数；任一不合法得到 A0401
     * @return 入库结果：skipped=true 表示内容未变、什么都没做；rebuilt=true 表示替换了旧块；两者都为 false 表示首次新建
     * @throws BusinessException 参数不合法或传了 manual（A0401）、源数据不存在 （A0402，同时把已有文档标记为失败态）、向量化失败（C0200）
     */
    KnowledgeIngestVO sync(KnowledgeSyncRequest request);

    // 检索

    /**
     * 检索与问题相关的资料，返回「拼好的资料文本 + 溯源列表」
     *
     * @param question 用户提问；为空或纯空白时直接返回空结果，不会抛异常
     * @return 检索结果：无命中时 contextText 是空串、sources 是空列表，两者都不为 null
     * @throws BusinessException 问题向量化失败（C0200）；是否捕获由调用方决定（问答链路会降级）
     */
    RetrievalResult retrieve(String question);

    // 管理端操作

    /**
     * 全量同步：把三类业务数据（未删除的项目 / 成员 / 证书）一次性灌进知识库
     *
     * @return 统计：total / created / rebuilt / skipped / failed，前四项之和恒等于 total；库里没有数据时各字段都是 0
     */
    KnowledgeSyncAllVO syncAll();

    /**
     * 强制重建全部文档的向量（向量库的恢复手段）
     *
     * @return 统计：rebuilt 是成功重建向量的文档数，skipped 是没有可用切分块而跳过的文档数，failed 是失败数；没有文档时三项都是 0
     */
    KnowledgeSyncAllVO reindexAll();

    /**
     * 管理端分页查询文档列表（不含正文）
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页参数），允许为 null，此时按第一页 10 条返回
     * @return 分页结果，记录为 KnowledgeDocVO（不含正文）；无数据时 records 为空列表
     * @throws BusinessException 来源类型取值非法时抛出（A0401）
     */
    Page<KnowledgeDocVO> listDocByPage(KnowledgeDocQueryRequest request);

    /**
     * 删除文档：逻辑删文档 + 同事务级联逻辑删切分块，提交后尽力清理向量
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 恒为 true；删除不成功一律抛异常
     */
    boolean deleteDoc(long id);

    /**
     * 重建一篇文档：回业务源重新读正文，重新切分与向量化
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     */
    KnowledgeIngestVO rebuildDoc(long id);

    /**
     * 检索结果：拼给模型读的资料文本 + 给前端展示的溯源列表
     *
     * @param contextText 拼好的资料文本（带序号、标题与来源标注）；无命中时是空串而不是 null
     * @param sources 溯源列表；无命中时是空列表而不是 null
     */
    record RetrievalResult(String contextText, List<KnowledgeSourceVO> sources) {

        /**
         * 空结果：无命中、或入参为空时返回它，避免调用方到处判 null
         *
         * @return 空结果实例；每次调用都会新建一个，但内容恒定
         */
        public static RetrievalResult empty() {
            return new RetrievalResult("", List.of());
        }

        /**
         * 是否命中了资料
         *
         * @return true 表示至少命中一条资料；false 表示没有命中
         */
        public boolean hit() {
            return sources != null && !sources.isEmpty();
        }
    }
}
