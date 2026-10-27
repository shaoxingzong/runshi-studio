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
 * 两个入库入口不能合并：ingestManual 是手工录入，没有业务主键，每次新建、不幂等
 * （强行用标题去重是错的，两篇同名文档完全合法）；sync 是业务来源同步，靠
 * (sourceType, sourceId) 定位文档，再比对内容哈希决定跳过还是重建。
 *
 * 事务纪律：外部 HTTP（向量化）绝不能包在数据库事务里，否则连接会被十几秒的调用占住；
 * 而「写文档行 + 逻辑删旧块 + 插入新块」必须同事务，否则会出现旧块已删、新块没插完的空窗，
 * 那段时间这篇文档检索不到。
 *
 * @author shaoshing
 */
public interface KnowledgeDocService {

    /**
     * 手工录入一篇文档：每次都新建，不参与幂等
     *
     * 手工录入的正文不对应任何业务数据，无法判断「这次提交和上次是不是同一篇」，
     * 因此把「要不要重复录入」的判断交给人。
     *
     * 坑：正文长度上限有两道校验（DTO 的 {@code @Size} 与 Service 常量），缺一不可——
     * 注解只在 Web 入口生效。纯空格正文必须在 trim 之后判空，否则会切出一个全空白的块，
     * 白白产生一次向量化费用。
     *
     * @param request 录入请求：title 与 content 均必填，正文不超过 20000 字；
     *                整个对象为 null 时得到 A0401
     * @return 入库结果：docId、标题、切分块数、状态（1 已索引）、skipped 恒为 false
     * @throws BusinessException 参数不合法（A0401）、向量化失败（C0200）、影响行数不为 1（B0002）
     */
    KnowledgeIngestVO ingestManual(KnowledgeManualIngestRequest request);

    /**
     * 把一条业务数据（项目 / 成员 / 证书）同步进知识库：幂等、可重建
     *
     * 用 (sourceType, sourceId) 定位文档，再比对正文的 SHA-256 摘要：哈希没变且状态为
     * 「已索引」时直接返回 skipped=true，不调 Embedding、不写库——Embedding 按文本量计费。
     *
     * 坑：幂等判断不能只比哈希，必须同时要求「状态是已索引」。失败的文档不写入新哈希，
     * 于是源恢复且内容未变时会命中「哈希相同 → 跳过」，状态永远停在失败态。
     * 另外不能「先删旧块再向量化」，那样一次 Embedding 故障就让文档彻底查不到。
     *
     * @param request 同步请求：sourceType ∈ project / member / certificate（大小写敏感），
     *                sourceId 必须是正整数；任一不合法得到 A0401
     * @return 入库结果：skipped=true 表示内容未变、什么都没做；rebuilt=true 表示替换了旧块；
     *         两者都为 false 表示首次新建
     * @throws BusinessException 参数不合法或传了 manual（A0401）、源数据不存在
     *         （A0402，同时把已有文档标记为失败态）、向量化失败（C0200）
     */
    KnowledgeIngestVO sync(KnowledgeSyncRequest request);

    // ==================== 检索 ====================

    /**
     * 检索与问题相关的资料，返回「拼好的资料文本 + 溯源列表」
     *
     * 这是内部编排方法，失败时抛异常而不自己吞掉：「检索失败能不能继续聊天」是调用方的策略。
     * 固定只走 2 条 SQL（按向量 ID 批量回查切分块、按文档 ID 批量回查文档），
     * 命中 5 条也只查这两次。
     *
     * 坑：回查为空必须跳过而不是报错。向量库清理是尽力而为的，
     * 「命中一条向量、回查却查不到块」是正常现象，一条脏数据不该让整次提问失败。
     * 组装时必须按向量检索给出的命中顺序遍历，模型对「最相关资料放最前」很敏感。
     *
     * @param question 用户提问；为空或纯空白时直接返回空结果，不会抛异常
     * @return 检索结果：无命中时 contextText 是空串、sources 是空列表，两者都不为 null
     * @throws BusinessException 问题向量化失败（C0200）；是否捕获由调用方决定（问答链路会降级）
     */
    RetrievalResult retrieve(String question);

    // ==================== 管理端操作 ====================

    /**
     * 全量同步：把三类业务数据（未删除的项目 / 成员 / 证书）一次性灌进知识库
     *
     * 本质是「N 次幂等 sync 的循环」，可以放心重跑：内容没变的会被跳过，第二遍代价接近 0。
     * 刻意不包进一个大事务——每条 sync 自己是一个事务，单条失败只回滚那一条，
     * 否则「50 条成功、1 条失败」会变成 51 条全回滚。
     *
     * 坑：本方法返回统计而不抛异常，「全部失败」也是 HTTP 200 加一份 failed=N 的统计，
     * 调用方必须看 failed 字段，不能只看「接口没报错」。
     *
     * @return 统计：total / created / rebuilt / skipped / failed，前四项之和恒等于 total；
     *         库里没有数据时各字段都是 0
     */
    KnowledgeSyncAllVO syncAll();

