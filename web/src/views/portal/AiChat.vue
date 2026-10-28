<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { aiApi, chatStream } from '@/api/ai';
import { isRequestError } from '@/api/request';
import { useUserStore } from '@/stores/user';
import type { AiSession, KnowledgeSource } from '@/api/types';

const router = useRouter();

/**
 * AI 助手（SSE 流式）
 *
 * 事件序列（后端契约）：meta → sources → delta* → done / error
 * meta 首次提问带回 sessionId（续聊靠它）；sources 是本次引用的知识库来源（恒发，无命中为空数组）。
 */
interface Bubble {
    role: 'user' | 'assistant';
    content: string;
    sources?: KnowledgeSource[];
}

const SESSION_KEY = 'ai_session_id';

const messages = ref<Bubble[]>([]);
const input = ref('');
const sessionId = ref(localStorage.getItem(SESSION_KEY) || '');
const streaming = ref(false);

/**
 * AI 服务是否不可用（输入区上方的持续提示条）
 *
 * 不用全局红色弹窗：token 冻结这类持续性故障下用户会连续提问，每问一次弹一次只是骚扰。
 * 提示条一直挂着，下次成功自动消失。
 */
const aiDown = ref(false);

/** 历史会话（接口要求登录，游客侧这一栏不出现） */
const sessionList = ref<AiSession[]>([]);
const user = useUserStore();
const windowRef = ref<HTMLElement | null>(null);
let controller: AbortController | null = null;

async function scrollBottom() {
    await nextTick();
    if (windowRef.value) {
        windowRef.value.scrollTop = windowRef.value.scrollHeight;
    }
}

async function send() {
    const text = input.value.trim();
    if (!text || streaming.value) {
        return;
    }
    input.value = '';
    messages.value.push({ role: 'user', content: text });
    const answer: Bubble = { role: 'assistant', content: '', sources: [] };
    messages.value.push(answer);

    streaming.value = true;
    controller = new AbortController();
    try {
        await chatStream(
            { sessionId: sessionId.value || null, message: text },
            {
                onMeta: (payload) => {
                    sessionId.value = payload.sessionId;
                    localStorage.setItem(SESSION_KEY, payload.sessionId);
                },
                onSources: (sources) => {
                    answer.sources = sources;
                },
                onDelta: (delta) => {
                    // 收到内容说明服务恢复了，撤掉提示条
                    aiDown.value = false;
                    answer.content += delta;
                    void scrollBottom();
                },
                onError: (payload) => {
                    // 会话丢失（被删除 / 服务端数据重置）：本地 id 已失效，
                    // 丢掉它让下一次提问自动开新会话——否则每次发送都会撞 A0402，
                    // 用户只能自己去点「新建会话」
                    if (payload.code === 'A0402') {
                        sessionId.value = '';
                        localStorage.removeItem(SESSION_KEY);
                        answer.content = '（会话已失效，已自动开启新对话，请重新提问）';
                        return;
                    }
                    // 配额用完（A0501）：不能自助恢复，必须管理员重置，
                    // 所以单独给一句「该找谁」，而不是让他对着「回答失败」发呆
                    if (payload.code === 'A0501') {
                        aiDown.value = true;
                        answer.content =
                            '（提问次数已用完）请联系工作室负责人为你重置次数后继续使用。';
                        return;
                    }
                    // SSE 的失败契约：error 事件里带业务码与文案（可能是参数错误、
                    // 配额超限，也可能是 AI 不可用）。统一挂上提示条，让用户知道要重试
                    aiDown.value = true;
                    answer.content = `（回答失败）${payload.message || ''}`;
                },
            },
            controller.signal,
        );
        // 走到这里说明整条流正常收尾：清除提示条。
        // （不能只在 onDelta 里清——回答为空时没有增量，提示会一直挂着）
        aiDown.value = false;
    } catch (e) {
        if ((e as Error).name !== 'AbortError') {
            // 不再弹全局红窗：挂持续提示条 + 气泡内说明，同一次失败不打扰两次
            aiDown.value = true;
            answer.content = '（请求失败，请稍后重试）';
        }
    } finally {
        streaming.value = false;
        controller = null;
        void scrollBottom();
        // 首次提问会创建出新会话，侧边栏要跟着出现
        void loadSessionList();
    }
}

function stop() {
    controller?.abort();
}

function newChat() {
    sessionId.value = '';
    localStorage.removeItem(SESSION_KEY);
    messages.value = [];
}

/**
 * 拉取历史会话
 *
 * 未登录时直接返回而不是发请求：接口要求登录，游客调它会拿到 A0201，
 * 而请求层遇到 A0201 会清掉本地令牌——为一个不该发的请求赔上登录态不划算。
 */
async function loadSessionList() {
    if (!user.isLogin) {
        sessionList.value = [];
        return;
    }
    try {
        const page = await aiApi.sessionList({ current: 1, pageSize: 20 });
        sessionList.value = page.records ?? [];
    } catch {
        // 任何失败都静默降级为「不显示列表」，不影响对话本身
        sessionList.value = [];
    }
}

