<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { adminApi } from '@/api/admin';
import { postApi } from '@/api/post';
import { portalApi } from '@/api/portal';
import { trafficApi } from '@/api/traffic';
import type { Overview, TrafficOverview } from '@/api/types';

/**
 * 管理后台 · 首页概览
 *
 * 定位：管理员登录后落地的第一屏——「有多少事等着我处理」比任何功能入口都重要。
 * `/admin` 不跳成员列表而落地待审队列：看不到积压，就容易漏掉最紧急的事。
 *
 * 全部复用现有接口，不新增后端：待审帖子 / 待审评论 / 用户 / 成员 / 证书 / 知识库文档
 * 六项计数，分别来自既有的管理端分页接口（pageSize=1 只取 total）与官网大盘接口。
 *
 * 失败隔离：六项计数并行请求，任何一项失败只让那一张卡片显示「—」，
 * 不拖垮整页——概览页挂掉会比没有概览更糟（它还是登录后的落地页）。
 */
const router = useRouter();

/** 单项计数：value 为 null 表示「取数失败」，与「真的是 0」是两回事 */
interface CountCard {
    key: string;
    label: string;
    value: number | null;
    hint?: string;
    to?: string;
}

const cards = ref<CountCard[]>([
    { key: 'pendingPost', label: '待审帖子', value: null, hint: '先去处理', to: '/admin/posts' },
    { key: 'pendingComment', label: '待审评论', value: null, hint: '先去处理', to: '/admin/post-comments' },
    { key: 'user', label: '注册用户', value: null, to: '/admin/users' },
    { key: 'member', label: '成员档案', value: null, to: '/admin/members' },
    { key: 'certificate', label: '荣誉证书', value: null, to: '/admin/certificates' },
    { key: 'knowledge', label: '知识库文档', value: null, to: '/admin/knowledge' },
]);

const overview = ref<Overview | null>(null);
const traffic = ref<TrafficOverview | null>(null);
const loading = ref(false);

/** 证书级别的中文映射：与官网荣誉墙同一套（后端存英文枚举） */
const LEVEL_TEXT: Record<string, string> = { national: '国家级', provincial: '省级', municipal: '市级/校级' };
const TYPE_TEXT: Record<string, string> = {
    competition: '学科竞赛',
    soft_copyright: '软件著作权',
    patent: '专利',
    paper: '论文',
};

/** 分布数据转有序数组：与「是否有数据」共用同一份，过滤掉 0 值维度 */
function entriesOf(source: Record<string, number> | undefined): [string, number][] {
    return Object.entries(source ?? {}).filter(([, value]) => value > 0);
}

function setCard(key: string, value: number | null) {
    const card = cards.value.find((item) => item.key === key);
    if (card) {
        card.value = value;
    }
}

/**
 * 取一项计数，失败时落 null 而不是抛出
 *
 * 每项各自 catch：一项接口挂掉不应该让其余五项也变成「—」。
 */
async function countOf(key: string, loader: () => Promise<number>) {
    try {
        setCard(key, await loader());
    } catch (e) {
        console.warn(`概览计数失败：${key}`, e);
        setCard(key, null);
    }
}

