import axios, { type AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from './types';

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

/** 错误类别：决定「提示什么」与「用户能做什么」 */
export type ErrorKind =
    /** 没连上服务器：断网、DNS 失败、服务未启动、跨域被拦 */
    | 'network'
    /** 请求超时：服务端在跑但没在时限内返回 */
    | 'timeout'
    /** 服务端 5xx 或业务码 B 类：后端出问题，用户重试可能有效 */
    | 'server'
    /** 第三方服务不可用（AI 等 C 类）：不是本系统的错，等对方恢复 */
    | 'upstream'
    /** 参数/权限/未登录等 A 类：用户侧可修正 */
    | 'client'
    /** 其它未归类的失败 */
    | 'unknown';

/** 统一的请求错误对象：业务代码 catch 到的就是它 */
export class RequestError extends Error {
    readonly kind: ErrorKind;
    readonly code?: string;
    readonly notified: boolean;

    constructor(message: string, kind: ErrorKind, code?: string, notified = false) {
        super(message);
        this.name = 'RequestError';
        this.kind = kind;
        this.code = code;
        this.notified = notified;
    }

    /** 这类错误重试是否有意义：参数错误重试一百次还是错，网络/服务类才值得重试 */
    get retryable(): boolean {
        return this.kind === 'network' || this.kind === 'timeout' || this.kind === 'server';
    }
}

/** 判断一个 catch 到的东西是不是我们的请求错误 */
export function isRequestError(e: unknown): e is RequestError {
    return e instanceof RequestError;
}

/** 把后端业务码映射成错误类别 */
function kindOfCode(code: string): ErrorKind {
    const head = code.charAt(0).toUpperCase();
    if (head === 'C') {
        return 'upstream';
    }
    if (head === 'B') {
        return 'server';
    }
    if (head === 'A') {
        return 'client';
    }
    return 'unknown';
}

/** 网络层错误的可读文案 */
function describeNetworkError(kind: ErrorKind): string {
    if (kind === 'timeout') {
        return '请求超时，服务器响应太慢，请稍后重试';
    }
    return '网络连接失败，请检查网络后重试';
}

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

/** 把 HTTP 层错误翻译成可展示的 RequestError */
function toHttpRequestError(error: unknown): RequestError | null {
    const response = (error as { response?: { status?: number; data?: unknown } })?.response;
    const status = response?.status;
    if (typeof status !== 'number' || ![400, 413, 414, 431].includes(status)) {
        return null;
    }

    // 后端业务校验失败时可能返回 JSON（含 message）；网关拒绝时 data 是 HTML 字符串
    const body = response?.data;
    const backendMessage =
        body && typeof body === 'object' && typeof (body as { message?: unknown }).message === 'string'
            ? (body as { message: string }).message
            : '';
    const code =
        body && typeof body === 'object' && typeof (body as { code?: unknown }).code === 'string'
            ? (body as { code: string }).code
            : undefined;

    const message =
        backendMessage ||
        (status === 413
            ? '提交内容过大，请压缩后重试'
            : status === 414 || status === 431
              ? '输入内容过长，请精简后重试'
              : '请求参数不正确，请检查输入');

    ElMessage.error(message);
    return new RequestError(message, 'client', code, true);
}
instance.interceptors.response.use(
    (response) => response,
    (error) => {
        // 已有 response：HTTP 层收到了响应（4xx/5xx），不走「断网」兜底
        if (error?.response) {
            const httpError = toHttpRequestError(error);
            return Promise.reject(httpError ?? error);
        }

        const isTimeout = error?.code === 'ECONNABORTED' || /timeout/i.test(String(error?.message));
        const kind: ErrorKind = isTimeout ? 'timeout' : 'network';
        const message = describeNetworkError(kind);

        // silent 请求（后台轮询、注销）连不上服务端也不打扰用户
        const silent = Boolean(error?.config?.silent);
        if (!silent) {
            ElMessage.error(message);
        }
        return Promise.reject(new RequestError(message, kind, undefined, !silent));
    },
);

/** 把 {code,data,message} 解成 data，非 0 抛错 */
async function unwrap<T>(promise: Promise<AxiosResponse<ApiResponse<T>>>, silent = false): Promise<T> {
    const response = await promise;
    const body = response.data;

    // 成功码是 5 个零（字符串），不要写 if (!body.code) 之类的假值判断
    if (body.code === '00000') {
        return body.data;
    }

    const kind = kindOfCode(body.code);

    if (body.code === 'A0201') {
        tokenStore.clear();
        window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    }

    const message = body.message || `请求失败（${body.code}）`;
    if (!silent) {
        ElMessage.error(message);
    }

    throw new RequestError(message, kind, body.code, !silent);
}

/** 单次请求的可选项：超时 + 静默 */
export interface RequestOptions {
    timeout?: number;
    /** 静默模式：失败时不弹全局提示，把「要不要告诉用户」交给调用方 */
    silent?: boolean;
}

/** 令牌失效事件名 */
export const AUTH_EXPIRED_EVENT = 'auth:expired';

/** 带超时的请求方法（默认 30 秒） */
export const http = {
    get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.get<ApiResponse<T>>(url, { params, ...options }), options?.silent);
    },
    post<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, options), options?.silent);
    },

    long<T>(url: string, data?: unknown, timeout = 300_000): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, { timeout }));
    },
};

/** 流式提问专用：不走 axios（需要读取原始流），基础地址与上面保持一致 */
export const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
