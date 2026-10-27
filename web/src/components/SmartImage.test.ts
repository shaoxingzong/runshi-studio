import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import SmartImage from './SmartImage.vue';

// SmartImage 的兜底行为：图片加载失败时不能留裂图
// （外部图床失效、上传文件被清理都会走到这条路径）
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
});
