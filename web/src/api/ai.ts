import { http, baseUrl, tokenStore } from './request';
import type { AiSession, ApiResponse, ChatResponse, ChatMessage, KnowledgeSource, PageResult } from './types';

/**
 * AI 咨询
 *
 * ⚠️ 流式接口<b>不能用 EventSource</b>：它无法携带自定义请求头，
 * 而登录态就在 satoken 头里——用 EventSource 会让登录用户的提问退化成匿名（被 IP 限流）。
 * 因此这里用 fetch + ReadableStream 手动解析 SSE 帧。
 */

export const aiApi = {
    /**
     * 非流式提问（⚠️ **当前无调用方**）
     *
     * <p>AI 页面统一走 {@link chatStream}（SSE，边收边渲染），这个同步版本是早期实现留下的。
     * 后端 `POST /ai/chat` 仍在，保留以备脚本化 / 非流式场景复用；
     * 新增调用前请先确认是否真的不需要流式。
     */
    chat(sessionId: string | null, message: string) {
        return http.post<ChatResponse>('/ai/chat', { sessionId, message });
    },
    history(sessionId: string) {
        return http.get<ChatMessage[]>('/ai/chat/history', { sessionId });
    },
    /**
     * 删除会话及其全部消息
     *
     * <p>⚠️ 后端 DTO 的字段叫 {@code sessionId}，不是 {@code id}。
     * 早先这里发的是 `{ id }`，参数绑不上、后端拿到空值——请求不会报错，
     * 但会话也删不掉，属于「点了没反应」那类最难发现的 bug。
     */
    deleteSession(id: string) {
        return http.post<boolean>('/ai/session/delete', { sessionId: id });
    },

    /**
     * 我的会话列表（按最近活跃倒序，含消息条数）
     *
     * <p>接口要求登录（{@code @SaCheckLogin}），而 AI 页对游客开放。
     * 未登录时<b>不要调它</b>：会拿到 A0201，而请求层遇到 A0201 会顺手清掉本地令牌。
     */
    sessionList: (params: Record<string, unknown> = {}) =>
        http.get<PageResult<AiSession>>('/ai/session/list', params),
};

export interface StreamHandlers {
    onMeta?: (payload: { sessionId: string }) => void;
    onSources?: (sources: KnowledgeSource[]) => void;
    onDelta?: (delta: string) => void;
    onDone?: (payload: { messageId: string }) => void;
    onError?: (payload: { code: string; message: string }) => void;
}

/**
 * SSE 流式提问
 *
 * 事件序列（后端契约）：meta → sources → delta* → done / error
 */
export async function chatStream(
    payload: { sessionId?: string | null; message: string },
    handlers: StreamHandlers,
    signal?: AbortSignal,
): Promise<void> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = tokenStore.get();
    if (token) {
        headers.satoken = token;
    }

    const response = await fetch(`${baseUrl}/ai/chat/stream`, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
        // 支持「停止生成」：前端 AbortController 中断后，这里会抛 AbortError
        signal,
    });

    // 未登录 / 被拦截 / 服务端异常时返回的不是事件流，而是统一响应体的 JSON。
    // 必须先判内容类型，否则会把一段 JSON 当成 SSE 帧去分帧，最后一个事件也收不到
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) {
        const body = (await response.json().catch(() => null)) as ApiResponse<unknown> | null;
        throw new Error(body?.message || 'AI 服务暂不可用，请登录后重试');
    }

    const reader = response.body?.getReader();
    if (!reader) {
        throw new Error('浏览器不支持流式读取');
    }

    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        // SSE 以空行分隔事件帧
        let index: number;
        while ((index = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, index);
            buffer = buffer.slice(index + 2);
            dispatch(frame, handlers);
        }
    }
}

function dispatch(frame: string, handlers: StreamHandlers): void {
    let name = 'message';
    let data = '';
    for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) {
            name = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
            data += line.slice(5).trim();
        }
    }
    if (!data) {
        return;
    }
    let payload: Record<string, never>;
    try {
        payload = JSON.parse(data);
    } catch {
        return;
    }
    switch (name) {
        case 'meta':
            handlers.onMeta?.(payload as never);
            break;
        case 'sources':
            handlers.onSources?.((payload.sources as never) ?? []);
            break;
        case 'delta':
            handlers.onDelta?.((payload.delta as never) ?? '');
            break;
        case 'done':
            handlers.onDone?.(payload as never);
            break;
        case 'error':
            handlers.onError?.(payload as never);
            break;
        default:
            break;
    }
}
