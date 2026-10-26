import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading, ElMessage } from 'element-plus';

import App from './App.vue';
import router from './router';
import { isRequestError } from './api/request';
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
//
// 唯一的例外是请求错误：async 生命周期（onMounted 里 await 接口）抛出的
// RequestError 也会走到这里，但它已经被请求层按业务码提示过（如「未登录」
// 「会话不存在」），再补一句「页面显示异常，请刷新重试」只会让用户以为页面坏了。
// 这类错误只记控制台，不打扰用户。
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
