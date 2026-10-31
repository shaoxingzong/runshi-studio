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
 * @author shaoshing
 */
class BaseResponseTest {

    /** 这里故意用 new 出来的 ObjectMapper：本测试校验的是「响应体的结构约定」，与 Spring 容器里那个被 JsonConfig 定制过的实例无关（后者的验收在 JsonConfigTest） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功响应：data 为 null 时 code 仍为 00000，不应抛异常")
    void successWithNullData() {
        // 明确允许 data 为 null：查不到列表时返回空集合、无需返回数据时返回 null 都是合法用法
        BaseResponse<String> response = ResultUtils.success(null);
        assertEquals("00000", response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("失败响应：data 必须为 null")
    void errorDataIsNull() {
        assertNull(ResultUtils.error(ErrorCode.PARAMS_ERROR).getData());
        // 自定义状态码路径：现在传的是 5 位字符串（这里用 NOT_FOUND 的码验证「不走枚举也 OK」）
        assertNull(ResultUtils.error("A0402", "自定义错误").getData());
    }

    @Test
    @DisplayName("失败响应：序列化后 data 字段必须存在（值为 null），不能整个字段消失")
    void errorSerializesDataField() {
        String json = objectMapper.writeValueAsString(ResultUtils.error(ErrorCode.PARAMS_ERROR));
        assertTrue(json.contains("\"data\""), "序列化结果必须包含 data 字段：" + json);
        assertTrue(json.contains("A0401"), "序列化结果必须包含错误码：" + json);
    }

    @Test
    @DisplayName("错误码规范（阿里手册）：成功为 00000，其余 5 位且首位标明来源 A/B/C")
    void errorCodeSegments() {
        assertEquals("00000", ErrorCode.SUCCESS.getCode());

        // A 类：错误来源于用户（原样重试无意义，前端应提示用户）
        assertEquals('A', ErrorCode.PARAMS_ERROR.getCode().charAt(0));
        assertEquals('A', ErrorCode.NOT_LOGIN_ERROR.getCode().charAt(0));
        assertEquals('A', ErrorCode.NO_AUTH_ERROR.getCode().charAt(0));
        assertEquals('A', ErrorCode.FORBIDDEN_ERROR.getCode().charAt(0));
        assertEquals('A', ErrorCode.NOT_FOUND_ERROR.getCode().charAt(0));
        assertEquals('A', ErrorCode.TOO_MANY_REQUESTS_ERROR.getCode().charAt(0));

        // B 类：错误来源于当前系统（查后端日志，重试可能成功）
        assertEquals('B', ErrorCode.SYSTEM_ERROR.getCode().charAt(0));
        assertEquals('B', ErrorCode.OPERATION_ERROR.getCode().charAt(0));

        // C 类：错误来源于第三方服务（等服务商恢复，不是本系统 bug）
        assertEquals('C', ErrorCode.AI_SERVICE_ERROR.getCode().charAt(0));

        // 全部取值都必须是 5 位——以后有人加了一个 4 位或 6 位的码，这里立刻红
        for (ErrorCode ec : ErrorCode.values()) {
            assertEquals(5, ec.getCode().length(), "错误码必须是 5 位：" + ec.name());
        }
    }

    @Test
    @DisplayName("ThrowUtils：条件成立时抛出携带正确错误码的业务异常")
    void throwIfConditionTrue() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ThrowUtils.throwIf(true, ErrorCode.NOT_LOGIN_ERROR));
        // code 与 message 都必须来自枚举，保证「抛异常」与「返回错误码」两条路径的对外表现一致
        assertEquals("A0201", ex.getCode());
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
        assertEquals("A0301", ex.getCode());
        assertEquals("仅管理员可操作", ex.getMessage());
    }
}
