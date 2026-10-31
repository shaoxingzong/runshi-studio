import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading, ElMessage } from 'element-plus';

import App from './App.vue';
import router from './router';
import { isRequestError } from './api/request';
import './styles/index.css';
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/loading/style/css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
// v-loading 指令：按需模式下必须显式注册
app.directive('loading', ElLoading.directive);
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
