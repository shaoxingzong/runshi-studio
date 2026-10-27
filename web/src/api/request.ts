import axios, { type AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from './types';

// 统一请求层：自动带 satoken、解包 {code,data,message}、A0201 时清理登录态
//
// 业务代码拿到的是 data 本身，不必每层判断 code。
// A0201 只清 localStorage 不够——内存里的登录态还在，界面照常显示「已登录」
// 但每个请求都失败，所以还要派发事件让 store 回收。

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

/**
 * 错误类别：决定「提示什么」与「用户能做什么」
 *
 * 后端 message 说的是「发生了什么」，用户要的是「我该怎么办」——断网、服务器挂了、
 * 参数错了对应三种动作，混成一句「请求失败」等于什么都没说。
 */
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

/**
 * 统一的请求错误对象：业务代码 catch 到的就是它
 *
 * kind 决定页面要不要给「重试」、文案怎么写；notified 表示请求层已经弹过全局提示，
 * 页面再 catch 时只更新状态、别再弹一次，否则同一个错误会提示两遍。
 */
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

/**
 * 把后端业务码映射成错误类别
 *
 * 号段约定见后端 ErrorCode：A 用户端、B 当前系统、C 第三方。按首字母归类，
 * 后端新增错误码时前端不用跟着改。
 */
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

/**
 * 网络层错误的可读文案
 *
 * axios 原始的 "Network Error" / "ECONNABORTED" 直接透给用户等于没说，
 * 这里按 kind 给出「原因 + 建议动作」。
 */
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

/**
 * 把 HTTP 层错误翻译成可展示的 RequestError
 *
 * 只处理 400/413/414/431：这几个是网关或框架在进 Controller 前就拒绝的，响应体是 HTML 而不是
 * 统一 JSON（常见于搜索词过长，超出 ~8KB 请求行上限），不翻译页面只会显示「网络异常」。
 */
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

// 网络层兜底：根本没连上服务端（断网 / 超时 / 服务未启动）时的统一处理
//
// 与 unwrap 的分工：unwrap 管「有响应但业务码非 0」，这里管「error.response 为空」。
// 400/413/414/431 先翻译成人话（见 toHttpRequestError），其余状态码原样抛出。
// 产出的是 RequestError：message 可直接展示，kind 决定要不要给「重试」。
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
        // 未登录（A 类：用户端错误 → 登录异常）：清掉本地令牌，后续请求会以游客身份访问公开接口。
        // 只清 localStorage 是不够的——登录态还活在内存里，界面照常显示「已登录」，
        // 之后每个请求都会继续失败却没有状态回退。因此派发事件让持有方统一回收
        tokenStore.clear();
        window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
    }

    // 业务错误优先用后端 message（它比通用文案具体，如「账号或密码错误」），
    // 为空时才退回带错误码的兜底文案——错误码对用户无意义，但排障时需要
    const message = body.message || `请求失败（${body.code}）`;
    if (!silent) {
        ElMessage.error(message);
    }

    throw new RequestError(message, kind, body.code, !silent);
}

/** 单次请求的可选项：超时 + 静默 */
export interface RequestOptions {
    timeout?: number;
    /**
     * 静默模式：失败时不弹全局提示，把「要不要告诉用户」交给调用方
     *
     * 用于后台轮询（未读数）和注销——注销时登录态可能本就已失效，
     * 再弹一条红字会让用户误以为「退出失败」。
     */
    silent?: boolean;
}

/**
 * 令牌失效事件名
 *
 * 用事件而不是直接 import store：stores/user.ts 反过来依赖本文件的 tokenStore，
 * 直接互引会形成循环依赖。
 */
export const AUTH_EXPIRED_EVENT = 'auth:expired';

/**
 * 带超时的请求方法（默认 30 秒）
 *
 * 耗时接口（知识库同步 / 重建向量 / 手工录入）必须显式传更长的超时，
 * 否则前端先超时报错，后端其实还在跑，用户既看不到结果也不知道该等。
 */
export const http = {
    get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.get<ApiResponse<T>>(url, { params, ...options }), options?.silent);
    },
    post<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, options), options?.silent);
    },

    // 长耗时请求：给后端真实调用模型的操作用（默认 5 分钟）
    //
    // 这些操作一旦被 30 秒掐断，前端会弹「请求失败」，但后端任务仍在继续，
    // 结果是「以为失败了、其实跑完了」——比单纯的慢更糟糕。
    long<T>(url: string, data?: unknown, timeout = 300_000): Promise<T> {
        return unwrap<T>(instance.post<ApiResponse<T>>(url, data, { timeout }));
    },
};

/** 流式提问专用：不走 axios（需要读取原始流），基础地址与上面保持一致 */
export const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
