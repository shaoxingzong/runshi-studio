import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import SmartImage from './SmartImage.vue';
describe('SmartImage', () => {
    it('没有 src 时直接显示首字兜底，不发请求', () => {
        const wrapper = mount(SmartImage, { props: { src: null, alt: '张三', seed: '1' } });
        expect(wrapper.find('img').exists()).toBe(false);
        expect(wrapper.find('.img-fallback__box').text()).toBe('张');
    });

    it('有 src 时先渲染 img', () => {
        const wrapper = mount(SmartImage, {
            props: { src: 'https://example.com/a.png', alt: '李四' },
        });
        expect(wrapper.find('img').exists()).toBe(true);
        expect(wrapper.find('img').attributes('src')).toBe('https://example.com/a.png');
    });

    it('图片加载失败时切换为兜底（关键：不留裂图）', async () => {
        const wrapper = mount(SmartImage, {
            props: { src: 'https://dead.example.com/x.png', alt: '王五' },
        });
        await wrapper.find('img').trigger('error');
        expect(wrapper.find('img').exists()).toBe(false);
        expect(wrapper.find('.img-fallback__box').text()).toBe('王');
    });

    it('换图后失败标记被重置（同一组件复用于新数据时仍能显示）', async () => {
        const wrapper = mount(SmartImage, {
            props: { src: 'https://dead.example.com/x.png', alt: '赵六' },
        });
        await wrapper.find('img').trigger('error');
        expect(wrapper.find('img').exists()).toBe(false);

        await wrapper.setProps({ src: 'https://example.com/ok.png' });
        expect(wrapper.find('img').exists()).toBe(true);
    });

    it('同名得到相同渐变，不同名得到不同渐变', () => {
        const a1 = mount(SmartImage, { props: { alt: '甲' } }).find('.img-fallback__box').attributes('style');
        const a2 = mount(SmartImage, { props: { alt: '甲' } }).find('.img-fallback__box').attributes('style');
        const b = mount(SmartImage, { props: { alt: '乙' } }).find('.img-fallback__box').attributes('style');
        expect(a1).toBe(a2);
        expect(a1).not.toBe(b);
    });

    it('头像模式没有 src 时直接显示统一默认头像', () => {
        const wrapper = mount(SmartImage, { props: { src: null, alt: '张三', fallback: 'avatar' } });
        const img = wrapper.find('img');
        expect(img.exists()).toBe(true);
        expect(img.attributes('src')).toBe('/default-avatar.png');
        expect(wrapper.find('.img-fallback__box').exists()).toBe(false);
    });

    it('头像模式加载失败时切换为统一默认头像', async () => {
        const wrapper = mount(SmartImage, {
            props: { src: 'https://dead.example.com/x.png', alt: '王五', fallback: 'avatar' },
        });
        await wrapper.find('img').trigger('error');
        expect(wrapper.find('img').attributes('src')).toBe('/default-avatar.png');
    });

    it('默认头像也加载失败时最后退回首字，不出现裂图循环', async () => {
        const wrapper = mount(SmartImage, { props: { src: null, alt: '王五', fallback: 'avatar' } });
        await wrapper.find('img').trigger('error');
        expect(wrapper.find('img').exists()).toBe(false);
        expect(wrapper.find('.img-fallback__box').text()).toBe('王');
    });
});
