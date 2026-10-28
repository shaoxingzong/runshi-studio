<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminApi } from '@/api/admin';
import ErrorRetry from '@/components/ErrorRetry.vue';
import ImageUploader from '@/components/ImageUploader.vue';
import SmartImage from '@/components/SmartImage.vue';
import type { AdminUser, PageResult } from '@/api/types';

/**
 * 用户管理：「给注册用户授予成员身份」的地方
 *
 * 注册只能产生普通用户（`user`），管理员在这里改成 `member` / `admin`，或封禁（`userStatus=1`）。
 *
 * 角色取值必须是后端 `UserRoleEnum` 的原始值（user / member / admin），中文只用于显示——
 * 写错了 `@SaCheckRole` 会判不出来，而界面看起来一切正常。
 */
const ROLES = [
    { value: 'user', label: '普通用户' },
    { value: 'member', label: '成员' },
    { value: 'admin', label: '管理员' },
];

const query = reactive({ current: 1, pageSize: 10, userAccount: '', userRole: '' });
const result = ref<PageResult<AdminUser> | null>(null);
const loading = ref(false);
/** 列表加载失败的原因（空 = 正常）。失败时不能只靠请求层那一次弹窗——弹窗会消失，表格却一直空白 */
const error = ref('');

const records = computed(() => result.value?.records ?? []);
const total = computed(() => Number(result.value?.total || 0));

// ===== 重置密码 =====
const pwdVisible = ref(false);
const pwdTarget = ref<AdminUser | null>(null);
const newPassword = ref('');

