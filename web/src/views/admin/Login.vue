<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { adminApi } from '@/api/admin';
import { useUserStore } from '@/stores/user';

const route = useRoute();
const router = useRouter();
const user = useUserStore();

const form = reactive({ account: '', password: '' });
const loading = ref(false);

async function submit() {
    if (!form.account || !form.password) {
        ElMessage.warning('请输入账号与密码');
        return;
    }
    loading.value = true;
    try {
        const data = await adminApi.login(form.account, form.password);
        if (data.userRole !== 'admin') {
            user.logout();
            ElMessage.error('该账号不是管理员，无法进入后台');
            return;
        }
        user.setLogin(
            data.token || '',
            form.account,
            data.userRole,
            data.userName || '',
            data.userAvatar || '',
            data.id || '',
        );
        const redirect = (route.query.redirect as string) || '/admin';
        await router.push(redirect);
    } catch (e) {
        // A0301（角色不足）等错误已在请求层统一提示，这里不重复弹窗
        console.warn('登录失败', e);
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <div class="login">
        <div class="card box">
            <h1>后台登录</h1>
            <p class="muted">需要管理员账号。后台不提供注册——账号由管理方分配，普通用户请到官网注册后使用。</p>

            <el-form label-position="top" @submit.prevent="submit">
                <el-form-item label="账号">
                    <el-input v-model="form.account" autocomplete="username" placeholder="管理员账号" />
                </el-form-item>
                <el-form-item label="密码">
                    <el-input
                        v-model="form.password"
                        type="password"
                        show-password
                        autocomplete="current-password"
                        placeholder="密码"
                        @keyup.enter="submit"
                    />
                </el-form-item>
                <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">登录</el-button>
            </el-form>

            <div class="back">
                <router-link to="/">← 返回官网</router-link>
            </div>
        </div>
    </div>
</template>

<style scoped>
.login {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
}

.box {
    width: 380px;
}

h1 {
    margin: 0 0 6px;
    font-size: 22px;
}

p {
    margin: 0 0 18px;
    font-size: 13px;
}

.back {
    margin-top: 16px;
    font-size: 13px;
}
</style>
