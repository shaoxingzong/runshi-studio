<script setup lang="ts">
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

// 根组件：渲染路由视图，并用 ElConfigProvider 下发中文语言包
//
// 按需引入后没有全局的 app.use(ElementPlus, { locale })，不靠这个 Provider，
// 日期选择器、分页等组件会回退成英文。
</script>

<template>
    <el-config-provider :locale="zhCn">
        <router-view />
    </el-config-provider>
</template>
