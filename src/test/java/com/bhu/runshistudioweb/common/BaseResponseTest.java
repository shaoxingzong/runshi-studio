package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一响应体与错误码的验收测试
 *
 * author: shaoshing
 *
 * <p>这就是所谓的「契约测试」：不启动 Spring 容器、不连数据库，只锁定对外契约。
 * 以后谁把成功码改成 200、把失败响应的 data 字段去掉，这里会立刻红，不用等到联调才发现。
 *
 * <p>注意：Spring Boot 4 使用 Jackson 3，ObjectMapper 的包名为 {@code tools.jackson.databind}，
 * 写成 Jackson 2 的 {@code com.fasterxml.jackson.databind.ObjectMapper} 会直接编译不过。
 */
class BaseResponseTest {

    // 这里故意用 new 出来的 ObjectMapper：本测试校验的是「响应体的结构约定」，
    // 与 Spring 容器里那个被 JsonConfig 定制过的实例无关（后者的验收在 JsonConfigTest）
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功响应：data 为 null 时 code 仍为 0，不应抛异常")
    void successWithNullData() {
        // 明确允许 data 为 null：查不到列表时返回空集合、无需返回数据时返回 null 都是合法用法
        BaseResponse<String> response = ResultUtils.success(null);
        assertEquals(0, response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("失败响应：data 必须为 null")
    void errorDataIsNull() {
        // 失败时前端只看 code 与 message，data 必须为 null，
        // 否则出现「有数据但 code 非 0」会让前端不知道该信哪个
        assertNull(ResultUtils.error(ErrorCode.PARAMS_ERROR).getData());
        assertNull(ResultUtils.error(40400, "自定义错误").getData());
    }

    @Test
    @DisplayName("失败响应：序列化后 data 字段必须存在（值为 null），不能整个字段消失")
    void errorSerializesDataField() {
        String json = objectMapper.writeValueAsString(ResultUtils.error(ErrorCode.PARAMS_ERROR));
        // 若全局配置了 NON_NULL（或等价策略），data 字段会从 JSON 中消失，
        // 前端写 res.data.xxx 就会报 undefined —— 这里把该行为钉死
        assertTrue(json.contains("\"data\""), "序列化结果必须包含 data 字段：" + json);
        assertTrue(json.contains("40000"), "序列化结果必须包含错误码：" + json);
    }

    @Test
    @DisplayName("错误码号段：成功为 0，客户端错误为 4xxxx，服务端错误为 5xxxx")
    void errorCodeSegments() {
        // 号段是前后端的契约：前端依据首位数字判断「该提示用户」还是「该重试/上报」
        assertEquals(0, ErrorCode.SUCCESS.getCode());
        assertEquals(4, String.valueOf(ErrorCode.PARAMS_ERROR.getCode()).charAt(0) - '0');
        assertEquals(5, String.valueOf(ErrorCode.SYSTEM_ERROR.getCode()).charAt(0) - '0');
    }

    @Test
    @DisplayName("ThrowUtils：条件成立时抛出携带正确错误码的业务异常")
    void throwIfConditionTrue() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ThrowUtils.throwIf(true, ErrorCode.NOT_LOGIN_ERROR));
        // code 与 message 都必须来自枚举，保证「抛异常」与「返回错误码」两条路径的对外表现一致
        assertEquals(40100, ex.getCode());
        assertEquals("未登录", ex.getMessage());
    }

    @Test
    @DisplayName("ThrowUtils：条件不成立时不抛异常")
    void throwIfConditionFalse() {
        // 断言式工具类的核心语义：条件是 false 就必须安静通过，不能有任何副作用
        assertDoesNotThrow(() -> ThrowUtils.throwIf(false, ErrorCode.SYSTEM_ERROR));
    }

    @Test
    @DisplayName("ThrowUtils：自定义 message 可覆盖错误码默认提示")
    void throwIfWithCustomMessage() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR, "仅管理员可操作"));
        // code 仍取枚举（前端逻辑不变），只有给人看的 message 被替换
        assertEquals(40101, ex.getCode());
        assertEquals("仅管理员可操作", ex.getMessage());
    }
}
