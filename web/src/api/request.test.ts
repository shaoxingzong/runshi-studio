import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTH_EXPIRED_EVENT, http, isRequestError, RequestError, tokenStore } from './request';

// 请求层单测（纯逻辑，不起服务、不联网）
//
// 为什么值得单测：request.ts 是所有接口共用的一条命脉——
// 响应解包、A0201 登录态回收、错误提示映射、网络兜底，任何一处回归都是全站性的
// （表现：所有页面同时不弹提示或同时乱跳登录）。而它此前的验证方式是「点一遍页面」，
// 漏掉某个分支毫无察觉。
//
// 策略：mock 掉 axios 实例与 ElMessage，只驱 unwrap 与响应拦截器的判断逻辑。
// axios 的实例是模块内私有变量，因此 mock axios.create 的返回值来接管它。

const { mockGet, mockPost, messageError, responseChain, runResponseChain } = vi.hoisted(() => {
    /** 收集 request.ts 注册的响应拦截器（与真 axios 的 interceptors.response 同构） */
    const responseChain = {
        fulfilled: [] as Array<(r: unknown) => unknown>,
        rejected: [] as Array<(e: unknown) => unknown>,
    };

    /**
     * 让 mock 的 axios 实例像真的一样跑响应拦截器链
     *
     * 为什么必须模拟这一层：request.ts 的网络兜底逻辑就注册在响应拦截器里，
     * 若 mock 的 use 只是个 vi.fn()（只记录不执行），拦截器等于不存在，
     * 「网络异常兜底」那条用例测的就是个空壳。
     */
    const runResponseChain = <T>(promise: Promise<T>): Promise<T> =>
        promise.then(
            (res) =>
                responseChain.fulfilled.reduce<Promise<unknown>>(
                    (p, h) => p.then(h),
                    Promise.resolve(res),
                ) as Promise<T>,
            (err) => {
                /** 失败链按注册顺序逐级 catch：任一 handler 返回 rejected 就继续往下传 */
                let p: Promise<unknown> = Promise.reject(err);
                for (const handler of responseChain.rejected) {
                    p = p.catch(handler);
                }
                return p as Promise<T>;
            },
        );

    return {
        responseChain,
        runResponseChain,
        mockGet: vi.fn(),
        mockPost: vi.fn(),
        messageError: vi.fn(),
    };
});

vi.mock('axios', () => ({
    default: {
        create: () => ({
            get: (url: string, config?: unknown) => runResponseChain(mockGet(url, config)),
            post: (url: string, data?: unknown, config?: unknown) =>
                runResponseChain(mockPost(url, data, config)),
            interceptors: {
                request: { use: vi.fn() },
                response: {
                    use: (
                        onFulfilled: (r: unknown) => unknown,
                        onRejected: (e: unknown) => unknown,
                    ) => {
                        responseChain.fulfilled.push(onFulfilled);
                        responseChain.rejected.push(onRejected);
                    },
                },
            },
        }),
    },
}));

vi.mock('element-plus', () => ({
    ElMessage: { error: messageError, success: vi.fn() },
}));

/** 构造后端的标准响应壳 {code, data, message} */
function ok<T>(data: T) {
    return { data: { code: '00000', data, message: 'ok' } };
}

function fail(code: string, message: string) {
    return { data: { code, data: null, message } };
}

/** 取一次请求失败抛出的 RequestError，顺便断言它确实是 RequestError */
async function catchRequestError(p: Promise<unknown>): Promise<RequestError> {
    try {
        await p;
    } catch (e) {
        expect(isRequestError(e)).toBe(true);
        return e as RequestError;
    }
    throw new Error('预期请求失败，但它成功了');
}

beforeEach(() => {
    mockGet.mockReset();
    mockPost.mockReset();
    messageError.mockClear();
    tokenStore.clear();
});

describe('unwrap：响应解包', () => {
    it('成功码 00000：返回 data，不弹提示', async () => {
        mockGet.mockResolvedValue(ok({ id: '123', name: '测试项目' }));

        const result = await http.get<{ id: string }>('/project/list');

        expect(result).toEqual({ id: '123', name: '测试项目' });
        expect(messageError).not.toHaveBeenCalled();
    });

    it('业务错误码：弹后端 message 并抛出同文案错误', async () => {
        mockGet.mockResolvedValue(fail('A0401', '参数错误'));

        await expect(http.get('/project/list')).rejects.toThrow('参数错误');
        expect(messageError).toHaveBeenCalledWith('参数错误');
    });

    it('业务错误但 message 为空：用错误码兜底文案', async () => {
        mockGet.mockResolvedValue({ data: { code: 'B0001', data: null, message: '' } });

        await expect(http.get('/x')).rejects.toThrow();
        expect(messageError).toHaveBeenCalledWith('请求失败（B0001）');
    });
});

