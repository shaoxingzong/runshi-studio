<script setup lang="ts">
import type { QuizMode } from '@/api/types';

// 三种模式的选择卡（对照设计稿的三张卡）
//
// 为什么错题重刷在「待复习为 0」时要置灰：后端对空错题本会返回 A0402，
// 但如果按钮可点，用户会先点一下、看到一条报错，才知道「没有错题」。
// 错误的时机应该在点之前，而不是报错之后。
const props = defineProps<{
    modelValue: QuizMode;
    // 待复习错题数（错题卡右上角显示，为 0 时整卡置灰）
    wrongPending: number;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: QuizMode] }>();

interface ModeOption {
    value: QuizMode;
    title: string;
    desc: string;
    // 为 true 时该卡在无数据时置灰（目前只有错题重刷）
    needsWrongBook: boolean;
}

const MODES: ModeOption[] = [
    { value: 0, title: '顺序刷题', desc: '按教材章节逐题', needsWrongBook: false },
    { value: 1, title: '错题重刷', desc: '只刷错题本', needsWrongBook: true },
    { value: 2, title: '随机刷题', desc: '随机抽题', needsWrongBook: false },
];

// 某张卡是否禁用
function disabled(option: ModeOption): boolean {
    return option.needsWrongBook && props.wrongPending <= 0;
}

function pick(option: ModeOption) {
    if (disabled(option)) {
        return;
    }
    emit('update:modelValue', option.value);
}
</script>

<template>
    <div class="modes">
        <div
            v-for="option in MODES"
            :key="option.value"
            class="mode"
            :class="{ active: modelValue === option.value, disabled: disabled(option) }"
            :title="disabled(option) ? '错题本已清空' : ''"
            @click="pick(option)"
        >
            <div class="mode-title">
                {{ option.title }}
                <span v-if="option.needsWrongBook && wrongPending > 0" class="badge">
                    {{ wrongPending }}
                </span>
            </div>
            <div class="mode-desc">{{ option.desc }}</div>
        </div>
    </div>
</template>

<style scoped>
.modes {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
}

.mode {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 14px 16px;
    cursor: pointer;
    transition: border-color 150ms ease, background 150ms ease;
    text-align: center;
}

.mode:hover {
    border-color: var(--brand);
}

/* 选中态：品牌色描边 + 浅底（与设计稿一致） */
.mode.active {
    border-color: var(--brand);
    background: var(--brand-soft);
}

.mode.disabled {
    cursor: not-allowed;
    opacity: 0.55;
}

.mode.disabled:hover {
    border-color: var(--border);
}

.mode-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--text);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
}

.mode.active .mode-title {
    color: var(--brand);
}

/* 待复习数量：小圆点，提示「这里有活要干」 */
.badge {
    min-width: 20px;
    height: 20px;
    padding: 0 6px;
    border-radius: 10px;
    background: var(--danger);
    color: #fff;
    font-size: 12px;
    line-height: 20px;
    font-weight: 500;
}

.mode-desc {
    margin-top: 4px;
    font-size: 12px;
    color: var(--muted);
}

/* 手机：三列会挤成「顺序/错题/随机」，改单列堆叠 */
@media (max-width: 640px) {
    .modes {
        grid-template-columns: 1fr;
    }
}
</style>
