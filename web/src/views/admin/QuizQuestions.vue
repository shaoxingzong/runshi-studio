<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminQuizApi } from '@/api/quiz';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type {
    AdminQuizChapter,
    AdminQuizQuestion,
    PageResult,
    QuizImportResult,
    QuizOption,
} from '@/api/types';

/**
 * 题库管理（管理端）
 *
 * <p><b>三个刻意的设计</b>：
 * <ol>
 *     <li><b>章节不是独立实体</b>：它是题目上的两个名字列，因此没有「新增章节」，
 *     只有改名 / 移章 / 整章删除。UI 上把章节呈现成「可编辑的分组行」，
 *     而不是一张独立表的 CRUD——数据上本来就不是一张表，UI 应该诚实地反映这一点；</li>
 *     <li><b>停用与删除分开</b>：停用是可逆的下线（错题条目保留），
 *     删除会清理错题条目。列表上两个操作都要有，且删除要二次确认；</li>
 *     <li><b>导入先预览</b>：`dryRun` 校验通过后再落库，避免「导入了一半」。</li>
 * </ol>
 *
 * <p><b>改题的两个提示</b>：有作答记录时提示「修改答案不会重算历史判定」；
 * 判定相关变更（改答案 / 改题型 / 增删选项）时提示「将重置错题连对进度」。
 * 这两句必须让管理员看到——它们是后端真实行为的说明书。
 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<AdminQuizQuestion> | null>(null);
const loading = ref(false);
const error = ref('');

/** 筛选条件 */
const filter = reactive<{
    textbookName: string;
    chapterName: string;
    type: '' | 0 | 1;
    keyword: string;
    status: '' | 0 | 1;
}>({ textbookName: '', chapterName: '', type: '', keyword: '', status: '' });

/** 章节列表（用于筛选下拉与统计条） */
const chapters = ref<AdminQuizChapter[]>([]);
/** 已选中的行（批量删除） */
const selected = ref<AdminQuizQuestion[]>([]);

/** 教材名候选（来自章节列表，去重） */
const textbookOptions = computed(() => {
    const names = new Set<string>();
    chapters.value.forEach((item) => names.add(item.textbookName));
    return Array.from(names);
});

/** 当前教材下的章节候选 */
const chapterOptions = computed(() =>
    chapters.value.filter((item) => !filter.textbookName || item.textbookName === filter.textbookName),
);

/** 统计条 */
const stats = computed(() => {
    const list = result.value?.records ?? [];
    return {
        total: Number(result.value?.total ?? 0),
        single: list.filter((item) => item.type === 0).length,
        multiple: list.filter((item) => item.type === 1).length,
        disabled: list.filter((item) => item.status === 1).length,
    };
});

// ==================== 列表 ====================

