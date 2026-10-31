package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.convert.Convert;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sa-Token 的角色 / 权限数据源
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    /** 用户状态：0-正常（与 db/user.sql 中 user_status 的取值保持一致：0-正常，1-封禁） */
    private static final int USER_STATUS_NORMAL = 0;

    private final SysUserMapper sysUserMapper;

    /** 查询权限点集合：本项目不使用细粒度权限点，恒返回空集合 */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /** 查询账号拥有的角色，直接取 sys_user.user_role */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Convert.toLong(loginId);
        if (userId == null) {
            return List.of();
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getUserRole() == null || user.getUserRole().isBlank()) {
            return List.of();
        }

        if (user.getUserStatus() == null || user.getUserStatus() != USER_STATUS_NORMAL) {
            return List.of();
        }

        return List.of(user.getUserRole());
    }
}
