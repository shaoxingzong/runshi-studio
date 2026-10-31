<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';
import { useUserStore } from '@/stores/user';
import ImageUploader from './ImageUploader.vue';

/**
 * 成员编辑自己的展示资料
 *
 * 从 Workspace 和右上角账号菜单共用：只开放展示字段，登录账号/届别/职务等
 * 由后端 DTO 白名单约束，前端不提供输入口。
 */
const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    saved: [];
}>();

const visible = computed({
    get: () => props.modelValue,
    set: (value: boolean) => emit('update:modelValue', value),
});

const user = useUserStore();
const saving = ref(false);

const form = reactive({
    name: '',
    avatar: '',
    major: '',
    direction: '',
    githubUrl: '',
    summary: '',
});

/** 每次打开都用当前档案回填，避免带进上一次编辑的残留值 */
function fill() {
    const profile = user.memberProfile;
    form.name = profile?.name ?? '';
    form.avatar = profile?.avatar ?? '';
    form.major = profile?.major ?? '';
    form.direction = profile?.direction ?? '';
    form.githubUrl = profile?.githubUrl ?? '';
    form.summary = profile?.summary ?? '';
}

watch(
    () => props.modelValue,
    (open) => {
        if (open) {
            fill();
        }
    },
    { immediate: true },
);

async function save() {
    const name = form.name.trim();
    if (!name) {
        ElMessage.warning('姓名不能为空');
        return;
    }
    const githubUrl = form.githubUrl.trim();
    if (githubUrl && !/^https?:\/\//i.test(githubUrl)) {
        ElMessage.warning('GitHub 地址需要以 http:// 或 https:// 开头');
        return;
    }

    saving.value = true;
    try {
        // 空串表示清空该字段；账号/届别/职务/状态不在请求体内
        const updated = await userApi.updateMyMember({
            name,
            avatar: form.avatar.trim(),
            major: form.major.trim(),
            direction: form.direction.trim(),
            githubUrl,
            summary: form.summary.trim(),
        });
        // 直接写回 store：导航展示名与「我的资料」卡片即时刷新
        user.memberProfile = updated;
        ElMessage.success('资料已更新');
        emit('saved');
        visible.value = false;
    } catch {
        // 错误提示已在请求层统一处理
    } finally {
        saving.value = false;
    }
}
</script>

<template>
    <el-dialog v-model="visible" title="编辑我的资料" width="520px" append-to-body>
        <p class="dialog-tip">
            登录账号不可修改；以下信息会展示在文章作者与项目成员卡片上。
        </p>
        <el-form label-position="top">
            <el-form-item label="登录账号">
                <span class="account">{{ user.account }}（不可修改，如需更换请联系管理员）</span>
            </el-form-item>
            <el-form-item label="姓名" required>
                <el-input v-model="form.name" maxlength="64" placeholder="展示在文章与项目成员卡片上的姓名" />
            </el-form-item>
            <el-form-item label="头像">
                <ImageUploader v-model="form.avatar" tip="可选；用于帖子作者与项目成员卡片展示" />
            </el-form-item>
            <el-form-item label="专业">
                <el-input v-model="form.major" maxlength="128" />
            </el-form-item>
            <el-form-item label="技术方向">
                <el-input v-model="form.direction" maxlength="128" />
            </el-form-item>
            <el-form-item label="GitHub">
                <el-input v-model="form.githubUrl" maxlength="256" placeholder="https://github.com/your-name" />
            </el-form-item>
            <el-form-item label="个人简介">
                <el-input
                    v-model="form.summary"
                    type="textarea"
                    :rows="3"
                    maxlength="512"
                    show-word-limit
                />
            </el-form-item>
        </el-form>
        <template #footer>
            <el-button @click="visible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.dialog-tip {
    margin: -4px 0 16px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.7;
}

.account {
    color: var(--muted);
    font-size: 13px;
}
</style>
