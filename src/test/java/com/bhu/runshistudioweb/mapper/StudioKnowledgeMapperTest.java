package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeChunk;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeDoc;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RAG 知识库两张表的 Mapper 冒烟测试
 *
 * author: shaoshing
 *
 * <p>本类验证「表结构 → 实体 → Mapper」这条链路真的通（同步/检索 Service 是 的事），
 * 覆盖四类事实：
 * <ul>
 *     <li>插入：雪花主键回填 + 4 个审计字段自动填充（全局约定的延续）；</li>
 *     <li>查询：按 doc_id 取块并按 chunk_index 升序 —— 检索命中后拼上下文的真实路径；</li>
 *     <li>逻辑删除：查不到行 + deleted_at 落库为 13 位毫秒时间戳（ADR-7 对新表同样生效）；</li>
 *     <li>唯一键 {@code uk_doc_chunk (doc_id, chunk_index, deleted_at)} 的两个方向：
 *     同序号「未删除」块必须撞键失败；而「逻辑删旧块 → 插同序号新块」的重建路径必须成功
 *     —— 这正是唯一键要带 deleted_at 的原因。</li>
 * </ul>
 *
 * <p>两张表没有业务唯一键（主键为雪花 ID），用例之间天然隔离；
 * 除毫秒时间戳取证外全部 @Transactional 自动回滚，无需手工清理。
 */
@SpringBootTest
class StudioKnowledgeMapperTest {

    // 与其它 Mapper 测试一致：@Resource 按名称注入，报错信息更直观
    @Resource
    private StudioKnowledgeDocMapper docMapper;

    @Resource
    private StudioKnowledgeChunkMapper chunkMapper;

    // 毫秒时间戳取证要绕过 ORM 直查落库值
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 构造待插入文档：只填必填字段，审计字段故意不填（要验证的就是「不填也会被自动填充」），
     * sourceId 保持 null —— manual 来源没有指向任何业务数据
     *
     * @return 待插入的文档实体
     */
    private StudioKnowledgeDoc newDoc() {
        StudioKnowledgeDoc doc = new StudioKnowledgeDoc();
        doc.setTitle("冒烟测试文档");
        doc.setSourceType("manual");
        doc.setContentHash("hash-" + System.nanoTime());
        doc.setStatus(0);
        doc.setChunkCount(0);
        return doc;
    }

    /**
     * 构造待插入块
     *
     * @param docId      所属文档 ID（由调用方先插入文档取回）
     * @param chunkIndex 块序号
     * @return 待插入的块实体
     */
    private StudioKnowledgeChunk newChunk(Long docId, int chunkIndex) {
        StudioKnowledgeChunk chunk = new StudioKnowledgeChunk();
        chunk.setDocId(docId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent("第 " + chunkIndex + " 块正文（冒烟测试）");
        return chunk;
    }

    @Test
    @Transactional
    @DisplayName("文档插入：雪花主键回填，4 个审计字段自动填充")
    void docInsertShouldBackfillIdAndFillAuditFields() {
        StudioKnowledgeDoc doc = newDoc();

        int rows = docMapper.insert(doc);

        // 影响行数校验不能省：有些失败表现为「返回 0 但不抛异常」
        assertEquals(1, rows);
        // 雪花 ID 由 MP 生成后回填到实体，后续切块才能立刻拿到 docId
        assertNotNull(doc.getId(), "主键未回填");
        // 这 4 个字段在 DDL 中是 NOT NULL 且无 DB 默认值：没被填充就说明 MetaObjectHandler 失效了
        assertNotNull(doc.getCreatedAt(), "createdAt 未被 MetaObjectHandler 填充");
        assertNotNull(doc.getUpdatedAt(), "updatedAt 未被 MetaObjectHandler 填充");
        assertEquals(0L, doc.getCreatedBy(), "createdBy 未被填充");
        assertEquals(0L, doc.getUpdatedBy(), "updatedBy 未被填充");
    }

    @Test
    @Transactional
    @DisplayName("文档查询：按主键读回字段一致；manual 来源的 sourceId 必须为 NULL")
    void docSelectShouldRoundTripFields() {
        StudioKnowledgeDoc doc = newDoc();
        // 换成非默认值，验证这两列真实往返（否则 0 与「没查到」无法区分）
        doc.setStatus(1);
        doc.setChunkCount(3);
        docMapper.insert(doc);

        StudioKnowledgeDoc loaded = docMapper.selectById(doc.getId());

        assertNotNull(loaded, "插入后按主键查不到文档");
        assertEquals(doc.getTitle(), loaded.getTitle());
        assertEquals("manual", loaded.getSourceType());
        assertNull(loaded.getSourceId(), "manual 来源不应有 sourceId");
        assertEquals(doc.getContentHash(), loaded.getContentHash());
        assertEquals(1, loaded.getStatus());
        assertEquals(3, loaded.getChunkCount());
    }

    @Test
    @Transactional
    @DisplayName("文档逻辑删除：deleteById 后 selectById 查不到（MP 自动追加 deleted_at = 0）")
    void docLogicalDeleteShouldHideRow() {
        StudioKnowledgeDoc doc = newDoc();
        docMapper.insert(doc);
        Long id = doc.getId();

        docMapper.deleteById(id);

        assertNull(docMapper.selectById(id), "逻辑删除后仍能查到文档");
    }

    @Test
    @Transactional
    @DisplayName("块插入与查询：按 doc_id 取块并按 chunk_index 升序返回")
    void chunkSelectShouldReturnOrderedByChunkIndex() {
        StudioKnowledgeDoc doc = newDoc();
        docMapper.insert(doc);

        // 故意乱序插入：顺序必须由 SQL 的 ORDER BY 给出，而不是靠插入顺序碰巧一致
        chunkMapper.insert(newChunk(doc.getId(), 2));
        chunkMapper.insert(newChunk(doc.getId(), 0));
        chunkMapper.insert(newChunk(doc.getId(), 1));

        LambdaQueryWrapper<StudioKnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioKnowledgeChunk::getDocId, doc.getId());
        wrapper.orderByAsc(StudioKnowledgeChunk::getChunkIndex);
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(wrapper);

        assertEquals(3, chunks.size());
        assertEquals(List.of(0, 1, 2),
                chunks.stream().map(StudioKnowledgeChunk::getChunkIndex).toList());
        // 块与文档走同一套审计填充：抽查首块的 createdAt 已被填充
        assertNotNull(chunks.get(0).getCreatedAt(), "chunk createdAt 未被 MetaObjectHandler 填充");
    }

