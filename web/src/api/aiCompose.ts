import { baseUrl, http, tokenStore } from './request';
import type { ApiResponse } from './types';

// AI 写作助手（发帖页）
//
// 润色走 SSE：与 AI 咨询同一理由——EventSource 带不了 satoken 头，
// 只能用 fetch + ReadableStream 手动分帧。

/** 生成的标题与摘要 */
export interface ComposeMetaResult {
    title: string;
    summary: string;
}

/** 润色流式回调 */
export interface PolishHandlers {
    /** 收到一段增量 */
    onDelta?: (delta: string) => void;
    /** 正常结束；text 是模型最终完整文本（优先用它，避免增量丢失） */
    onDone?: (payload: { text?: string }) => void;
    /** 失败（含未登录、非成员、AI 故障） */
    onError?: (payload: { code: string; message: string }) => void;
}

/**
 * AI 润色选中的片段（SSE 流式）
 *
 * 事件序列（后端契约）：delta* → done / error。
 * 与问答不同，本接口不发 meta / sources——它不涉及会话与检索。
 */
export async function polishStream(
    payload: { text: string },
    handlers: PolishHandlers,
    signal?: AbortSignal,
): Promise<void> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = tokenStore.get();
    if (token) {
        headers.satoken = token;
    }

    const response = await fetch(`${baseUrl}/ai/compose/polish`, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
        // 支持「取消」：前端 AbortController 中断后，这里会抛 AbortError
        signal,
    });

    // 未登录 / 非成员 / 服务端异常时返回的是统一响应体 JSON，不是事件流。
    // 必须先判内容类型，否则会把一段 JSON 当 SSE 帧去分帧，最后一个事件也收不到。
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) {
        const body = (await response.json().catch(() => null)) as ApiResponse<unknown> | null;
        throw new Error(body?.message || 'AI 写作助手暂不可用，请稍后重试');
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
            dispatchPolish(frame, handlers);
        }
    }
}

function dispatchPolish(frame: string, handlers: PolishHandlers): void {
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
    let payload: Record<string, unknown>;
    try {
        payload = JSON.parse(data) as Record<string, unknown>;
    } catch {
        return;
    }
    switch (name) {
        case 'delta':
            handlers.onDelta?.((payload.delta as string) ?? '');
            break;
        case 'done':
            handlers.onDone?.(payload as { text?: string });
            break;
        case 'error':
            handlers.onError?.(payload as { code: string; message: string });
            break;
        default:
            break;
    }
}

/**
 * 生成标题与摘要（同步）
 *
 * 后端会结合知识库事实与高赞历史帖风格生成；失败抛 C0200。
 */
export function generateMeta(payload: { content: string; title?: string }) {
    return http.post<ComposeMetaResult>('/ai/compose/meta', payload);
}
