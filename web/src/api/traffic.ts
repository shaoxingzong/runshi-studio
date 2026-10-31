import { http } from './request';
import type { TrafficOverview } from './types';

/** 流量埋点 / 统计接口 */
export const trafficApi = {
    report: (body: { pagePath: string; visitorId: string; referrerHost?: string; deviceType?: string }) =>
        http.post<boolean>('/traffic/report', body),

    overview: (params?: { days?: number }) =>
        http.get<TrafficOverview>('/admin/traffic/overview', params ?? {}),
};

/** 生成 uuid 的兜底实现 */
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

/** 取访客标识（localStorage 持久化的随机 uuid） */
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
        if (!memoryVisitorId) {
            memoryVisitorId = fallbackUuid();
        }
        return memoryVisitorId;
    }
}

/** 设备类型粗分：pc / mobile */
export function deviceType(): string {
    return /Mobi|Android|iPhone|iPad/i.test(navigator.userAgent) ? 'mobile' : 'pc';
}

/**
 * 来源域名（外站跳转进来才有值）
 *
 * 只取 host 不取完整 URL：query 里可能带 token。解析失败返回空串，埋点不能把页面搞坏。
 */
export function referrerHost(): string {
    try {
        return document.referrer ? new URL(document.referrer).host : '';
    } catch {
        return '';
    }
}
