import { http } from './request';
import type { AdminMember, Project } from './types';

/**
 * 关联维护接口（成员 ↔ 项目、成员 ↔ 证书），全部要求 admin
 *
 * 独立于三方各自的 CRUD：这里处理的是两张表之间的关系，而不是任何一方自己的字段。
 *
 * 参数名照抄后端 DTO：项目侧 `projectId` + `memberId`，证书侧 `certificateId` + `memberId`
 * （不是 certId）。写错不报编译错误，只会拿到「参数为空」，排查很费时间。
 */
export const relationApi = {
    // ==================== 项目 ↔ 成员 ====================

    // 项目的参与成员（队长也在里面）
    //
    // 返回值是后台视图 AdminMember（含 userId），
    // 与 C 端脱敏的 Member 不是一回事。
    projectMemberList: (projectId: string) =>
        http.get<AdminMember[]>('/member-project/member/list', { projectId }),

    // 添加参与成员
    //
    // 重复绑定会返回 A0401——后端刻意「让冲突被看见」，
    // 前端不需要提前去重，提示出来即可。
    projectMemberBind: (projectId: string, memberId: string) =>
        http.post<boolean>('/member-project/bind', { projectId, memberId }),

    // 移除参与成员
    //
    // 当前队长不可移除（A0401）：队长由 `studio_project.leader_id` 决定，
    // 后端保证「队长必然在成员列表里」。要换人得先走 `/project/update` 改 leaderId。
    // 界面上应把队长那一行标出来并禁用移除按钮，而不是让用户点了才知道。
    projectMemberUnbind: (projectId: string, memberId: string) =>
        http.post<boolean>('/member-project/unbind', { projectId, memberId }),

    // 某成员参与的全部项目（反向查询）
    //
    // 当前无调用方：成员详情页不对外展示，这里作为备用能力保留。
    // 前端若要恢复成员维度的展示，可直接复用这个方法。
    memberProjectList: (memberId: string) =>
        http.get<Project[]>('/member-project/project/list', { memberId }),

    // ==================== 证书 ↔ 成员 ====================

    // 某证书的获奖成员列表
    certificateMemberList: (certificateId: string) =>
        http.get<AdminMember[]>('/member-certificate/member/list', { certificateId }),

    // 绑定获奖成员
    //
    // 与项目侧不同，证书没有「队长」这类不变量，绑定/解绑是自由的。
    certificateMemberBind: (memberId: string, certificateId: string) =>
        http.post<boolean>('/member-certificate/bind', { memberId, certificateId }),

    // 解绑获奖成员（关系不存在时返回 A0402）
    certificateMemberUnbind: (memberId: string, certificateId: string) =>
        http.post<boolean>('/member-certificate/unbind', { memberId, certificateId }),

    // 某成员持有的全部证书（反向查询）
    //
    // 当前无调用方：原因同 memberProjectList——成员维度的展示不对公众开放。
    memberCertificateList: (memberId: string) =>
        http.get<AdminMember[]>('/member-certificate/certificate/list', { memberId }),
};
