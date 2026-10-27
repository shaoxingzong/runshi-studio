import { http, baseUrl, tokenStore } from './request';
import type { AiSession, ApiResponse, ChatResponse, ChatMessage, KnowledgeSource, PageResult } from './types';

// AI 咨询
//
// 流式接口不能用 EventSource：它无法携带自定义请求头，
// 而登录态就在 satoken 头里——用 EventSource 会让登录用户的提问退化成匿名（被 IP 限流）。
// 因此这里用 fetch + ReadableStream 手动解析 SSE 帧。

export const aiApi = {
    // 非流式提问（当前无调用方）
    //
    // AI 页面统一走 chatStream（SSE，边收边渲染）；
    // 本方法保留给脚本化 / 非流式场景复用，新增调用前请先确认是否真的不需要流式。
    chat(sessionId: string | null, message: string) {
        return http.post<ChatResponse>('/ai/chat', { sessionId, message });
    },
    // 会话历史
    //
    // 静默：调用方（AiChat）会把「会话已失效」当作可恢复状态处理（清掉本地 id
    // 直接开新对话），不该先弹一条红字再自我修复。其余失败由调用方决定是否提示。
    history(sessionId: string) {
        return http.get<ChatMessage[]>('/ai/chat/history', { sessionId }, { silent: true });
    },
    // 删除会话及其全部消息
    //
    // 后端 DTO 的字段叫 sessionId，不是 id——
    // 字段名发错时参数绑不上，请求不报错但会话删不掉（「点了没反应」那类 bug）。
    deleteSession(id: string) {
        return http.post<boolean>('/ai/session/delete', { sessionId: id });
    },

    // 我的会话列表（按最近活跃倒序，含消息条数）
    //
    // 接口要求登录（ @SaCheckLogin），而 AI 页对游客开放。
    // 未登录时不要调它：会拿到 A0201，而请求层遇到 A0201 会顺手清掉本地令牌。
    sessionList: (params: Record<string, unknown> = {}) =>
        http.get<PageResult<AiSession>>('/ai/session/list', params),
};

/**
 * 流式提问的回调集合（每个事件一个回调，全部可选）
 *
 * 用回调而不是 AsyncGenerator：浏览器端 SSE 只能靠 fetch 手动分帧，
 * 回调让「收到一段渲染一段」更直接，页面里也不用维护可暂停的迭代器。
 */
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

    /**
     * 未登录 / 被拦截 / 服务端异常时返回的是统一响应体 JSON，不是事件流。
     * 必须先判内容类型，否则会把一段 JSON 当 SSE 帧去分帧，最后一个事件也收不到。
     */
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
        /** SSE 以空行分隔事件帧 */
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
