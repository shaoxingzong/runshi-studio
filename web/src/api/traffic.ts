import { http } from './request';
import type { TrafficOverview } from './types';

/**
 * 流量埋点 / 统计接口
 *
 * 埋点（ report）匿名可访问——游客的访问也要统计。
 * 统计（ overview）只给管理端。
 */
export const trafficApi = {
    // 上报一次页面访问
    //
    // 调用方应静默处理失败（只 console.warn）：埋点是旁路，
    // 它失败不该在用户面前弹错，更不该阻塞页面。
    report: (body: { pagePath: string; visitorId: string; referrerHost?: string; deviceType?: string }) =>
        http.post<boolean>('/traffic/report', body),

    // 流量总览（管理端）
    //
    // @param params.days 趋势天数，默认 30，上限 90
    overview: (params?: { days?: number }) =>
        http.get<TrafficOverview>('/admin/traffic/overview', params ?? {}),
};

/**
 * 生成 uuid 的兜底实现
 *
 * 为什么需要它：`crypto.randomUUID()` 只在「安全上下文」（HTTPS 或 localhost）
 * 存在。本站用局域网 HTTP 访问（如 http://10.193.10.244），`crypto.randomUUID`
 * 直接是 undefined——而 visitorId() 又在路由 afterEach 里被同步调用，
 * 于是一次抛错就会让**每一次页面跳转**都被全局错误处理器接住，
 * 表现为「点哪里都弹页面显示异常」。
 *
 * `crypto.getRandomValues()` 不受安全上下文限制，用它手拼 v4 uuid；
 * 连它都不可用时退回时间戳 + 随机数——访客标识只用于 UV 去重，
 * 不需要密码学强度，但绝不能因为「生成不了 ID」把整站导航搞崩。
 */
function fallbackUuid(): string {
    try {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
        bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx
        const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    } catch {
        return `v-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    }
}

/** 无痕模式 / 存储被禁用时的进程内标识：保证一次会话内稳定，不落盘 */
let memoryVisitorId = '';

/**
 * 取访客标识（localStorage 持久化的随机 uuid）
 *
 * 为什么放前端而不是后端：后端拿不到「同一个人」的稳定标识——
 * 未登录时 IP 会变（移动网络）、UA 会撞（同款手机）。
 * 前端 localStorage 的 uuid 至少在同一浏览器内稳定，这正是 UV 去重要的全部依据。
 *
 * 已知边界：换设备 / 清缓存 / 无痕模式会算成新的 UV。
 * 这是轻量埋点的固有代价（不记 IP、不记设备指纹的取舍），
 * 管理端页面需注明这个口径。
 *
 * 本函数**永不抛错**：它在路由 afterEach 里同步执行，抛出去就是全站导航报错。
 */
export function visitorId(): string {
    const key = 'traffic_visitor_id';
    try {
        const cached = localStorage.getItem(key);
        if (cached) {
            return cached;
        }
        const next =
            typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
                ? crypto.randomUUID()
                : fallbackUuid();
        localStorage.setItem(key, next);
        return next;
    } catch {
        // 无痕模式 / 存储配额满：退回内存标识，本次会话内仍然稳定
        if (!memoryVisitorId) {
            memoryVisitorId = fallbackUuid();
        }
        return memoryVisitorId;
    }
}

/**
 * 设备类型粗分：pc / mobile
 *
 * 只按 UA 里的关键词判断，不引特性检测库——这个维度只是参考，
 * 为它加一个依赖不值得。
 */
export function deviceType(): string {
    return /Mobi|Android|iPhone|iPad/i.test(navigator.userAgent) ? 'mobile' : 'pc';
}

/**
 * 来源域名（外站跳转进来才有值）
 *
 * 只取 host，不取完整 URL：query 里可能带 token。
 * 解析失败时返回空串而不是抛错——埋点不能把页面搞坏。
 */
export function referrerHost(): string {
    try {
        return document.referrer ? new URL(document.referrer).host : '';
    } catch {
        return '';
    }
}
