import { http } from './request';
import type { TrafficOverview } from './types';

// 流量埋点 / 统计接口
//
// 埋点（ report）匿名可访问——游客的访问也要统计。
// 统计（ overview）只给管理端。
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

// 取访客标识（localStorage 持久化的随机 uuid）
//
// 为什么放前端而不是后端：后端拿不到「同一个人」的稳定标识——
// 未登录时 IP 会变（移动网络）、UA 会撞（同款手机）。
// 前端 localStorage 的 uuid 至少在同一浏览器内稳定，这正是 UV 去重要的全部依据。
//
// 已知边界：换设备 / 清缓存 / 无痕模式会算成新的 UV。
// 这是轻量埋点的固有代价（不记 IP、不记设备指纹的取舍），
// 管理端页面需注明这个口径。
export function visitorId(): string {
    const key = 'traffic_visitor_id';
    let id = localStorage.getItem(key);
    if (!id) {
        id = crypto.randomUUID();
        localStorage.setItem(key, id);
    }
    return id;
}

// 设备类型粗分：pc / mobile
//
// 只按 UA 里的关键词判断，不引特性检测库——这个维度只是参考，
// 为它加一个依赖不值得。
export function deviceType(): string {
    return /Mobi|Android|iPhone|iPad/i.test(navigator.userAgent) ? 'mobile' : 'pc';
}

// 来源域名（外站跳转进来才有值）
//
// 只取 host，不取完整 URL：query 里可能带 token。
// 解析失败时返回空串而不是抛错——埋点不能把页面搞坏。
export function referrerHost(): string {
    try {
        return document.referrer ? new URL(document.referrer).host : '';
    } catch {
        return '';
    }
}
