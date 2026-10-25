import axios, { type AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from './types';

// 统一的请求层
//
// 三件事集中在这里做，业务代码不要重复实现：
// 1. 自动带上登录态（satoken 请求头）；
// 2. 统一响应解包：后端恒返回 {code, data, message}，
// 非 0 直接提示并抛错，调用方拿到的就是 data（不用每层判断 code）；
// 3. A0201（未登录/登录失效）时清理本地令牌，避免带着过期 token 反复撞墙。

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
 * 为什么要分类而不是直接显示后端 message：
 * 后端 message 描述的是「发生了什么」（如「请求参数错误」），
 * 但用户真正需要的是「我该怎么办」。两类信息不该由同一个字段承担——
 * 网络断了要检查网络，服务器挂了只能等，参数错了要改输入，
 * 这三句话指向三种完全不同的动作，混成一句「请求失败」等于什么都没说。
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
 * 统一的请求错误对象
 *
 * 业务代码 catch 到的就是它。相比裸 Error，多了三样东西：
 * - kind：错误类别，页面据此决定要不要显示「重试」按钮、文案怎么写；
 * - code：后端业务码（网络类错误没有）；
 * - notified：请求层是否已经弹过提示。
 *
 * notified 是解决「同一个错误弹两次」的关键：请求层负责弹一次全局提示，
 * 页面再 catch 时若还想在页内显示错误条，就不该再弹一次。调用方看到
 * notified=true 就该安静地更新页面状态，而不是重复 ElMessage.error。
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
 * 约定来自后端 ErrorCode 的号段设计（见 exception/ErrorCode.java）：
 * A 用户端、B 当前系统、C 第三方。这里按首字母归类，
 * 避免每加一个错误码就要来改一次前端。
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
 * axios 在没到达服务器时抛的原始 message 是 "Network Error"、
 * "ECONNABORTED" 这类英文技术词。直接透给用户等于没说，
 * 而且每个页面都要各自判断「这是网络错还是业务错」。
 *
 * 这里按 kind 给出「原因 + 建议动作」，让用户知道下一步做什么。
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

// 网络层兜底：没到达服务器（断网 / 超时 / 服务未启动）时的统一处理
//
// 与 unwrap 的分工：unwrap 管「服务端正常响应但业务码非 0」，
// 本拦截器管「根本没连上服务端」—— error.response 为空正是后者的判据。
// 有 response 的错误（4xx/5xx）不在这里处理，交给 unwrap，避免同一错误弹两次提示。
//
// 这里把 axios 的英文错误改写成 RequestError：
// 调用方拿到的 message 直接可展示，kind 可用于决定是否给「重试」按钮。
instance.interceptors.response.use(
    (response) => response,
    (error) => {
        // 已有 response：HTTP 层收到了响应（4xx/5xx），交给 unwrap 统一处理
        if (error?.response) {
            return Promise.reject(error);
        }

        const isTimeout = error?.code === 'ECONNABORTED' || /timeout/i.test(String(error?.message));
        const kind: ErrorKind = isTimeout ? 'timeout' : 'network';
        const message = describeNetworkError(kind);

        ElMessage.error(message);
        return Promise.reject(new RequestError(message, kind, undefined, true));
    },
);

/** 把 {code,data,message} 解成 data，非 0 抛错 */
async function unwrap<T>(promise: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
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
    ElMessage.error(message);

    throw new RequestError(message, kind, body.code, true);
}

/** 单次请求的可选项：目前只需要能单独指定超时 */
export interface RequestOptions {
    timeout?: number;
}

/**
 * 令牌失效事件名
 *
 * 用事件而不是直接 import store：stores/user.ts 反过来依赖本文件的
 * tokenStore，直接互引会形成循环依赖。事件让两边解耦。
 */
export const AUTH_EXPIRED_EVENT = 'auth:expired';

/**
 * 带超时的请求方法（默认 30 秒）
 *
 * 第三个参数专为耗时操作准备：全局 30 秒对「调用 Embedding」这类接口远远不够
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
