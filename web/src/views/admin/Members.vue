<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import type { Member, PageResult } from '@/api/types';

/** 成员管理：分页表格 + 新增/编辑/删除 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<Member> | null>(null);
const loading = ref(false);

const dialogVisible = ref(false);
const editing = ref<Member | null>(null);
const form = reactive<Record<string, unknown>>({
    name: '',
    gradeYear: null,
    major: '',
    direction: '',
    teamPosition: 'member',
    memberStatus: 0,
    githubUrl: '',
    summary: '',
    sortOrder: 0,
});

const POSITION_TEXT: Record<string, string> = { member: '成员', leader: '队长', tech_lead: '组长' };

async function load() {
    loading.value = true;
    try {
        result.value = await adminApi.memberPage({ ...page });
    } finally {
        loading.value = false;
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, {
        name: '',
        gradeYear: null,
        major: '',
        direction: '',
        teamPosition: 'member',
        memberStatus: 0,
        githubUrl: '',
        summary: '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function openEdit(row: Member) {
    editing.value = row;
    Object.assign(form, {
        name: row.name,
        gradeYear: row.gradeYear ?? null,
        major: row.major ?? '',
        direction: row.direction ?? '',
        teamPosition: row.teamPosition ?? 'member',
        memberStatus: row.memberStatus ?? 0,
        githubUrl: row.githubUrl ?? '',
        summary: row.summary ?? '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

async function save() {
    if (!form.name) {
        ElMessage.warning('姓名必填');
        return;
    }
    try {
        if (editing.value) {
            await adminApi.memberUpdate({ id: editing.value.id, ...form });
        } else {
            await adminApi.memberAdd(form as Partial<Member>);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 错误提示已在请求层统一处理
    }
}

async function remove(row: Member) {
    try {
        await ElMessageBox.confirm(`确认删除成员「${row.name}」？`, '删除确认', { type: 'warning' });
    } catch {
        return;
    }
    try {
        await adminApi.memberDelete(row.id);
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
            <h2>成员管理</h2>
            <span class="spacer" />
            <el-button type="primary" @click="openCreate">新增成员</el-button>
        </div>

        <el-table v-loading="loading" :data="result?.records ?? []" border>
            <el-table-column prop="name" label="姓名" min-width="120" />
            <el-table-column prop="gradeYear" label="届别" width="90" />
            <el-table-column prop="major" label="专业" min-width="120" />
            <el-table-column prop="direction" label="方向" min-width="120" />
            <el-table-column label="职务" width="100">
                <template #default="{ row }">
                    {{ POSITION_TEXT[row.teamPosition] || row.teamPosition }}
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.memberStatus === 1 ? 'info' : 'success'">
                        {{ row.memberStatus === 1 ? '毕业' : '在队' }}
                    </el-tag>
                </template>
            </el-table-column>
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

        <el-dialog v-model="dialogVisible" :title="editing ? '编辑成员' : '新增成员'" width="560px">
            <el-form label-position="top">
                <el-form-item label="姓名" required>
                    <el-input v-model="form.name as string" />
                </el-form-item>
                <el-form-item label="届别">
                    <el-input-number v-model="form.gradeYear as number" :min="1990" :max="2100" />
                </el-form-item>
                <el-form-item label="专业"><el-input v-model="form.major as string" /></el-form-item>
                <el-form-item label="技术方向"><el-input v-model="form.direction as string" /></el-form-item>
                <el-form-item label="职务">
                    <el-select v-model="form.teamPosition as string">
                        <el-option label="成员" value="member" />
                        <el-option label="队长" value="leader" />
                        <el-option label="组长" value="tech_lead" />
                    </el-select>
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="form.memberStatus as number">
                        <el-option label="在读/在队" :value="0" />
                        <el-option label="毕业/离队" :value="1" />
                    </el-select>
                </el-form-item>
                <el-form-item label="GitHub"><el-input v-model="form.githubUrl as string" /></el-form-item>
                <el-form-item label="简介">
                    <el-input v-model="form.summary as string" type="textarea" :rows="3" />
                </el-form-item>
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
