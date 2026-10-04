package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeDocQueryRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeManualIngestRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeSyncRequest;
import com.bhu.runshistudioweb.model.vo.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeSourceVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeSyncAllVO;

import java.util.List;

/**
 * 知识库文档服务（RAG 的编排中枢）
 *
 * author: shaoshing
 *
 * <p>四个能力分四期落地，全部收在本接口里（读本类时按这个分层找方法）：
 * <ul>
 *     <li><b>入库</b>：{@link #ingestManual}、{@link #sync}——
 *     把「一段正文」变成「1 行 doc + N 行 chunk + N 个向量」；</li>
 *     <li><b>检索</b>：{@link #retrieve}——供 AI 问答链路调用，
 *     返回「拼好的资料文本 + 溯源列表」；</li>
 *     <li><b>管理端操作</b>：{@link #syncAll}、{@link #listDocByPage}、
 *     {@link #deleteDoc}、{@link #rebuildDoc}；</li>
 *     <li><b>向量重建</b>：{@link #reindexAll}——应用重启后的恢复手段。</li>
 * </ul>
 *
 * <p>一句话职责：把「一段正文」变成
 * 「1 行 doc + N 行 chunk + N 个向量」，并且做到<b>幂等可重建、失败可重试</b>。
 *
 * <p><b>两个入口的差异（不要合并）</b>：
 * <ul>
 *     <li>{@link #ingestManual}：手工录入，<b>每次新建、不幂等</b>——
 *     它没有业务主键，拿什么做去重？</li>
 *     <li>{@link #sync}：业务来源同步，<b>幂等</b>——
 *     靠 {@code (sourceType, sourceId)} 定位文档，再比对内容哈希决定"跳过"还是"重建"。</li>
 * </ul>
 *
 * <p><b>事务边界（本模块最重要的一条）</b>：
 * 外部 HTTP（向量化）<b>绝不能</b>包在数据库事务里；
 * 「doc 行 + 旧块逻辑删 + 新块插入」三处写必须<b>在同一个事务</b>内。
 * 详见实现类的流程图注释。
 */
public interface KnowledgeDocService {

    /**
     * 手工录入一篇文档（每次都新建）
     *
     * @param request 录入请求（title、content 必填）
     * @return 入库结果（status=1 表示已索引）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     *         向量化失败（C0200）时抛出
     */
    KnowledgeIngestVO ingestManual(KnowledgeManualIngestRequest request);

    /**
     * 同步某条业务数据到知识库（幂等、可重建）
     *
     * <p>内容哈希未变<b>且文档已索引</b>时直接返回 {@code skipped=true}，
     * <b>不调用 Embedding、不写库</b>；内容变了才重建（旧块逻辑删、新块插入、旧向量清理）。
     * 状态必须是「已索引」：失败态（status=2）的文档即使哈希相同也要重跑一次，
     * 否则它会永远停在失败态（失败不写 hash，重试就会被判定为"没变"）。
     *
     * @param request 同步请求（sourceType ∈ project/member/certificate，sourceId 必填）
     * @return 入库结果（skipped / rebuilt 说明这次到底做了什么）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法或传了 manual（A0401）、
     *         源数据不存在（A0402，同时把已有文档标记 status=2）、向量化失败（C0200）时抛出
     */
    KnowledgeIngestVO sync(KnowledgeSyncRequest request);

    // ==================== 检索 ====================

    /**
     * 检索与问题相关的资料，返回「拼好的资料文本 + 溯源列表」
     *
     * <p><b>定位：这是内部编排方法，不是对外接口</b>。它由 AI 问答链路调用，
     * 因此失败时会抛出（由调用方决定是否降级），而不是自己吞掉异常——
     * 「检索失败能不能继续聊天」是问答链路的策略，不该写死在这里。
     *
     * <p><b>检索三步，恒定 2 条 SQL（零 N+1）</b>：
     * <ol>
     *     <li>向量检索（{@code KnowledgeBaseManager#search}，纯内存、无 SQL）；</li>
     *     <li>按 {@code embedding_id IN (...)} <b>一条</b> SQL 批量回查命中的块；</li>
     *     <li>按 {@code doc_id IN (...)} <b>一条</b> SQL 批量回查这些块所属的文档。</li>
     * </ol>
     * 与「会话列表的 messageCount 必须批量」是同一条纪律：命中 N 条也只查两次，
     * 绝不在循环里逐条回查。
     *
     * <p><b>反查为空即跳过该条（悬挂容忍）</b>：块被逻辑删除、或它所属文档被逻辑删除时，
     * 向量库里可能还残留对应条目（清理是 best-effort），于是反查会查不到。
     * 这类条目<b>静默跳过</b>而不是报错——一条脏数据不该让整次提问失败（R2 的收口）。
     *
     * @param question 用户提问（允许为空，返回空结果）
     * @return 检索结果；无命中时 contextText 为空串、sources 为空列表（不为 null）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 向量化失败（C0200）时抛出
     */
    RetrievalResult retrieve(String question);

    // ==================== 管理端操作 ====================

