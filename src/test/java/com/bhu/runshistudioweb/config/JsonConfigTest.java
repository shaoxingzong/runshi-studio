package com.bhu.runshistudioweb.config;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局 JSON 配置验收测试
 * 关键：断言的是 Spring 容器里的 JsonMapper（即 Spring MVC 实际使用的那个），
 * 而不是 new 出来的实例，否则证明不了配置真的生效。
 */
@SpringBootTest
class JsonConfigTest {

    /** 19 位雪花 ID，超过 JS Number 安全整数上限 2^53-1 */
    private static final long SNOWFLAKE_ID = 1790493804982123456L;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("雪花 ID：Long 必须序列化为带引号的字符串，否则前端精度丢失")
    void longSerializesAsString() {
        SysUser user = new SysUser();
        user.setId(SNOWFLAKE_ID);

        String json = jsonMapper.writeValueAsString(user);

        assertTrue(json.contains("\"id\":\"" + SNOWFLAKE_ID + "\""),
                "Long 未序列化为字符串，前端会精度丢失。实际 JSON：" + json);
        assertFalse(json.contains("\"id\":" + SNOWFLAKE_ID),
                "Long 被输出为裸数字。实际 JSON：" + json);
    }

    @Test
    @DisplayName("雪花 ID：字符串形式的 ID 必须能反序列化回 Long，且值不丢精度")
    void longDeserializesFromString() {
        String json = "{\"id\":\"" + SNOWFLAKE_ID + "\"}";

        SysUser user = jsonMapper.readValue(json, SysUser.class);

        assertNotNull(user.getId());
        assertEquals(SNOWFLAKE_ID, user.getId(), "反序列化后精度丢失");
    }

    @Test
    @DisplayName("时间格式：LocalDateTime 序列化为 yyyy-MM-dd HH:mm:ss（不带 T）")
    void localDateTimeUsesAgreedFormat() {
        SysUser user = new SysUser();
        user.setCreatedAt(LocalDateTime.of(2026, 9, 27, 15, 44, 13));

        String json = jsonMapper.writeValueAsString(user);

        assertTrue(json.contains("\"createdAt\":\"2026-09-27 15:44:13\""),
                "时间格式不符合约定。实际 JSON：" + json);
        assertFalse(json.contains("T15:44:13"),
                "时间仍为 ISO-8601 格式。实际 JSON：" + json);
    }

    @Test
    @DisplayName("时间格式：空格分隔与 ISO-8601 的 T 分隔两种入参都必须能解析")
    void localDateTimeDeserializesBothFormats() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 27, 15, 44, 13);

        SysUser withSpace = jsonMapper.readValue("{\"createdAt\":\"2026-09-27 15:44:13\"}", SysUser.class);
        SysUser withT = jsonMapper.readValue("{\"createdAt\":\"2026-09-27T15:44:13\"}", SysUser.class);

        assertEquals(expected, withSpace.getCreatedAt(), "空格分隔格式解析失败");
        assertEquals(expected, withT.getCreatedAt(), "ISO-8601 格式解析失败");
    }

    @Test
    @DisplayName("null 字段必须保留：BaseResponse.data 为 null 时字段不能消失")
    void nullFieldIsPreserved() {
        BaseResponse<String> response = new BaseResponse<>(0, null, "ok");

        String json = jsonMapper.writeValueAsString(response);

        assertTrue(json.contains("\"data\""),
                "data 字段被省略，前端统一拦截器会读不到该字段。实际 JSON：" + json);
    }
}