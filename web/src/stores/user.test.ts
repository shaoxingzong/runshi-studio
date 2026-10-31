import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore, AUTH_EXPIRED_EVENT } from '@/api/request';
import { useUserStore } from './user';

const { mineMember } = vi.hoisted(() => ({ mineMember: vi.fn() }));

vi.mock('@/api/user', () => ({ userApi: { mineMember } }));

// 刷新页面恢复登录态依赖 localStorage，每个用例都从干净状态开始
beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    mineMember.mockReset();
    mineMember.mockResolvedValue(null);
});

/** 等掉 setLogin 内部触发的异步档案补拉（不 await 的火里捞栗） */
function flush() {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('setLogin：四处持久化', () => {
    it('token / 账号 / 角色 / 姓名 / 账号 ID 都写入，isLogin 与 isAdmin 随之成立', async () => {
        const store = useUserStore();
        store.setLogin('tok-xyz', 'alice', 'admin', '张三', '', 'u-1');
        await flush();

        expect(tokenStore.get()).toBe('tok-xyz');
        expect(localStorage.getItem('user_account')).toBe('alice');
        expect(localStorage.getItem('user_role')).toBe('admin');
        expect(localStorage.getItem('user_name')).toBe('张三');
        expect(localStorage.getItem('user_id')).toBe('u-1');
        expect(store.userId).toBe('u-1');
        expect(store.displayName).toBe('张三');
        expect(store.isLogin).toBe(true);
        expect(store.isAdmin).toBe(true);
    });

    it('登录后主动补拉成员档案（布局不会重建，等不到下一次 onMounted）', async () => {
        const store = useUserStore();
        store.setLogin('tok-xyz', 'bob', 'user');

        await flush();

        expect(mineMember).toHaveBeenCalledTimes(1);
    });
});

describe('logout：全清', () => {
    it('内存与 localStorage 一处不留（残留账号名会让界面假装已登录）', async () => {
        const store = useUserStore();
        store.setLogin('tok-xyz', 'alice', 'admin', '张三', '', 'u-1');
        await flush();
        store.memberProfile = { id: 'm1' } as never;

        store.logout();

        expect(store.token).toBe('');
        expect(store.account).toBe('');
        expect(store.userId).toBe('');
        expect(store.userName).toBe('');
        expect(store.role).toBe('');
        expect(store.memberProfile).toBeNull();
        expect(store.isLogin).toBe(false);
        expect(store.isAdmin).toBe(false);
        expect(store.isMember).toBe(false);
        expect(tokenStore.get()).toBe('');
        expect(localStorage.getItem('user_account')).toBeNull();
        expect(localStorage.getItem('user_name')).toBeNull();
        expect(localStorage.getItem('user_role')).toBeNull();
        expect(localStorage.getItem('user_id')).toBeNull();
    });
});

describe('isAdmin：角色边界', () => {
    it('普通用户与空角色都不是管理员', () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'user');
        expect(store.isAdmin).toBe(false);

        store.setLogin('t', 'u', '');
        expect(store.isAdmin).toBe(false);
    });

    it('大小写不宽容：Admin 不算 admin（与后端 Sa-Token 的精确比较同口径）', () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'Admin');
        expect(store.isAdmin).toBe(false);
    });
});

describe('isMember：按档案不按角色', () => {
    it('没有档案就不是成员（哪怕角色是 member——角色与档案可能不同步）', () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'member');
        expect(store.isMember).toBe(false);
    });

    it('有档案就是成员（哪怕角色是 user——档案才是发帖/打卡的依据）', () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'user');
        store.memberProfile = { id: 'm1', name: '张三' } as never;
        expect(store.isMember).toBe(true);
    });
});

describe('displayName：成员档案姓名 > 注册姓名 > 账号', () => {
    it('成员档案存在时以档案姓名为准（与帖子作者名同口径）', async () => {
        const store = useUserStore();
        store.setLogin('t', 'chenyu', 'user', '陈宇');
        store.memberProfile = { id: 'm1', name: '档案姓名' } as never;

        expect(store.displayName).toBe('档案姓名');
    });

    it('没有档案时用注册姓名；注册姓名为空才回退登录账号', async () => {
        const store = useUserStore();
        store.setLogin('t', 'chenyu', 'user', '陈宇');
        expect(store.displayName).toBe('陈宇');

        store.setLogin('t', 'chenyu', 'user');
        expect(store.displayName).toBe('chenyu');
    });
});

describe('登录失效事件：回收内存里的假登录态', () => {
    it('收到 AUTH_EXPIRED_EVENT 后全清（只清 localStorage 会让界面假装已登录）', async () => {
        const store = useUserStore();
        store.setLogin('tok-xyz', 'alice', 'admin', '张三', '', 'u-1');
        store.memberProfile = { id: 'm1', name: '张三' } as never;
        await flush();

        // 模拟 request.ts 的派发：token 过期时它清了 localStorage 并发这个事件
        window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));

        // 内存与持久化必须同时归零，否则导航栏仍显示已登录，而每个请求都会失败
        expect(store.token).toBe('');
        expect(store.account).toBe('');
        expect(store.userId).toBe('');
        expect(store.userName).toBe('');
        expect(store.role).toBe('');
        expect(store.memberProfile).toBeNull();
        expect(store.isLogin).toBe(false);
        expect(store.isAdmin).toBe(false);
        expect(tokenStore.get()).toBe('');
        expect(localStorage.getItem('user_account')).toBeNull();
        expect(localStorage.getItem('user_name')).toBeNull();
        expect(localStorage.getItem('user_role')).toBeNull();
        expect(localStorage.getItem('user_id')).toBeNull();
    });

    it('未登录时收到事件也不报错（幂等）', () => {
        const store = useUserStore();

        expect(() => window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT))).not.toThrow();
        expect(store.isLogin).toBe(false);
    });
});

describe('loadMemberProfile：失败降级', () => {
    it('未登录时不发请求（游客没有档案可查）', async () => {
        const store = useUserStore();
        await store.loadMemberProfile();

        expect(mineMember).not.toHaveBeenCalled();
        expect(store.memberProfile).toBeNull();
    });

    it('接口失败按「不是成员」处理，不往外抛（未登录/接口异常都是可恢复情况）', async () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'user');
        mineMember.mockRejectedValue(new Error('A0201'));

        await expect(store.loadMemberProfile()).resolves.toBeUndefined();
        expect(store.memberProfile).toBeNull();
        expect(store.isMember).toBe(false);
    });

    it('接口成功则写入档案', async () => {
        const store = useUserStore();
        store.setLogin('t', 'u', 'user');
        mineMember.mockResolvedValue({ id: 'm1', name: '张三' });

        await store.loadMemberProfile();

        expect(store.memberProfile).toEqual({ id: 'm1', name: '张三' });
        expect(store.isMember).toBe(true);
    });
});
