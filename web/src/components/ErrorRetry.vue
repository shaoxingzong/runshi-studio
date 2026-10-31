<script setup lang="ts">
import { computed } from 'vue';
import { isRequestError, type ErrorKind } from '@/api/request';

const props = defineProps<{
    // 错误信息（一般为后端 message）
    message?: string;
    // 错误对象本身：传了就能自动识别类别并给出处置建议
    error?: unknown;
    // 手动指定类别（页面自己构造的错误，没有 RequestError 时用）
    kind?: ErrorKind;
}>();

const emit = defineEmits<{ retry: [] }>();

/** 识别出的错误类别：优先用显式传入的，其次从 error 推断 */
const resolvedKind = computed<ErrorKind | undefined>(() => {
    if (props.kind) {
        return props.kind;
    }
    return isRequestError(props.error) ? props.error.kind : undefined;
});

/** 按类别给出的处置建议：一句话说清「我现在能做什么」 */
const HINT: Record<ErrorKind, string> = {
    network: '请确认设备已联网；若网络正常，可能是服务暂时不可达。',
    timeout: '服务器响应较慢，稍等片刻再试通常能成功。',
    server: '服务端出现异常，重试一次；若持续失败请联系管理员。',
    upstream: '这是外部服务的问题，稍后再试；本站其它功能不受影响。',
    client: '请检查输入内容是否合法，修正后重试。',
    unknown: '',
};

const hint = computed(() => {
    const kind = resolvedKind.value;
    return kind ? HINT[kind] : '';
});

/** 重试按钮是否显示 */
const canRetry = computed(() => {
    const kind = resolvedKind.value;
    if (!kind) {
        return true;
    }
    return kind !== 'client';
});
</script>

<template>
    <el-alert type="error" :closable="false" show-icon class="error-retry">
        <template #title>
            <div class="head">
                <span class="msg">{{ message || '加载失败，请重试' }}</span>
                <el-button v-if="canRetry" link type="primary" @click="emit('retry')">重试</el-button>
            </div>
            <!-- 处置建议单独一行：与错误文案分开，避免长句挤在一起读不清 -->
            <p v-if="hint" class="hint">{{ hint }}</p>
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
    flex-direction: column;
    gap: 2px;
}

.head {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
}

.msg {
    flex: 1;
    min-width: 0;
    word-break: break-all;
}

.hint {
    margin: 0;
    font-size: 12px;
    font-weight: 400;
    line-height: 1.6;
    opacity: 0.85;
}
</style>
