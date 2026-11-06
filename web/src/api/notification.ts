import { baseUrl, http, tokenStore } from './request';
import type { Notification, PageResult } from './types';

/** 站内通知接口（C 端，全部要求登录） */
export const notificationApi = {
    list: (params: { current?: number; pageSize?: number; isRead?: number }) =>
        http.get<PageResult<Notification>>('/notification/page', { ...params }),

    unreadCount: () => http.get<number>('/notification/unread-count', undefined, { silent: true }),

    // 标记单条已读（幂等：重复调用不刷新已读时间）
    markRead: (id: string) => http.post<boolean>('/notification/read', { id }),

    markAllRead: () => http.post<number>('/notification/read-all', {}),
};

/** 未读数实时推送的回调 */
export interface UnreadStreamHandlers {
    /** 收到 unread 事件（连接建立时的首帧也会走这里） */
    onUnread: (count: number) => void;
    /** 连接结束或失败：由调用方决定重连；主动取消不会触发 */
    onDisconnect?: (reason: 'closed' | 'error') => void;
}

/**
 * 解析一个 SSE 帧（以空行分隔）
 *
 * <p>注释帧（心跳 `: ping`）没有 data，返回 null。抽成纯函数便于单测。
 */
export function parseUnreadFrame(frame: string): number | null {
    let name = 'message';
    let data = '';
    for (const line of frame.split('\n')) {
        const trimmed = line.trimEnd();
        if (trimmed.startsWith('event:')) {
            name = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
            data += trimmed.slice(5).trim();
        }
    }
    if (name !== 'unread' || !data) {
        return null;
    }
    try {
        const payload = JSON.parse(data) as { unread?: unknown };
        const count = Number(payload.unread);
        return Number.isFinite(count) && count >= 0 ? Math.floor(count) : null;
    } catch {
        return null;
    }
}

/**
 * 订阅未读数实时推送（SSE 长连接）
 *
 * <p>不用原生 EventSource：它不能带 `satoken` 请求头，而这个接口要求登录。
 * 与 AI 流式接口同样用 fetch + ReadableStream 手解帧。
 *
 * <p>返回的取消函数用于退出登录 / 组件卸载；主动取消不会触发 onDisconnect，
 * 否则卸载后还会被安排一次重连。
 */
export async function subscribeUnread(
    handlers: UnreadStreamHandlers,
    signal?: AbortSignal,
): Promise<() => void> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal) {
        if (signal.aborted) {
            controller.abort();
        } else {
            signal.addEventListener('abort', abort, { once: true });
        }
    }

    const headers: Record<string, string> = { Accept: 'text/event-stream' };
    const token = tokenStore.get();
    if (token) {
        headers.satoken = token;
    }

    let disposed = false;
    /** 统一收尾：注销外部 signal 监听，避免重连时监听器累积 */
    const dispose = () => {
        if (disposed) {
            return;
        }
        disposed = true;
        if (signal) {
            signal.removeEventListener('abort', abort);
        }
    };

    // 异步主流程：连接建立后持续读流，直到断开
    void (async () => {
        try {
            const response = await fetch(`${baseUrl}/notification/stream`, {
                method: 'GET',
                headers,
                signal: controller.signal,
            });
            const contentType = response.headers.get('content-type') ?? '';
            if (!response.ok || !contentType.includes('text/event-stream')) {
                // 未登录 / 连接数超限：交给轮询兜底，不弹错打扰用户
                handlers.onDisconnect?.('error');
                dispose();
                return;
            }

            const reader = response.body?.getReader();
            if (!reader) {
                handlers.onDisconnect?.('error');
                dispose();
                return;
            }

            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            while (true) {
                const { done, value } = await reader.read();
                if (done) {
                    break;
                }
                buffer += decoder.decode(value, { stream: true });
                let index: number;
                // 兼容 CRLF 帧分隔：先归一成 \n，再按空行切
                buffer = buffer.replace(/\r\n/g, '\n');
                while ((index = buffer.indexOf('\n\n')) >= 0) {
                    const frame = buffer.slice(0, index);
                    buffer = buffer.slice(index + 2);
                    const count = parseUnreadFrame(frame);
                    if (count !== null) {
                        handlers.onUnread(count);
                    }
                }
            }
            if (!controller.signal.aborted) {
                handlers.onDisconnect?.('closed');
            }
        } catch {
            // AbortError（主动取消）也走这里，靠 signal.aborted 区分
            if (!controller.signal.aborted) {
                handlers.onDisconnect?.('error');
            }
        } finally {
            dispose();
        }
    })();

    return () => {
        controller.abort();
        dispose();
    };
}
