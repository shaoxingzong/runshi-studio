<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { MdEditor } from 'md-editor-v3';
import 'md-editor-v3/lib/style.css';
import { postApi, uploadPostImage } from '@/api/post';
import { generateMeta, polishStream } from '@/api/aiCompose';
import { isRequestError } from '@/api/request';
import { useUserStore } from '@/stores/user';

/** 发帖 / 修改自己的文章（仅工作室成员；修改后重新送审） */
const router = useRouter();
const route = useRoute();
const user = useUserStore();

const form = reactive({ title: '', content: '', summary: '', coverImage: '' });
const submitting = ref(false);
const coverInput = ref<HTMLInputElement | null>(null);

/** 改稿模式：正在修改的帖子 id（空串 = 新建） */
const editingId = ref('');
/** 原稿审核状态：0-待审, 1-已通过, 2-已驳回；用于文案与「查看原文」入口 */
const editingStatus = ref<number | null>(null);
/** 改稿模式下原稿的加载态：正文没回来之前禁用保存，避免提交半模态数据 */
const loadingDraft = ref(false);

const isEditing = computed(() => !!editingId.value);

onMounted(async () => {
    // 布局不会在路由切换时重建，这里主动拉一次确保拿到最新的成员身份
    await user.loadMemberProfile();
    if (!user.isMember) {
        ElMessage.warning('仅工作室成员可以发帖');
        router.replace('/posts');
        return;
    }
    const editId = typeof route.query.edit === 'string' ? route.query.edit : '';
    if (editId) {
        await loadDraft(editId);
    }
});

/**
 * 加载待修改的原稿
 *
 * 正文必须单独拉：列表 VO 刻意不含 content，而公开详情只返回已通过的帖子。
 */
async function loadDraft(postId: string) {
    loadingDraft.value = true;
    try {
        const detail = await postApi.myDetail(postId);
        editingId.value = postId;
        editingStatus.value = detail.status ?? null;
        form.title = detail.title;
        form.summary = detail.summary ?? '';
        form.coverImage = detail.coverImage ?? '';
        form.content = detail.content ?? '';
    } catch (e) {
        if (!isRequestError(e) || !e.notified) {
            ElMessage.error((e as Error).message || '原稿加载失败');
        }
        router.replace('/posts/my');
    } finally {
        loadingDraft.value = false;
    }
}

/** 编辑器上传图片 */
async function onUploadImg(files: Array<File>, callback: (urls: string[]) => void) {
    try {
        const urls = await Promise.all(files.map((file) => uploadPostImage(file)));
        callback(urls);
    } catch {
        ElMessage.error('图片上传失败，请重试');
    }
}

/** 编辑器实例（只声明本页用到的 expose 方法） */
type EditorExpose = {
    insert: (generate: (selectedText: string) => { targetValue: string }) => void;
    getSelectedText: () => string | undefined;
};
const editorRef = ref<EditorExpose | null>(null);

/** 正在上传的图片张数（用于界面提示，0 表示空闲） */
const uploading = ref(0);

/**
 * 上传图片并插入正文光标处（粘贴与拖拽共用），与工具栏上传最终都走 uploadPostImage
 *
 * @param files 待处理文件（可能混有非图片，内部会过滤）
 */
async function insertImages(files: File[]) {
    const images = files.filter((file) => file.type.startsWith('image/'));
    if (!images.length) {
        return;
    }

    uploading.value += images.length;
    try {
        for (const file of images) {
            /** 必须是站内路径：后端会拒绝正文里的外链图，base64 内嵌又会让正文膨胀 */
            const url = await uploadPostImage(file);
            // 末尾补一个换行：连续插入多张图时不会挤在同一行
            editorRef.value?.insert(() => ({ targetValue: `![](${url})\n` }));
        }
        ElMessage.success(`已插入 ${images.length} 张图片`);
    } catch {
        // 已成功的图片保持插入状态，只让失败的那张重试，不做整批回滚
        ElMessage.error('部分图片上传失败，请重试');
    } finally {
        uploading.value = Math.max(0, uploading.value - images.length);
    }
}

/** 粘贴图片（Ctrl+V 截图即可插入正文） */
async function onPaste(event: ClipboardEvent) {
    const files = Array.from(event.clipboardData?.files ?? []);
    if (!files.some((file) => file.type.startsWith('image/'))) {
        return; // 纯文本 / HTML 粘贴：放行，交给编辑器默认行为
    }
    event.preventDefault();
    await insertImages(files);
}

/**
 * 拖拽图片到编辑器（md-editor-v3 用 `onDrop` prop 透传）
 *
 * 必须 `preventDefault`：浏览器默认会把拖入的图片当作「打开文件」直接导航走，正文什么都不剩。
 */
async function onDrop(event: DragEvent) {
    const files = Array.from(event.dataTransfer?.files ?? []);
    if (!files.some((file) => file.type.startsWith('image/'))) {
        return; // 拖入的不是图片（如 PDF）：放行
    }
    event.preventDefault();
    await insertImages(files);
}

