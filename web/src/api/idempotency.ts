/**
 * 创建型接口的一次性请求令牌
 *
 * <p>后端用 Redis SETNX + 30s TTL 挡重复提交；前端在表单生命周期内持有同一个令牌，
 * 失败重试复用它（不会被当成新请求），成功后才换新令牌。
 *
 * <p>注意：不能用 crypto.randomUUID——局域网 HTTP 不是安全上下文，它是 undefined，
 * 会直接抛错导致整页异常（本项目踩过这个坑）。这里复制 traffic.ts 里的兜底实现，
 * 优先 getRandomValues 手拼 v4 uuid，全失败再退化到时间戳随机串。
 */
function fallbackUuid(): string {
    try {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    } catch {
        return `r-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
    }
}

/** 生成一个新的一次性令牌（≤64 字符，与后端 @Size(max = 64) 对齐） */
export function newRequestId(): string {
    try {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            return crypto.randomUUID();
        }
    } catch {
        // 落到兜底实现
    }
    return fallbackUuid();
}

/**
 * 可重置的令牌持有器：一次表单编辑周期内 get() 恒返回同一个值
 *
 * <p>用法：进入表单 / 成功提交后调 reset()；每次提交读 get() 放进请求体。
 * 失败不 reset —— 用户重试时后端仍认是同一个请求，避免重复落库。
 */
export function createRequestIdHolder() {
    let current = newRequestId();
    return {
        get: () => current,
        reset: () => {
            current = newRequestId();
        },
    };
}