describe('错误分类：决定页面能否给出正确的处置建议', () => {
    it('A 类（用户端）归为 client，且不可重试', async () => {
        mockGet.mockResolvedValue(fail('A0401', '参数错误'));

        const err = await catchRequestError(http.get('/x'));

        expect(err.kind).toBe('client');
        // 参数错误重试一百次还是错，页面不该给「重试」按钮
        expect(err.retryable).toBe(false);
    });

    it('B 类（当前系统）归为 server，可重试', async () => {
        mockGet.mockResolvedValue(fail('B0001', '系统错误'));

        const err = await catchRequestError(http.get('/x'));

        expect(err.kind).toBe('server');
        expect(err.retryable).toBe(true);
    });

    it('C 类（第三方服务）归为 upstream：与自身故障区分开', async () => {
        mockGet.mockResolvedValue(fail('C0200', 'AI 服务暂时不可用'));

        const err = await catchRequestError(http.get('/x'));

        // AI 服务挂了不是本系统的 bug，处置方式是「等」而不是「重试或报修」
        expect(err.kind).toBe('upstream');
    });

    it('保留业务错误码，便于排障时对日志', async () => {
        mockGet.mockResolvedValue(fail('A0501', '请求过于频繁'));

        const err = await catchRequestError(http.get('/x'));

        expect(err.code).toBe('A0501');
    });
});

describe('notified：消除重复弹窗', () => {
    it('业务错误标记为已提示，页面不必再弹一次', async () => {
        mockGet.mockResolvedValue(fail('A0401', '参数错误'));

        const err = await catchRequestError(http.get('/x'));

        // 请求层已经弹过一次；页面 catch 时若再弹，用户会看到两个相同提示框
        expect(err.notified).toBe(true);
        expect(messageError).toHaveBeenCalledTimes(1);
    });

    it('网络错误同样标记为已提示', async () => {
        mockGet.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }));

        const err = await catchRequestError(http.get('/x'));

        expect(err.notified).toBe(true);
        expect(messageError).toHaveBeenCalledTimes(1);
    });
});

describe('A0201：登录态回收', () => {
    it('清空本地 token 并派发失效事件', async () => {
        tokenStore.set('satoken-abc');
        mockGet.mockResolvedValue(fail('A0201', '未登录'));

        const events: string[] = [];
        const listener = () => events.push(AUTH_EXPIRED_EVENT);
        window.addEventListener(AUTH_EXPIRED_EVENT, listener);

        await expect(http.get('/user/current')).rejects.toThrow('未登录');

        // 三件事必须同时发生：token 清掉（否则后续请求继续带废 token）、
        // 事件派发（让 store 回收内存登录态，否则界面假装已登录）、提示用户
        expect(tokenStore.get()).toBe('');
        expect(events).toEqual([AUTH_EXPIRED_EVENT]);
        expect(messageError).toHaveBeenCalledWith('未登录');

        window.removeEventListener(AUTH_EXPIRED_EVENT, listener);
    });

    it('未登录态下遇到 A0201 不报错（幂等清理）', async () => {
        tokenStore.clear();
        mockGet.mockResolvedValue(fail('A0201', '未登录'));

        await expect(http.get('/user/current')).rejects.toThrow('未登录');
        expect(tokenStore.get()).toBe('');
    });
});

describe('网络层兜底', () => {
    it('没到达服务器：改写中文文案并提示（页面错误条不再出现英文）', async () => {
        // axios 断网/超时时的错误特征：没有 response 字段
        mockGet.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }));

        await expect(http.get('/project/list')).rejects.toThrow('网络连接失败，请检查网络后重试');
        expect(messageError).toHaveBeenCalledWith('网络连接失败，请检查网络后重试');
    });

    it('断网归为 network，可重试', async () => {
        mockGet.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }));

        const err = await catchRequestError(http.get('/x'));

        expect(err.kind).toBe('network');
        expect(err.retryable).toBe(true);
    });

    it('超时与断网区分开：文案不同，用户处置也不同', async () => {
        // 超时的特征是 ECONNABORTED，此前与断网共用一句「网络异常」，掩盖了真实原因
        mockGet.mockRejectedValue(
            Object.assign(new Error('timeout of 30000ms exceeded'), { code: 'ECONNABORTED' }),
        );

        const err = await catchRequestError(http.get('/x'));

        expect(err.kind).toBe('timeout');
        expect(err.message).toContain('超时');
        expect(err.message).not.toContain('检查网络');
    });

    it('服务端有响应（4xx/5xx）不走网络兜底：避免与 unwrap 双重提示', async () => {
        mockGet.mockRejectedValue(
            Object.assign(new Error('Request failed with status code 500'), {
                response: { status: 500, data: {} },
            }),
        );

        await expect(http.get('/x')).rejects.toThrow('Request failed with status code 500');
        expect(messageError).not.toHaveBeenCalled();
    });
});

describe('http.long：长耗时请求', () => {
    it('用更长的超时发 POST', async () => {
        mockPost.mockResolvedValue(ok(true));

        await http.long('/knowledge/sync-all');

        expect(mockPost).toHaveBeenCalledWith('/knowledge/sync-all', undefined, { timeout: 300_000 });
    });

    it('默认超时 30 秒的 post 不带 timeout 覆盖', async () => {
        mockPost.mockResolvedValue(ok(true));

        await http.post('/user/login', { userAccount: 'a' });

        expect(mockPost).toHaveBeenCalledWith('/user/login', { userAccount: 'a' }, undefined);
    });
});
