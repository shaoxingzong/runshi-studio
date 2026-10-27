<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ImageUploader from '@/components/ImageUploader.vue';
import type { AdminMember, AdminUser, PageResult } from '@/api/types';

/** 成员管理：分页表格 + 新增/编辑/删除 */
const page = reactive({ current: 1, pageSize: 10 });
const result = ref<PageResult<AdminMember> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时给原因 + 重试，而不是留一张空白表格 */
const error = ref('');

const dialogVisible = ref(false);
const editing = ref<AdminMember | null>(null);
const form = reactive<Record<string, unknown>>({
    // 绑定的登录账号 ID；空 = 未绑定（编辑时留空表示不修改，解绑需另设计）
    userId: '',
    name: '',
    gradeYear: null,
    major: '',
    direction: '',
    teamPosition: 'member',
    memberStatus: 0,
    avatar: '',
    githubUrl: '',
    summary: '',
    sortOrder: 0,
});

const POSITION_TEXT: Record<string, string> = { member: '成员', leader: '队长', tech_lead: '组长' };

/**
 * 候选登录账号：用来把「注册用户」转成「成员」
 *
 * 这是成员能否发帖、能否打卡的唯一开关——成员身份认的是 studio_member.user_id，不是账号角色。
 * 没有这个入口，管理员就只能改数据库才能让自己变成成员。
 */
const userOptions = ref<AdminUser[]>([]);

async function loadUsers() {
    try {
        const page = await adminApi.userPage({ current: 1, pageSize: 100 });
        userOptions.value = page.records ?? [];
    } catch {
        userOptions.value = [];
    }
}

/** 由 userId 反查账号名，让表格里的绑定状态一眼可读 */
function accountOf(userId: string): string {
    const found = userOptions.value.find((item) => item.id === userId);
    return found ? found.userAccount : userId;
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.memberPage({ ...page });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

function openCreate() {
    editing.value = null;
    Object.assign(form, {
        userId: '',
        name: '',
        gradeYear: null,
        major: '',
        direction: '',
        teamPosition: 'member',
        memberStatus: 0,
        avatar: '',
        githubUrl: '',
        summary: '',
        sortOrder: 0,
    });
    dialogVisible.value = true;
}

function openEdit(row: AdminMember) {
    editing.value = row;
    Object.assign(form, {
        userId: row.userId ?? '',
        name: row.name,
        gradeYear: row.gradeYear ?? null,
        major: row.major ?? '',
        direction: row.direction ?? '',
        teamPosition: row.teamPosition ?? 'member',
        memberStatus: row.memberStatus ?? 0,
        avatar: row.avatar ?? '',
        githubUrl: row.githubUrl ?? '',
        summary: row.summary ?? '',
        sortOrder: row.sortOrder ?? 0,
    });
    dialogVisible.value = true;
}

async function save() {
    if (!form.name) {
        ElMessage.warning('姓名必填');
        return;
    }
    /**
     * 新增时未绑账号：**不阻止**，但必须让管理员知道后果
     *
     * {@code db/user.sql} 保留「可以有档案没账号」的能力，强制必填属于过度修正。但准入判据是
     * 「档案的 userId 命中当前登录账号」，没绑定的档案永远不会生效——管理员却在列表里看到「这个人是成员」。
     */
    if (!editing.value && !form.userId) {
        try {
            await ElMessageBox.confirm(
                '没有绑定登录账号，这条成员档案不会对任何账号生效：' +
                    '本人登录后看不到工作台与刷题入口。\n\n' +
                    '如果只是先建档、稍后再绑定，可以继续；否则建议先选择要绑定的账号。',
                '未绑定登录账号',
                { type: 'warning', confirmButtonText: '仍然保存', cancelButtonText: '返回选择' },
            );
        } catch {
            return;
        }
    }
    /**
     * 未选账号时不带该字段：后端 @Positive 不接受空串，而「null = 不修改绑定」是编辑时的既有语义
     * ——留空不等于解绑
     */
    const payload: Record<string, unknown> = { ...form };
    if (!payload.userId) {
        delete payload.userId;
    }
    try {
        if (editing.value) {
            await adminApi.memberUpdate({ id: editing.value.id, ...payload });
        } else {
            await adminApi.memberAdd(payload as Partial<AdminMember>);
        }
        ElMessage.success('保存成功');
        dialogVisible.value = false;
        await load();
    } catch {
        // 错误提示已在请求层统一处理
    }
}

async function remove(row: AdminMember) {
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

onMounted(() => {
    load();
    void loadUsers();
});
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
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
            <el-table-column label="绑定账号" min-width="130">
                <template #default="{ row }">
                    <span v-if="row.userId" class="bound">{{ accountOf(row.userId) }}</span>
                    <span v-else class="muted">未绑定</span>
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
                <el-form-item label="头像">
                    <ImageUploader
                        v-model="form.avatar as string"
                        tip="成员头像（可选）；项目详情的「参与成员」卡片会用到"
                    />
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
                <el-form-item label="绑定登录账号">
                    <el-select
                        v-model="form.userId as string"
                        :placeholder="editing ? '未绑定（留空表示不修改）' : '建议选择：不绑定则档案不生效'"
                        clearable
                        filterable
                        class="full"
                    >
                        <el-option
                            v-for="option in userOptions"
                            :key="option.id"
                            :label="
                                option.userName
                                    ? `${option.userAccount}（${option.userName}）`
                                    : option.userAccount
                            "
                            :value="option.id"
                        />
                    </el-select>
                    <div class="hint">
                        绑定后，该账号登录即可使用工作台（考勤）与刷题。
                        <b v-if="!editing">不绑定的档案不会对任何账号生效。</b>
                        <b v-else>编辑时留空表示「不修改」现有绑定，不能用来解绑。</b>
                    </div>
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

.full {
    width: 100%;
}

.hint {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: #6b7280;
}

/* 已绑定的账号用绿色，与「未绑定」的灰色形成对比 */
.bound {
    color: #059669;
}
</style>
