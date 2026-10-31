import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ErrorRetry from './ErrorRetry.vue';
import { RequestError } from '@/api/request';

describe('ErrorRetry', () => {
    it('透传后端 message 作为错误文案', () => {
        const wrapper = mount(ErrorRetry, { props: { message: 'AI 服务暂时不可用' } });

        expect(wrapper.text()).toContain('AI 服务暂时不可用');
    });

    it('message 缺省时用兜底文案（「不知道为什么失败」比失败本身更让人沮丧）', () => {
        const wrapper = mount(ErrorRetry);

        expect(wrapper.text()).toContain('加载失败，请重试');
    });

    it('点击重试按钮派发 retry 事件', async () => {
        const wrapper = mount(ErrorRetry, { props: { message: '网络异常' } });
        await wrapper.find('button').trigger('click');

        expect(wrapper.emitted('retry')).toHaveLength(1);
    });
});

describe('ErrorRetry：按错误类型给出处置建议', () => {
    it('网络错误：提示检查网络', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: '网络连接失败', error: new RequestError('x', 'network') },
        });

        // 关键不是「失败」，而是给出用户能执行的动作：确认是否联网
        expect(wrapper.text()).toContain('联网');
    });

    it('服务端错误：建议重试并说明可能需要联系管理员', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: '系统错误', error: new RequestError('x', 'server') },
        });

        expect(wrapper.text()).toContain('重试');
    });

    it('第三方服务错误：说明是外部问题，避免用户误报本系统故障', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: 'AI 服务暂时不可用', error: new RequestError('x', 'upstream') },
        });

        // 关键：告诉用户「不是本站的问题」，否则会当成 bug 反复反馈
        expect(wrapper.text()).toContain('外部服务');
    });

    it('参数错误：不显示重试按钮（同样的输入必然得到同样的拒绝）', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: '参数错误', error: new RequestError('x', 'client') },
        });

        expect(wrapper.find('button').exists()).toBe(false);
    });

    it('可重试的错误：显示重试按钮', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: '系统错误', error: new RequestError('x', 'server') },
        });

        expect(wrapper.find('button').exists()).toBe(true);
    });

    it('未分类的错误：保留重试按钮（不知道原因时，重试是唯一能做的事）', () => {
        const wrapper = mount(ErrorRetry, { props: { message: '出错了' } });

        expect(wrapper.find('button').exists()).toBe(true);
    });

    it('kind 可直接传入，不必构造 RequestError', () => {
        const wrapper = mount(ErrorRetry, {
            props: { message: 'x', kind: 'timeout' },
        });

        // 超时的处置建议：说明是「响应慢」，让用户知道等一会儿再试
        expect(wrapper.text()).toContain('响应');
    });
});
