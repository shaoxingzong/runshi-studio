import { nextTick, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import { usePageMeta } from './usePageMeta';

// usePageMeta 单测（jsdom 下 document.title / meta 均可读写）
//
// 锁两条最容易回归的规则：
// - 拼接格式： 标题 - 站点名；首页（空标题）退回纯站点名，
// 不带冗余的「首页 - 」前缀；
// - 响应式跟进：详情页传 computed，数据异步回来后标题自动换真名——
// 这是详情页 SEO 的命门（静态标题「项目详情」对收录毫无价值）。
describe('usePageMeta', () => {
    it('静态标题：拼成「标题 - 站点名」', () => {
        usePageMeta({ title: '项目案例' });

        expect(document.title).toBe('项目案例 - 润石工作室');
    });

    it('空标题：退回纯站点名（首页不该带「首页 - 」后缀）', () => {
        usePageMeta({ title: '' });

        expect(document.title).toBe('润石工作室');
    });

    it('写入 description 到 meta 标签', () => {
        usePageMeta({ title: '荣誉墙', description: '工作室历年获奖证书一览' });

        const tag = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
        expect(tag?.content).toBe('工作室历年获奖证书一览');
    });

    it('响应式标题：数据异步回来后自动更新（详情页场景）', async () => {
        // 模拟详情页：先有占位标题，接口数据回来后有真名
        const projectName = ref('');
        usePageMeta({ title: projectName });

        expect(document.title).toBe('润石工作室'); // 空值先退回站点名

        projectName.value = '基于 RAG 的工作室知识库';
        await nextTick();

        expect(document.title).toBe('基于 RAG 的工作室知识库 - 润石工作室');
    });
});
