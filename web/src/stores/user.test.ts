import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '@/api/request';
import { useUserStore } from './user';

// 登录态 store 单测（纯逻辑）
//
// 为什么值得单测：这个 store 是「刷新页面后登录态不丢」的唯一依靠，
// 管着三处持久化（token / 账号 / 角色）+ 两个派生判断（isAdmin / isMember）。
// 它此前的验证方式是「登录一次、刷新看一眼」——而 isMember 的口径
// （按档案不按角色，见 store 注释）恰恰是最容易在重构中被改成「按角色」的地方，
// 那样的回归在界面上要等到「成员看到不该看的入口」才被发现。

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

describe('setLogin：三处持久化', () => {
    it('token / 账号 / 角色都写入，isLogin 与 isAdmin 随之成立', async () => {
        const store = useUserStore();
        store.setLogin('tok-xyz', 'alice', 'admin');
        await flush();

        expect(tokenStore.get()).toBe('tok-xyz');
        expect(localStorage.getItem('user_account')).toBe('alice');
        expect(localStorage.getItem('user_role')).toBe('admin');
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
        store.setLogin('tok-xyz', 'alice', 'admin');
        await flush();
        store.memberProfile = { id: 'm1' } as never;

        store.logout();

        expect(store.token).toBe('');
        expect(store.account).toBe('');
        expect(store.role).toBe('');
        expect(store.memberProfile).toBeNull();
        expect(store.isLogin).toBe(false);
        expect(store.isAdmin).toBe(false);
        expect(store.isMember).toBe(false);
        expect(tokenStore.get()).toBe('');
        expect(localStorage.getItem('user_account')).toBeNull();
        expect(localStorage.getItem('user_role')).toBeNull();
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
