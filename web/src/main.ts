import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading, ElMessage } from 'element-plus';

import App from './App.vue';
import router from './router';
import { isRequestError } from './api/request';
import './styles/index.css';

// 按需引入下这三处样式必须手动补（它们不出现在模板里，扫描器发现不了）：
// ElMessage / ElMessageBox 在 .ts 里函数式调用，v-loading 指令也不在模板标签上。
// 漏掉的表现是「功能正常但样式全丢」，比报错更难发现。
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
// 渲染 / 生命周期里未捕获的错误默认只在控制台留一行，用户看到的是白屏。
// 控制台保留完整现场，用户侧给一条可操作的提示；不在 try-catch 里吞掉——
// 吞掉会让「错了但看起来没事」，比白屏更难查。
//
// 例外是 RequestError：它已被请求层按业务码提示过，再补一句「页面显示异常」只会误导。
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
