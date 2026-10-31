<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { quizApi } from '@/api/quiz';
import type { QuizWrongDetail, QuizWrongItem } from '@/api/types';

/** 错题本抽屉（分页列表 + 详情回看 + 手动标记已掌握） */
const props = defineProps<{
    modelValue: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; refresh: [] }>();

const loading = ref(false);
const error = ref('');
const list = ref<QuizWrongItem[]>([]);
const page = ref({ current: 1, pageSize: 10 });
const total = ref(0);

/** 详情弹窗 */
const detailVisible = ref(false);
const detail = ref<QuizWrongDetail | null>(null);
const detailLoading = ref(false);

async function load() {
    loading.value = true;
    error.value = '';
    try {
        const result = await quizApi.wrongPage({ ...page.value });
        list.value = result.records ?? [];
        total.value = Number(result.total ?? 0);
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

async function openDetail(item: QuizWrongItem) {
    detailVisible.value = true;
    detailLoading.value = true;
    detail.value = null;
    try {
        detail.value = await quizApi.wrongDetail(item.id);
    } catch {
        // 具体原因已由请求层提示；这里关掉弹窗避免留一个空壳
        detailVisible.value = false;
    } finally {
        detailLoading.value = false;
    }
}

/** 手动标记已掌握 */
async function resolve(item: QuizWrongItem) {
    try {
        await quizApi.resolveWrong(item.id);
        ElMessage.success('已标记为掌握');
        await load();
        // 让首页的「待复习 / 已消解」两个数字同步刷新
        emit('refresh');
    } catch {
        // 提示已在请求层
    }
}

function turn(delta: number) {
    const next = page.value.current + delta;
    const pages = Math.max(1, Math.ceil(total.value / page.value.pageSize));
    if (next < 1 || next > pages) {
        return;
    }
    page.value.current = next;
    void load();
}

// 抽屉每次打开都重新拉：错题可能在答题过程中新增
watch(
    () => props.modelValue,
    (visible) => {
        if (visible) {
            page.value.current = 1;
            void load();
        }
    },
);

function close() {
    emit('update:modelValue', false);
}
</script>

<template>
    <el-drawer :model-value="modelValue" title="我的错题本" size="560px" @update:model-value="close">
        <div v-loading="loading" class="drawer-body">
            <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
                <template #default>
                    <el-button link type="primary" @click="load">重试</el-button>
                </template>
            </el-alert>

            <el-empty v-else-if="!loading && list.length === 0" description="还没有错题。答错的题会自动收进这里。" />

            <template v-else>
                <div v-for="item in list" :key="item.id" class="wrong-item">
                    <div class="item-main">
                        <div class="item-meta">
                            <span class="tag" :class="{ multiple: item.type === 1 }">
                                {{ item.type === 1 ? '多选' : '单选' }}
                            </span>
                            <span class="muted small">{{ item.textbookName }} · {{ item.chapterName }}</span>
                        </div>
                        <div class="item-stem">{{ item.stem }}</div>
                        <div class="item-foot">
                            <span class="muted small">错 {{ item.wrongCount }} 次</span>
                            <span v-if="item.correctStreak > 0" class="muted small">
                                · 已连对 {{ item.correctStreak }} 次
                            </span>
                        </div>
                    </div>
                    <div class="item-actions">
                        <el-button link type="primary" @click="openDetail(item)">查看</el-button>
                        <el-button link type="success" @click="resolve(item)">已掌握</el-button>
                    </div>
                </div>

                <div class="pager">
                    <el-button link :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                    <span class="muted small">共 {{ total }} 题</span>
                    <el-button
                        link
                        :disabled="page.current >= Math.max(1, Math.ceil(total / page.pageSize))"
                        @click="turn(1)"
                    >
                        下一页
                    </el-button>
                </div>
            </template>
        </div>
    </el-drawer>

    <el-dialog v-model="detailVisible" title="错题回顾" width="600px">
        <div v-loading="detailLoading">
            <template v-if="detail">
                <div class="detail-stem">{{ detail.stem }}</div>
                <div class="detail-options">
                    <div
                        v-for="option in detail.options"
                        :key="option.label"
                        class="detail-option"
                        :class="{
                            right: detail.correctLabels.includes(option.label),
                            wrong:
                                detail.lastChosenLabels?.includes(option.label) &&
                                !detail.correctLabels.includes(option.label),
                        }"
                    >
                        <span class="label">{{ option.label }}</span>
                        <span>{{ option.content }}</span>
                    </div>
                </div>
                <div class="detail-line">
                    正确答案 <b>{{ detail.correctLabels }}</b>
                    <template v-if="detail.lastChosenLabels">
                        · 你上次选了 <b class="bad">{{ detail.lastChosenLabels }}</b>
                    </template>
                </div>
                <div v-if="detail.analysis" class="detail-analysis">
                    <div class="analysis-title">解析</div>
                    <div>{{ detail.analysis }}</div>
                </div>
            </template>
        </div>
    </el-dialog>
</template>

<style scoped>
.drawer-body {
    min-height: 200px;
}

.wrong-item {
    display: flex;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid var(--border);
}

.wrong-item:last-of-type {
    border-bottom: none;
}

.item-main {
    flex: 1;
    min-width: 0;
}

.item-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
    flex-wrap: wrap;
}

.tag {
    display: inline-block;
    padding: 1px 8px;
    border-radius: 999px;
    font-size: 12px;
    background: var(--brand-soft);
    color: var(--brand-dark);
}

.tag.multiple {
    background: #fffbeb;
    color: var(--warning);
}

/* 题干两行截断：列表里不需要看完整题干，点「查看」才有 */
.item-stem {
    font-size: 14px;
    color: var(--text);
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.item-foot {
    margin-top: 6px;
}

.item-actions {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;
}

.small {
    font-size: 12px;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding-top: 14px;
}

/* ===== 详情弹窗 ===== */
.detail-stem {
    font-size: 16px;
    line-height: 1.7;
    margin-bottom: 14px;
}

.detail-options {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-bottom: 14px;
}

.detail-option {
    display: flex;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    font-size: 14px;
}

.detail-option.right {
    border-color: var(--success);
    background: #ecfdf5;
}

.detail-option.wrong {
    border-color: var(--danger);
    background: #fef2f2;
}

.detail-option .label {
    flex-shrink: 0;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--bg);
    font-size: 12px;
    line-height: 22px;
    text-align: center;
    font-weight: 600;
}

.detail-line {
    font-size: 14px;
    color: var(--text-secondary);
    margin-bottom: 12px;
}

.detail-line b {
    font-size: 15px;
    letter-spacing: 1px;
    color: var(--text);
}

.detail-line b.bad {
    color: var(--danger);
}

.detail-analysis {
    background: var(--bg);
    border-radius: var(--radius-sm);
    padding: 12px 14px;
    font-size: 14px;
    color: var(--text-secondary);
    line-height: 1.7;
}

.analysis-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
    margin-bottom: 6px;
}
</style>
