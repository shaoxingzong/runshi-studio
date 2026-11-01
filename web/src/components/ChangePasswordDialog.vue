<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { userApi } from '@/api/user';

/**
 * 修改自己的密码
 *
 * 成功即意味着后端已清理该账号的全部会话（含当前这一个），
 * 所以调用方必须在 changed 事件里清本地登录态并跳登录页——否则界面会假装还登录着。
 */
const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    changed: [];
}>();

const visible = computed({
    get: () => props.modelValue,
    set: (value: boolean) => emit('update:modelValue', value),
});

const formRef = ref<FormInstance>();
const saving = ref(false);

const form = reactive({
    oldPassword: '',
    newPassword: '',
    checkPassword: '',
});

/** 每次打开都清空：密码不该在内存里多留一次，也避免带进上次的残留值 */
watch(
    () => props.modelValue,
    (open) => {
        if (!open) {
            return;
        }
        form.oldPassword = '';
        form.newPassword = '';
        form.checkPassword = '';
        formRef.value?.clearValidate();
    },
);

const rules: FormRules = {
    oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
    newPassword: [
        { required: true, message: '请输入新密码', trigger: 'blur' },
        { min: 8, max: 20, message: '密码长度需在 8 ~ 20 位之间', trigger: 'blur' },
    ],
    checkPassword: [
        { required: true, message: '请再次输入新密码', trigger: 'blur' },
        {
            // 字段间比较注解做不到，这里补一道；后端还会再校验一次
            validator: (_rule, value: string, callback: (error?: Error) => void) => {
                if (value !== form.newPassword) {
                    callback(new Error('两次输入的新密码不一致'));
                    return;
                }
                callback();
            },
            trigger: 'blur',
        },
    ],
};

async function submit() {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid) {
        return;
    }
    saving.value = true;
    try {
        await userApi.changePassword(form.oldPassword, form.newPassword, form.checkPassword);
        ElMessage.success('密码已修改，请用新密码重新登录');
        visible.value = false;
        emit('changed');
    } catch {
        // 错误提示由请求层统一弹出（原密码错误 / 新旧相同 / 会话失效），这里只负责不关闭弹窗
    } finally {
        saving.value = false;
    }
}
</script>

<template>
    <el-dialog v-model="visible" title="修改密码" width="420px" :close-on-click-modal="false">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="86px" @submit.prevent>
            <el-form-item label="原密码" prop="oldPassword">
                <el-input
                    v-model="form.oldPassword"
                    type="password"
                    show-password
                    autocomplete="current-password"
                    placeholder="用于确认是本人操作"
                />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
                <el-input
                    v-model="form.newPassword"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    placeholder="8 ~ 20 位"
                />
            </el-form-item>
            <el-form-item label="确认密码" prop="checkPassword">
                <el-input
                    v-model="form.checkPassword"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    placeholder="再输入一次新密码"
                />
            </el-form-item>
        </el-form>
        <template #footer>
            <el-button @click="visible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="submit">确认修改</el-button>
        </template>
        <p class="hint">修改成功后所有设备都会退出登录，需要用新密码重新登录。</p>
    </el-dialog>
</template>

<style scoped>
.hint {
    margin: 0;
    font-size: 12px;
    color: var(--muted);
}
</style>
