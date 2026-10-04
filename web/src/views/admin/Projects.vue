<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import type { AdminProject, PageResult } from '@/api/types';

/** 项目管理：正文（Markdown）与技术栈是知识库的数据源，改完记得同步 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<AdminProject> | null>(null);
const loading = ref(false);

const dialogVisible = ref(false);
const editing = ref<AdminProject | null>(null);
const form = reactive<Record<string, unknown>>({
    title: '',
    description: '',
    content: '',
    techStackText: '',
    leaderId: '',
    coverImage: '',
    demoUrl: '',
    githubUrl: '',
    status: 1,
    sortOrder: 0,
});

const STATUS_TEXT: Record<string, string> = { 0: '研发中', 1: '已上线', 2: '已结题' };

async function load() {
    loading.value = true;
    try {
        result.value = await adminApi.projectPage({ ...page });
    } finally {
        loading.value = false;
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, {
        title: '',
        description: '',
        content: '',
        techStackText: '',
        leaderId: '',
        coverImage: '',
        demoUrl: '',
        githubUrl: '',
        status: 1,
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function openEdit(row: AdminProject) {
    editing.value = row;
    Object.assign(form, {
        title: row.title,
        description: row.description ?? '',
        content: row.content ?? '',
        // 数组在表单里用「逗号分隔」编辑，保存时再拆回数组
        techStackText: (row.techStack ?? []).join(', '),
        leaderId: row.leaderId,
        coverImage: row.coverImage ?? '',
        demoUrl: row.demoUrl ?? '',
        githubUrl: row.githubUrl ?? '',
        status: row.status ?? 1,
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function toTechStack(): string[] {
    return String(form.techStackText ?? '')
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean);
}

async function save() {
    if (!form.title || !form.description) {
        ElMessage.warning('项目名称与摘要必填');
        return;
    }
    const payload = {
        title: form.title as string,
        description: form.description as string,
        content: form.content as string,
        techStack: toTechStack(),
        leaderId: form.leaderId as string,
        coverImage: form.coverImage as string,
        demoUrl: form.demoUrl as string,
        githubUrl: form.githubUrl as string,
        status: form.status as number,
        sortOrder: form.sortOrder as number,
    };
    try {
        if (editing.value) {
            await adminApi.projectUpdate({ id: editing.value.id, ...payload });
        } else {
            await adminApi.projectAdd(payload);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 统一提示已在请求层
    }
}

async function remove(row: AdminProject) {
    try {
        await ElMessageBox.confirm(`确认删除项目「${row.title}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.projectDelete(row.id);
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
            <h2>项目管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增项目</el-button>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="title" label="项目" min-width="180" />
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.status === 1 ? 'success' : 'info'">
                        {{ STATUS_TEXT[String(row.status)] || row.status }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="description" label="摘要" min-width="220" show-overflow-tooltip />
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

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑项目' : '新增项目'" width="640px">
            <el-form label-position="top">
                <el-form-item label="项目名称" required><el-input v-model="form.title as string" /></el-form-item>
                <el-form-item label="摘要" required><el-input v-model="form.description as string" /></el-form-item>
                <el-form-item label="队长 ID（成员表主键）" required>
                    <el-input v-model="form.leaderId as string" placeholder="如 1900000000000000011" />
                </el-form-item>
                <el-form-item label="正文（Markdown，知识库数据源）">
                    <el-input v-model="form.content as string" type="textarea" :rows="6" />
                </el-form-item>
                <el-form-item label="技术栈（逗号分隔）">
                    <el-input v-model="form.techStackText as string" placeholder="Spring Boot, MySQL" />
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="form.status as number">
                        <el-option label="研发中" :value="0" />
                        <el-option label="已上线" :value="1" />
                        <el-option label="已结题" :value="2" />
                    </el-select>
                </el-form-item>
                <el-form-item label="封面/体验/仓库地址">
                    <el-input v-model="form.coverImage as string" placeholder="封面图 URL" />
                    <el-input v-model="form.demoUrl as string" placeholder="体验地址" />
                    <el-input v-model="form.githubUrl as string" placeholder="仓库地址" />
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
