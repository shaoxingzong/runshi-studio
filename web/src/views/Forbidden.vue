<script setup lang="ts">
import { useRouter } from 'vue-router';

// 403 页
//
// 触发场景：已登录但不是管理员的人访问 /admin/*（路由守卫拦下送来这里）。
// 此前的行为是跳后台登录页——对已登录的人说「请先登录」，信息是错的，
// 正确的信息是「这个页面需要管理员权限」。
//
// 注意这只是体验层：真正的权限判定在后端 `@SaCheckRole("admin")`，
// 前端的一切判断都可以被绕过，不能当安全边界看。
const router = useRouter();
</script>

<template>
    <div class="forbidden">
        <el-result icon="error" title="403" sub-title="该页面需要管理员权限">
            <template #extra>
                <el-button type="primary" @click="router.push('/')">返回首页</el-button>
            </template>
        </el-result>
    </div>
</template>

<style scoped>
.forbidden {
    display: flex;
    justify-content: center;
    padding: 60px 20px;
}
</style>
