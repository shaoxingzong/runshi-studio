<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';

/**
 * 门户注册（C 端）
 *
 * 业务规则：**注册只能得到普通用户（user）**。
 * 「成员」是管理给的——注册完之后由管理员在后台「用户管理」里把角色改成 `member`。
 *
 * 后台（`/admin/*`）不提供注册：管理员账号由管理方分配，
 * 自助注册一个账号并不能进后台（路由守卫 + 后端 @SaCheckRole("admin") 双重拦）。
 */
const router = useRouter();

const form = reactive({ account: '', password: '', checkPassword: '' });
const loading = ref(false);

/**
 * 前端先按后端同一套规则挡一遍（省一次往返、提示更及时）
 *
 * 权威校验永远在后端（UserRegisterRequest 的注解 + Service）——
 * 前端校验是体验，不是安全边界，别指望它能防住直接调接口的人。
 */
const ACCOUNT_PATTERN = /^[a-zA-Z0-9_]+$/;

function validate(): string | null {
    if (!form.account || !form.password || !form.checkPassword) {
        return '请填写完整信息';
    }
    if (form.account.length < 4 || form.account.length > 16) {
        return '账号长度需为 4-16 位';
    }
    if (!ACCOUNT_PATTERN.test(form.account)) {
        return '账号只能包含字母、数字与下划线';
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
    const error = validate();
    if (error) {
        ElMessage.warning(error);
        return;
    }
    loading.value = true;
    try {
        await userApi.register(form.account, form.password, form.checkPassword);
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
            <img src="/logo.png" alt="润石工作室" class="brand-logo" />
            <h1>注册</h1>
            <p class="muted">
                已有账号？<router-link to="/login">去登录</router-link>
            </p>

            <el-form label-position="top" @submit.prevent="submit">
                <el-form-item label="账号">
                    <el-input
                        v-model="form.account"
                        autocomplete="username"
                        placeholder="4-16 位字母、数字或下划线"
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
                注册后是普通用户，可使用 AI 助手；如需成为工作室成员，请联系管理员在后台授予。
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
