package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.LoginUserVO;
import com.bhu.runshistudioweb.service.UserService;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 *
 * author: shaoshing
 *
 * <p>为什么继承 {@code ServiceImpl<SysUserMapper, SysUser>}：
 * MyBatis-Plus 的 ServiceImpl 已经把单表 CRUD（save / getById / count / updateById 等）
 * 用 Mapper 实现好了，这里只需要写「业务动作」，不用重复写 CRUD。
 * 注意第一个泛型必须是 Mapper 类型，写错会导致启动时找不到对应 Bean。
 *
 * <p>校验统一走 {@code ThrowUtils.throwIf}：条件成立即抛 {@code BusinessException}，
 * 由全局异常处理器转成标准响应，所以本类**不需要写 try-catch**，也不需要层层返回错误码。
 *
 * <p>注意：涉及多写的操作（注册）应保证原子性。当前注册只有一次 insert 是安全的；
 * 若以后注册还要写成员档案等第二张表，必须加 {@code @Transactional}，
 * 否则中途失败会留下「有账号无档案」的脏数据。
 */
@Service
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 空值兜底：Controller 的 @Valid 已经拦了一道，这里再拦一次是因为
        // Service 也可能被其他入口（管理端、定时任务、测试）直接调用，不能只依赖 Web 层校验
        ThrowUtils.throwIf(userAccount == null || userPassword == null, ErrorCode.PARAMS_ERROR, "用户账号或密码为空");
        ThrowUtils.throwIf(!userPassword.equals(checkPassword), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");

        // 注意：这里的长度规则必须与 UserRegisterRequest 上的 @Size 保持一致，
        // 否则会出现「DTO 说最多 16 位、Service 说最多 20 位」两套标准（目前就不一致，待统一）
        ThrowUtils.throwIf(userAccount.length() < 4 || userAccount.length() > 20, ErrorCode.PARAMS_ERROR, "用户账号长度必须在 4 到 20 个字符之间");
        ThrowUtils.throwIf(userPassword.length() < 8 || userPassword.length() > 20, ErrorCode.PARAMS_ERROR, "用户密码长度必须在 8 到 20 个字符之间");

        // 账号查重：count 而不是 selectOne，避免「查回整行」带来的无谓开销；
        // 逻辑删除由 MyBatis-Plus 自动追加 deleted_at = 0，因此已注销的账号不占用账号名（可重新注册）
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        long count = this.count(queryWrapper);
        // 注意：这里必须明确告知「账号已存在」，不能像登录那样统一成「账号或密码错误」——
        // 注册的对手是普通用户（需要明确反馈才能完成注册），登录的对手是攻击者（提示差异是他唯一的探测手段）。
        // 注册接口本身就是账号存在性探测器，这是它的功能；防的是「被批量扫描」，靠 的限流 + 验证码，
        // 而不是靠隐藏提示。
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");

        // 必须存哈希串：明文入库一旦泄露就等于全网账号沦陷；
        // BCrypt 自带随机盐，所以不需要额外的 salt 字段，也不必担心盐值丢失
        String encryptedPassword = PasswordUtils.encrypt(userPassword);

        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(userAccount);
        sysUser.setUserPassword(encryptedPassword);
        // 昵称：DDL 中 user_name 是 NOT NULL 且**没有默认值**，不赋值会直接插入失败
        // （报错 Field 'user_name' doesn't have a default value）。
        // 新注册用户还没有昵称，先用账号兜底，后续在「个人资料」里自行修改
        sysUser.setUserName(userAccount);
        // 默认角色：新注册用户给「最低权限」，绝不能在注册接口里允许前端指定角色
        // （否则任何人都能注册一个 admin，属于最常见的越权漏洞）
        sysUser.setUserRole("user");
        // userStatus / aiQueryCount / deletedAt 留空即可：
        // 它们在 DDL 中都有 DEFAULT（0），MyBatis-Plus 插入时会跳过 null 字段，由数据库默认值兜底

        boolean saveResult = this.save(sysUser);
        // save 返回 false 说明插入没成功（如唯一索引冲突），必须显式判断，
        // 否则会把「没存进去」当成注册成功返回给前端
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "注册失败，数据库异常");

        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体，这里直接取即可
        return sysUser.getId();
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword) {
        ThrowUtils.throwIf(userAccount == null || userPassword == null, ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        SysUser user = this.getOne(queryWrapper);

        // 防账号枚举：账号不存在与密码错误使用**同一个提示文案**。
        // 若分开提示（"账号不存在"/"密码错误"），攻击者可用两种报错扫出一批有效账号，再集中爆破。
        boolean passwordMatched = user != null && PasswordUtils.matches(userPassword, user.getUserPassword());
        ThrowUtils.throwIf(!passwordMatched, ErrorCode.PARAMS_ERROR, "账号或密码错误");

        // 封禁校验放在密码校验之后：此时已确认密码正确，攻击者无法再通过报错差异探测账号状态
        ThrowUtils.throwIf(user.getUserStatus() != null && user.getUserStatus() == 1, ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");

        // 登录成功：Sa-Token 会把 loginId 写入会话（Redis，见 sa-token-redis-template），
        // 并按 yml 配置把 token 写进响应 Cookie
        StpUtil.login(user.getId());

        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        // token 只在登录这一刻返回，前端需保存并在后续请求里带上（请求头 satoken: xxx）
        loginUserVO.setToken(StpUtil.getTokenValue());
        return loginUserVO;
    }

    @Override
    public SysUser getLoginUser() {
        // isLogin 会检查 token 是否有效/未过期，未登录时本方法不允许返回 null，
        // 否则调用方（Controller）就得自己判空，容易漏
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");

        long loginId = StpUtil.getLoginIdAsLong();
        SysUser user = this.getById(loginId);
        // token 还在但用户已不存在：典型场景是账号被逻辑删除，
        // 此时必须按「未登录」处理并把原因记下来，不能返回 null 让上游 NPE
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        return user;
    }

    @Override
    public LoginUserVO getLoginUserVO(SysUser user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx（漏一个字段就是线上 bug）；
        // VO 中没有 userPassword 字段，因此密码哈希串**不会**被拷贝进去，这是脱敏的关键
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public boolean userLogout() {
        // 未登录时直接失败：让前端明确知道「当前本来就没有登录态」，
        // 而不是返回一个含糊的成功，掩盖了 token 过期这类问题
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        // 注销会同时失效服务端会话（Redis）与客户端 Cookie，两处都清才算真正退出
        StpUtil.logout();
        return true;
    }
}