async function switchSession(id: string) {
    if (id === sessionId.value) {
        return;
    }
    // 三处必须同时更新：只改内存 ref 的话，刷新页面后会跳回旧会话
    sessionId.value = id;
    localStorage.setItem(SESSION_KEY, id);
    messages.value = [];
    await loadHistory();
}

async function removeCurrent() {
    const id = sessionId.value;
    if (!id) {
        return;
    }
    try {
        await aiApi.deleteSession(id);
        ElMessage.success('会话已删除');
    } catch {
        // 提示已在请求层；失败时保持原状，不清理本地状态
        return;
    }
    // 删除成功后清掉当前会话，并让侧边栏跟着刷新
    newChat();
    await loadSessionList();
}

/** 拉取当前会话的历史（后端按时间正序返回最近 50 条） */
async function loadHistory() {
    if (!sessionId.value) {
        return;
    }
    try {
        const history = await aiApi.history(sessionId.value);
        messages.value = history.map((item) => ({ role: item.role as 'user' | 'assistant', content: item.content }));
        void scrollBottom();
    } catch (e) {
        // 会话不存在（被删除 / 服务端数据重置）：本地存的 id 已经失效。
        // 静默丢掉它，直接进入空白新对话——否则用户会卡在
        // 「每次都要先点一次新建会话才能提问」上
        if (isRequestError(e) && e.code === 'A0402') {
            newChat();
            ElMessage.info('上次的会话已失效，已为你开启新对话');
            return;
        }
        // 请求层已弹过提示（notified）就不再重复，否则同一条错误弹两次
        if (!isRequestError(e) || !e.notified) {
            ElMessage.error((e as Error).message);
        }
    }
}

onMounted(() => {
    // 未登录不拉任何数据：历史与会话列表接口都要求登录，调了只会换来 A0201
    if (!user.isLogin) {
        return;
    }
    if (sessionId.value) {
        void loadHistory();
    }
    void loadSessionList();
});
</script>

<template>
    <div class="page">
        <div class="page-head">
            <h1>AI 助手</h1>
            <p>基于工作室资料回答，命中资料时会在回答下方给出来源。</p>
        </div>

        <!-- 未登录：AI 服务需登录后使用（配额规则放在登录后的界面里说，避免游客被用不上的信息干扰） -->
        <div v-if="!user.isLogin" class="card gate">
            <el-empty description="">
                <template #description>
                    <p class="gate-title">登录后即可使用 AI 助手</p>
                </template>
                <el-button type="primary" @click="router.push('/login')">去登录</el-button>
            </el-empty>
        </div>

        <div v-else class="ai-layout">
            <!-- 历史会话：接口要求登录（此处已在「已登录」分支内） -->
            <aside class="sessions">
                <div class="sessions-head">
                    <span class="sessions-title">历史会话</span>
                    <el-button link type="danger" :disabled="!sessionId" @click="removeCurrent">
                        删除当前
                    </el-button>
                </div>
                <el-scrollbar max-height="460px">
                    <div v-if="!sessionList.length" class="muted sessions-empty">还没有历史会话</div>
                    <div
                        v-for="item in sessionList"
                        :key="item.id"
                        class="session-item"
                        :class="{ active: item.id === sessionId }"
                        @click="switchSession(item.id)"
                    >
                        <div class="session-title">{{ item.title || '新会话' }}</div>
                        <div class="session-meta">
                            <span>{{ (item.updatedAt ?? '').slice(5, 16) }}</span>
                            <span v-if="item.messageCount">{{ item.messageCount }} 条</span>
                        </div>
                    </div>
                </el-scrollbar>
            </aside>

            <div class="card chat">
                <!--
                  AI 不可用的持续提示条：替代全局红色弹窗。
                  持续性故障下用户会连续提问，弹窗只会反复骚扰；这条一直挂着，下次成功自动消失
                -->
                <el-alert
                    v-if="aiDown"
                    type="warning"
                    :closable="false"
                    title="AI 服务暂时不可用，可稍后重试"
                    class="down-alert"
                />

                <div ref="windowRef" class="window">
                <el-empty v-if="messages.length === 0" description="问点什么吧，例如「星盘计划用了什么技术？」" />
                <div v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
                    <div class="bubble">
                        <!--
                            首个 delta 前显示「思考中」：模型首个 token 通常要等 1~5 秒（检索 + 生成），
                            一片空白会让用户以为卡住、反复点发送。sources 通常先到，可顺便告知「已参考 N 条资料」
                        -->
                        <span
                            v-if="
                                msg.role === 'assistant' &&
                                !msg.content &&
                                streaming &&
                                index === messages.length - 1
                            "
                            class="thinking"
                        >
                            <span class="dots"><i /><i /><i /></span>
                            <template v-if="msg.sources && msg.sources.length">
                                已参考 {{ msg.sources.length }} 条资料，正在作答…
                            </template>
                            <template v-else>思考中…</template>
                        </span>
                        <template v-else>{{ msg.content }}</template>
                        <span
                            v-if="streaming && index === messages.length - 1 && msg.content"
                            class="caret"
                        />
                    </div>
                    <div v-if="msg.sources && msg.sources.length" class="sources">
                        <span class="muted">参考来源：</span>
                        <span v-for="src in msg.sources" :key="src.docId" class="src">{{ src.title }}</span>
                    </div>
                </div>
            </div>

            <div class="composer">
                <el-input
                    v-model="input"
                    type="textarea"
                    :rows="2"
                    resize="none"
                    placeholder="输入你的问题，Ctrl + Enter 发送"
                    @keydown.ctrl.enter="send"
                />
                <div class="buttons">
                    <el-button v-if="streaming" @click="stop">停止生成</el-button>
                    <el-button v-else type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
                    <el-button :disabled="!sessionId" @click="loadHistory">历史</el-button>
                    <el-button @click="newChat">新会话</el-button>
                </div>
            </div>
            </div>

            <!-- 配额规则只在登录后展示：游客用不上这段信息（门禁已挡在前头）。
                 次数用完后不能自助恢复，必须由管理员重置，所以把「找谁」直接写出来，
                 否则用户只会看到一句「次数已用完」然后无从下手 -->
            <p class="muted quota">
                普通用户可免费提问 5 次；用完后可以联系工作室负责人为你重置次数。
            </p>
        </div>
    </div>