    /**
     * 全量同步：把三类业务数据（未删除的 project / member / certificate）一次性入库
     *
     * <p><b>它本质就是「N 次幂等 sync 的循环」</b>，因此：
     * <ul>
     *     <li><b>可重入</b>：内容没变的会被幂等跳过（零 Embedding、零写库），
     *     跑第二遍的代价接近 0；</li>
     *     <li><b>不包进一个大事务</b>：每条 sync 自己是一个事务（沿用 {@code ingest} 的
     *     {@code TransactionTemplate}），单条失败只回滚那一条，其余继续；
     *     而向量化（HTTP）本来就在事务外，包大事务只会长时间占着连接；</li>
     *     <li><b>单条失败不中断</b>：计入 {@code failed} 并记日志，接口照常返回统计。</li>
     * </ul>
     *
     * <p><b>为什么不做「批量向量化」优化</b>：一次 sync-all 的耗时主要在 Embedding HTTP
     * （每条十几秒量级）。理论上可以把所有块攒成一批请求，但那要重写
     * 「块 ↔ embeddingId 一一对应」的装配逻辑，而换来的只是请求数变少——
     * 计费是按文本量而非请求数。收益与风险不成比例，本期不做。
     *
     * @return 统计（created + rebuilt + skipped + failed == total）
     */
    KnowledgeSyncAllVO syncAll();

    /**
     * 强制重建全部文档的向量（<b>观察项 e：向量库恢复</b>，）
     *
     * <p><b>核心洞察：恢复向量不需要读业务源、也不需要重新切分</b>——
     * 向量化的输入本来就是 {@code studio_knowledge_chunk} 里已有的文本。
     * 因此「重新嵌入现有 chunk」就够了，而且对 {@code manual} 与 {@code source} 类文档
     * <b>一视同仁</b>（manual 类文档没有业务源，靠 sync 是恢复不了的，
     * 只有这条路径能救回它的向量）。
     *
     * <p><b>什么时候需要它</b>：本期向量库是进程内内存（场景 I），
     * <b>应用每次重启，库里的 chunk 行还在但向量全没了</b>。
     * 此时 {@code sync-all} 会因为内容哈希未变而全部 skipped（什么都不会做），
     * 检索命中 0 条——必须调本接口才能恢复。
     *
     * <p>与 {@link #syncAll()} 同样的风格：逐文档独立事务、单条失败不中断、
     * 返回统计（本方法复用 {@link KnowledgeSyncAllVO}，字段映射见实现注释）。
     *
     * @return 统计：{@code rebuilt} = 成功重建向量的文档数，
     *         {@code skipped} = 没有可用块而跳过的文档数，{@code failed} = 失败数
     */
    KnowledgeSyncAllVO reindexAll();

    /**
     * 管理端分页查询文档列表
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页），允许为 null
     * @return 分页结果，记录为 {@link KnowledgeDocVO}（<b>不含正文</b>）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 来源类型取值非法时抛出（A0401）
     */
    Page<KnowledgeDocVO> listDocByPage(KnowledgeDocQueryRequest request);

    /**
     * 删除文档：逻辑删 doc + <b>同事务</b>级联逻辑删 chunk，提交后 best-effort 清向量
     *
     * @param id 文档 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（A0401）、
     *         文档不存在或已删除（A0402）时抛出
     */
    boolean deleteDoc(long id);

    /**
     * 重建一篇文档（回到业务源重新读一遍正文）
     *
     * <p><b>手工录入（{@code manual}）的文档会被拒绝</b>：它没有可回读的业务源，
     * 从 chunk 拼回正文会丢失原切分意图，因此明确报错并引导到「删除后重新录入」，
     * 而不是静默拼接。
     *
     * <p>内容没变时返回 {@code skipped=true}（幂等），此时没有产生任何 Embedding 调用。
     *
     * @param id 文档 ID
     * @return 入库结果
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或文档不存在（A0401/A0402）、
     *         manual 来源不支持重建（A0401）、源数据已删除（A0402）时抛出
     */
    KnowledgeIngestVO rebuildDoc(long id);

    /**
     * 检索结果（资料文本 + 溯源列表）
     *
     * <p>定义为接口内的嵌套 record 而不是单独文件：它只在本接口的检索场景里出现，
     * 单独建文件反而增加一层跳转，且容易被当成对外 VO 误用。
     *
     * @param contextText 拼好的资料文本（供模型阅读，<b>不对外返回</b>）；无命中时为空串
     * @param sources     溯源列表（供前端展示）；无命中时为空列表
     */
    record RetrievalResult(String contextText, List<KnowledgeSourceVO> sources) {

        /**
         * 空结果：无命中、或入参为空时返回它，避免到处判 null
         *
         * <p>显式写 {@code public}：嵌套在接口里的 record 默认 public，
         * 但 record <b>体内</b>的成员仍是「不加修饰符即包级可见」，
         * 跨包的实现类会访问不到（编译期才发现，很坑）。
         */
        public static RetrievalResult empty() {
            return new RetrievalResult("", List.of());
        }

        /** 是否命中了资料 */
        public boolean hit() {
            return sources != null && !sources.isEmpty();
        }
    }
}
