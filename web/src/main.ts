import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElLoading } from 'element-plus';

import App from './App.vue';
import router from './router';
import './styles/index.css';

/**
 * 按需引入模式下，这三处必须手动补（它们不在模板里出现，扫描器发现不了）：
 * 1. ElMessage / ElMessageBox：在 .ts 里函数式调用，样式不会被自动注入；
 * 2. v-loading：指令同样不在模板标签上，需要手动注册 + 手动引样式。
 * 漏掉的表现是"功能正常但样式全丢"——比报错更难发现。
 */
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/loading/style/css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
// v-loading 指令：按需模式下必须显式注册
app.directive('loading', ElLoading.directive);

app.mount('#app');
