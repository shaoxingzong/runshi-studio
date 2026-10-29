<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { attendanceApi } from '@/api/attendance';
import ErrorRetry from '@/components/ErrorRetry.vue';
import { userApi } from '@/api/user';
import { useUserStore } from '@/stores/user';
import type { AttendanceBoardItem, AttendanceRecord } from '@/api/types';

/**
 * 成员工作台（考勤）
 *
 * 准入：只有在队成员能用（认 studio_member 表）。非成员被拦下并送回首页——
 * 不是报错，「不是成员」是正常的身份状态。
 *
 * 隐私：看板不含 IP / UA / 账号，只呈现「是否内网」的布尔结论。
 */
const router = useRouter();
const user = useUserStore();

const loading = ref(false);
const checkingIn = ref(false);
const board = ref<AttendanceBoardItem[]>([]);
const history = ref<AttendanceRecord[]>([]);
/** 当前登录账号 ID：用于在看板里定位「我」这一行 */
const userId = ref('');
/** 加载失败：非空时展示错误条 + 重试 */
const error = ref('');

/** 看板里属于我的那一行 */
const me = computed(() => board.value.find((item) => item.userId === userId.value));
/** 今天是否已打卡（看板里我自己那行的标记） */
const checkedToday = computed(() => !!me.value?.checkedIn);

/**
 * 冷却剩余秒数
 *
 * 由后端给出的 nextCheckInAt 驱动（后端按「最后一次签到 + 配置间隔」计算），
 * 前端只做倒计时展示——间隔是配置项，写死 60 秒会在配置变化后与后端不一致，
 * 表现就是「按钮亮着，点下去必报错」。
 */
const cooldownSeconds = ref(0);
/** 每秒重算一次剩余时间；页面离开时必须清掉，否则反复进出会叠加多个定时器 */
let cooldownTimer: number | null = null;

