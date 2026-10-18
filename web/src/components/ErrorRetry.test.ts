import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ErrorRetry from './ErrorRetry.vue';

/**
 * 错误态组件单测
 *
 * author: shaoshing
 *
 * <p><b>为什么值得单测</b>：这是全站 12+ 个页面共用的错误态组件（门户 2 页 + 后台 9 页），
 * 「重试按钮派发事件」是它唯一的行为契约——按钮要是因为模板改动静默失效，
 * 所有页面的错误态就同时失去恢复手段，而这种回归肉眼很难第一时间发现。
 *
 * <p>策略：只测行为契约（文案透传、兜底文案、retry 事件），不测 el-alert 内部结构
 * （Element Plus 的 DOM 随版本漂移，测了就是维护负担）。
 */

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
