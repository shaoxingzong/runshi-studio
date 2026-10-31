<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';

/** 门户注册（C 端） */
const router = useRouter();

const form = reactive({ account: '', name: '', password: '', checkPassword: '' });
const loading = ref(false);

/** 前端先按后端同一套规则挡一遍（省一次往返、提示更及时） */
const ACCOUNT_PATTERN = /^[a-z0-9_]+$/;

function validate(): string | null {
    if (!form.account || !form.name || !form.password || !form.checkPassword) {
        return '请填写完整信息';
    }
    if (!form.name.trim()) {
        return '请填写姓名';
    }
    if (form.name.length > 64) {
        return '姓名长度不能超过 64 个字符';
    }
    if (form.account.length < 4 || form.account.length > 16) {
        return '账号长度需为 4-16 位';
    }
    if (!ACCOUNT_PATTERN.test(form.account)) {
        return '账号只能包含小写字母、数字与下划线';
    }
    if (form.password.length < 8 || form.password.length > 20) {
        return '密码长度需为 8-20 位';
    }
    if (form.password !== form.checkPassword) {
        return '两次输入的密码不一致';
    }
    return null;
}

async function submit() {
    // 与后端 normalizeAccount 同口径：先 trim + 小写，再校验长度与字符集
    form.account = form.account.trim().toLowerCase();
    // 姓名同样先 trim：只输空格不算填了姓名，也避免把首尾空格带进库
    form.name = form.name.trim();
    const error = validate();
    if (error) {
        ElMessage.warning(error);
        return;
    }
    loading.value = true;
    try {
        await userApi.register(form.account, form.name, form.password, form.checkPassword);
        ElMessage.success('注册成功，请登录');
        // 不自动登录：登录动作让用户明确做一次，避免「注册即登录」带来的账号误用
        await router.push({ name: 'login' });
    } catch (e) {
        // 「账号已存在」等业务错误已由请求层提示
        console.warn('注册失败', e);
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <div class="page wrap">
        <div class="card box">
            <img src="/logo.jpg" alt="润石工作室" class="brand-logo" />
            <h1>注册</h1>
            <p class="muted">
                已有账号？<router-link to="/login">去登录</router-link>
            </p>

            <el-form label-position="top" @submit.prevent="submit">
                <el-form-item label="账号">
                    <el-input
                        v-model="form.account"
                        autocomplete="username"
                        placeholder="4-16 位小写字母、数字或下划线"
                    />
                </el-form-item>
                <el-form-item label="姓名">
                    <el-input
                        v-model="form.name"
                        maxlength="64"
                        autocomplete="name"
                        placeholder="请输入真实姓名，用于站内展示"
                    />
                </el-form-item>
                <el-form-item label="密码">
                    <el-input
                        v-model="form.password"
                        type="password"
                        show-password
                        autocomplete="new-password"
                        placeholder="8-20 位"
                    />
                </el-form-item>
                <el-form-item label="确认密码">
                    <el-input
                        v-model="form.checkPassword"
                        type="password"
                        show-password
                        autocomplete="new-password"
                        placeholder="再输入一次"
                        @keyup.enter="submit"
                    />
                </el-form-item>
                <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">注册</el-button>
            </el-form>

            <div class="tip muted">
                注册后默认为普通用户，可使用 AI 助手；如需成为工作室成员，请联系管理员开通成员身份。
            </div>
        </div>
    </div>
</template>

<style scoped>
.wrap {
    max-width: 420px;
    margin: 56px auto;
    padding: 0 20px;
}

.box {
    padding: 26px;
}

.brand-logo {
    display: block;
    height: 60px;
    margin: 4px auto 12px;
}

h1 {
    margin: 0 0 6px;
    font-size: 22px;
    text-align: center;
}

p {
    margin: 0 0 18px;
    font-size: 13px;
    text-align: center;
}

.tip {
    margin-top: 16px;
    font-size: 12px;
    line-height: 1.7;
}
</style>
