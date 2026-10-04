<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import type { Certificate, PageResult } from '@/api/types';

/** 证书管理（等级与类型是闭集枚举，后端会校验非法值 → A0401） */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<Certificate> | null>(null);
const loading = ref(false);

const dialogVisible = ref(false);
const editing = ref<Certificate | null>(null);
const form = reactive<Record<string, unknown>>({
    title: '',
    awardLevel: 'national',
    awardType: 'competition',
    awardDate: '',
    imageUrl: '',
    sortOrder: 0,
});

async function load() {
    loading.value = true;
    try {
        result.value = await adminApi.certificatePage({ ...page });
    } finally {
        loading.value = false;
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, {
        title: '',
        awardLevel: 'national',
        awardType: 'competition',
        awardDate: '',
        imageUrl: '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function openEdit(row: Certificate) {
    editing.value = row;
    Object.assign(form, {
        title: row.title,
        awardLevel: row.awardLevel ?? 'national',
        awardType: row.awardType ?? 'competition',
        awardDate: row.awardDate ?? '',
        imageUrl: row.imageUrl ?? '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

async function save() {
    if (!form.title) {
        ElMessage.warning('证书名称必填');
        return;
    }
    try {
        if (editing.value) {
            await adminApi.certificateUpdate({ id: editing.value.id, ...form });
        } else {
            await adminApi.certificateAdd(form as never);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 统一提示已在请求层
    }
}

async function remove(row: Certificate) {
    try {
        await ElMessageBox.confirm(`确认删除证书「${row.title}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.certificateDelete(row.id);
        ElMessage.success('已删除');
        await load();
    } catch {
        // 同上
    }
}

function turn(delta: number) {
    const next = page.current + delta;
    const pages = result.value ? Number(result.value.pages) : 1;
    if (next < 1 || next > pages) {
        return;
    }
    page.current = next;
    load();
}

onMounted(load);
</script>

<template>
    <div>
        <div class="bar">
            <h2>证书管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增证书</el-button>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="title" label="名称" min-width="240" />
            <el-table-column prop="awardLevel" label="级别" width="100" />
            <el-table-column prop="awardType" label="类型" width="120" />
            <el-table-column prop="awardDate" label="日期" width="120" />
            <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                    <el-button link type="danger" @click="remove(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div v-if="result" class="pager">
            <el-button :disabled="page.current <= 1" @click="turn(-1)">上一页</el-button>
            <span class="muted">第 {{ page.current }} / {{ result.pages }} 页 · 共 {{ result.total }} 条</span>
            <el-button :disabled="page.current >= Number(result.pages)" @click="turn(1)">下一页</el-button>
        </div>

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑证书' : '新增证书'" width="560px">
            <el-form label-position="top">
                <el-form-item label="证书名称" required><el-input v-model="form.title as string" /></el-form-item>
                <el-form-item label="级别">
                    <el-select v-model="form.awardLevel as string">
                        <el-option label="国家级" value="national" />
                        <el-option label="省级" value="provincial" />
                        <el-option label="市级/校级" value="municipal" />
                    </el-select>
                </el-form-item>
                <el-form-item label="类型">
                    <el-select v-model="form.awardType as string">
                        <el-option label="学科竞赛" value="competition" />
                        <el-option label="软件著作权" value="soft_copyright" />
                        <el-option label="专利" value="patent" />
                        <el-option label="论文" value="paper" />
                    </el-select>
                </el-form-item>
                <el-form-item label="获奖日期">
                    <el-date-picker
                        v-model="form.awardDate as string"
                        type="date"
                        value-format="YYYY-MM-DD"
                        placeholder="选择日期"
                    />
                </el-form-item>
                <el-form-item label="证书图片 URL"><el-input v-model="form.imageUrl as string" /></el-form-item>
                <el-form-item label="置顶权重">
                    <el-input-number v-model="form.sortOrder as number" :min="0" :max="999" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" @click="save">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.bar {
    display: flex;
    align-items: center;
    margin-bottom: 16px;
}

h2 {
    margin: 0;
    font-size: 20px;
}

.spacer {
    flex: 1;
}

.pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    margin-top: 16px;
}
</style>