    @Test
    @Transactional
    @DisplayName("块唯一键：同文档同序号的两条「未删除」块，第二条必须撞 uk_doc_chunk")
    void chunkDuplicateLiveIndexShouldBeRejected() {
        StudioKnowledgeDoc doc = newDoc();
        docMapper.insert(doc);
        chunkMapper.insert(newChunk(doc.getId(), 0));

        assertThrows(DuplicateKeyException.class,
                () -> chunkMapper.insert(newChunk(doc.getId(), 0)),
                "唯一索引未生效：同文档同序号插入了两条未删除块");
    }

    @Test
    @Transactional
    @DisplayName("重建索引路径：逻辑删旧块后，可插入同序号新块（唯一键携带 deleted_at）")
    void chunkReinsertAfterLogicalDeleteShouldSucceed() {
        StudioKnowledgeDoc doc = newDoc();
        docMapper.insert(doc);

        StudioKnowledgeChunk oldChunk = newChunk(doc.getId(), 0);
        chunkMapper.insert(oldChunk);

        // 重建第一步：逻辑删除旧块（真实实现是按 docId 批量删，这里用主键等价验证逻辑删除本身）
        chunkMapper.deleteById(oldChunk.getId());
        assertNull(chunkMapper.selectById(oldChunk.getId()), "旧块逻辑删除后仍能查到");

        // 重建第二步：插入同序号新块 —— 唯一键若不带 deleted_at，这一步会撞键
        StudioKnowledgeChunk rebuilt = newChunk(doc.getId(), 0);
        assertEquals(1, chunkMapper.insert(rebuilt));
        assertNotNull(rebuilt.getId());
    }

    @Test
    @DisplayName("逻辑删除落库值：deleted_at 必须是 13 位毫秒时间戳，而非固定值 1")
    void docLogicalDeleteWritesMillisecondTimestamp() {
        StudioKnowledgeDoc doc = newDoc();
        docMapper.insert(doc);
        Long id = doc.getId();
        assertNotNull(id);

        try {
            docMapper.deleteById(id);

            // 绕过 ORM 直接查库：确认 yml 里的 CAST 表达式对新增的两张表同样生效
            Long deletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM studio_knowledge_doc WHERE id = ?", Long.class, id);

            assertNotNull(deletedAt, "deleted_at 为空");
            assertNotEquals(0L, deletedAt, "deleted_at 仍为 0，逻辑删除未生效");
            assertNotEquals(1L, deletedAt,
                    "deleted_at 被写成固定值 1 —— logic-delete-value 仍是静态值，会导致重建 uuid 撞唯一索引");
            // 长度 13 是毫秒时间戳的特征：秒级只有 10 位，写入秒级会导致极端情况下同秒二次删除冲突
            assertEquals(13, String.valueOf(deletedAt).length(),
                    "deleted_at 不是 13 位毫秒时间戳，实际值：" + deletedAt);
        } finally {
            // 本用例未开启 @Transactional（要验证真实落库且不依赖事务实现），因此必须手工清理，
            // 否则残留数据会污染后续运行
            jdbcTemplate.update("DELETE FROM studio_knowledge_chunk WHERE doc_id = ?", id);
            jdbcTemplate.update("DELETE FROM studio_knowledge_doc WHERE id = ?", id);
        }
    }
}