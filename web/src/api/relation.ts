import { http } from './request';
import type { AdminMember, Project } from './types';

/** 关联维护接口（成员 ↔ 项目、成员 ↔ 证书），全部要求 admin */
export const relationApi = {
    // 项目 ↔ 成员

    projectMemberList: (projectId: string) =>
        http.get<AdminMember[]>('/member-project/member/list', { projectId }),

    projectMemberBind: (projectId: string, memberId: string) =>
        http.post<boolean>('/member-project/bind', { projectId, memberId }),

    projectMemberUnbind: (projectId: string, memberId: string) =>
        http.post<boolean>('/member-project/unbind', { projectId, memberId }),

    memberProjectList: (memberId: string) =>
        http.get<Project[]>('/member-project/project/list', { memberId }),

    // 证书 ↔ 成员

    // 某证书的获奖成员列表
    certificateMemberList: (certificateId: string) =>
        http.get<AdminMember[]>('/member-certificate/member/list', { certificateId }),

    certificateMemberBind: (memberId: string, certificateId: string) =>
        http.post<boolean>('/member-certificate/bind', { memberId, certificateId }),

    // 解绑获奖成员（关系不存在时返回 A0402）
    certificateMemberUnbind: (memberId: string, certificateId: string) =>
        http.post<boolean>('/member-certificate/unbind', { memberId, certificateId }),

    memberCertificateList: (memberId: string) =>
        http.get<AdminMember[]>('/member-certificate/certificate/list', { memberId }),
};