async function load() {
    loading.value = true;
    error.value = '';
    try {
        result.value = await adminApi.userPage({ ...query });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

/** 查询：从第一页重新开始，否则「在第 5 页筛选出 0 条」会让人以为没有数据 */
function reload() {
    query.current = 1;
    void load();
}

function roleText(role: string) {
    return ROLES.find((r) => r.value === role)?.label || role;
}

async function changeRole(row: AdminUser, value: unknown) {
    const role = String(value);
    if (role === row.userRole) {
        return;
    }
    /**
     * 授予成员 / 管理员角色前，先确认该账号已有成员档案
     *
     * 准入判据是「studio_member 档案的 userId 命中当前账号」，userRole 只是权限标签；只改角色
     * 不建档案时成员入口不会出现，硬进页面后端也返回 A0301。
     */
    if (role === 'member' || role === 'admin') {
        try {
            await ElMessageBox.confirm(
                `「${row.userAccount}」需要先有成员档案，成员功能才会真正生效。\n` +
                    '如果该账号还没有档案，请先点列表里的「转为成员」按钮（会同时建档并改角色）。\n\n' +
                    '确认该账号已经有成员档案，继续修改角色吗？',
                '修改角色',
                { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
            );
        } catch {
            // 用户取消：把下拉框拉回真实值，否则界面会停在「已改」的假象
            await load();
            return;
        }
    }
    try {
        await adminApi.userUpdate({ id: row.id, userRole: role });
        ElMessage.success(`已把「${row.userAccount}」设为${roleText(role)}`);
        await load();
    } catch (e) {
        // 失败时刷新列表，把下拉框的显示拉回真实值（否则界面会停在「已改」的假象）
        console.warn('修改角色失败', e);
        await load();
    }
}

async function toggleStatus(row: AdminUser) {
    const nextStatus = row.userStatus === 1 ? 0 : 1;
    const action = nextStatus === 1 ? '封禁' : '解封';
    try {
        await ElMessageBox.confirm(`确定要${action}账号「${row.userAccount}」吗？`, '提示', { type: 'warning' });
    } catch {
        return; // 用户取消
    }
    try {
        await adminApi.userUpdate({ id: row.id, userStatus: nextStatus });
        ElMessage.success(`已${action}`);
        await load();
    } catch (e) {
        console.warn(`${action}失败`, e);
        await load();
    }
}

// =
/**
 * 背景：注册只能产生普通用户，而成员档案在 studio_member 里，两者通过 user_id 可选绑定。
 * 分开做要建档案、再回来改角色，两步且容易漏掉绑定。这里合成一步。
 */
const promoteVisible = ref(false);
const promoteTarget = ref<AdminUser | null>(null);
const promoteForm = reactive({
    name: '',
    gradeYear: new Date().getFullYear(),
    direction: '',
    teamPosition: 'member',
    summary: '',
});

function openPromote(row: AdminUser) {
    promoteTarget.value = row;
    Object.assign(promoteForm, {
        // 昵称往往就是真实姓名，预填能省一次输入；没昵称就退回账号名
        name: row.userName || row.userAccount,
        gradeYear: new Date().getFullYear(),
        direction: '',
        teamPosition: 'member',
        summary: '',
    });
    promoteVisible.value = true;
}

/**
 * 提交顺序是刻意的：先建档案（绑定账号），成功后再改角色。
 * 反过来会留下「角色已是成员、却没有档案」的脏数据——界面看不出异常，只是成员管理里查不到人。
 */
async function submitPromote() {
    const target = promoteTarget.value;
    if (!target) {
        return;
    }
    if (!promoteForm.name) {
        ElMessage.warning('成员姓名必填');
        return;
    }
    if (!promoteForm.gradeYear) {
        ElMessage.warning('入学年份必填');
        return;
    }
    try {
        await adminApi.memberAdd({
            name: promoteForm.name,
            gradeYear: promoteForm.gradeYear,
            direction: promoteForm.direction || undefined,
            teamPosition: promoteForm.teamPosition,
            summary: promoteForm.summary || undefined,
            // 关键：把这条档案绑到该登录账号（后端校验账号存在且未被其它成员绑定，
            // 唯一索引 uk_userid_deleted 兜底并发）
            userId: target.id,
        });
        await adminApi.userUpdate({ id: target.id, userRole: 'member' });
        ElMessage.success(`已把「${target.userAccount}」转为成员，并创建了他的成员档案`);
        promoteVisible.value = false;
        await load();
    } catch (e) {
        // 「该账号已绑定成员档案」等错误已由请求层提示；这里只留痕便于排查
        console.warn('转为成员失败', e);
    }
}

function openResetPassword(row: AdminUser) {
    pwdTarget.value = row;
    newPassword.value = '';
    pwdVisible.value = true;
}

async function submitPassword() {
    const target = pwdTarget.value;
    if (!target) {
        return;
    }
    if (newPassword.value.length < 8 || newPassword.value.length > 20) {
        ElMessage.warning('密码长度需为 8-20 位');
        return;
    }
    try {
        await adminApi.userUpdate({ id: target.id, userPassword: newPassword.value });
        ElMessage.success('密码已重置，请通过安全渠道告知用户');
        pwdVisible.value = false;
    } catch (e) {
        console.warn('重置密码失败', e);
    }
}

// ===== 新增账号 =====
const createVisible = ref(false);
const createForm = reactive({
    userAccount: '',
    userPassword: '',
    userName: '',
    // 默认「成员」：新增账号多为把新人拉进工作室，省一次改角色
    userRole: 'member',
});

/**
 * 初始密码结果框（创建成功后弹出）
 *
 * 管理员没填密码时后端生成随机口令，只随创建响应返回这一次——后端不存明文、也查不回来。
 * 不展示就等于管理员本人也不知道密码，账号等于白建。
 */
const passwordResultVisible = ref(false);
/** 待展示给管理员的初始密码明文（展示后即弃，不落任何持久化存储） */
const initialPassword = ref('');

function openCreate() {
    Object.assign(createForm, {
        userAccount: '',
        userPassword: '',
        userName: '',
        userRole: 'member',
    });
    createVisible.value = true;
}

/**
 * 新增账号
 *
 * 密码留空时不下发 userPassword 字段：后端会生成随机初始密码，下发空串会撞上 DTO 的 @Size(min=8)。
 */
async function submitCreate() {
    if (createForm.userAccount.length < 4 || createForm.userAccount.length > 16) {
        ElMessage.warning('账号长度需为 4-16 位');
        return;
    }
    if (createForm.userPassword && (createForm.userPassword.length < 8 || createForm.userPassword.length > 20)) {
        ElMessage.warning('密码长度需为 8-20 位（留空则生成随机初始密码）');
        return;
    }
    try {
        const result = await adminApi.userAdd({
            userAccount: createForm.userAccount,
            userPassword: createForm.userPassword || undefined,
            userName: createForm.userName || undefined,
            userRole: createForm.userRole,
        });
        ElMessage.success('账号已创建');
        createVisible.value = false;
        // 初始密码只出现这一次：立即展示给管理员，由其通过安全渠道转交本人
        initialPassword.value = result.initialPassword;
        passwordResultVisible.value = true;
        await load();
    } catch (e) {
        console.warn('新增账号失败', e);
    }
}

/** 复制初始密码到剪贴板：navigator.clipboard 在非安全上下文（http）下不可用，给个兜底提示 */
async function copyInitialPassword() {
    try {
        await navigator.clipboard.writeText(initialPassword.value);
        ElMessage.success('已复制，请通过安全渠道转交本人');
    } catch {
        // 复制失败（通常是 http 下浏览器策略）：让管理员手动选中复制，不静默
        ElMessage.warning('浏览器不允许自动复制，请手动选中复制');
    }
}

/** 删除账号（逻辑删除）：账号名会被释放、可被重新注册，所以确认文案里要说清后果 */
async function removeUser(row: AdminUser) {
    try {
        await ElMessageBox.confirm(
            `确认删除账号「${row.userAccount}」？删除后该账号无法登录，账号名可被重新注册。`,
            '删除确认',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    try {
        await adminApi.userDelete(row.id);
        ElMessage.success('已删除');
        await load();
    } catch (e) {
        console.warn('删除账号失败', e);
        await load();
    }
}

/**
 * 重置某个用户的 AI 提问次数
 *
 * 用户额度用完会来找管理员，点这里把已用次数清零即可。
 * 加二次确认是因为它紧挨着「重置密码」——两个按钮都是「重置」，误点代价虽小但很困惑。
 */
async function resetAiQuota(row: AdminUser) {
    const used = row.aiQueryCount ?? 0;
    try {
        await ElMessageBox.confirm(
            `确认重置「${row.userAccount}」的 AI 提问次数？当前已用 ${used} 次，重置后归零。`,
            '重置 AI 次数',
            { type: 'warning' },
        );
    } catch {
        return;
    }
    try {
        // 后端返回重置前的真实用量（含未回刷的增量），比列表里的基准值更准
        const before = await adminApi.userResetAiQuota(row.id);
        ElMessage.success(`已从 ${before} 次清零`);
        await load();
    } catch (e) {
        console.warn('重置 AI 次数失败', e);
        await load();
    }
}

// =
/**
 * 后端 /user/update 是「部分更新」：传 null 的字段不改，空串会写入。
 * 所以弹窗预填现有值、提交什么就改什么。两项都清空也允许（昵称为空时，列表与门户兜底显示账号名）。
 */
const editVisible = ref(false);
const editTarget = ref<AdminUser | null>(null);
const editForm = reactive({
    userName: '',
    userAvatar: '',
});

function openEdit(row: AdminUser) {
    editTarget.value = row;
    Object.assign(editForm, {
        userName: row.userName ?? '',
        userAvatar: row.userAvatar ?? '',
    });
    editVisible.value = true;
}

async function submitEdit() {
    const target = editTarget.value;
    if (!target) {
        return;
    }
    try {
        await adminApi.userUpdate({
            id: target.id,
            userName: editForm.userName,
            userAvatar: editForm.userAvatar,
        });
        ElMessage.success('资料已更新');
        editVisible.value = false;
        await load();
    } catch (e) {
        // 失败也刷新：与 changeRole / toggleStatus 一致，
        // 让弹窗里的输入回到服务端的真实值，而不是停在「已保存」的假象
        console.warn('更新用户资料失败', e);
        await load();
    }
}

onMounted(load);
</script>

<template>
    <div>
        <ErrorRetry v-if="error" :message="error" @retry="load" />
        <div class="toolbar card">
            <el-input
                v-model="query.userAccount"
                placeholder="账号关键词"
                clearable
                maxlength="64"
                style="width: 200px"
                @keyup.enter="reload"
            />
            <el-select v-model="query.userRole" placeholder="全部角色" clearable style="width: 150px">
                <el-option v-for="r in ROLES" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
            <el-button type="primary" @click="reload">查询</el-button>
            <span class="spacer" />
            <span class="hint muted">注册只能得到「普通用户」；把角色改成「成员」即授予成员身份</span>
            <el-button type="primary" @click="openCreate">新增账号</el-button>
        </div>

        <el-table v-loading="loading" :data="records" class="card table">
            <el-table-column prop="userAccount" label="账号" min-width="140" />
            <el-table-column label="头像" width="80">
                <template #default="{ row }">
                    <!-- 与门户同一套兜底：头像失效时显示姓名首字，不留裂图 -->
                    <span class="avatar">
                        <SmartImage
                            :src="row.userAvatar"
                            :alt="row.userName || row.userAccount || '?'"
                            :seed="row.id"
                        />
                    </span>
                </template>
            </el-table-column>
            <el-table-column prop="userName" label="昵称" min-width="120" />
            <el-table-column label="角色" min-width="160">
                <template #default="{ row }">
                    <el-select
                        :model-value="row.userRole"
                        size="small"
                        @change="(value: unknown) => changeRole(row, value)"
                    >
                        <el-option v-for="r in ROLES" :key="r.value" :label="r.label" :value="r.value" />
                    </el-select>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
                <template #default="{ row }">
                    <el-tag :type="row.userStatus === 1 ? 'danger' : 'success'" size="small">
                        {{ row.userStatus === 1 ? '已封禁' : '正常' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="aiQueryCount" label="AI 次数" width="100" />
            <el-table-column prop="createdAt" label="注册时间" min-width="170" />
            <el-table-column label="操作" width="340" fixed="right">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                    <el-button
                        v-if="row.userRole === 'user'"
                        link
                        type="primary"
                        @click="openPromote(row)"
                    >
                        转为成员
                    </el-button>
                    <el-button link type="primary" @click="openResetPassword(row)">重置密码</el-button>
                    <!-- 用户 AI 额度用完会来找管理员，这里是那条链路的终点 -->
                    <el-button link type="primary" @click="resetAiQuota(row)">重置 AI</el-button>
                    <el-button
                        link
                        :type="row.userStatus === 1 ? 'success' : 'danger'"
                        @click="toggleStatus(row)"
                    >
                        {{ row.userStatus === 1 ? '解封' : '封禁' }}
                    </el-button>
                    <el-button link type="danger" @click="removeUser(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div class="pager">
            <el-pagination
                layout="total, prev, pager, next"
                :total="total"
                :current-page="query.current"
                :page-size="query.pageSize"
                @current-change="
                    (p: number) => {
                        query.current = p;
                        load();
                    }
                "
            />
        </div>

        <el-dialog v-model="createVisible" title="新增账号" width="440px">
            <p class="muted">
                直接创建登录账号。密码留空则生成<b>随机初始密码</b>，创建成功后仅展示一次，请及时通过安全渠道告知本人。
            </p>
            <el-form label-position="top">
                <el-form-item label="账号" required>
                    <el-input v-model="createForm.userAccount" placeholder="4-16 位，需唯一" />
                </el-form-item>
                <el-form-item label="初始密码">
                    <el-input
                        v-model="createForm.userPassword"
                        placeholder="留空则生成随机初始密码（8-20 位）"
                        show-password
                    />
                </el-form-item>
                <el-form-item label="昵称">
                    <el-input v-model="createForm.userName" placeholder="选填，留空则用账号名" />
                </el-form-item>
                <el-form-item label="角色">
                    <el-select v-model="createForm.userRole">
                        <el-option v-for="r in ROLES" :key="r.value" :label="r.label" :value="r.value" />
                    </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="createVisible = false">取消</el-button>
                <el-button type="primary" @click="submitCreate">创建</el-button>
            </template>
        </el-dialog>

        <!-- 初始密码结果框：随机口令只随创建响应返回这一次（后端不存明文、查不回来），
             关掉这个框就再也看不到——所以「复制」按钮是主操作，关闭是次操作 -->
        <el-dialog v-model="passwordResultVisible" title="账号已创建" width="420px">
            <p class="muted">初始密码仅此一次展示，请立即复制并通过安全渠道转交本人，并督促其登录后自行修改。</p>
            <div class="init-password">
                <code class="init-password__value">{{ initialPassword }}</code>
                <el-button type="primary" @click="copyInitialPassword">复制</el-button>
            </div>
            <template #footer>
                <el-button @click="passwordResultVisible = false">关闭</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="editVisible" title="编辑用户资料" width="440px">
            <p class="muted">
                为账号「{{ editTarget?.userAccount }}」修改昵称与头像。角色、状态与密码请用行内操作，不走这里。
            </p>
            <el-form label-position="top">
                <el-form-item label="昵称">
                    <el-input
                        v-model="editForm.userName"
                        maxlength="64"
                        placeholder="留空则各处显示兜底为账号名"
                    />
                </el-form-item>
                <el-form-item label="头像">
                    <ImageUploader v-model="editForm.userAvatar" tip="仅支持 PNG / JPEG；移除即清空头像" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="editVisible = false">取消</el-button>
                <el-button type="primary" @click="submitEdit">保存</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="pwdVisible" title="重置密码" width="380px">
            <p class="muted">为账号「{{ pwdTarget?.userAccount }}」设置新密码（8-20 位）</p>
            <el-input v-model="newPassword" placeholder="新密码" show-password />
            <template #footer>
                <el-button @click="pwdVisible = false">取消</el-button>
                <el-button type="primary" @click="submitPassword">确定</el-button>
            </template>
        </el-dialog>

        <el-dialog v-model="promoteVisible" title="转为工作室成员" width="440px">
            <p class="muted">
                为账号「{{ promoteTarget?.userAccount }}」创建成员档案并绑定，同时把角色改为「成员」。
            </p>
            <el-form label-position="top">
                <el-form-item label="姓名">
                    <el-input v-model="promoteForm.name" placeholder="成员姓名（必填）" />
                </el-form-item>
                <el-form-item label="入学年份">
                    <el-input-number v-model="promoteForm.gradeYear" :min="2000" :max="2100" />
                </el-form-item>
                <el-form-item label="技术方向">
                    <el-input
                        v-model="promoteForm.direction"
                        placeholder="如：Java后端 / AI应用 / 前端工程化"
                    />
                </el-form-item>
                <el-form-item label="团队职务">
                    <el-select v-model="promoteForm.teamPosition">
                        <el-option label="成员" value="member" />
                        <el-option label="组长" value="tech_lead" />
                        <el-option label="队长" value="leader" />
                    </el-select>
                </el-form-item>
                <el-form-item label="个人简介">
                    <el-input v-model="promoteForm.summary" type="textarea" :rows="2" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="promoteVisible = false">取消</el-button>
                <el-button type="primary" @click="submitPromote">确定</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    overflow: hidden;
    display: inline-flex;
}

/* 兜底（姓名首字）在 32px 的圆里需要小一号字号 */
.avatar :deep(.img-fallback__box) {
    font-size: 13px;
}

.toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    margin-bottom: 14px;
}

.hint {
    font-size: 12px;
}

.table {
    padding: 4px 8px;
}

.pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
}

p {
    margin: 0 0 10px;
    font-size: 13px;
}

/* 初始密码展示行：等宽字体 + 弱背景，让「这是一串要抄走的字符」一眼可辨 */
.init-password {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 14px;
    background: var(--brand-soft);
    border: 1px dashed var(--brand);
    border-radius: var(--radius-sm);
}

.init-password__value {
    flex: 1;
    min-width: 0;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 18px;
    font-weight: 600;
    letter-spacing: 2px;
    color: var(--brand-dark);
    word-break: break-all;
    user-select: all;
}
</style>
