<script setup lang="ts">
import type { QuizOption, QuizQuestionType, QuizVerdict } from '@/api/types';

/**
 * 选项列表：单选用 radio 语义、多选用 checkbox 语义，判定后着色
 *
 * <p><b>只有两种交互，没有第三种</b>：题型是闭集（0-单选 / 1-多选），
 * 因此这里只有 {@code radio} 与 {@code checkbox} 两个分支。
 * 判断题被刻意排除在设计之外——它就是选项为「正确 / 错误」的单选。
 *
 * <p><b>判定后的着色规则</b>（三条，缺一条用户就看不懂自己错在哪）：
 * <ul>
 *     <li>正确项：绿框（无论用户选没选）；</li>
 *     <li>用户选了但<b>不在</b>正确答案里：红框（这是「多选/错选」的错）；</li>
 *     <li>用户<b>漏选</b>的正确项：绿框（这是「漏选」的错）。</li>
 * </ul>
 * 注意「正确项」的判定用的是 {@link QuizVerdict#correctLabels}，
 * 而不是选项自带的标记——选项里<b>本来就没有</b>正确性标记。
 */
const props = defineProps<{
    options: QuizOption[];
    type: QuizQuestionType;
    /** 已选标签（多选是升序拼接串，单选是单字符） */
    chosen: string;
    /** 判定结果；null 表示还没提交 */
    verdict: QuizVerdict | null;
    /** 是否禁用（提交中或已判定） */
    disabled: boolean;
}>();

const emit = defineEmits<{ pick: [label: string] }>();

/** 是否为多选 */
function isMultiple(): boolean {
    return props.type === 1;
}

/** 该选项是否被用户选中 */
function isChosen(label: string): boolean {
    return props.chosen.includes(label);
}

/** 该选项是否是正确项（仅判定后有值） */
function isCorrect(label: string): boolean {
    return props.verdict?.correctLabels?.includes(label) ?? false;
}

/**
 * 选项的视觉状态
 *
 * <p>返回值直接对应样式类：{@code right} 绿、{@code wrong} 红、
 * {@code picked} 只是选中（未判定时）。
 */
function stateOf(label: string): string {
    if (!props.verdict) {
        return isChosen(label) ? 'picked' : '';
    }
    if (isCorrect(label)) {
        return 'right';
    }
    // 选了但不在正确答案里：这是错误选择
    return isChosen(label) ? 'wrong' : '';
}

function pick(label: string) {
    if (props.disabled) {
        return;
    }
    emit('pick', label);
}
</script>

<template>
    <div class="options">
        <div
            v-for="option in options"
            :key="option.label"
            class="option"
            :class="[stateOf(option.label), { disabled }]"
            @click="pick(option.label)"
        >
            <span class="label">{{ option.label }}</span>
            <span class="content">{{ option.content }}</span>
            <!-- 多选用方框标记，单选用圆点：让「能选几个」在视觉上就能看出来 -->
            <span class="mark" :class="{ box: isMultiple() }" />
        </div>
    </div>
</template>

<style scoped>
.options {
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.option {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 14px 16px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: border-color 120ms ease, background 120ms ease;
    /* 触屏可点区域：行高 + 内边距已超过 44px */
    min-height: 44px;
}

.option:hover:not(.disabled) {
    border-color: var(--brand);
    background: var(--brand-soft);
}

.option.disabled {
    cursor: default;
}

/* 未判定时的选中态 */
.option.picked {
    border-color: var(--brand);
    background: var(--brand-soft);
}

/* 判定后：正确项绿框 */
.option.right {
    border-color: var(--success);
    background: #ecfdf5;
}

/* 判定后：选错的项红框 */
.option.wrong {
    border-color: var(--danger);
    background: #fef2f2;
}

.label {
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: var(--bg);
    color: var(--text-secondary);
    font-size: 13px;
    font-weight: 600;
    line-height: 24px;
    text-align: center;
}

.option.picked .label,
.option.right .label {
    background: var(--brand);
    color: #fff;
}

.option.right .label {
    background: var(--success);
}

.option.wrong .label {
    background: var(--danger);
    color: #fff;
}

.content {
    flex: 1;
    min-width: 0;
    font-size: 15px;
    color: var(--text);
    line-height: 1.6;
    word-break: break-word;
}

/* 多选的方形标记 vs 单选的圆形标记 */
.mark {
    flex-shrink: 0;
    width: 18px;
    height: 18px;
    border: 1.5px solid var(--border);
    border-radius: 50%;
    margin-top: 3px;
    position: relative;
}

.mark.box {
    border-radius: 4px;
}

.option.picked .mark {
    border-color: var(--brand);
    background: var(--brand);
}

.option.picked .mark::after {
    content: '';
    position: absolute;
    left: 5px;
    top: 2px;
    width: 5px;
    height: 9px;
    border: solid #fff;
    border-width: 0 2px 2px 0;
    transform: rotate(45deg);
}

.option.right .mark {
    border-color: var(--success);
    background: var(--success);
}

.option.right .mark::after {
    content: '';
    position: absolute;
    left: 5px;
    top: 2px;
    width: 5px;
    height: 9px;
    border: solid #fff;
    border-width: 0 2px 2px 0;
    transform: rotate(45deg);
}

.option.wrong .mark {
    border-color: var(--danger);
    background: var(--danger);
}

.option.wrong .mark::after {
    content: '×';
    position: absolute;
    inset: 0;
    color: #fff;
    font-size: 14px;
    line-height: 16px;
    text-align: center;
}
</style>
