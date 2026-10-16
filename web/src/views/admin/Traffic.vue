<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { trafficApi } from '@/api/traffic';
import ErrorRetry from '@/components/ErrorRetry.vue';
import type { TrafficOverview } from '@/api/types';

/**
 * 流量分析（管理端）
 *
 * <p><b>数据口径必须先说清</b>：UV 按前端 localStorage 的随机 uuid 去重，
 * 因此「同一人换设备 / 清缓存 / 无痕浏览」会算成多个 UV。
 * 这是刻意取舍——不记 IP、不记设备指纹，隐私面更小，代价是精度。
 * 所以页面顶部常驻这条说明，避免管理员拿着数字做过度解读。
 *
 * <p><b>为什么不做图表</b>：趋势数据是「日期 + 两个数」，
 * 表格已经足够读；后台是工具页面，加载快、可复制比好看更重要。
 * （门户首页用 ECharts 是因为它承担「第一印象」的职能，两者诉求不同。）
 */
const DAY_OPTIONS = [
    { value: 7, label: '近 7 天' },
    { value: 30, label: '近 30 天' },
    { value: 90, label: '近 90 天' },
];

const days = ref(30);
const overview = ref<TrafficOverview | null>(null);
const loading = ref(false);
const error = ref('');

async function load() {
    loading.value = true;
    error.value = '';
    try {
        overview.value = await trafficApi.overview({ days: days.value });
    } catch (e) {
        // 与门户一致的错误态：给原因 + 重试，而不是留一片空白
        error.value = (e as Error).message || '加载失败';
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <div v-loading="loading">
        <div class="bar">
            <div>
                <h2>流量分析</h2>
                <span class="muted">门户页面访问统计（匿名访客也计入）</span>
            </div>
            <div class="ops">
                <el-select v-model="days" style="width: 120px" @change="load">
                    <el-option
                        v-for="option in DAY_OPTIONS"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                </el-select>
                <el-button @click="load">刷新</el-button>
            </div>
        </div>

        <!-- 口径说明常驻：UV 的精度边界必须在数字旁边，而不是写在文档里等人踩坑 -->
        <el-alert
            type="info"
            :closable="false"
            class="notice"
            title="UV 按浏览器标识去重：同一人换设备、清缓存或使用无痕模式会被记为多个 UV。只统计页面路径，不记录 IP、UA 与网址参数。"
        />

        <!-- 错误态改用共享组件 ErrorRetry：此前的内联手写 alert 与组件 markup 重复，
             正是该组件要消除的「各写一遍」——改组件样式时不会漏掉这里 -->
        <ErrorRetry v-if="error" :message="error" @retry="load" />

        <template v-if="overview">
            <div class="grid">
                <div class="card stat">
                    <div class="num">{{ overview.totalPv }}</div>
                    <div class="label">总 PV（{{ days }} 天）</div>
                </div>
                <div class="card stat">
                    <div class="num">{{ overview.totalUv }}</div>
                    <div class="label">总 UV（{{ days }} 天）</div>
                </div>
                <div class="card stat today">
                    <div class="num">{{ overview.todayPv }}</div>
                    <div class="label">今日 PV</div>
                </div>
                <div class="card stat today">
                    <div class="num">{{ overview.todayUv }}</div>
                    <div class="label">今日 UV</div>
                </div>
            </div>

            <div class="card block">
                <h3>每日趋势</h3>
                <el-empty v-if="!overview.daily.length" description="暂无数据" :image-size="60" />
                <el-table v-else :data="overview.daily" border max-height="420">
                    <el-table-column prop="date" label="日期" width="140" />
                    <el-table-column prop="pv" label="PV" width="120" />
                    <el-table-column prop="uv" label="UV" width="120" />
                </el-table>
            </div>

            <div class="card block">
                <h3>热门页面 TOP 10</h3>
                <el-empty v-if="!overview.topPages.length" description="暂无数据" :image-size="60" />
                <el-table v-else :data="overview.topPages" border>
                    <el-table-column prop="pagePath" label="页面路径" min-width="240" show-overflow-tooltip />
                    <el-table-column prop="pv" label="PV" width="120" />
                    <el-table-column prop="uv" label="UV" width="120" />
                </el-table>
            </div>
        </template>
    </div>
</template>

<style scoped>
.bar {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
}

h2 {
    margin: 0 0 4px;
    font-size: 18px;
    font-weight: 500;
}

h3 {
    margin: 0 0 14px;
    font-size: 15px;
    font-weight: 500;
}

.ops {
    display: flex;
    gap: 8px;
}

.notice {
    margin-bottom: 16px;
}

.notice :deep(.el-alert__title) {
    display: flex;
    align-items: center;
    gap: 10px;
}

.msg {
    flex: 1;
    min-width: 0;
    word-break: break-all;
}

.grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 14px;
    margin-bottom: 16px;
}

.stat {
    padding: 20px 18px;
    text-align: center;
}

.stat.today .num {
    color: #059669;
}

.num {
    font-size: 30px;
    font-weight: 700;
    color: var(--brand);
    line-height: 1.2;
}

.label {
    margin-top: 6px;
    font-size: 13px;
    color: var(--muted);
}

.block {
    padding: 18px 20px;
    margin-bottom: 16px;
}
</style>