async function onCoverPicked(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
        return;
    }
    try {
        form.coverImage = await uploadPostImage(file);
        ElMessage.success('封面已上传');
    } catch (e) {
        ElMessage.error((e as Error).message || '封面上传失败，请重试');
    } finally {
        // 清空 value，否则连续选同一张图不会再触发 change
        input.value = '';
    }
}

/** 润色结果（流式累积）；非空时显示「采用 / 取消」面板 */
const polishText = ref('');
/** 是否正在润色（含首字等待） */
const polishing = ref(false);
const polishSourceLength = ref(0);
/** 润色失败的提示（面板内展示，不打断用户） */
const polishError = ref('');
let polishAbort: AbortController | null = null;

/** 是否正在生成标题摘要 */
const generatingMeta = ref(false);

/** 润色当前选中的文字 */
async function startPolish() {
    const selected = editorRef.value?.getSelectedText() ?? '';
    if (!selected.trim()) {
        ElMessage.warning('请先在正文中选中要润色的文字');
        return;
    }
    if (selected.length > 2000) {
        ElMessage.warning('润色片段不能超过 2000 字，请分段处理');
        return;
    }

    polishAbort?.abort();
    polishAbort = new AbortController();
    polishing.value = true;
    polishText.value = '';
    polishError.value = '';
    polishSourceLength.value = selected.length;

    try {
        await polishStream(
            { text: selected },
            {
                onDelta: (delta) => {
                    polishText.value += delta;
                },
                onDone: (payload) => {
                    // done 带完整文本：以它为准，避免增量丢失导致少字
                    if (payload.text) {
                        polishText.value = payload.text;
                    }
                },
                onError: (payload) => {
                    polishError.value = payload.message || '润色失败，请重试';
                },
            },
            polishAbort.signal,
        );
    } catch (e) {
        // 用户主动中止不算错误
        if ((e as Error).name !== 'AbortError') {
            polishError.value = (e as Error).message || '润色失败，请重试';
        }
    } finally {
        polishing.value = false;
    }
}

/** 采用润色结果：替换编辑器里当前选中的内容 */
function applyPolish() {
    if (!polishText.value.trim()) {
        return;
    }
    editorRef.value?.insert(() => ({ targetValue: polishText.value }));
    polishText.value = '';
    polishError.value = '';
    ElMessage.success('已替换选中内容');
}
function discardPolish() {
    polishAbort?.abort();
    polishAbort = null;
    polishText.value = '';
    polishError.value = '';
}

/**
 * 生成标题与摘要
 *
 * 覆盖前必须确认：用户可能已经手写过满意的标题，静默覆盖等于丢数据。
 */
async function generateTitleSummary() {
    if (!form.content.trim()) {
        ElMessage.warning('请先写正文，再生成标题和摘要');
        return;
    }
    if (form.title.trim() || form.summary.trim()) {
        try {
            await ElMessageBox.confirm('将覆盖当前的标题和摘要，是否继续？', 'AI 生成', {
                confirmButtonText: '覆盖并生成',
                cancelButtonText: '取消',
                type: 'warning',
            });
        } catch {
            return;
        }
    }

    generatingMeta.value = true;
    try {
        const result = await generateMeta({
            content: form.content,
            title: form.title.trim() || undefined,
        });
        form.title = result.title;
        form.summary = result.summary;
        ElMessage.success('已生成标题和摘要');
    } catch (e) {
        ElMessage.error((e as Error).message || '生成失败，请重试');
    } finally {
        generatingMeta.value = false;
    }
}

async function submit() {
    if (!form.title.trim()) {
        ElMessage.warning('请填写标题');
        return;
    }
    if (!form.content.trim()) {
        ElMessage.warning('请填写正文');
        return;
    }
    submitting.value = true;
    try {
        const payload = {
            title: form.title.trim(),
            content: form.content,
            summary: form.summary.trim() || undefined,
            // 封面留空时后端会自动取正文首图
            coverImage: form.coverImage || undefined,
        };
        if (isEditing.value) {
            await postApi.updateMine({ id: editingId.value, ...payload });
            // 改完必然回到待审：这句话必须说清楚，否则作者会以为改完就生效了
            ElMessage.success('已保存，文章已重新送审');
        } else {
            await postApi.add(payload);
            // 先审后发：此刻内容是待审状态，不会出现在公开列表里
            ElMessage.success('已提交，审核通过后可见');
        }
        router.push('/posts/my');
    } finally {
        submitting.value = false;
    }
}
</script>

