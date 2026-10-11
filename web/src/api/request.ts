import axios, { type AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from './types';

/**
 * 统一的请求层
 *
 * 三件事集中在这里做，业务代码不要重复实现：
 * 1. 自动带上登录态（satoken 请求头）；
 * 2. 统一响应解包：后端恒返回 {code, data, message}，
 *    非 0 直接提示并抛错，调用方拿到的就是 data（不用每层判断 code）；
 * 3. A0201（未登录/登录失效）时清理本地令牌，避免带着过期 token 反复撞墙。
 */

const TOKEN_KEY = 'satoken';

export const tokenStore = {
    get: () => localStorage.getItem(TOKEN_KEY) || '',
    set: (value: string) => {
        if (value) {
            localStorage.setItem(TOKEN_KEY, value);
        } else {
            localStorage.removeItem(TOKEN_KEY);
        }
    },
    clear: () => localStorage.removeItem(TOKEN_KEY),
};

const instance = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
    timeout: 30_000,
});

instance.interceptors.request.use((config) => {
    const token = tokenStore.get();
    if (token) {
        config.headers.satoken = token;
    }
    return config;
});

/** 把 {code,data,message} 解成 data，非 0 抛错 */
async function unwrap<T>(promise: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
    const response = await promise;
    const body = response.data;
    // 成功码是 5 个零（字符串），不要写 if (!body.code) 之类的假值判断
    if (body.code === '00000') {
        return body.data;
    }
    if (body.code === 'A0201') {
        // 未登录（A 类：用户端错误 → 登录异常）：清掉本地令牌，后续请求会以游客身份访问公开接口。
        // 只清 localStorage 是不够的——登录态还活在内存里，界面照常显示「已登录」，
        // 之后每个请求都会继续失败却没有状态回退。因此派发事件让持有方统一回收
        tokenStore.clear();
        window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    }
    ElMessage.error(body.message || `请求失败（${body.code}）`);
    throw new Error(body.message || String(body.code));
}

/** 单次请求的可选项：目前只需要能单独指定超时 */
export interface RequestOptions {
    timeout?: number;
}

/**
 * 令牌失效事件名
 *
 * <p>用事件而不是直接 import store：{@code stores/user.ts} 反过来依赖本文件的
 * {@link tokenStore}，直接互引会形成循环依赖。事件让两边解耦。
 */
export const AUTH_EXPIRED_EVENT = 'auth:expired';

/**
 * 带超时的请求方法（默认 30 秒）
 *
 * <p>第三个参数专为<b>耗时操作</b>准备：全局 30 秒对「调用 Embedding」这类接口远远不够
 * （知识库的全量同步 / 重建向量 / 手工录入，后端要逐条调用模型）。
 * 这类接口必须显式传更长的超时——否则前端先超时报错，后端其实还在跑，
 * 用户既看不到结果，也不知道该等。
 */
export const http = {
    get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.get<ApiResponse<T>>(url, { params, ...options }));
    },
    post<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, options));
    },

    /**
     * 长耗时请求：给后端真实调用模型的操作用（默认 5 分钟）
     *
     * <p>这些操作一旦被 30 秒掐断，前端会弹「请求失败」，但后端任务仍在继续，
     * 结果是「以为失败了、其实跑完了」——比单纯的慢更糟糕。
     */
    long<T>(url: string, data?: unknown, timeout = 300_000): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, { timeout }));
    },
};

/** 流式提问专用：不走 axios（需要读取原始流），基础地址与上面保持一致 */
export const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
