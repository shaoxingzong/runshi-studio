import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading, ElMessage } from 'element-plus';

import App from './App.vue';
import router from './router';
import { AUTH_EXPIRED_EVENT, isRequestError } from './api/request';
import './styles/index.css';
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/loading/style/css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
// v-loading 指令：按需模式下必须显式注册
app.directive('loading', ElLoading.directive);

/**
 * 登录失效后回到对应的登录页
 *
 * 只清本地登录态是不够的：用户会停在一个「看起来还在、其实所有请求都 401」的页面上。
 * 后台区和门户的登录页不是同一个，所以按路径前缀分流，并带上 redirect 便于登录后回跳。
 * 放在 main.ts 而不是 store 里，是因为 store 不能依赖 router（router 反过来依赖 store，会成环）。
 */
window.addEventListener(AUTH_EXPIRED_EVENT, () => {
    const current = router.currentRoute.value;
    if (current.path.startsWith('/admin')) {
        void router.replace({ name: 'admin-login', query: { redirect: current.fullPath } });
        return;
    }
    if (current.meta.requiresAuth) {
        void router.replace({ name: 'login', query: { redirect: current.fullPath } });
    }
});
app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue 渲染异常]', err, {
        component: instance?.$options?.name ?? '(匿名组件)',
        info,
    });
    if (isRequestError(err)) {
        return;
    }
    ElMessage.error('页面显示异常，请刷新重试');
};

app.mount('#app');
