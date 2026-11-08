import { describe, expect, it } from 'vitest';
import { createRequestIdHolder, newRequestId } from './idempotency';

/**
 * 幂等令牌单测
 *
 * <p>重点验证「表单生命周期内恒定、成功后才换」这个语义：
 * 如果失败重试时令牌变了，后端会当成新请求，重复落库 —— 这正是防重要解决的问题。
 */
describe('newRequestId', () => {
    it('生成非空且不超过后端 @Size(max=64) 的令牌', () => {
        for (let i = 0; i < 20; i++) {
            const id = newRequestId();
            expect(id.length).toBeGreaterThan(0);
            expect(id.length).toBeLessThanOrEqual(64);
        }
    });

    it('连续生成的令牌互不相同', () => {
        const ids = new Set(Array.from({ length: 20 }, () => newRequestId()));
        expect(ids.size).toBe(20);
    });

    it('crypto.randomUUID 不可用时也能生成令牌（局域网 HTTP 场景）', () => {
        const original = crypto.randomUUID;
        // 模拟不安全上下文：属性为 undefined
        Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
        try {
            const id = newRequestId();
            expect(id.length).toBeGreaterThan(0);
            expect(id.length).toBeLessThanOrEqual(64);
        } finally {
            Object.defineProperty(crypto, 'randomUUID', { value: original, configurable: true });
        }
    });
});

describe('createRequestIdHolder', () => {
    it('get() 在 reset() 之前恒定：失败重试复用同一令牌', () => {
        const holder = createRequestIdHolder();
        const first = holder.get();
        expect(holder.get()).toBe(first);
        expect(holder.get()).toBe(first);
    });

    it('reset() 后换新令牌：成功后可以再开一次表单', () => {
        const holder = createRequestIdHolder();
        const first = holder.get();
        holder.reset();
        expect(holder.get()).not.toBe(first);
    });
});
