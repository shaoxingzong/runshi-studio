package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 审计字段填充器的行为验收（纯单元测试，不启动 Spring、不连数据库）
 *
 * @author shaoshing
 */
class MetaObjectHandlerTest {

    private final MyMetaObjectHandler handler = new MyMetaObjectHandler();

    /** 手工初始化实体元信息 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StudioPost.class);
    }

    @Test
    @DisplayName("无登录上下文：审计列回退为 0，时间列照常填充，且不抛异常")
    void insertFillFallsBackToSystemWhenNoContext() {
        StudioPost post = new StudioPost();
        MetaObject metaObject = SystemMetaObject.forObject(post);

        assertDoesNotThrow(() -> handler.insertFill(metaObject),
                "定时任务等无请求上下文的线程不能因为取不到登录人就抛异常");

        assertEquals(0L, post.getCreatedBy(), "没有登录人时应记为系统（0），而不是留下 null");
        assertEquals(0L, post.getUpdatedBy());
        assertNotNull(post.getCreatedAt(), "创建时间是 NOT NULL 列，必须被填充");
        assertNotNull(post.getUpdatedAt(), "新数据的 updated_at 也必须有值，否则按时间排序会垫底");
    }

    @Test
    @DisplayName("业务显式赋值的创建人不会被覆盖")
    void insertFillKeepsExplicitCreator() {
        StudioPost post = new StudioPost();
        post.setCreatedBy(42L);

        handler.insertFill(SystemMetaObject.forObject(post));

        assertEquals(42L, post.getCreatedBy(), "数据迁移等场景显式写入的创建人必须保留");
    }

    @Test
    @DisplayName("更新只填 updated_by / updated_at，绝不触碰 created_by")
    void updateFillNeverTouchesCreator() {
        StudioPost post = new StudioPost();
        post.setCreatedBy(7L);
        post.setUpdatedBy(0L);

        handler.updateFill(SystemMetaObject.forObject(post));

        assertEquals(7L, post.getCreatedBy(), "创建人被改写会让追溯链断在最后一次修改上");
        assertEquals(0L, post.getUpdatedBy(), "无登录上下文时更新人同样回退为系统（0）");
        assertNotNull(post.getUpdatedAt());
    }
}
