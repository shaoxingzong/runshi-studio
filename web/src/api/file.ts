import { ElMessage } from 'element-plus';
import { baseUrl, tokenStore } from './request';
import type { ApiResponse } from './types';

/**
 * 通用文件上传
 *
 * <p>后端 `POST /file/upload` 走 `multipart/form-data`、字段名 `file`，且**要求登录**
 * （该接口不在 Sa-Token 白名单里——匿名上传等于给攻击者一个免费图床）。
 * 返回的是站内相对访问路径（如 `/uploads/2026/10/xxxx.png`），
 * 开发态由 Vite 的 `/uploads` 代理指向后端，直接用作 `src` 即可展示。
 *
 * <p><b>为什么不用 request.ts 的 http.post</b>：那个方法发的是 JSON，
 * 而上传必须让浏览器自己补 multipart 的 boundary——手写 `Content-Type` 会让后端解析不到文件。
 * 所以这里单独走 `fetch`，并手动带上 `satoken` 头（`fetch` 不经过 axios 拦截器）。
 *
 * @param file 图片文件（后端只接受 PNG / JPEG，真实类型由**魔数**判定，不看扩展名）
 * @returns 站内相对访问路径，如 `/uploads/2026/10/xxxx.png`
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
