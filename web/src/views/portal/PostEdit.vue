<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { MdEditor } from 'md-editor-v3';
import 'md-editor-v3/lib/style.css';
import { postApi, uploadPostImage } from '@/api/post';
import { useUserStore } from '@/stores/user';

/**
 * 发帖（仅工作室成员）
 *
 * <p><b>准入判断</b>：进入页面时会主动拉一次「我的成员档案」——
 * 不能只看 localStorage 里的角色（角色是权限标签，可能与成员档案不同步）。
 * 后端同样只准成员发帖，这里是同一规则的界面表达，不是真正的拦截。
 */
const router = useRouter();
const user = useUserStore();

const form = reactive({ title: '', content: '', summary: '', coverImage: '' });
const submitting = ref(false);
const coverInput = ref<HTMLInputElement | null>(null);

onMounted(async () => {
    // 布局不会在路由切换时重建，这里主动拉一次确保拿到最新的成员身份
    await user.loadMemberProfile();
    if (!user.isMember) {
        ElMessage.warning('仅工作室成员可以发帖');
        router.replace('/posts');
    }
});

/**
 * 编辑器上传图片
 *
 * <p>必须上传到站内并把返回的 `/uploads/...` 路径回调给编辑器：
 * 后端会拒绝正文里的外链图（防追踪像素与裂图），
 * 而 base64 内嵌会让正文膨胀到难以存储。
 */
async function onUploadImg(files: Array<File>, callback: (urls: string[]) => void) {
    try {
        const urls = await Promise.all(files.map((file) => uploadPostImage(file)));
        callback(urls);
    } catch {
        // 失败时不回调：编辑器会把这次插入作废，用户看到的是「图片没进去」，
        // 比塞一个坏链接进去更好排查
        ElMessage.error('图片上传失败，请重试');
    }
}

/**
 * 编辑器实例（只声明本页用到的 expose 方法）
 *
 * <p>md-editor-v3 的 `insert` 能把内容插到**当前光标处**——这正是粘贴 / 拖拽上传需要的；
 * 若改用 `v-model` 整体赋值，插入点会被推到正文末尾。
 */
type EditorExpose = {
    insert: (generate: (selectedText: string) => { targetValue: string }) => void;
};
const editorRef = ref<EditorExpose | null>(null);

/** 正在上传的图片张数（用于界面提示，0 表示空闲） */
const uploading = ref(0);

/**
 * 上传图片并插入正文光标处（粘贴与拖拽共用）
 *
 * <p>与工具栏上传的分工：两条链路最终都走 {@link uploadPostImage}，
 * 差别只在「谁触发」和「插到哪」——这里由粘贴 / 拖拽触发，并显式插到光标处。
 *
 * @param files 待处理的文件列表（可能混有非图片，内部会过滤）
 */
async function insertImages(files: File[]) {
    const images = files.filter((file) => file.type.startsWith('image/'));
    if (!images.length) {
        return;
    }

    uploading.value += images.length;
    try {
        for (const file of images) {
            // 必须是站内路径：后端会拒绝正文里的外链图，base64 内嵌又会让正文膨胀
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

/**
 * 粘贴图片（Ctrl+V 截图即可插入正文）
 *
 * <p>md-editor-v3 **没有**暴露 paste 事件（`EditorEmits` 里没有它），
 * 但 contenteditable 区域内的 paste 会冒泡到外层容器，因此在这里统一接管。
 *
 * <p>⚠️ `preventDefault` 必须在**同步阶段**调用：等 `await` 之后再调就无效了
 * （事件已分发完毕，默认的「插入文本」行为早已发生）。
 */
async function onPaste(event: ClipboardEvent) {
    const files = Array.from(event.clipboardData?.files ?? []);
    if (!files.some((file) => file.type.startsWith('image/'))) {
        return; // 纯文本 / HTML 粘贴：放行，交给编辑器默认行为
    }
    event.preventDefault();
    await insertImages(files);
}

/**
 * 拖拽图片到编辑器
 *
 * <p>md-editor-v3 用 `onDrop` prop 透传拖拽事件。
 * ⚠️ 必须 `preventDefault`：浏览器默认会把拖入的图片当作「打开文件」直接导航走，
 * 正文里什么都不会留下。
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
    } finally {
        // 清空 value，否则连续选同一张图不会再触发 change
        input.value = '';
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
        await postApi.add({
            title: form.title.trim(),
            content: form.content,
            summary: form.summary.trim() || undefined,
            // 封面留空时后端会自动取正文首图
            coverImage: form.coverImage || undefined,
        });
        // 先审后发：此刻内容是待审状态，不会出现在公开列表里
        ElMessage.success('已提交，审核通过后可见');
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
                <h1>写文章</h1>
                <p>支持 Markdown；可直接粘贴或拖拽图片，也可点工具栏上传。</p>
            </div>
            <el-button @click="router.push('/posts/my')">我的发帖</el-button>
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

            <!-- 外层容器接管 paste：md-editor-v3 没有 paste 事件，
                 但 contenteditable 区域内的粘贴会冒泡到这里 -->
            <div class="editor-wrap" @paste="onPaste">
                <MdEditor
                    ref="editorRef"
                    v-model="form.content"
                    :on-upload-img="onUploadImg"
                    :on-drop="onDrop"
                    class="md"
                />
            </div>

            <div class="foot">
                <span class="muted">
                    <template v-if="uploading">正在上传图片…</template>
                    <template v-else>提交后需要审核，通过后才会出现在社区列表</template>
                </span>
                <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
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
</style>
