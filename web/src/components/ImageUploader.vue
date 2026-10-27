<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { uploadFile } from '@/api/file';

// 通用图片上传组件（`v-model` 绑定站内相对路径）
//
// 把「选图 → 上传 → 回填路径 → 预览」串成一步，省得管理员手填 URL。
// 绑定值始终是站内相对路径（如 `/uploads/2026/10/xxxx.png`），预览时直接当 `src`
// （开发态由 Vite 代理 `/uploads`）。
defineProps<{
    // 当前图片相对路径；空串表示未上传
    modelValue?: string | null;
    // 输入下方的说明文字
    tip?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: string] }>();

const uploading = ref(false);

async function onSelect(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
        return;
    }
    uploading.value = true;
    try {
        const path = await uploadFile(file);
        emit('update:modelValue', path);
        ElMessage.success('图片已上传');
    } catch {
        // 失败提示已在 uploadFile 内统一处理
    } finally {
        uploading.value = false;
        // 清空 input：否则再次选择同一个文件不会触发 change
        input.value = '';
    }
}

function clear() {
    emit('update:modelValue', '');
}
</script>

<template>
    <div class="uploader">
        <div class="preview">
            <img v-if="modelValue" :src="modelValue" alt="图片预览" />
            <span v-else class="empty">未上传</span>
        </div>
        <div class="ops">
            <label class="pick" :class="{ disabled: uploading }">
                <input
                    type="file"
                    accept="image/png,image/jpeg"
                    :disabled="uploading"
                    @change="onSelect"
                />
                {{ uploading ? '上传中…' : '选择图片' }}
            </label>
            <el-button v-if="modelValue" link type="danger" @click="clear">移除</el-button>
        </div>
        <p v-if="tip" class="tip">{{ tip }}</p>
    </div>
</template>

<style scoped>
.uploader {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.preview {
    width: 120px;
    height: 84px;
    border: 1px dashed #d1d5db;
    border-radius: 6px;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f9fafb;
}

.preview img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
}

.empty {
    font-size: 12px;
    color: #9ca3af;
}

.ops {
    display: flex;
    align-items: center;
    gap: 10px;
}

/* label 关联隐藏的 file input：不手写 Content-Type，交给浏览器处理 multipart boundary */
.pick {
    display: inline-flex;
    align-items: center;
    padding: 6px 14px;
    border: 1px solid var(--brand);
    border-radius: 6px;
    color: var(--brand);
    font-size: 13px;
    cursor: pointer;
    transition: background 0.2s;
}

.pick:hover {
    background: var(--brand-soft);
}

.pick.disabled {
    cursor: not-allowed;
    opacity: 0.6;
}

.pick input {
    display: none;
}

.tip {
    margin: 0;
    font-size: 12px;
    line-height: 1.5;
    color: #6b7280;
}
</style>
