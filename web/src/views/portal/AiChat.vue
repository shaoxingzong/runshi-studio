<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { aiApi, chatStream } from '@/api/ai';
import { useUserStore } from '@/stores/user';
import type { AiSession, KnowledgeSource } from '@/api/types';

const router = useRouter();

/**
 * AI 助手（SSE 流式）
 *
 * 事件序列（后端契约）：meta → sources → delta* → done / error
 *  - meta：首次提问带回 sessionId，记住它即可续聊
 *  - sources：本次回答引用的知识库来源（**恒发**，无命中时是空数组）
 *  - delta：回答增量，边收边渲染
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
                    answer.content += delta;
                    void scrollBottom();
                },
                onError: (payload) => {
                    answer.content = `（回答失败）${payload.message || ''}`;
                },
            },
            controller.signal,
        );
    } catch (e) {
        if ((e as Error).name !== 'AbortError') {
            ElMessage.error((e as Error).message || '请求失败');
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
 * <p>未登录时<b>直接返回</b>而不是发请求：接口要求登录，游客调它会拿到 A0201，
 * 而请求层遇到 A0201 会顺手清掉本地令牌——为一个不该发的请求赔上登录态，不划算。
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
        ElMessage.error((e as Error).message);
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
            <p>
                基于工作室资料回答，命中资料时会在回答下方给出来源。
                普通用户可免费提问 5 次，成员与管理员不限次数。
            </p>
        </div>

        <!-- 未登录：AI 服务需登录后使用 -->
        <div v-if="!user.isLogin" class="card gate">
            <el-empty description="">
                <template #description>
                    <p class="gate-title">登录后即可使用 AI 助手</p>
                    <p class="gate-sub">普通用户可免费提问 5 次，成员与管理员不限次数。</p>
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
                <div ref="windowRef" class="window">
                <el-empty v-if="messages.length === 0" description="问点什么吧，例如「星盘计划用了什么技术？」" />
                <div v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
                    <div class="bubble">
                        <!--
                            未收到首个 delta 之前显示「思考中」：
                            模型的首个正文 token 通常要等 1~5 秒（检索 + 生成），
                            这段时间若一片空白，用户会以为卡住了、甚至反复点发送。
                            sources 事件通常早于首个 delta 到达，因此命中资料时
                            可以顺便告诉用户「已参考 N 条资料」——比干等更可信。
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

            <p v-if="sessionId" class="muted session">当前会话：{{ sessionId }}</p>
            </div>
        </div>
    </div>
</template>

<style scoped>
.gate {
    padding: 40px 20px;
}

.gate-title {
    margin: 0 0 6px;
    font-size: 15px;
    font-weight: 500;
    color: #1f2937;
}

.gate-sub {
    margin: 0;
    font-size: 13px;
    color: #6b7280;
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

.session {
    font-size: 12px;
    margin: 0;
}
</style>
