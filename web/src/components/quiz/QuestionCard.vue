<script setup lang="ts">
import type { QuizQuestionType, QuizVerdict } from '@/api/types';
import OptionList from './OptionList.vue';

// 题卡：题型标签 + 题干 + 选项
//
// 单选「点击即提交」：这是刷题工具最重要的效率设计——
// 每道题少一次点击，20 题就少 20 次。代价是「点了就不能改」，
// 但这与真实考试一致，且判定后本来就要禁用选项。
//
// 多选必须显式提交：多选的选法是「逐个勾选、最后确认」，
// 点击即提交会让用户永远只能选到一个。
const props = defineProps<{
    stem: string;
    type: QuizQuestionType;
    options: { label: string; content: string }[];
    chosen: string;
    verdict: QuizVerdict | null;
    // 是否禁用选项（提交中或已判定）
    disabled: boolean;
}>();

const emit = defineEmits<{ pick: [label: string] }>();

// 题型文案：只有单选与多选
function typeText(): string {
    return props.type === 1 ? '多选' : '单选';
}
</script>

<template>
    <div class="question-card">
        <div class="meta">
            <span class="type" :class="{ multiple: type === 1 }">{{ typeText() }}</span>
            <!-- 多选的评分规则与单选不同，必须提前说清楚，否则用户会以为漏选能得分 -->
            <span v-if="type === 1" class="hint">多选题：少选、多选均不得分</span>
        </div>

        <div class="stem">{{ stem }}</div>

        <OptionList
            :options="options"
            :type="type"
            :chosen="chosen"
            :verdict="verdict"
            :disabled="disabled"
            @pick="(label: string) => emit('pick', label)"
        />
    </div>
</template>

<style scoped>
.question-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    padding: 24px;
}

.meta {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    flex-wrap: wrap;
}

.type {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
    background: var(--brand-soft);
    color: var(--brand-dark);
}

/* 多选用警告色：它是一个「更容易丢分」的题型 */
.type.multiple {
    background: #fffbeb;
    color: var(--warning);
}

.hint {
    font-size: 12px;
    color: var(--muted);
}

.stem {
    font-size: 17px;
    line-height: 1.75;
    color: var(--text);
    margin-bottom: 18px;
    white-space: pre-wrap;
    word-break: break-word;
}
</style>
