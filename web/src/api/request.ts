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
 * 3. 40100（未登录/登录失效）时清理本地令牌，避免带着过期 token 反复撞墙。
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
    if (body.code === 0) {
        return body.data;
    }
    if (body.code === 40100) {
        // 登录失效：清掉本地令牌，后续请求会以游客身份访问公开接口
        tokenStore.clear();
    }
    ElMessage.error(body.message || `请求失败（${body.code}）`);
    throw new Error(body.message || String(body.code));
}

export const http = {
    get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
        return unwrap<T>(instance.get<ApiResponse<T>>(url, { params }));
    },
    post<T>(url: string, data?: unknown): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data));
    },
};

/** 流式提问专用：不走 axios（需要读取原始流），基础地址与上面保持一致 */
export const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
