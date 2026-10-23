<script setup lang="ts">
import { computed } from 'vue';
import type { QuizOverview } from '@/api/types';

/**
 * 学习概览的四个格子（对照设计稿）
 *
 * 正确率为 null 时显示「--」而不是「0%」：0 次作答时正确率是「无定义」，
 * 不是 0%。显示 0% 读起来像「全错」，那是错误的信息。
 *
 * 「待复习」用红色、「已消解」用绿色：这两个数字是用户最该关注的
 * ——一个要消灭，一个是成果。
 */
const props = defineProps<{
    overview: QuizOverview | null;
}>();

const accuracyText = computed(() => {
    const value = props.overview?.accuracy;
    if (value === null || value === undefined) {
        return '--';
    }
    return `${Math.round(value * 100)}%`;
});

const stats = computed(() => [
    { key: 'answered', value: String(props.overview?.answeredTotal ?? 0), label: '累计作答', tone: '' },
    { key: 'accuracy', value: accuracyText.value, label: '正确率', tone: '' },
    { key: 'pending', value: String(props.overview?.wrongPending ?? 0), label: '待复习错题', tone: 'danger' },
    { key: 'resolved', value: String(props.overview?.wrongResolved ?? 0), label: '已消解', tone: 'success' },
]);
</script>

<template>
    <div class="stats">
        <div v-for="item in stats" :key="item.key" class="stat">
            <div class="value" :class="item.tone">{{ item.value }}</div>
            <div class="label">{{ item.label }}</div>
        </div>
    </div>
</template>

<style scoped>
.stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
}

.stat {
    background: var(--bg);
    border-radius: var(--radius-sm);
    padding: 18px 12px;
    text-align: center;
}

.value {
    font-size: 26px;
    font-weight: 600;
    line-height: 1.2;
    color: var(--text);
}

.value.danger {
    color: var(--danger);
}

.value.success {
    color: var(--success);
}

.label {
    margin-top: 6px;
    font-size: 13px;
    color: var(--muted);
}

/* 手机：四列会挤成两行且数字贴边，改成两列 */
@media (max-width: 640px) {
    .stats {
        grid-template-columns: repeat(2, 1fr);
    }

    .value {
        font-size: 22px;
    }
}
</style>
