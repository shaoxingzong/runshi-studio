import { computed, ref, type Ref } from 'vue';
import type { PageResult } from '@/api/types';

/** 后端分页上限（UserServiceImpl / StudioMemberServiceImpl 的 MAX_PAGE_SIZE 都是 50） */
export const REMOTE_OPTION_LIMIT = 50;

/** 输入防抖窗口（毫秒） */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * 后台下拉框的远程选项（三个页面共用：项目队长、成员绑定账号、证书关联成员）
 *
 * 为什么需要它：后端分页上限是 50（安全边界，不能放开），前端原先把 pageSize 写成 100，
 * 结果被静默截断——第 51 个以后的成员根本选不到，界面也不会提示。
 * 正确做法不是调大 pageSize，而是「先给前 50 个，再用关键词搜」。
 *
 * 输入防抖 300ms：下拉搜索每敲一个字符都发请求，对后端是无意义压力，
 * 也会让输入框的联想结果乱序返回。
 *
 * @param fetcher 按关键词取一页数据；关键词为空串表示「取首页」
 * @returns options（当前选项）、total（匹配总数）、loading、load（立即加载）、search（防抖搜索）、dispose（清定时器）
 */
export function useRemoteOptions<T>(fetcher: (keyword: string) => Promise<PageResult<T>>) {
    // ref<T[]>([]) 在泛型下会被推导成 UnwrapRef<T>[]，这里显式收窄，调用方拿到的就是 T[]
    const options = ref([]) as Ref<T[]>;
    /** 与当前选项同条件的总数：用来判断是否被截断（total > options.length） */
    const total = ref(0);
    const loading = ref(false);
    /** 是否只显示了第一页（还有更多候选） */
    const truncated = computed(() => total.value > options.value.length);

    let timer: number | null = null;

    /**
     * 立即加载（首次挂载、刷新时用）
     *
     * 失败时清空选项而不是保留上一次的结果：保留会让「搜索 A 再搜 B 失败」显示成 A 的人，
     * 用户以为搜到了，实际选中的是错的人。
     */
    async function load(keyword = '') {
        loading.value = true;
        try {
            const page = await fetcher(keyword.trim());
            options.value = page.records ?? [];
            total.value = Number(page.total ?? 0);
        } catch {
            options.value = [];
            total.value = 0;
        } finally {
            loading.value = false;
        }
    }

    /** 防抖搜索（绑定到 el-select 的 remote-method） */
    function search(keyword: string) {
        if (timer !== null) {
            window.clearTimeout(timer);
        }
        timer = window.setTimeout(() => {
            timer = null;
            void load(keyword);
        }, SEARCH_DEBOUNCE_MS);
    }

    /** 组件卸载时清掉待执行的搜索：页面都走了还发请求没有意义，回调也会写进已销毁的状态 */
    function dispose() {
        if (timer !== null) {
            window.clearTimeout(timer);
            timer = null;
        }
    }

    return { options, total, truncated, loading, load, search, dispose };
}
