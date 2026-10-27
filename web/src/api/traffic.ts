import { http } from './request';
import type { TrafficOverview } from './types';

/**
 * 流量埋点 / 统计接口
 *
 * 埋点（report）匿名可访问——游客的访问也要统计；统计（overview）只给管理端。
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
 * `crypto.randomUUID()` 只在安全上下文（HTTPS / localhost）存在，局域网 HTTP 下是 undefined；
 * 而 visitorId() 在路由 afterEach 里同步调用，一次抛错就让**每次页面跳转**都弹「页面显示异常」。
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
 * 放前端而不是后端：未登录时 IP 会变、UA 会撞。已知边界是换设备 / 清缓存 / 无痕会算成新 UV。
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
 * 只按 UA 关键词判断，不引特性检测库——这个维度只是参考，加依赖不值得。
 */
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
