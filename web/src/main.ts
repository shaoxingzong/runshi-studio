import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading, ElMessage } from 'element-plus';

import App from './App.vue';
import router from './router';
import './styles/index.css';

// 按需引入模式下，这三处必须手动补（它们不在模板里出现，扫描器发现不了）：
// 1. ElMessage / ElMessageBox：在 .ts 里函数式调用，样式不会被自动注入；
// 2. v-loading：指令同样不在模板标签上，需要手动注册 + 手动引样式。
// 漏掉的表现是"功能正常但样式全丢"——比报错更难发现。
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/loading/style/css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
// v-loading 指令：按需模式下必须显式注册
app.directive('loading', ElLoading.directive);

// 全局渲染异常兜底
//
// 为什么需要它：Vue 组件渲染 / 生命周期里抛出的未捕获错误，
// 默认只在控制台留一行，用户看到的是白屏——没有提示、没有原因、没有恢复手段。
// 请求层错误不走这里（axios 拦截器已统一处理 A0201 / 业务码），
// 这里是渲染期的最后一道网。
//
// 处理口径：控制台保留完整现场（错误 + 组件名 + 阶段，便于排查），
// 用户侧给一条可操作的提示（刷新重试）。不在 try-catch 里吞掉异常——
// 吞掉会让「错了但看起来没事」，比白屏更难查。
app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue 渲染异常]', err, {
        component: instance?.$options?.name ?? '(匿名组件)',
        info,
    });
    ElMessage.error('页面显示异常，请刷新重试');
};

app.mount('#app');
