package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 管理员新增用户的返回结果
 *
 * @author shaoshing
 */
@Data
public class UserAddResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新用户 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String initialPassword;
}