async function load() {
    loading.value = true;
    try {
        // 六项并行：串行会把首屏时间拉成六倍，而这些接口互不依赖
        await Promise.all([
            countOf('pendingPost', async () => Number((await postApi.pending({ current: 1, pageSize: 1 })).total ?? 0)),
            countOf('pendingComment', async () =>
                Number((await postApi.commentPending({ current: 1, pageSize: 1 })).total ?? 0)),
            countOf('user', async () => Number((await adminApi.userPage({ current: 1, pageSize: 1 })).total ?? 0)),
            countOf('member', async () => Number((await adminApi.memberPage({ current: 1, pageSize: 1 })).total ?? 0)),
            countOf('knowledge', async () => Number((await adminApi.docPage({ current: 1, pageSize: 1 })).total ?? 0)),
            countOf('certificate', async () => Number((await portalApi.overview()).certificateTotal ?? 0)),
        ]);
        // 证书分布单独取一次：与证书总数同源，放在这里让 overview 失败时分布区显示空态即可
        overview.value = await portalApi.overview();
        // 流量：与六项计数同属「并行、失败隔离」。它失败只让流量区显示「—」
        try {
            traffic.value = await trafficApi.overview({ days: 30 });
        } catch (e) {
            console.warn('概览流量加载失败', e);
            traffic.value = null;
        }
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
                <h2>首页概览</h2>
                <span class="muted">各项计数实时查询；待审积压建议优先处理</span>
            </div>
            <el-button link type="primary" @click="load">刷新</el-button>
        </div>

        <!-- 计数卡片：点击可跳转到对应管理页 -->
        <div class="grid">
            <div
                v-for="card in cards"
                :key="card.key"
                class="card stat"
                :class="{ clickable: card.to }"
                @click="card.to && router.push(card.to)"
            >
                <div class="num">
                    {{ card.value === null ? '—' : card.value }}
                </div>
                <div class="label">{{ card.label }}</div>
                <div v-if="card.hint && card.value" class="hint">{{ card.hint }}</div>
            </div>
        </div>

        <!-- 待处理事项：只列真正需要动手的项，点击直达队列 -->
        <div class="card block">
            <h3>待处理事项</h3>
            <div class="todos">
                <div class="todo" @click="router.push('/admin/posts')">
                    <span class="todo-label">帖子待审队列</span>
                    <span class="todo-num">{{ cards[0]?.value ?? '—' }}</span>
                </div>
                <div class="todo" @click="router.push('/admin/post-comments')">
                    <span class="todo-label">评论待审队列</span>
                    <span class="todo-num">{{ cards[1]?.value ?? '—' }}</span>
                </div>
                <div class="todo" @click="router.push('/admin/knowledge')">
                    <span class="todo-label">知识库文档（同步 / 重建按需）</span>
                    <span class="todo-num">{{ cards[5]?.value ?? '—' }}</span>
                </div>
            </div>
        </div>

        <!-- 流量速览：只放今日两个数，完整分析去「流量分析」页 -->
        <div class="card block">
            <h3>今日流量</h3>
            <div v-if="traffic" class="traffic-row">
                <div class="traffic-item">
                    <span class="traffic-num">{{ traffic.todayPv }}</span>
                    <span class="traffic-label">今日 PV</span>
                </div>
                <div class="traffic-item">
                    <span class="traffic-num">{{ traffic.todayUv }}</span>
                    <span class="traffic-label">今日 UV</span>
                </div>
                <div class="traffic-item clickable" @click="router.push('/admin/traffic')">
                    <span class="traffic-num muted-num">{{ traffic.totalPv }}</span>
                    <span class="traffic-label">近 30 天 PV · 查看分析</span>
                </div>
            </div>
            <el-empty v-else description="流量数据加载失败" :image-size="60" />
        </div>

        <!-- 证书分布：与官网首页同一份数据，这里用轻量进度条而不是图表 -->
        <div class="card block">
            <h3>荣誉分布（共 {{ overview?.certificateTotal ?? '—' }} 项）</h3>
            <el-empty
                v-if="!entriesOf(overview?.certificateByLevel).length && !entriesOf(overview?.certificateByType).length"
                description="暂无数据"
                :image-size="60"
            />
            <div v-else class="dist">
                <div v-if="entriesOf(overview?.certificateByLevel).length" class="dist-row">
                    <span class="dist-title">按级别</span>
                    <div class="dist-bars">
                        <div v-for="[key, value] in entriesOf(overview?.certificateByLevel)" :key="key" class="bar-item">
                            <span class="bar-name">{{ LEVEL_TEXT[key] || key }}</span>
                            <el-progress
                                :percentage="Math.round((value / (overview?.certificateTotal || 1)) * 100)"
                                :stroke-width="10"
                                :text-inside="true"
                            />
                            <span class="bar-value">{{ value }}</span>
                        </div>
                    </div>
                </div>
                <div v-if="entriesOf(overview?.certificateByType).length" class="dist-row">
                    <span class="dist-title">按类型</span>
                    <div class="dist-bars">
                        <div v-for="[key, value] in entriesOf(overview?.certificateByType)" :key="key" class="bar-item">
                            <span class="bar-name">{{ TYPE_TEXT[key] || key }}</span>
                            <el-progress
                                :percentage="Math.round((value / (overview?.certificateTotal || 1)) * 100)"
                                :stroke-width="10"
                                :text-inside="true"
                            />
                            <span class="bar-value">{{ value }}</span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
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

/* 卡片自适应排布：宽屏多列、窄屏自动减少，不写死列数 */
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

.clickable {
    cursor: pointer;
    transition: box-shadow 0.2s, transform 0.2s;
}

.clickable:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
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

.hint {
    margin-top: 6px;
    font-size: 12px;
    color: #d97706;
}

.block {
    padding: 18px 20px;
    margin-bottom: 16px;
}

.traffic-row {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}

.traffic-item {
    flex: 1;
    min-width: 140px;
    padding: 14px 16px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    text-align: center;
}

.traffic-item.clickable {
    cursor: pointer;
    transition: border-color 0.2s, background 0.2s;
}

.traffic-item.clickable:hover {
    border-color: var(--brand);
    background: var(--brand-soft);
}

.traffic-num {
    display: block;
    font-size: 24px;
    font-weight: 700;
    color: var(--brand);
}

.muted-num {
    color: var(--muted);
}

.traffic-label {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: var(--muted);
}

.todos {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.todo {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 14px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: border-color 0.2s, background 0.2s;
}

.todo:hover {
    border-color: var(--brand);
    background: var(--brand-soft);
}

.todo-label {
    font-size: 14px;
}

.todo-num {
    font-size: 18px;
    font-weight: 600;
    color: var(--brand);
}

.dist {
    display: flex;
    flex-direction: column;
    gap: 18px;
}

.dist-row {
    display: flex;
    align-items: flex-start;
    gap: 16px;
}

.dist-title {
    flex-shrink: 0;
    width: 56px;
    padding-top: 4px;
    font-size: 13px;
    color: var(--muted);
}

.dist-bars {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.bar-item {
    display: flex;
    align-items: center;
    gap: 10px;
}

.bar-name {
    flex-shrink: 0;
    width: 96px;
    font-size: 13px;
}

.bar-value {
    flex-shrink: 0;
    width: 32px;
    text-align: right;
    font-size: 13px;
    color: var(--muted);
}
</style>