async function load() {
    loading.value = true;
    error.value = '';
    try {
        const params: Record<string, unknown> = { current: page.current, pageSize: page.pageSize };
        if (filter.textbookName) {
            params.textbookName = filter.textbookName;
        }
        if (filter.chapterName) {
            params.chapterName = filter.chapterName;
        }
        if (filter.type !== '') {
            params.type = filter.type;
        }
        if (filter.keyword) {
            params.keyword = filter.keyword;
        }
        if (filter.status !== '') {
            params.status = filter.status;
        }
        result.value = await adminQuizApi.questionPage(params);
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

async function loadChapters() {
    try {
        chapters.value = (await adminQuizApi.chapterList()) ?? [];
    } catch {
        // 章节列表失败不该阻断主列表：筛选下拉会空着，但题目仍能管理
        chapters.value = [];
    }
}

function search() {
    page.current = 1;
    void load();
}

function turn(delta: number) {
    const next = page.current + delta;
    const pages = Number(result.value?.pages ?? 1);
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    void load();
}

// ==================== 新增 / 编辑 ====================

const dialogVisible = ref(false);
const saving = ref(false);
const editing = ref<AdminQuizQuestion | null>(null);

/** 表单：选项用固定 4 行，可增到 6 行（与后端上限一致） */
interface FormState {
    textbookName: string;
    chapterName: string;
    type: 0 | 1;
    stem: string;
    options: { label: string; content: string }[];
    correctLabels: string;
    analysis: string;
    difficulty: number;
    source: string;
    questionSeq: number;
}

const form = reactive<FormState>(blankForm());

function blankForm(): FormState {
    return {
        textbookName: filter.textbookName || textbookOptions.value[0] || '',
        chapterName: filter.chapterName || '',
        type: 0,
        stem: '',
        options: [
            { label: 'A', content: '' },
            { label: 'B', content: '' },
            { label: 'C', content: '' },
            { label: 'D', content: '' },
        ],
        correctLabels: '',
        analysis: '',
        difficulty: 1,
        source: '',
        questionSeq: 0,
    };
}

/** 单选题的正确项是单选的：勾一个自动取消其他 */
function toggleCorrect(label: string) {
    if (form.type === 0) {
        form.correctLabels = form.correctLabels === label ? '' : label;
        return;
    }
    form.correctLabels = form.correctLabels.includes(label)
        ? form.correctLabels.replace(label, '')
        : (form.correctLabels + label).split('').sort().join('');
}

function addOption() {
    if (form.options.length >= 6) {
        return;
    }
    const nextLabel = String.fromCharCode(65 + form.options.length);
    form.options.push({ label: nextLabel, content: '' });
}

function removeOption(index: number) {
    if (form.options.length <= 2) {
        ElMessage.warning('至少保留 2 个选项');
        return;
    }
    const removed = form.options[index].label;
    form.options.splice(index, 1);
    // 重新编号：后端要求标签从 A 连续，删掉中间一行后必须整体重排
    form.options.forEach((option, i) => {
        option.label = String.fromCharCode(65 + i);
    });
    if (form.correctLabels.includes(removed)) {
        form.correctLabels = form.correctLabels.replace(removed, '');
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, blankForm());
    dialogVisible.value = true;
}

function openEdit(row: AdminQuizQuestion) {
    editing.value = row;
    Object.assign(form, {
        textbookName: row.textbookName,
        chapterName: row.chapterName,
        type: (row.type === 1 ? 1 : 0) as 0 | 1,
        stem: row.stem,
        options: row.options.map((option: QuizOption) => ({ label: option.label, content: option.content })),
        correctLabels: row.correctLabels,
        analysis: row.analysis ?? '',
        difficulty: row.difficulty ?? 1,
        source: row.source ?? '',
        questionSeq: row.questionSeq ?? 0,
    });
    dialogVisible.value = true;
}

/**
 * 判定相关变更检测（前端侧的提示用）
 *
 * <p>与后端 `isVerdictChanged` 保持同一口径：改答案、改题型、改选项标签集合
 * 都算。只改选项文字不算——「你当时选了 C」仍指向同一个选项。
 *
 * <p>前端做这个判断只是为了<b>提前提示</b>，真正的处理在后端。
 * 两边不一致的后果只是提示多一句或少一句，不会影响数据。
 */
function isVerdictChanged(): boolean {
    const old = editing.value;
    if (!old) {
        return false;
    }
    if (old.correctLabels !== form.correctLabels || old.type !== form.type) {
        return true;
    }
    const oldLabels = old.options.map((option) => option.label).join('');
    const newLabels = form.options.map((option) => option.label).join('');
    return oldLabels !== newLabels;
}

async function save() {
    if (!form.textbookName || !form.chapterName) {
        ElMessage.warning('请填写教材与章节');
        return;
    }
    if (!form.stem.trim()) {
        ElMessage.warning('请填写题干');
        return;
    }
    if (form.options.some((option) => !option.content.trim())) {
        ElMessage.warning('选项内容不能为空');
        return;
    }
    if (!form.correctLabels) {
        ElMessage.warning('请标记正确答案');
        return;
    }

    // 改题的后果提示：有作答记录时提醒「不会重算历史判定」，
    // 判定相关变更时提醒「会重置错题连对进度」
    if (editing.value) {
        const hints: string[] = [];
        if (editing.value.answerCount > 0) {
            hints.push(`该题已有 ${editing.value.answerCount} 条作答记录，修改答案不会重算历史判定。`);
        }
        if (isVerdictChanged()) {
            hints.push('本次修改会影响判定结果，相关错题的连对进度将被重置（已消解状态保持不变）。');
        }
        if (hints.length > 0) {
            try {
                await ElMessageBox.confirm(hints.join('\n'), '确认保存', { type: 'warning' });
            } catch {
                return;
            }
        }
    }

    saving.value = true;
    try {
        const body: Record<string, unknown> = {
            textbookName: form.textbookName,
            chapterName: form.chapterName,
            type: form.type,
            stem: form.stem,
            options: form.options,
            correctLabels: form.correctLabels,
            analysis: form.analysis,
            difficulty: form.difficulty,
            source: form.source || null,
            questionSeq: form.questionSeq,
        };
        if (editing.value) {
            await adminQuizApi.questionUpdate({ id: editing.value.id, ...body });
            ElMessage.success('已保存');
        } else {
            await adminQuizApi.questionAdd(body);
            ElMessage.success('已新增');
        }
        dialogVisible.value = false;
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 具体校验错误（单选 2 个正确项、标签跳号等）已由请求层提示
    } finally {
        saving.value = false;
    }
}

// ==================== 停用 / 删除 ====================

async function toggleStatus(row: AdminQuizQuestion) {
    const next = row.status === 0 ? 1 : 0;
    try {
        await adminQuizApi.questionStatus(row.id, next as 0 | 1);
        ElMessage.success(next === 0 ? '已启用' : '已停用');
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 提示已在请求层
    }
}

async function remove(row: AdminQuizQuestion) {
    try {
        await ElMessageBox.confirm(
            row.answerCount > 0
                ? `该题有 ${row.answerCount} 条作答记录。删除后题目与相关错题条目会被移除（作答统计保留）。只想临时下线请用「停用」。`
                : '删除后不可恢复。只想临时下线请用「停用」。',
            '确认删除',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    try {
        await adminQuizApi.questionDelete(row.id);
        ElMessage.success('已删除');
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 提示已在请求层
    }
}

async function batchRemove() {
    if (selected.value.length === 0) {
        return;
    }
    try {
        await ElMessageBox.confirm(
            `将删除 ${selected.value.length} 道题，并清理对应错题条目。作答统计保留。`,
            '确认批量删除',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    try {
        const count = await adminQuizApi.questionBatchDelete(selected.value.map((item) => item.id));
        ElMessage.success(`已删除 ${count} 道题`);
        selected.value = [];
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 提示已在请求层
    }
}

// ==================== 导入 ====================

const importVisible = ref(false);
const importText = ref('');
const importTarget = reactive({ textbookName: '', chapterName: '', startSeq: 0 });
const importPreview = ref<QuizImportResult | null>(null);
const importChecking = ref(false);
const importSaving = ref(false);

const IMPORT_PLACEHOLDER = `【单选】物质的唯一特性是？
A. 运动性
B. 客观实在性*
C. 可知性
D. 矛盾性
解析：客观实在性是物质的唯一特性。

【多选】下列属于唯物辩证法基本规律的有？
A. 对立统一规律*
B. 质量互变规律*
C. 否定之否定规律*
D. 因果律
解析：因果律属于认识论范畴。`;

function openImport() {
  importText.value = '';
  importPreview.value = null;
  importTarget.textbookName = filter.textbookName || textbookOptions.value[0] || '';
  importTarget.chapterName = filter.chapterName || '';
  importTarget.startSeq = 0;
  importVisible.value = true;
}

/** 先校验（dryRun）：拿到解析数量与逐行错误 */
async function checkImport() {
    if (!importTarget.textbookName || !importTarget.chapterName) {
        ElMessage.warning('请填写教材与章节');
        return;
    }
    if (!importText.value.trim()) {
        ElMessage.warning('请粘贴题目文本');
        return;
    }
    importChecking.value = true;
    try {
        importPreview.value = await adminQuizApi.questionImport({
            textbookName: importTarget.textbookName,
            chapterName: importTarget.chapterName,
            text: importText.value,
            startSeq: importTarget.startSeq,
            dryRun: true,
        });
    } catch {
        importPreview.value = null;
    } finally {
        importChecking.value = false;
    }
}

/** 确认导入：只有校验通过（无错误）才允许 */
async function confirmImport() {
    if (!importPreview.value || importPreview.value.errors.length > 0) {
        return;
    }
    importSaving.value = true;
    try {
        const result = await adminQuizApi.questionImport({
            textbookName: importTarget.textbookName,
            chapterName: importTarget.chapterName,
            text: importText.value,
            startSeq: importTarget.startSeq,
        });
        ElMessage.success(`已导入 ${result.imported} 道题`);
        importVisible.value = false;
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 提示已在请求层
    } finally {
        importSaving.value = false;
    }
}

// ==================== 章节操作 ====================

async function renameChapter(row: AdminQuizChapter) {
    try {
        const { value } = await ElMessageBox.prompt('新的章节名称', '章节改名', {
            inputValue: row.name,
            inputPattern: /\S+/,
            inputErrorMessage: '章节名不能为空',
        });
        await adminQuizApi.chapterRename({
            textbookName: row.textbookName,
            oldName: row.name,
            newName: value.trim(),
        });
        ElMessage.success('已改名（同名章节会自动合并）');
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 用户取消或请求失败（提示已在请求层）
    }
}

async function deleteChapter(row: AdminQuizChapter) {
    try {
        await ElMessageBox.confirm(
            `将删除「${row.name}」下的全部 ${row.questionCount} 道题，并清理对应错题条目。作答统计保留。`,
            '确认整章删除',
            { type: 'warning' },
        );
        await adminQuizApi.chapterDelete(row.textbookName, row.name);
        ElMessage.success('已删除该章');
        await Promise.all([load(), loadChapters()]);
    } catch {
        // 用户取消或请求失败
    }
}

onMounted(async () => {
    await Promise.all([load(), loadChapters()]);
});
</script>

<template>
    <div class="quiz-admin">
        <div class="head">
            <div>
                <h1>题库管理</h1>
                <p class="muted">维护刷题题目；章节与教材是题目上的分组，随题目存在。</p>
            </div>
            <div class="head-actions">
                <el-button @click="openImport">批量导入</el-button>
                <el-button type="primary" @click="openCreate">新增题目</el-button>
            </div>
        </div>

        <!-- 筛选 -->
        <div class="card filters">
            <el-select v-model="filter.textbookName" placeholder="教材" clearable style="width: 180px" @change="search">
                <el-option v-for="name in textbookOptions" :key="name" :label="name" :value="name" />
            </el-select>
            <el-select v-model="filter.chapterName" placeholder="章节" clearable style="width: 200px" @change="search">
                <el-option v-for="item in chapterOptions" :key="item.name" :label="item.name" :value="item.name" />
            </el-select>
            <el-select v-model="filter.type" placeholder="题型" clearable style="width: 120px" @change="search">
                <el-option label="单选" :value="0" />
                <el-option label="多选" :value="1" />
            </el-select>
            <el-select v-model="filter.status" placeholder="状态" clearable style="width: 120px" @change="search">
                <el-option label="启用" :value="0" />
                <el-option label="停用" :value="1" />
            </el-select>
            <el-input
                v-model="filter.keyword"
                placeholder="题干关键词"
                clearable
                style="width: 200px"
                @keyup.enter="search"
            />
            <el-button type="primary" @click="search">搜索</el-button>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <!-- 统计条 -->
        <div class="stats-line">
            <span>共 {{ stats.total }} 题</span>
            <span class="dot">·</span>
            <span>本页单选 {{ stats.single }}</span>
            <span class="dot">·</span>
            <span>多选 {{ stats.multiple }}</span>
            <span class="dot">·</span>
            <span>已停用 {{ stats.disabled }}</span>
            <span class="spacer" />
            <el-button v-if="selected.length > 0" type="danger" plain size="small" @click="batchRemove">
                批量删除（{{ selected.length }}）
            </el-button>
        </div>

        <!-- 表格 -->
        <div class="card table-card">
            <el-table
                v-loading="loading"
                :data="result?.records ?? []"
                row-key="id"
                @selection-change="(rows: AdminQuizQuestion[]) => (selected = rows)"
            >
                <el-table-column type="selection" width="44" />
                <el-table-column label="题干" min-width="280">
                    <template #default="{ row }">
                        <div class="stem">{{ row.stem }}</div>
                        <div class="muted small">{{ row.analysis ? '有解析' : '无解析' }}</div>
                    </template>
                </el-table-column>
                <el-table-column label="题型" width="80">
                    <template #default="{ row }">
                        <el-tag size="small" :type="row.type === 1 ? 'warning' : 'info'">
                            {{ row.type === 1 ? '多选' : '单选' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="教材 / 章节" min-width="180">
                    <template #default="{ row }">
                        <div class="small">{{ row.textbookName }}</div>
                        <div class="muted small">{{ row.chapterName }}</div>
                    </template>
                </el-table-column>
                <el-table-column label="答案" width="90">
                    <template #default="{ row }">
                        <span class="answer">{{ row.correctLabels }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="作答" width="80">
                    <template #default="{ row }">{{ row.answerCount }}</template>
                </el-table-column>
                <el-table-column label="正确率" width="90">
                    <template #default="{ row }">
                        <!-- 0 次作答时显示 "--"：0% 读起来像「全错」 -->
                        {{ row.accuracy === null ? '--' : `${Math.round(row.accuracy * 100)}%` }}
                    </template>
                </el-table-column>
                <el-table-column label="状态" width="80">
                    <template #default="{ row }">
                        <el-tag size="small" :type="row.status === 0 ? 'success' : 'info'">
                            {{ row.status === 0 ? '启用' : '停用' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                    <template #default="{ row }">
                        <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                        <el-button link @click="toggleStatus(row)">
                            {{ row.status === 0 ? '停用' : '启用' }}
                        </el-button>
                        <el-button link type="danger" @click="remove(row)">删除</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <div class="pager">
                <el-button link :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
                <span class="muted small">第 {{ page.current }} / {{ Number(result?.pages ?? 1) }} 页</span>
                <el-button
                    link
                    :disabled="page.current >= Number(result?.pages ?? 1)"
                    @click="turn(1)"
                >
                    下一页
                </el-button>
            </div>
        </div>

        <!-- 章节管理（不是独立实体：只有改名 / 整章删除） -->
        <div class="card chapter-card">
            <h2>章节概览</h2>
            <p class="muted small">
                章节是题目上的分组，随题目存在；「新增章节」就是往该章新增一道题。
            </p>
            <el-table :data="chapters" size="small" max-height="320">
                <el-table-column prop="textbookName" label="教材" min-width="160" />
                <el-table-column prop="name" label="章节" min-width="200" />
                <el-table-column label="题数" width="120">
                    <template #default="{ row }">
                        {{ row.questionCount }}（启用 {{ row.enabledCount }}）
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="150">
                    <template #default="{ row }">
                        <el-button link type="primary" @click="renameChapter(row)">改名</el-button>
                        <el-button link type="danger" @click="deleteChapter(row)">整章删除</el-button>
                    </template>
                </el-table-column>
            </el-table>
        </div>

        <!-- 新增 / 编辑抽屉 -->
        <el-drawer v-model="dialogVisible" :title="editing ? '编辑题目' : '新增题目'" size="720px">
            <div class="form">
                <div class="form-row">
                    <div class="form-field">
                        <label>教材</label>
                        <el-select v-model="form.textbookName" filterable allow-create placeholder="选择或输入新教材">
                            <el-option v-for="name in textbookOptions" :key="name" :label="name" :value="name" />
                        </el-select>
                    </div>
                    <div class="form-field">
                        <label>章节</label>
                        <el-select v-model="form.chapterName" filterable allow-create placeholder="选择或输入新章节">
                            <el-option
                                v-for="item in chapterOptions.filter((c) => c.textbookName === form.textbookName)"
                                :key="item.name"
                                :label="item.name"
                                :value="item.name"
                            />
                        </el-select>
                    </div>
                </div>

                <div class="form-row">
                    <div class="form-field">
                        <label>题型</label>
                        <el-radio-group v-model="form.type" @change="form.correctLabels = ''">
                            <el-radio-button :value="0">单选</el-radio-button>
                            <el-radio-button :value="1">多选</el-radio-button>
                        </el-radio-group>
                        <div class="muted small">
                            {{ form.type === 1 ? '多选至少 2 个正确项，全对才得分' : '单选恰好 1 个正确项' }}
                        </div>
                    </div>
                    <div class="form-field">
                        <label>难度 / 题序</label>
                        <div class="inline">
                            <el-select v-model="form.difficulty" style="width: 100px">
                                <el-option label="易" :value="1" />
                                <el-option label="中" :value="2" />
                                <el-option label="难" :value="3" />
                            </el-select>
                            <el-input-number v-model="form.questionSeq" :min="0" controls-position="right" />
                        </div>
                    </div>
                </div>

                <div class="form-field">
                    <label>题干</label>
                    <el-input
                        v-model="form.stem"
                        type="textarea"
                        :rows="3"
                        maxlength="2000"
                        show-word-limit
                        placeholder="纯文本，不做 Markdown 渲染"
                    />
                </div>

                <div class="form-field">
                    <label>选项（勾选「正确」标记答案，标签从 A 连续）</label>
                    <div v-for="(option, index) in form.options" :key="index" class="option-row">
                        <span class="option-label">{{ option.label }}</span>
                        <el-input v-model="option.content" placeholder="选项内容" maxlength="500" />
                        <el-checkbox
                            :model-value="form.correctLabels.includes(option.label)"
                            @change="toggleCorrect(option.label)"
                        >
                            正确
                        </el-checkbox>
                        <el-button link type="danger" @click="removeOption(index)">删除</el-button>
                    </div>
                    <el-button v-if="form.options.length < 6" link type="primary" @click="addOption">
                        + 添加选项
                    </el-button>
                </div>

                <div class="form-field">
                    <label>解析（选填）</label>
                    <el-input
                        v-model="form.analysis"
                        type="textarea"
                        :rows="3"
                        maxlength="2000"
                        show-word-limit
                        placeholder="只在作答之后下发给学员"
                    />
                </div>

                <div class="form-field">
                    <label>题源（选填）</label>
                    <el-input v-model="form.source" maxlength="128" placeholder="如：2023年真题" />
                </div>

                <div class="form-actions">
                    <el-button @click="dialogVisible = false">取消</el-button>
                    <el-button type="primary" :loading="saving" @click="save">保存</el-button>
                </div>
            </div>
        </el-drawer>

        <!-- 导入对话框 -->
        <el-dialog v-model="importVisible" title="批量导入题目" width="760px">
            <div class="import-form">
                <div class="form-row">
                    <div class="form-field">
                        <label>教材</label>
                        <el-select v-model="importTarget.textbookName" filterable allow-create>
                            <el-option v-for="name in textbookOptions" :key="name" :label="name" :value="name" />
                        </el-select>
                    </div>
                    <div class="form-field">
                        <label>章节</label>
                        <el-select v-model="importTarget.chapterName" filterable allow-create>
                            <el-option
                                v-for="item in chapterOptions.filter(
                                    (c) => c.textbookName === importTarget.textbookName,
                                )"
                                :key="item.name"
                                :label="item.name"
                                :value="item.name"
                            />
                        </el-select>
                    </div>
                    <div class="form-field">
                        <label>起始题序</label>
                        <el-input-number v-model="importTarget.startSeq" :min="0" controls-position="right" />
                    </div>
                </div>

                <div class="form-field">
                    <label>粘贴题目文本</label>
                    <el-input
                        v-model="importText"
                        type="textarea"
                        :rows="12"
                        :placeholder="IMPORT_PLACEHOLDER"
                    />
                </div>

                <!-- 校验结果：数量 + 逐行错误 -->
                <div v-if="importPreview" class="preview">
                    <el-alert
                        v-if="importPreview.errors.length === 0"
                        type="success"
                        :closable="false"
                        show-icon
                        :title="`解析出 ${importPreview.parsed} 道题，全部合法`"
                    />
                    <template v-else>
                        <el-alert
                            type="error"
                            :closable="false"
                            show-icon
                            :title="`有 ${importPreview.errors.length} 处错误，整批不会落库`"
                        />
                        <ul class="error-list">
                            <li v-for="(item, index) in importPreview.errors" :key="index">
                                第 {{ item.line }} 行：{{ item.message }}
                            </li>
                        </ul>
                    </template>
                </div>
            </div>

            <template #footer>
                <el-button @click="importVisible = false">取消</el-button>
                <el-button :loading="importChecking" @click="checkImport">校验</el-button>
                <el-button
                    type="primary"
                    :loading="importSaving"
                    :disabled="!importPreview || importPreview.errors.length > 0"
                    @click="confirmImport"
                >
                    确认导入
                </el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
    flex-wrap: wrap;
}

.head h1 {
    margin: 0 0 4px;
    font-size: 22px;
    font-weight: 700;
}

.head p {
    margin: 0;
    font-size: 13px;
}

.head-actions {
    display: flex;
    gap: 10px;
}

.filters {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 14px;
    padding: 16px;
}

.stats-line {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    font-size: 13px;
    color: var(--muted);
    flex-wrap: wrap;
}

.stats-line .dot {
    opacity: 0.5;
}

.stats-line .spacer {
    flex: 1;
}

.table-card {
    padding: 8px 8px 0;
    margin-bottom: 18px;
}

.stem {
    font-size: 14px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.answer {
    font-weight: 600;
    letter-spacing: 1px;
    color: var(--brand);
}

.small {
    font-size: 12px;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 12px 0;
}

.chapter-card {
    margin-bottom: 18px;
}

.chapter-card h2 {
    margin: 0 0 4px;
    font-size: 17px;
    font-weight: 600;
}

.chapter-card p {
    margin: 0 0 12px;
}

/* ===== 表单 ===== */
.form {
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.form-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
}

.form-field {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.form-field label {
    font-size: 13px;
    color: var(--muted);
}

.inline {
    display: flex;
    gap: 8px;
}

.option-row {
    display: flex;
    align-items: center;
    gap: 10px;
}

.option-label {
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: var(--brand-soft);
    color: var(--brand-dark);
    font-size: 12px;
    font-weight: 600;
    line-height: 24px;
    text-align: center;
}

.form-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    padding-top: 8px;
    border-top: 1px solid var(--border);
}

/* ===== 导入 ===== */
.import-form {
    display: flex;
    flex-direction: column;
    gap: 14px;
}

.preview {
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.error-list {
    margin: 0;
    padding-left: 20px;
    font-size: 13px;
    color: var(--danger);
    line-height: 1.8;
    max-height: 160px;
    overflow-y: auto;
}

/* 手机：表单单列 */
@media (max-width: 640px) {
    .form-row {
        grid-template-columns: 1fr;
    }
}
</style>
