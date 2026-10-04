<script setup lang="ts">
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

/**
 * 根组件：渲染路由视图，并用 ElConfigProvider 提供中文语言包。
 *
 * 按需引入后不再有全局的 app.use(ElementPlus, { locale })，
 * 语言包必须靠这个 Provider 下发（否则日期选择器、分页等组件回退成英文）。
 */
</script>

<template>
    <el-config-provider :locale="zhCn">
        <router-view />
    </el-config-provider>
</template>
