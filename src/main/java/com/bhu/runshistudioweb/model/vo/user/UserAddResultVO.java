package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

// 管理员新增用户的返回结果
//
// 为什么单独一个 VO 而不是直接返回 id：初始密码是随机生成的
// （见 UserServiceImpl.addUser），管理员必须看见它一次才能交付给本人。
// 返回 id 做不到这件事——而如果退回「固定默认口令」，任何知道本系统的人
// 都能登录所有未及时改密的账号，那是把安全性寄托在「所有人都会立即改密」上。
//
// initialPassword 只出现这一次：后端不存明文（BCrypt 哈希已入库），
// 也没有「再查一次初始密码」的接口。前端展示后即弃，刷新页面再也看不到——
// 这是刻意的，管理员应当立即通过安全渠道（面对面 / 加密消息）转交本人。
//
// id 上的 JsonSerialize 与 LoginUserVO 同理：全局已把 Long
// 序列化为字符串，这里再标一次是字段级兜底，防全局规则被调整后精度丢失。
@Data
public class UserAddResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 新用户 ID（雪花算法 19 位，序列化为字符串）
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    // 本次生成的初始密码（明文，仅此一次返回）
    //
    // 管理员指定了密码时，原样回显他指定的那个（不是随机值）——
    // 保持「创建结果里能看到本次生效的口令」这一稳定契约。
    private String initialPassword;
}