</template>

<style scoped>
.gate {
    padding: 40px 20px;
}

/* 提示条贴在卡片顶部：不加圆角，让它看起来是卡片的一部分而不是浮层 */
.down-alert {
    border-radius: var(--radius) var(--radius) 0 0;
    margin-bottom: 0;
}

.quota {
    margin: 10px 2px 0;
    font-size: 12px;
}

.gate-title {
    margin: 0 0 6px;
    font-size: 15px;
    font-weight: 500;
    color: #1f2937;
}

.ai-layout {
    display: flex;
    gap: 16px;
    align-items: flex-start;
}

.sessions {
    width: 240px;
    flex-shrink: 0;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 14px;
}

.sessions-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 10px;
}

.sessions-title {
    font-size: 14px;
    font-weight: 500;
}

.sessions-empty {
    font-size: 12px;
}

.session-item {
    padding: 8px 10px;
    margin-bottom: 4px;
    margin-right: 6px;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.2s;
}

.session-item:hover {
    background: #f1f5f9;
}

.session-item.active {
    background: #eff6ff;
}

.session-title {
    font-size: 13px;
    color: #1f2937;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.session-meta {
    margin-top: 2px;
    font-size: 12px;
    color: #9ca3af;
    display: flex;
    gap: 8px;
}

/* 窄屏放不下侧边栏：改为上下堆叠 */
@media (max-width: 900px) {
    .ai-layout {
        flex-direction: column;
    }

    .sessions {
        width: 100%;
    }
}

.chat {
    display: flex;
    flex-direction: column;
    gap: 14px;
    /* 在 flex 容器里要显式声明，否则内容会被侧边栏挤变形 */
    flex: 1;
    min-width: 0;
}

.window {
    height: 460px;
    overflow-y: auto;
    padding: 4px;
}

.msg {
    margin-bottom: 18px;
}

.msg.user {
    text-align: right;
}

.bubble {
    display: inline-block;
    max-width: 82%;
    padding: 10px 14px;
    border-radius: var(--radius-sm);
    white-space: pre-wrap;
    word-break: break-word;
    text-align: left;
    background: #f3f4f6;
}

.msg.user .bubble {
    background: var(--brand);
    color: #fff;
}

.caret {
    display: inline-block;
    width: 6px;
    height: 15px;
    background: currentColor;
    vertical-align: text-bottom;
    margin-left: 3px;
    animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
    to {
        visibility: hidden;
    }
}

/* 「思考中」占位：三个跳动的小圆点，明确表达「在等模型」而不是「卡住了」 */
.thinking {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: var(--muted);
    font-size: 13px;
}

.dots {
    display: inline-flex;
    gap: 3px;
}

.dots i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--muted);
    animation: bounce 1.2s infinite ease-in-out;
}

.dots i:nth-child(2) {
    animation-delay: 0.15s;
}

.dots i:nth-child(3) {
    animation-delay: 0.3s;
}

@keyframes bounce {
    0%,
    60%,
    100% {
        transform: translateY(0);
        opacity: 0.35;
    }

    30% {
        transform: translateY(-4px);
        opacity: 1;
    }
}

.sources {
    margin-top: 8px;
    font-size: 12px;
}

.src {
    display: inline-block;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 2px 8px;
    margin-right: 6px;
}

.composer {
    display: flex;
    gap: 12px;
    align-items: flex-start;
}

.buttons {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
</style>
