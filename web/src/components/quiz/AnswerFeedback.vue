<script setup lang="ts">
import type { QuizVerdict } from '@/api/types';

// 判定反馈：正确/错误横幅 + 正确答案 + 解析 + 错题本动作提示
//
// 错题本动作的文案为什么要分五种：用户最关心的不是「对/错」本身
// （选项着色已经说明了），而是「这道题接下来会怎样」——
// 是进了错题本、还是再答对一次就消解、还是已经消解了。
// 只显示「回答错误」会让用户以为错题本会自动收，但实际上只有
// added / reopened 两种情况才是「刚进本」。
const props = defineProps<{
    verdict: QuizVerdict;
}>();

const ACTION_TEXT: Record<QuizVerdict['wrongBookAction'], string> = {
    added: '已加入错题本',
    reopened: '这道题又回到错题本了',
    progress: '连对进度 +1',
    resolved: '已消解（连续答对达标）',
    none: '',
};

// 错题本提示的完整文案（progress 时带上「还差几次」）
function actionText(): string {
    const base = ACTION_TEXT[props.verdict.wrongBookAction];
    if (!base) {
        return '';
    }
    if (props.verdict.wrongBookAction === 'progress' && props.verdict.remainingStreak) {
        return `${base}，再答对 ${props.verdict.remainingStreak} 次即消解`;
    }
    return base;
}

// 提示条的语气：进本/回本用警告色，消解用成功色，其余中性
function actionTone(): string {
    switch (props.verdict.wrongBookAction) {
        case 'added':
        case 'reopened':
            return 'warning';
        case 'resolved':
            return 'success';
        default:
            return 'info';
    }
}
</script>

<template>
    <div class="feedback">
        <div class="banner" :class="verdict.result === 1 ? 'ok' : 'bad'">
            <span class="icon">{{ verdict.result === 1 ? '✓' : '×' }}</span>
            <span class="text">
                {{ verdict.result === 1 ? '回答正确' : '回答错误' }}
            </span>
            <span class="answer">
                正确答案 <b>{{ verdict.correctLabels }}</b>
                <template v-if="verdict.result === 2"> · 你的答案 {{ verdict.chosenLabels || '—' }}</template>
            </span>
        </div>

        <el-alert
            v-if="actionText()"
            :type="actionTone()"
            :title="actionText()"
            :closable="false"
            show-icon
            class="action"
        />

        <div v-if="verdict.analysis" class="analysis">
            <div class="analysis-title">解析</div>
            <div class="analysis-body">{{ verdict.analysis }}</div>
        </div>
    </div>
</template>

<style scoped>
.feedback {
    margin-top: 18px;
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.banner {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 16px;
    border-radius: var(--radius-sm);
    font-size: 15px;
    flex-wrap: wrap;
}

.banner.ok {
    background: #ecfdf5;
    color: var(--success);
}

.banner.bad {
    background: #fef2f2;
    color: var(--danger);
}

.icon {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: currentcolor;
    color: #fff;
    font-size: 14px;
    line-height: 22px;
    text-align: center;
    flex-shrink: 0;
}

.text {
    font-weight: 600;
}

.answer {
    margin-left: auto;
    font-size: 13px;
    color: var(--text-secondary);
}

.answer b {
    font-size: 15px;
    letter-spacing: 1px;
}

.analysis {
    background: var(--bg);
    border-radius: var(--radius-sm);
    padding: 14px 16px;
}

.analysis-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
    margin-bottom: 6px;
}

.analysis-body {
    font-size: 14px;
    color: var(--text-secondary);
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
}

/* 手机：正确答案换行到下一行，不挤在标题右边 */
@media (max-width: 640px) {
    .answer {
        margin-left: 0;
        width: 100%;
    }
}
</style>
