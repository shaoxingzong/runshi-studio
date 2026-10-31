/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';

/**
 * 把分享预览图（og:image）改写成**绝对地址**
 *
 * 为什么必须绝对：微信 / Twitter / Discord 的抓取器不解析相对路径，
 * og:image 写成 /logo.jpg 时，分享出去的链接没有预览图，只剩一行标题。
 * 而域名只有部署时才知道，不能写死在仓库里，所以构建时由 VITE_SITE_URL 注入。
 *
 * 未配置 VITE_SITE_URL 时保持原样的相对路径：本地预览照常，不阻断构建。
 *
 * @param siteUrl 站点根地址（不带尾斜杠），空串表示未配置
 * @returns 仅改写 og:image 的 Vite 插件
 */
function ogImageAbsoluteUrl(siteUrl: string): Plugin {
    return {
        name: 'og-image-absolute-url',
        transformIndexHtml(html) {
            if (!siteUrl) {
                return html;
            }
            // 只改 content 以 / 开头的相对路径，避免把故意写死的完整地址再拼一次
            return html.replace(
                /(<meta\s+property="og:image"\s+content=")(\/)/,
                `$1${siteUrl}$2`,
            );
        },
    };
}

/**
 * 生产级 Vite 配置要点：
 * 1. 开发代理：解决浏览器跨域（后端已开 CORS，但代理能让前端用相对路径 /api，
 *    避免把后端地址写死在浏览器里）；
 * 2. 手动分包：把 vue / element-plus 这类大依赖单独切出来，
 *    业务代码更新时用户不用重新下载它们（缓存命中率）；
 * 3. chunk 体积告警阈值调高到 1.5MB：element-plus 全量引入后必然超过默认的 500KB，
 *    否则构建会一直刷警告（那是"警告等价于失效"的典型场景）。
 *
 * <p>test 段给 vitest 用：jsdom 提供 localStorage / window / document
 * （请求层与登录态 store 的测试都依赖它们）。用三斜杠引用扩展 vite 配置类型，
 * 而不是把 import 换成 {@code vitest/config}——那样 {@code vite build}
 * 就会在没装 devDeps 的机器上直接失败，类型引用只在类型层面生效、构建零依赖。
 */
export default defineConfig(({ mode }) => {
    /**
     * 站点根地址（部署时通过 VITE_SITE_URL 注入，见 .env.production）
     *
     * 第三个参数传空串是为了把 .env 里**非 VITE_ 前缀**以外的变量也读进来，
     * 这里只需要它一个；留空时 og:image 保持相对路径。
     */
    const siteUrl = (loadEnv(mode, process.cwd(), '').VITE_SITE_URL || '').replace(/\/+$/, '');

    return {
    test: {
        environment: 'jsdom',
        globals: true,
        /**
         * 把 element-plus 纳入 Vite 处理链（内联），而不是外部化给 Node 原生 ESM。
         *
         * 不配的后果：组件测试挂上 el-alert / el-button 时，Node 直接加载
         * element-plus 的模块，遇到其内部的 `.css` 导入就抛
         * `Unknown file extension ".css"`——组件测试整个文件收集失败。
         * 内联后 CSS 由 Vite 接管（test.css=false 时替换为空模块），组件可正常挂载。
         */
        server: {
            deps: {
                inline: ['element-plus'],
            },
        },
    },
    plugins: [
        vue(),
        /**
         * Element Plus 按需引入：只打包**实际用到的组件**及其样式。
         *
         * 效果（实测对比）：全量引入时 JS 918KB / CSS 355KB，
         * 按需引入后只剩被使用的那十几个组件，体积降到约 1/3。
         * 代价是「函数式组件」（ElMessage / ElMessageBox）与「指令」（v-loading）
         * 不会被自动扫描到，需要手动补样式与注册（见 main.ts）。
         */
        AutoImport({ resolvers: [ElementPlusResolver()] }),
        Components({ resolvers: [ElementPlusResolver()] }),
        // 分享预览图必须绝对地址，域名由部署环境注入（见上方插件说明）
        ogImageAbsoluteUrl(siteUrl),
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            // 上传的图片由后端以静态资源方式提供（/uploads/**），与 /api 同源。
            // 不配这条，开发态（5173）下 /uploads/2026/10/xxx.png 会 404——
            // 表现为「编辑器里能插入图片，保存后页面上却裂图」。
            // ⚠️ 必须 rewrite：后端 context-path 是 /api，静态映射的真实地址是
            // /api/uploads/**；直接把 /uploads/x 转发过去同样会 404。
            // 生产由 Nginx 的同名 location 完成同样的事（见 deploy/nginx.conf）
            '/uploads': {
                target: 'http://localhost:8080',
                changeOrigin: true,
                rewrite: (path) => path.replace(/^\/uploads/, '/api/uploads'),
            },
        },
    },
    build: {
        outDir: 'dist',
        chunkSizeWarningLimit: 1500,
        rollupOptions: {
            output: {
                /**
                 * ⚠️ 这里**只**切 vue 全家桶，**不要**写 'element-plus'。
                 *
                 * 原因：manualChunks 里写包名时，Rollup 会把该包的**入口模块及其整个依赖图**
                 * 收进这个 chunk——等于把整个 Element Plus 拉回来，按需引入白做（实测 917KB 纹丝不动）。
                 * Element Plus 的组件由 unplugin 按需引入后，本来就分散在各自的业务 chunk 里，
                 * 不需要（也不能）在这里统一分组。
                 */
                manualChunks: {
                    vue: ['vue', 'vue-router', 'pinia'],
                },
            },
        },
    },
    };
});
