import { ElMessage } from 'element-plus';
import { baseUrl, tokenStore } from './request';
import type { ApiResponse } from './types';

/**
 * 通用文件上传：必须单独走 fetch（http.post 发的是 JSON，手写 Content-Type 会让后端解析不到
 * 文件），且不经过拦截器，所以要手动带 `satoken` 头。
 *
 * @returns 站内相对访问路径，如 `/uploads/2026/10/xxxx.png`，可直接用作 src
 */
export async function uploadFile(file: File): Promise<string> {
    const form = new FormData();
    form.append('file', file);

    const response = await fetch(`${baseUrl}/file/upload`, {
        method: 'POST',
        headers: { satoken: tokenStore.get() },
        body: form,
    });
    const body = (await response.json()) as ApiResponse<string>;
    // 与 request.ts 保持一致：成功码是 5 个零，不要写假值判断
    if (body.code !== '00000') {
        ElMessage.error(body.message || `上传失败（${body.code}）`);
        throw new Error(body.message || String(body.code));
    }
    return body.data;
}

/** 文件相关接口：目前只有上传，后台表单与发帖编辑器共用 */
export const fileApi = {
    upload: uploadFile,
};