    /**
     * 强制重建全部文档的向量（向量库的恢复手段）
     *
     * 向量化的输入本来就是切分块表里已有的文本，所以不需要读业务源、不需要重新切分。
     * 它对「手工录入」与「业务来源同步」两类文档一视同仁——手工录入没有业务源，
     * 靠 syncAll 恢复不了。
     *
     * 坑：不能指望 syncAll 替代它。向量库是进程内内存，应用重启后向量全没了，
     * 而哈希确实没变，syncAll 会全部 skipped——看起来接口成功、检索却始终为空。
     *
     * @return 统计：rebuilt 是成功重建向量的文档数，skipped 是没有可用切分块而跳过的文档数，
     *         failed 是失败数；没有文档时三项都是 0
     */
    KnowledgeSyncAllVO reindexAll();

    /**
     * 管理端分页查询文档列表（不含正文）
     *
     * 正文不在文档行里（已切分进块表），拼回来意味着一页 10 条要读几百行块。
     * 本接口只回答「有哪些文档、各自什么状态」，管理员据此决定删除还是重建。
     *
     * 坑：来源类型是闭集，传了约定外的值必须报 A0401 而不是静默返回空列表，
     * 否则管理员会以为「库里没有这类文档」。条件构造里的「是否拼接」开关必须与参数判空成对出现。
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页参数），
     *                允许为 null，此时按第一页 10 条返回
     * @return 分页结果，记录为 KnowledgeDocVO（不含正文）；无数据时 records 为空列表
     * @throws BusinessException 来源类型取值非法时抛出（A0401）
     */
    Page<KnowledgeDocVO> listDocByPage(KnowledgeDocQueryRequest request);

    /**
     * 删除文档：逻辑删文档 + 同事务级联逻辑删切分块，提交后尽力清理向量
     *
     * 只删文档会留下「块还在、文档没了」的孤儿行。向量清理放在事务提交之后，
     * 因为它是内存操作、无法回滚。不清理的话悬挂命中会长期占用 topK 名额，
     * 表现为「检索结果变少、相关性下降」且没有任何报错。
     *
     * 坑：向量 ID 必须在删除前取出。块被逻辑删除后查询会自动过滤，
     * 若写成「先删库、再查向量 ID」，清理动作会静默空转。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 恒为 true；删除不成功一律抛异常
     * @throws BusinessException 参数非法（A0401）、文档不存在或已删除（A0402）、影响行数不为 1（B0002）
     */
    boolean deleteDoc(long id);

    /**
     * 重建一篇文档：回业务源重新读正文，重新切分与向量化
     *
     * 必须回业务源读，不能从切分块拼回正文——拼回来会丢失原切分边界，
     * 相当于用二手文本重新入库。手工录入的文档没有业务源，明确拒绝并引导到「删除后重新录入」。
     *
     * 坑：不要以为「重建」一定会真的重建。它幂等：源内容与库里一致时返回 skipped=true，
     * 管理员点了一次却什么都没发生——这是正确行为，前端需要如实展示 skipped。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 入库结果：skipped=true 表示内容未变、本次零 Embedding 调用；rebuilt=true 表示旧块已被替换
     * @throws BusinessException 参数非法或文档不存在（A0401 / A0402）、
     *         manual 来源不支持重建（A0401）、源数据已删除（A0402）
     */
    KnowledgeIngestVO rebuildDoc(long id);

    /**
     * 检索结果：拼给模型读的资料文本 + 给前端展示的溯源列表
     *
     * 两个字段的消费方完全不同：contextText 只给模型读、不对外返回（可能很长），
     * sources 才给前端展示。把 contextText 也塞进响应，一次回答会平白多出几 KB 正文。
     *
     * @param contextText 拼好的资料文本（带序号、标题与来源标注）；无命中时是空串而不是 null
     * @param sources 溯源列表；无命中时是空列表而不是 null
     */
    record RetrievalResult(String contextText, List<KnowledgeSourceVO> sources) {

        /**
         * 空结果：无命中、或入参为空时返回它，避免调用方到处判 null
         *
         * 用 List.of() 顺带获得不可变语义，谁也别想往这个共享的空结果里塞元素。
         * 显式写 public 不能省：嵌套在接口里的 record 体内成员默认包级可见，
         * 跨包的实现类调用它会编译失败。
         *
         * @return 空结果实例；每次调用都会新建一个，但内容恒定
         */
        public static RetrievalResult empty() {
            return new RetrievalResult("", List.of());
        }

        /**
         * 是否命中了资料
         *
         * 判空与判空的判断收在这里，调用点就只剩一个 if。
         * 不要用 contextText 是否为空来判断命中，sources 才是权威依据。
         *
         * @return true 表示至少命中一条资料；false 表示没有命中
         */
        public boolean hit() {
            return sources != null && !sources.isEmpty();
        }
    }
}
