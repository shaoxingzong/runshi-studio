import { http, baseUrl, tokenStore } from './request';
import type { ChatResponse, ChatMessage, KnowledgeSource } from './types';

/**
 * AI 咨询
 *
 * ⚠️ 流式接口<b>不能用 EventSource</b>：它无法携带自定义请求头，
 * 而登录态就在 satoken 头里——用 EventSource 会让登录用户的提问退化成匿名（被 IP 限流）。
 * 因此这里用 fetch + ReadableStream 手动解析 SSE 帧。
 */

export const aiApi = {
    chat(sessionId: string | null, message: string) {
        return http.post<ChatResponse>('/ai/chat', { sessionId, message });
    },
    history(sessionId: string) {
        return http.get<ChatMessage[]>('/ai/chat/history', { sessionId });
    },
    deleteSession(id: string) {
        return http.post<boolean>('/ai/session/delete', { id });
    },
};

export interface StreamHandlers {
    onMeta?: (payload: { sessionId: string }) => void;
    onSources?: (sources: KnowledgeSource[]) => void;
    onDelta?: (delta: string) => void;
    onDone?: (payload: { messageId: string }) => void;
    onError?: (payload: { code: number; message: string }) => void;
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
