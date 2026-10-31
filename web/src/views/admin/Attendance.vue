<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { adminApi } from '@/api/admin';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { AttendanceCheckInRecord, AttendanceMonthStat } from '@/api/types';

/** 考勤统计（管理员） */
const now = new Date();
const month = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);

const records = ref<AttendanceMonthStat[]>([]);
const loading = ref(false);
/** 加载失败原因（空 = 正常）：只留一张空表，管理员会以为是「没人签到」 */
const error = ref('');

/** 抽屉：某成员的原始签到记录（时间 + IP） */
const drawerVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const checkIns = ref<AttendanceCheckInRecord[]>([]);
const detailTarget = ref<AttendanceMonthStat | null>(null);

/** 顶部汇总：人次、到岗人数、人均天数（都在前端由同一份列表算出，避免多一次接口） */
const summary = computed(() => {
    const totalTimes = records.value.reduce((sum, row) => sum + (row.times ?? 0), 0);
    const totalDays = records.value.reduce((sum, row) => sum + (row.days ?? 0), 0);
    const attended = records.value.filter((row) => (row.days ?? 0) > 0).length;
    return {
        totalTimes,
        totalDays,
        attended,
        people: records.value.length,
        avgDays: records.value.length ? (totalDays / records.value.length).toFixed(1) : '0.0',
    };
});

async function load() {
    loading.value = true;
    error.value = '';
    try {
        // 空月份不传：后端会按东八区当前月处理
        records.value = await adminApi.attendanceSummary({ month: month.value || undefined });
    } catch (e) {
        error.value = (e as Error).message || '加载失败';
        records.value = [];
    } finally {
        loading.value = false;
    }
}

async function openDetail(row: AttendanceMonthStat) {
    detailTarget.value = row;
    checkIns.value = [];
    detailError.value = '';
    drawerVisible.value = true;
    detailLoading.value = true;
    try {
        checkIns.value = await adminApi.attendanceRecords({
            userId: row.userId,
            month: month.value || undefined,
        });
    } catch (e) {
        detailError.value = (e as Error).message || '签到记录加载失败';
    } finally {
        detailLoading.value = false;
    }
}

/** 抽屉内重试：模板里不写非空断言，避免 vue-tsc 解析不到 */
function retryDetail() {
    if (detailTarget.value) {
        void openDetail(detailTarget.value);
    }
}

function formatTime(value?: string | null): string {
    return value ? value.slice(11, 16) : '—';
}

function statusText(status?: number | null): string {
    return status === 1 ? '已离队' : '在队';
}

onMounted(load);
</script>

