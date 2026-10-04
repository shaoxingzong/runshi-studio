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
        // 失败时前端只看 code 与 message，data 必须为 null，
        // 否则出现「有数据但 code 非 0」会让前端不知道该信哪个
        assertNull(ResultUtils.error(ErrorCode.PARAMS_ERROR).getData());
        // 自定义状态码路径：现在传的是 5 位字符串（这里用 NOT_FOUND 的码验证「不走枚举也 OK」）
        assertNull(ResultUtils.error("A0402", "自定义错误").getData());
    }

    @Test
    @DisplayName("失败响应：序列化后 data 字段必须存在（值为 null），不能整个字段消失")
    void errorSerializesDataField() {
        String json = objectMapper.writeValueAsString(ResultUtils.error(ErrorCode.PARAMS_ERROR));
        // 若全局配置了 NON_NULL（或等价策略），data 字段会从 JSON 中消失，
        // 前端写 res.data.xxx 就会报 undefined —— 这里把该行为钉死
        assertTrue(json.contains("\"data\""), "序列化结果必须包含 data 字段：" + json);
        assertTrue(json.contains("A0401"), "序列化结果必须包含错误码：" + json);
    }

    @Test
    @DisplayName("错误码规范（阿里手册）：成功为 00000，其余 5 位且首位标明来源 A/B/C")
    void errorCodeSegments() {
        // 这是与前端的契约，也是规范本身的守门人。三条硬性要求：
        // ① 成功码是 5 个零；② 错误码一律 5 位（来源 1 位 + 编号 4 位）；
        // ③ 首位标明错误产生来源——前端据此决定「提示用户 / 上报后端 / 等服务商恢复」
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
