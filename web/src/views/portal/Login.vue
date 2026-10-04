<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';
import { useUserStore } from '@/stores/user';

/**
 * 门户登录（C 端）
 *
 * 与后台登录（`/admin/login`）的关系：**接口是同一个** `/user/login`，区别在页面与后续去向——
 * 这里登录成功后回门户；管理员账号登录后导航会多出「后台管理」入口。
 *
 * 后台刻意**没有注册入口**：管理员账号由管理方分配；普通用户在本站注册。
 */
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
        const data = await userApi.login(form.account, form.password);
        // 必须带上 userRole：导航据此显示「后台管理」，后台路由守卫也靠它放行管理员
        user.setLogin(data.token || '', form.account, data.userRole);
        ElMessage.success('登录成功');
        const redirect = (route.query.redirect as string) || '/';
        await router.push(redirect);
    } catch (e) {
        // 账号密码错误等已由请求层统一提示，这里不重复弹窗
        console.warn('登录失败', e);
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <div class="page wrap">
        <div class="card box">
            <img src="/logo.png" alt="润石工作室" class="brand-logo" />
            <h1>登录</h1>
            <p class="muted">
                还没有账号？<router-link to="/register">立即注册</router-link>
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
                        autocomplete="current-password"
                        placeholder="8-20 位"
                        @keyup.enter="submit"
                    />
                </el-form-item>
                <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">登录</el-button>
            </el-form>

            <div class="tip muted">
                注册出来是普通用户，可使用 AI 助手；「成员」身份由管理员在后台授予。
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