<template>
    <div v-loading="loading">
        <div class="bar">
            <div>
                <h2>考勤统计</h2>
                <span class="muted">按月查看每位成员的签到天数与签到次数；同一天多次签到计入次数</span>
            </div>
            <div class="bar-actions">
                <!-- 月份是唯一筛选条件：换月立即重查，不需要再点「查询」 -->
                <el-date-picker
                    v-model="month"
                    type="month"
                    value-format="YYYY-MM"
                    placeholder="选择月份"
                    :clearable="false"
                    class="month-picker"
                    @change="load"
                />
                <el-button @click="load">刷新</el-button>
            </div>
        </div>

        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <div class="cards">
            <div class="stat-card">
                <span class="stat-label">签到人次</span>
                <span class="stat-value">{{ summary.totalTimes }}</span>
                <span class="stat-hint">当月所有签到记录条数</span>
            </div>
            <div class="stat-card">
                <span class="stat-label">到岗人数</span>
                <span class="stat-value">{{ summary.attended }}<em>/ {{ summary.people }}</em></span>
                <span class="stat-hint">当月至少签到过一次的成员</span>
            </div>
            <div class="stat-card">
                <span class="stat-label">人均签到天数</span>
                <span class="stat-value">{{ summary.avgDays }}</span>
                <span class="stat-hint">按名单内全部成员平均</span>
            </div>
        </div>

        <el-empty
            v-if="!loading && records.length === 0"
            :description="`${month} 没有可统计的成员`"
        />

        <el-table v-else :data="records" border class="table" row-key="userId">
            <el-table-column label="成员" min-width="180">
                <template #default="{ row }">
                    <span class="member-name">{{ row.userName || '—' }}</span>
                    <el-tag v-if="row.memberStatus === 1" size="small" type="info" effect="plain">
                        已离队
                    </el-tag>
                </template>
            </el-table-column>

            <el-table-column label="签到天数" width="110" align="center">
                <template #default="{ row }">
                    <!-- 0 天要显眼：管理员一眼要看出谁整月没来 -->
                    <span class="days" :class="{ zero: !row.days }">{{ row.days ?? 0 }}</span>
                </template>
            </el-table-column>

            <el-table-column label="签到次数" width="110" align="center">
                <template #default="{ row }">{{ row.times ?? 0 }}</template>
            </el-table-column>

            <el-table-column label="首次签到" width="110">
                <template #default="{ row }">{{ formatTime(row.firstAt) }}</template>
            </el-table-column>

            <el-table-column label="最后签到" width="110">
                <template #default="{ row }">{{ formatTime(row.lastAt) }}</template>
            </el-table-column>

            <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openDetail(row)">查看明细</el-button>
                </template>
            </el-table-column>
        </el-table>

        <el-drawer
            v-model="drawerVisible"
            size="560px"
            :title="`${detailTarget?.userName || '成员'} · ${month} 签到明细`"
        >
            <div v-loading="detailLoading" class="drawer-body">
                <ErrorRetry v-if="detailError" :message="detailError" @retry="retryDetail" />

                <template v-else>
                    <div v-if="detailTarget" class="drawer-meta">
                        <span class="muted">
                            签到 {{ detailTarget.days ?? 0 }} 天 / {{ detailTarget.times ?? 0 }} 次
                        </span>
                        <el-tag size="small" :type="detailTarget.memberStatus === 1 ? 'info' : 'success'">
                            {{ statusText(detailTarget.memberStatus) }}
                        </el-tag>
                    </div>

                    <el-empty v-if="checkIns.length === 0" description="当月没有签到记录" />

                    <el-table v-else :data="checkIns" border size="small">
                        <el-table-column label="签到时间" width="170">
                            <template #default="{ row }">
                                {{ row.checkInAt?.replace('T', ' ').slice(0, 19) || '—' }}
                            </template>
                        </el-table-column>
                        <!-- IP 只给管理员看：用于事后追溯，成员端接口不返回 -->
                        <el-table-column label="来源 IP" min-width="160">
                            <template #default="{ row }">
                                <span class="ip">{{ row.ip || '—' }}</span>
                            </template>
                        </el-table-column>
                    </el-table>
                </template>
            </div>
        </el-drawer>
    </div>
</template>

<style scoped>
.bar {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 16px;
}

h2 {
    margin: 0 0 4px;
    font-size: 18px;
    font-weight: 500;
}

.bar-actions {
    display: flex;
    align-items: center;
    gap: 8px;
}

.month-picker {
    width: 150px;
}

.cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 12px;
    margin-bottom: 16px;
}

.stat-card {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 16px 18px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
}

.stat-label {
    font-size: 13px;
    color: #6b7280;
}

.stat-value {
    font-size: 26px;
    font-weight: 600;
    line-height: 1.2;
}

.stat-value em {
    margin-left: 2px;
    font-size: 13px;
    font-style: normal;
    font-weight: 400;
    color: #9ca3af;
}

.stat-hint {
    font-size: 12px;
    color: #9ca3af;
}

.member-name {
    margin-right: 8px;
    font-weight: 500;
}

/* 0 天用红色：整月没来是这张表最需要被看见的信息 */
.days {
    font-weight: 600;
    color: #111827;
}

.days.zero {
    color: #dc2626;
}

.drawer-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 12px;
}

.drawer-body {
    min-height: 160px;
}

/* IP 用等宽字体：管理员常常要逐位核对，比例字体容易看错 1/l、0/O */
.ip {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
}
</style>
