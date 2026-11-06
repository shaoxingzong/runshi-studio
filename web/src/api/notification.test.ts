import { describe, expect, it } from 'vitest';
import { parseUnreadFrame } from './notification';

/**
 * 通知 SSE 帧解析单测
 *
 * <p>只测纯函数：不发起 fetch、不碰 DOM。断线重连的策略在 PortalLayout 里，
 * 由轮询兜底保证「解析/连接出问题红点仍能修正」，这里不重复造网络环境。
 */
describe('parseUnreadFrame', () => {
    it('解析 unread 事件里的未读数', () => {
        expect(parseUnreadFrame('event:unread\ndata:{"unread":3}')).toBe(3);
        expect(parseUnreadFrame('event: unread\ndata: {"unread":0}')).toBe(0);
    });

    it('CRLF 与多行 data 也能正确拼接', () => {
        expect(parseUnreadFrame('event:unread\r\ndata:{"unread":\r\ndata:12}')).toBe(12);
    });

    it('心跳注释帧返回 null（不是事件，不该触发红点刷新）', () => {
        expect(parseUnreadFrame(': ping')).toBeNull();
        expect(parseUnreadFrame('event:ping\ndata:{}')).toBeNull();
    });

    it('非法负载一律降级为 null，不能让 NaN 污染徽标', () => {
        expect(parseUnreadFrame('event:unread\ndata:不是 JSON')).toBeNull();
        expect(parseUnreadFrame('event:unread\ndata:{"unread":-1}')).toBeNull();
        expect(parseUnreadFrame('event:unread\ndata:{"unread":"abc"}')).toBeNull();
        expect(parseUnreadFrame('event:unread\ndata:{}')).toBeNull();
        expect(parseUnreadFrame('event:unread\n')).toBeNull();
    });

    it('只认 unread 事件，其它事件名不误触发', () => {
        expect(parseUnreadFrame('data:{"unread":9}')).toBeNull();
    });
});