function refreshCooldown() {
    const next = me.value?.nextCheckInAt;
    if (!next) {
        cooldownSeconds.value = 0;
        return;
    }
    const remaining = Math.ceil((new Date(next).getTime() - Date.now()) / 1000);
    cooldownSeconds.value = remaining > 0 ? remaining : 0;
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        // 三个请求互不依赖，并发拉取
        const [current, boardData, historyData] = await Promise.all([
            userApi.current(),
            attendanceApi.todayBoard(),
            attendanceApi.myHistory(),
        ]);
        userId.value = current.id;
        board.value = boardData ?? [];
        history.value = historyData ?? [];
        // 看板刷新后立刻重算冷却：签到成功后按钮应马上变成「N 秒后可再次签到」
        refreshCooldown();
    } catch (e) {
        // 三个并发请求任一失败都会落到这里：给出原因与重试，
        // 而不是 loading 一关、整个工作台空白
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

async function checkIn() {
    checkingIn.value = true;
    try {
        await attendanceApi.checkIn();
        ElMessage.success('签到成功');
        // 刷新看板与历史：本次签到的时刻、是否内网、本月次数都会变
        await load();
    } catch {
        // 失败原因（非成员 / 距上次签到太近）已由请求层统一提示，
        // 这里不重复弹窗，也不重置数据——保持用户看到的是上一次的真实状态
    } finally {
        checkingIn.value = false;
    }
}

function formatTime(value?: string | null): string {
    return value ? value.slice(11, 16) : '—';
}

function formatDate(value?: string | null): string {
    return value ? value.slice(0, 10) : '—';
}

onMounted(async () => {
    // 布局不会在路由切换时重建，这里主动拉一次确保身份是最新的
    await user.loadMemberProfile();
    if (!user.isMember) {
        ElMessage.warning('仅工作室成员可以使用工作台');
        await router.replace('/');
        return;
    }
    await load();
    // 倒计时每秒刷新一次：只有冷却中才有意义，但不做条件判断更简单，开销可以忽略
    cooldownTimer = window.setInterval(refreshCooldown, 1000);
});

onBeforeUnmount(() => {
    // 不清理会在每次进出页面时叠加一个定时器，切换几次后倒计时会跳秒
    if (cooldownTimer != null) {
        window.clearInterval(cooldownTimer);
        cooldownTimer = null;
    }
});
</script>

<template>
    <div class="page" v-loading="loading">
        <div class="page-head">
            <div>
                <h1>工作台</h1>
                <p>成员签到与出勤情况，仅工作室成员可见。</p>
            </div>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <!-- 签到卡片：品牌蓝渐变，页面里唯一的重色块，用来强调「今天要做的那件事」 -->
        <section class="checkin-card">
            <div class="checkin-main">
                <div class="checkin-title">
                    {{ checkedToday ? '今日已打卡' : '还没有打卡' }}
                </div>
                <div class="checkin-sub">
                    {{
                        checkedToday
                            ? `打卡时间 ${formatTime(me?.checkInAt)}`
                            : '在工作室内网环境下签到会记为「内网」'
                    }}
                </div>
                <!--
                  冷却期禁用：后端有 60 秒防抖（studio.attendance.min-interval-seconds），
                  按钮一直可点等于「点了必然报错」。倒计时由 nextCheckInAt 驱动，前端不写死间隔
                -->
                <el-button
                    class="checkin-btn"
                    size="large"
                    :loading="checkingIn"
                    :disabled="cooldownSeconds > 0"
                    @click="checkIn"
                >
                    {{
                        checkedToday
                            ? cooldownSeconds > 0
                                ? `冷却中 ${cooldownSeconds}s`
                                : '再次签到'
                            : '一键签到'
                    }}
                </el-button>
            </div>

            <div class="stats">
                <div class="stat">
                    <div class="stat-value">{{ formatTime(me?.checkInAt) }}</div>
                    <div class="stat-label">今日打卡</div>
                </div>
                <div class="stat">
                    <div class="stat-value">
                        {{ me?.inLan ? '内网' : me ? '外网' : '—' }}
                    </div>
                    <div class="stat-label">本次网络</div>
                </div>
                <div class="stat">
                    <div class="stat-value">{{ me?.monthCount ?? 0 }}</div>
                    <div class="stat-label">本月次数</div>
                </div>
            </div>
        </section>

        <!-- 今日出勤看板 -->
        <section class="card">
            <h2>今日出勤</h2>
            <el-empty v-if="!board.length" description="暂无在队成员" />
            <el-table v-else :data="board" size="large">
                <el-table-column label="成员" min-width="120">
                    <template #default="{ row }">
                        <span class="name">{{ row.userName || '成员' }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="今日打卡" width="160">
                    <template #default="{ row }">
                        <el-tag v-if="row.checkedIn" type="success" size="small">
                            {{ formatTime(row.checkInAt) }}
                        </el-tag>
                        <span v-else class="muted">未打卡</span>
                    </template>
                </el-table-column>
                <el-table-column label="本月次数" width="110">
                    <template #default="{ row }">{{ row.monthCount ?? 0 }}</template>
                </el-table-column>
                <el-table-column label="网络" width="100">
                    <template #default="{ row }">
                        <span v-if="!row.checkedIn" class="muted">—</span>
                        <span v-else>{{ row.inLan ? '内网' : '外网' }}</span>
                    </template>
                </el-table-column>
            </el-table>
        </section>

        <!-- 我的考勤历史 -->
        <section class="card">
            <h2>我的考勤记录</h2>
            <el-empty v-if="!history.length" description="还没有签到记录" />
            <div v-else class="history">
                <div v-for="(record, index) in history" :key="index" class="history-item">
                    <span class="dot" :class="{ lan: record.inLan }" />
                    <span class="date">{{ formatDate(record.attendanceDate) }}</span>
                    <span class="time">{{ formatTime(record.checkInAt) }}</span>
                    <el-tag size="small" :type="record.inLan ? 'success' : 'info'">
                        {{ record.inLan ? '内网' : '外网' }}
                    </el-tag>
                </div>
            </div>
        </section>
    </div>
</template>

<style scoped>
.page-head {
    margin-bottom: 20px;
}

.checkin-card {
    background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
    color: #fff;
    border-radius: var(--radius);
    padding: 28px 32px;
    margin-bottom: 20px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 32px;
    flex-wrap: wrap;
    box-shadow: 0 10px 24px -12px rgba(37, 99, 235, 0.6);
}

.checkin-title {
    font-size: 22px;
    font-weight: 600;
}

.checkin-sub {
    margin: 6px 0 16px;
    font-size: 13px;
    opacity: 0.85;
}

.checkin-btn {
    min-width: 140px;
    font-weight: 500;
}

.stats {
    display: flex;
    gap: 14px;
}

.stat {
    min-width: 112px;
    padding: 14px 18px;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.14);
    text-align: center;
}

.stat-value {
    font-size: 24px;
    font-weight: 600;
    line-height: 1.2;
}

.stat-label {
    margin-top: 4px;
    font-size: 12px;
    opacity: 0.85;
}

h2 {
    margin: 0 0 14px;
    font-size: 18px;
    font-weight: 500;
}

.name {
    font-weight: 500;
}

.history {
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.history-item {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 14px;
}

/* 左侧小圆点：内网用绿色，外网用灰色，一眼区分 */
.dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #d1d5db;
    flex-shrink: 0;
}

.dot.lan {
    background: #059669;
}

.date {
    min-width: 100px;
    color: #1f2937;
}

.time {
    min-width: 60px;
    color: #6b7280;
}
</style>
