<script setup lang="ts">
import { useRouter } from 'vue-router';

/** 403 页 */
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