<template>
    <div class="page">
        <div class="page-head">
            <div>
                <h1>{{ isEditing ? '修改文章' : '写文章' }}</h1>
                <p v-if="isEditing">修改后将重新送审，审核通过前不会在社区显示。</p>
                <p v-else>支持 Markdown；可直接粘贴或拖拽图片，也可点工具栏上传。</p>
            </div>
            <div class="head-actions">
                <el-button
                    v-if="isEditing && editingStatus === 1"
                    @click="router.push(`/posts/${editingId}`)"
                >
                    查看原文
                </el-button>
                <el-button @click="router.push('/posts/my')">我的文章</el-button>
            </div>
        </div>

        <div class="card editor-card">
            <el-input v-model="form.title" placeholder="文章标题" maxlength="128" class="title-input" />

            <div class="cover-row">
                <input
                    ref="coverInput"
                    type="file"
                    accept="image/png,image/jpeg"
                    hidden
                    @change="onCoverPicked"
                />
                <el-button @click="coverInput?.click()">上传封面</el-button>
                <el-input
                    v-model="form.summary"
                    placeholder="摘要（选填，留空会自动截取正文开头）"
                    maxlength="512"
                    class="summary-input"
                />
                <img v-if="form.coverImage" :src="form.coverImage" alt="封面预览" class="cover-preview" />
            </div>

            <!-- 外层容器接管 paste：md-editor-v3 没有 paste 事件，但 contenteditable 区域内的粘贴会冒泡到这里 -->
            <div class="editor-wrap" @paste="onPaste">
                <MdEditor
                    ref="editorRef"
                    v-model="form.content"
                    :on-upload-img="onUploadImg"
                    :on-drop="onDrop"
                    class="md"
                />
            </div>

            <div class="ai-panel">
                <div class="ai-head">
                    <span class="ai-title">AI 写作助手</span>
                    <div class="ai-actions">
                        <!-- @mousedown.prevent：点按钮不抢走编辑器焦点，否则选区会被清空 -->
                        <el-button
                            size="small"
                            :loading="polishing"
                            @mousedown.prevent
                            @click="startPolish"
                        >
                            润色选中
                        </el-button>
                        <el-button size="small" :loading="generatingMeta" @click="generateTitleSummary">
                            生成标题+摘要
                        </el-button>
                    </div>
                </div>

                <div v-if="polishing || polishText || polishError" class="ai-result">
                    <div v-if="polishError" class="ai-error">{{ polishError }}</div>
                    <template v-else>
                        <div class="ai-result-head">
                            <span>润色结果（原文 {{ polishSourceLength }} 字）</span>
                            <span v-if="polishing" class="ai-status">生成中…</span>
                        </div>
                        <div class="ai-result-body">{{ polishText }}</div>
                        <div class="ai-result-foot">
                            <el-button
                                size="small"
                                type="primary"
                                :disabled="polishing || !polishText.trim()"
                                @click="applyPolish"
                            >
                                采用并替换
                            </el-button>
                            <el-button size="small" :disabled="polishing" @click="discardPolish">
                                取消
                            </el-button>
                        </div>
                    </template>
                </div>
                <p v-else class="ai-hint">
                    在正文中选中一段文字后点「润色选中」；或写完后点「生成标题+摘要」。
                </p>
            </div>

            <div class="foot">
                <span class="muted">
                    <template v-if="uploading">正在上传图片…</template>
                    <template v-else>提交后需要审核，通过后才会出现在社区列表</template>
                </span>
                <el-button
                    type="primary"
                    :loading="submitting"
                    :disabled="loadingDraft"
                    @click="submit"
                >
                    {{ isEditing ? '保存修改' : '提交' }}
                </el-button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.page-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
}

.head-actions {
    display: flex;
    flex-shrink: 0;
    gap: 10px;
}

.editor-card {
    padding: 24px;
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.title-input {
    font-size: 18px;
}

.cover-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
}

.summary-input {
    flex: 1;
    min-width: 240px;
}

.cover-preview {
    width: 96px;
    height: 54px;
    object-fit: cover;
    border-radius: 6px;
    border: 1px solid var(--border);
}

.md {
    height: 520px;
}

.foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

/* ==================== AI 写作助手 ==================== */

.ai-panel {
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px 14px;
    background: var(--surface-soft, #fafafa);
}

.ai-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.ai-title {
    font-size: 14px;
    font-weight: 600;
}

.ai-actions {
    display: flex;
    gap: 8px;
}

.ai-hint {
    margin: 8px 0 0;
    font-size: 12px;
    color: var(--text-muted, #909399);
}

.ai-result {
    margin-top: 10px;
}

.ai-result-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
    color: var(--text-muted, #909399);
    margin-bottom: 6px;
}

.ai-status {
    color: var(--primary, #409eff);
}

.ai-result-body {
    max-height: 220px;
    overflow-y: auto;
    padding: 10px 12px;
    border: 1px dashed var(--border);
    border-radius: 6px;
    background: var(--surface, #fff);
    white-space: pre-wrap;
    line-height: 1.7;
    font-size: 14px;
}

.ai-result-foot {
    display: flex;
    gap: 8px;
    margin-top: 8px;
}

.ai-error {
    padding: 8px 12px;
    border-radius: 6px;
    background: #fef0f0;
    color: #f56c6c;
    font-size: 13px;
}
</style>
