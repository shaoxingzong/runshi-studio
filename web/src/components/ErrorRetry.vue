<script setup lang="ts">
/**
 * 加载失败提示条（带重试按钮）
 *
 * <p><b>为什么抽成组件</b>：门户有 6 个列表/详情页面此前都是「请求失败 → 整页空白」——
 * 用户分不清是「没数据」还是「坏了」，也没有任何恢复手段。统一成
 * 「一条错误提示 + 一个重试按钮」，既是同一套体验，也避免 6 处各写一遍。
 * （首页轮播 {@code ProjectSpotlight} 是第 7 处，但它是「整块组件」而非页面，
 *   失败提示写在组件内部，样式与本组件保持一致。）
 *
 * <p><b>文案口径</b>：透传后端的 message（例如「AI 服务暂时不可用」这类具体原因），
 * 取不到时才用兜底文案——「不知道为什么失败」比失败本身更让人沮丧。
 */
defineProps<{
    /** 错误信息（一般为后端 message） */
    message?: string;
}>();

const emit = defineEmits<{ retry: [] }>();
</script>

<template>
    <el-alert type="error" :closable="false" show-icon class="error-retry">
        <template #title>
            <span class="msg">{{ message || '加载失败，请重试' }}</span>
            <el-button link type="primary" @click="emit('retry')">重试</el-button>
        </template>
    </el-alert>
</template>

<style scoped>
.error-retry {
    margin-bottom: 16px;
}

/* 标题行内的按钮要垂直对齐文字，Element 默认是块级基线对齐 */
.error-retry :deep(.el-alert__title) {
    display: flex;
    align-items: center;
    gap: 10px;
}

.msg {
    flex: 1;
    min-width: 0;
    word-break: break-all;
}
</style>
