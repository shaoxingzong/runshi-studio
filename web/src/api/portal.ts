import { http } from './request';
import type {
    Certificate,
    Overview,
    PageResult,
    Project,
    ProjectDetail,
} from './types';

/**
 * C 端公开接口（匿名可访问，全部只读）
 *
 * ⚠️ 这里**没有成员列表 / 成员详情**：业务规则「团队成员不对外展示」，
 * 对应的 `/member/list`、`/member/detail`、`/member/certificate/list` 已从后端删除
 * （访问会得到 404）。成员信息对外唯一的出口是**项目详情里的「参与成员」**，
 * 由 `projectDetail` 一并返回。
 */
export const portalApi = {
    /** 首页大盘（只剩证书维度：证书总数 + 级别/类型分布） */
    overview: () => http.get<Overview>('/statistic/overview'),

    /** 项目分页 */
    projectList: (params: { current?: number; pageSize?: number; title?: string; status?: number | string }) =>
        http.get<PageResult<Project>>('/project/list', { ...params }),

    /** 项目详情：含正文与参与成员 */
    projectDetail: (id: string) => http.get<ProjectDetail>('/project/detail', { id }),

    /** 荣誉墙 */
    certificateList: () => http.get<Certificate[]>('/certificate/list'),
};
