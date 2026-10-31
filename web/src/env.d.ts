// / <reference types="vite/client" />

declare module '*.vue' {
    import type { DefineComponent } from 'vue';
    const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export default component;
}

interface ImportMetaEnv {
    readonly VITE_API_BASE_URL: string;
    /** 帖子正文允许的站外图片域名（逗号分隔，如 OSS/CDN 域名）；留空只允许 /uploads/ */
    readonly VITE_IMAGE_HOSTS?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
