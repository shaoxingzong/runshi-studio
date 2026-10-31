<script setup lang="ts">
import { useRouter } from 'vue-router';

/** 404 页 */
const router = useRouter();

function goBack() {
    // history 里没有来路（例如直接输网址）时 back() 无效，退回首页
    if (window.history.length > 1) {
        router.back();
    } else {
        router.push('/');
    }
}
</script>

<template>
    <div class="not-found">
        <el-result icon="warning" title="404" sub-title="页面不存在或已下线">
            <template #extra>
                <el-button type="primary" @click="router.push('/')">返回首页</el-button>
                <el-button @click="goBack">返回上一页</el-button>
            </template>
        </el-result>
    </div>
</template>

<style scoped>
.not-found {
    display: flex;
    justify-content: center;
    padding: 60px 20px;
}
</style>
