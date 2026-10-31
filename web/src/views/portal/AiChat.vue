<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { aiApi, chatStream } from '@/api/ai';
import { isRequestError } from '@/api/request';
import SmartImage from '@/components/SmartImage.vue';
import { useUserStore } from '@/stores/user';
import type { AiSession, KnowledgeSource } from '@/api/types';

const router = useRouter();

/** AI 助手（SSE 流式） */
interface Bubble {
    role: 'user' | 'assistant';
    content: string;
    sources?: KnowledgeSource[];
}

const SESSION_KEY = 'ai_session_id';

/** 提问长度上限，必须与后端 AiChatRequest 的 @Size(max = 500) 一致 */
const MESSAGE_MAX_LENGTH = 500;
const PROMPTS = ['工作室官网用了哪些技术栈？', '如何加入润石工作室？', '刷题系统的错题本怎么用？'];

const messages = ref<Bubble[]>([]);
const input = ref('');
const sessionId = ref(localStorage.getItem(SESSION_KEY) || '');
const streaming = ref(false);

/** AI 服务是否不可用（输入区上方的持续提示条） */
const aiDown = ref(false);

/** 历史会话（接口要求登录，游客侧这一栏不出现） */
const sessionList = ref<AiSession[]>([]);
const user = useUserStore();
const windowRef = ref<HTMLElement | null>(null);
let controller: AbortController | null = null;

/** 会话 → 消息（含参考来源）的内存缓存 */
const sessionMessages = new Map<string, Bubble[]>();

/** 把当前会话的消息数组登记进缓存（会话 ID 未知时——首次提问 meta 之前——跳过） */
function cacheCurrentMessages() {
    if (sessionId.value) {
        sessionMessages.set(sessionId.value, messages.value);
    }
}

/** 当前会话标题：优先取会话列表里的标题；刚创建、列表还没刷新时用兜底文案 */
const activeSessionTitle = computed(() => {
    const current = sessionList.value.find((item) => item.id === sessionId.value);
    return current?.title || (sessionId.value ? '当前会话' : '新的对话');
});

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
    if (text.length > MESSAGE_MAX_LENGTH) {
        ElMessage.warning(`提问不能超过 ${MESSAGE_MAX_LENGTH} 字，请精简后再发送`);
        return;
    }
    input.value = '';
    messages.value.push({ role: 'user', content: text });
    const answer: Bubble = { role: 'assistant', content: '', sources: [] };
    messages.value.push(answer);
    // 续聊场景：这一轮气泡要立刻进缓存，否则切走再回来会少一轮
    cacheCurrentMessages();

    streaming.value = true;
    controller = new AbortController();
    try {
        await chatStream(
            { sessionId: sessionId.value || null, message: text },
            {
                onMeta: (payload) => {
                    sessionId.value = payload.sessionId;
                    localStorage.setItem(SESSION_KEY, payload.sessionId);
                    // 首次提问：会话 ID 在 meta 里才出现，此时才能把这一轮登记进缓存
                    cacheCurrentMessages();
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
                    if (payload.code === 'A0402') {
                        sessionId.value = '';
                        localStorage.removeItem(SESSION_KEY);
                        answer.content = '（会话已失效，已自动开启新对话，请重新提问）';
                        return;
                    }
                    if (payload.code === 'A0501') {
                        aiDown.value = true;
                        answer.content =
                            '（提问次数已用完）请联系工作室负责人为你重置次数后继续使用。';
                        return;
                    }
                    aiDown.value = true;
                    answer.content = `（回答失败）${payload.message || ''}`;
                },
            },
            controller.signal,
        );
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
    stop();
    sessionId.value = '';
    localStorage.removeItem(SESSION_KEY);
    messages.value = [];
}

/** 拉取历史会话 */
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
    stop();
    // 三处必须同时更新：只改内存 ref 的话，刷新页面后会跳回旧会话
    sessionId.value = id;
    localStorage.setItem(SESSION_KEY, id);

    // 命中缓存直接还原（含参考来源）：历史接口没有 sources，再请求一次反而会把来源擦掉
    const cached = sessionMessages.get(id);
    if (cached) {
        messages.value = cached;
        void scrollBottom();
        return;
    }

    messages.value = [];
    await loadHistory();
}

async function removeCurrent() {
    const id = sessionId.value;
    if (!id) {
        return;
    }
    // 删除当前会话同样先中止：删除与生成并发时，回答可能落在已被清空的界面上
    stop();
    try {
        await aiApi.deleteSession(id);
        ElMessage.success('会话已删除');
    } catch {
        // 提示已在请求层；失败时保持原状，不清理本地状态
        return;
    }
    // 删除成功后清掉当前会话（连同内存缓存），并让侧边栏跟着刷新
    sessionMessages.delete(id);
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
        // 登记缓存：历史本身没有 sources，但本页后续问答产生的来源要能随会话留存
        cacheCurrentMessages();
        void scrollBottom();
    } catch (e) {
        if (isRequestError(e) && e.code === 'A0402') {
            // 会话在服务端已不存在：缓存也一并丢掉，避免切回时还原出一份「幽灵对话」
            sessionMessages.delete(sessionId.value);
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

onBeforeUnmount(() => {
    stop();
});
</script>

<template>
    <div class="page ai-page">
        <header class="ai-head">
            <div class="ai-head-left">
                <span class="ai-mark" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none">
                        <path
                            d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                            stroke="currentColor"
                            stroke-width="1.5"
                            stroke-linejoin="round"
                        />
                        <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" fill="currentColor" opacity=".75" />
                    </svg>
                </span>
                <div class="ai-head-copy">
                    <h1>AI 助手</h1>
                    <p class="sub">基于工作室资料回答，命中资料时会在回答下方给出来源。</p>
                </div>
            </div>
        </header>

        <!-- 未登录：AI 服务需登录后使用（配额规则放在登录后的界面里说，避免游客被用不上的信息干扰）-->
        <div v-if="!user.isLogin" class="gate-wrap">
            <div class="gate-card">
                <span class="gate-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none">
                        <path
                            d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                            stroke="currentColor"
                            stroke-width="1.5"
                            stroke-linejoin="round"
                        />
                        <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" fill="currentColor" opacity=".75" />
                    </svg>
                </span>
                <h2>登录后即可使用 AI 助手</h2>
                <p>AI 助手会结合工作室的项目、成员与荣誉资料回答你的问题，并给出引用来源。</p>
                <div class="gate-actions">
                    <el-button type="primary" @click="router.push('/login')">去登录</el-button>
                    <el-button @click="router.push('/projects')">先看看项目</el-button>
                </div>
            </div>
        </div>

        <div v-else class="ai-layout">
            <!-- 历史会话：接口要求登录（此处已在「已登录」分支内）-->
            <aside class="sessions">
                <div class="sessions-head">
                    <div>
                        <p class="sessions-eyebrow">HISTORY</p>
                        <span class="sessions-title">历史会话</span>
                    </div>
                    <div class="sessions-head-actions">
                        <span class="sessions-count">{{ sessionList.length }}</span>
                        <el-button link type="primary" @click="newChat">+ 新建</el-button>
                    </div>
                </div>

                <div class="sessions-list">
                    <div v-if="!sessionList.length" class="sessions-empty">
                        <span class="sessions-empty-icon" aria-hidden="true">
                            <svg viewBox="0 0 24 24" fill="none">
                                <path
                                    d="M5 6.5A2.5 2.5 0 0 1 7.5 4h9A2.5 2.5 0 0 1 19 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4 3v-3.2A2.5 2.5 0 0 1 5 13.5v-7Z"
                                    stroke="currentColor"
                                    stroke-width="1.5"
                                    stroke-linejoin="round"
                                />
                            </svg>
                        </span>
                        <span>还没有历史会话</span>
                        <small>你的提问会保存在这里</small>
                    </div>

                    <div
                        v-for="item in sessionList"
                        :key="item.id"
                        class="session-item"
                        :class="{ active: item.id === sessionId }"
                        @click="switchSession(item.id)"
                    >
                        <span class="session-dot"></span>
                        <div class="session-info">
                            <div class="session-title">{{ item.title || '新会话' }}</div>
                            <div class="session-meta">
                                <span>{{ (item.updatedAt ?? '').slice(5, 16) }}</span>
                                <span v-if="item.messageCount">{{ item.messageCount }} 条</span>
                            </div>
                        </div>
                    </div>
                </div>
            </aside>

            <section class="chat-card">
                <!-- AI 不可用的持续提示条：替代全局红色弹窗。持续性故障下用户会连续提问，弹窗只会反复骚扰；这条一直挂着，下次成功自动消失 -->
                <el-alert
                    v-if="aiDown"
                    type="warning"
                    :closable="false"
                    title="AI 服务暂时不可用，可稍后重试"
                    class="down-alert"
                />

                <header class="chat-head">
                    <div class="chat-head-main">
                        <span class="chat-avatar" aria-hidden="true">
                            <svg viewBox="0 0 24 24" fill="none">
                                <path
                                    d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                                    stroke="currentColor"
                                    stroke-width="1.5"
                                    stroke-linejoin="round"
                                />
                            </svg>
                        </span>
                        <div class="chat-head-copy">
                            <strong class="chat-title">{{ activeSessionTitle }}</strong>
                            <span class="chat-sub"><i class="online-dot"></i> 润石工作室知识库</span>
                        </div>
                    </div>
                    <div class="chat-actions">
                        <el-button v-if="sessionId" link type="danger" @click="removeCurrent">删除当前</el-button>
                    </div>
                </header>

                <div ref="windowRef" class="window">
                    <div v-if="messages.length === 0" class="empty-state">
                        <span class="empty-icon" aria-hidden="true">
                            <svg viewBox="0 0 24 24" fill="none">
                                <path
                                    d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                                    stroke="currentColor"
                                    stroke-width="1.5"
                                    stroke-linejoin="round"
                                />
                                <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" fill="currentColor" opacity=".75" />
                            </svg>
                        </span>
                        <h2>今天想了解点什么？</h2>
                        <p>可以问项目、成员、荣誉、刷题，或工作室的加入方式。</p>
                        <div class="prompt-chips">
                            <button
                                v-for="prompt in PROMPTS"
                                :key="prompt"
                                type="button"
                                class="prompt-chip"
                                @click="input = prompt"
                            >
                                {{ prompt }}
                            </button>
                        </div>
                    </div>

                    <template v-else>
                        <div v-for="(item, index) in messages" :key="index" class="msg" :class="item.role">
                            <span v-if="item.role === 'assistant'" class="msg-avatar assistant-avatar" aria-hidden="true">
                                <svg viewBox="0 0 24 24" fill="none">
                                    <path
                                        d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"
                                        stroke="currentColor"
                                        stroke-width="1.5"
                                        stroke-linejoin="round"
                                    />
                                </svg>
                            </span>

                            <div class="msg-body">
                                <div class="bubble">
                                    <!-- 首个 delta 前显示「思考中」：模型首个 token 通常要等 1~5 秒（检索 + 生成），一片空白会让用户以为卡住、反复点发送。sources 通常先到，可顺便告知「已参考 N 条资料」 -->
                                    <span
                                        v-if="
                                            item.role === 'assistant' &&
                                            !item.content &&
                                            streaming &&
                                            index === messages.length - 1
                                        "
                                        class="thinking"
                                    >
                                        <span class="dots"><i /><i /><i /></span>
                                        <template v-if="item.sources && item.sources.length">
                                            已参考 {{ item.sources.length }} 条资料，正在作答…
                                        </template>
                                        <template v-else>思考中…</template>
                                    </span>
                                    <template v-else>{{ item.content }}</template>
                                    <span
                                        v-if="streaming && index === messages.length - 1 && item.content"
                                        class="caret"
                                    />
                                </div>

                                <div v-if="item.sources && item.sources.length" class="sources">
                                    <span class="sources-label">参考来源</span>
                                    <span v-for="src in item.sources" :key="src.docId" class="src">{{ src.title }}</span>
                                </div>
                            </div>

                            <span v-if="item.role === 'user'" class="msg-avatar user-avatar" aria-hidden="true">
                                <SmartImage
                                    :src="user.displayAvatar"
                                    :alt="user.displayName || '我'"
                                    fallback="avatar"
                                />
                            </span>
                        </div>
                    </template>
                </div>

                <div class="composer">
                    <div class="composer-box">
                        <!-- maxlength 与后端 @Size(max=500) 对齐：让用户在输入时就看见边界 -->
                        <el-input
                            v-model="input"
                            type="textarea"
                            :rows="3"
                            resize="none"
                            :maxlength="MESSAGE_MAX_LENGTH"
                            show-word-limit
                            placeholder="输入你的问题，例如：工作室有哪些项目？"
                            @keydown.ctrl.enter="send"
                        />
                        <div class="composer-footer">
                            <span class="composer-hint">Ctrl + Enter 发送</span>
                            <el-button v-if="streaming" class="stop-btn" @click="stop">停止生成</el-button>
                            <el-button v-else type="primary" class="send-btn" :disabled="!input.trim()" @click="send">
                                发送
                            </el-button>
                        </div>
                    </div>
                </div>
            </section>
        </div>

        <p v-if="user.isLogin" class="quota">
            普通用户可免费提问 5 次，用完后可联系工作室负责人重置。
        </p>
    </div>
</template>

<style scoped>
.ai-page {
    /* 与首页一致的淡蓝色顶部光晕，避免 AI 页看起来像一块孤立的白色表单 */
    background: radial-gradient(900px 420px at 50% -140px, rgb(37 99 235 / 10%), transparent 70%);
}

.ai-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;
    padding: 2px 2px 0;
}

.ai-head-left {
    display: flex;
    align-items: center;
    gap: 14px;
    min-width: 0;
}

.ai-mark {
    display: grid;
    place-items: center;
    flex: 0 0 auto;
    width: 48px;
    height: 48px;
    border-radius: var(--radius-lg);
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 8px 20px rgb(37 99 235 / 20%);
}

.ai-mark svg {
    width: 27px;
    height: 27px;
}

.ai-head-copy {
    min-width: 0;
}

.ai-head h1 {
    margin: 0;
    font-size: 28px;
    line-height: 1.2;
    letter-spacing: -0.02em;
}

.ai-head .sub {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 13px;
}

.ai-layout {
    display: flex;
    align-items: stretch;
    gap: 18px;
}

/* ===== 历史会话 ===== */
.sessions {
    display: flex;
    flex-direction: column;
    width: 260px;
    flex-shrink: 0;
    height: clamp(500px, calc(100vh - 250px), 720px);
    padding: 16px 12px 12px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow);
}

.sessions-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 8px;
    padding: 0 6px 12px;
    border-bottom: 1px solid var(--border);
}

.sessions-eyebrow {
    margin: 0 0 1px;
    color: var(--muted);
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 0.16em;
}

.sessions-title {
    font-size: 15px;
    font-weight: 700;
}

.sessions-head-actions {
    display: flex;
    align-items: center;
    gap: 6px;
}

.sessions-count {
    min-width: 20px;
    padding: 1px 6px;
    border-radius: 999px;
    background: var(--bg);
    color: var(--muted);
    font-size: 11px;
    text-align: center;
}

.sessions-list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 10px 2px 0;
    scrollbar-width: thin;
}

.sessions-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 100%;
    gap: 6px;
    padding: 20px 12px;
    color: var(--muted);
    font-size: 12px;
    text-align: center;
}

.sessions-empty-icon {
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    margin-bottom: 2px;
    border-radius: 12px;
    background: var(--brand-soft);
    color: var(--brand);
}

.sessions-empty-icon svg {
    width: 20px;
    height: 20px;
}

.sessions-empty small {
    color: #9ca3af;
    font-size: 11px;
}

.session-item {
    display: flex;
    align-items: flex-start;
    gap: 9px;
    margin-bottom: 4px;
    padding: 9px 10px;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: background 0.2s ease, border-color 0.2s ease;
}

.session-item:hover {
    background: #f8fafc;
    border-color: var(--border);
}

.session-item.active {
    background: var(--brand-soft);
    border-color: #bfdbfe;
}

.session-dot {
    width: 6px;
    height: 6px;
    margin-top: 6px;
    flex-shrink: 0;
    border-radius: 50%;
    background: #cbd5e1;
}

.session-item.active .session-dot {
    background: var(--brand);
}

.session-info {
    flex: 1;
    min-width: 0;
}

.session-title {
    overflow: hidden;
    color: var(--text);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.session-meta {
    display: flex;
    gap: 8px;
    margin-top: 2px;
    color: #9ca3af;
    font-size: 11px;
}

/* ===== 对话区 ===== */
.chat-card {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    height: clamp(500px, calc(100vh - 250px), 720px);
    overflow: hidden;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow-md);
}

.down-alert {
    margin: 0;
    border: none;
    border-bottom: 1px solid #fde68a;
    border-radius: 0;
}

.chat-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 13px 18px;
    border-bottom: 1px solid var(--border);
    background: var(--surface);
}

.chat-head-main {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
}

.chat-avatar {
    display: grid;
    place-items: center;
    flex-shrink: 0;
    width: 34px;
    height: 34px;
    border: 1px solid #bfdbfe;
    border-radius: var(--radius-sm);
    background: linear-gradient(135deg, #eff6ff, #e0f2fe);
    color: var(--brand);
}

.chat-avatar svg {
    width: 19px;
    height: 19px;
}

.chat-head-copy {
    min-width: 0;
}

.chat-title {
    display: block;
    overflow: hidden;
    max-width: 420px;
    color: var(--text);
    font-size: 14px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.chat-sub {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    margin-top: 1px;
    color: var(--muted);
    font-size: 11px;
}

.online-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--brand);
}

.chat-actions {
    flex-shrink: 0;
}

.window {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 22px 20px;
    background: linear-gradient(180deg, #fff 0%, #f8fafc 100%);
    scrollbar-width: thin;
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    min-height: 300px;
    padding: 24px 16px;
    text-align: center;
}

.empty-icon {
    display: grid;
    place-items: center;
    width: 72px;
    height: 72px;
    margin-bottom: 16px;
    border-radius: var(--radius-xl);
    background: linear-gradient(135deg, #eff6ff, #e0f2fe);
    color: var(--brand);
    box-shadow: var(--shadow-md);
}

.empty-icon svg {
    width: 38px;
    height: 38px;
}

.empty-state h2 {
    margin: 0 0 8px;
    font-size: 20px;
    letter-spacing: -0.01em;
}

.empty-state p {
    max-width: 430px;
    margin: 0 0 20px;
    color: var(--muted);
    font-size: 13px;
    line-height: 1.7;
}

.prompt-chips {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
}

.prompt-chip {
    padding: 7px 13px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: #fff;
    color: var(--text-secondary);
    font: inherit;
    font-size: 12px;
    cursor: pointer;
    transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease, transform 0.2s ease;
}

.prompt-chip:hover {
    border-color: #93c5fd;
    background: var(--brand-soft);
    color: var(--brand);
    transform: translateY(-1px);
}

.msg {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-bottom: 20px;
}

.msg.user {
    /* 用户消息整体贴右：气泡在左、头像在右。
       不要用 row-reverse——它会把头像排到气泡左侧，看起来像「头像先于消息」 */
    justify-content: flex-end;
}

.msg-avatar {
    display: grid;
    place-items: center;
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    border-radius: var(--radius-sm);
    font-size: 12px;
    font-weight: 700;
}

.assistant-avatar {
    border: 1px solid #bfdbfe;
    background: linear-gradient(135deg, #eff6ff, #dbeafe);
    color: var(--brand);
}

.assistant-avatar svg {
    width: 17px;
    height: 17px;
}

.user-avatar {
    overflow: hidden;
    background: #e2e8f0;
    color: #475569;
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.msg-body {
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-width: 78%;
    min-width: 0;
}

.msg.user .msg-body {
    align-items: flex-end;
}

.bubble {
    padding: 11px 15px;
    border: 1px solid var(--border);
    border-radius: 4px var(--radius-lg) var(--radius-lg) var(--radius-lg);
    background: #fff;
    box-shadow: 0 2px 8px rgb(15 23 42 / 4%);
    color: var(--text);
    font-size: 14px;
    line-height: 1.75;
    white-space: pre-wrap;
    word-break: break-word;
}

.msg.user .bubble {
    border: none;
    border-radius: var(--radius-lg) 4px var(--radius-lg) var(--radius-lg);
    background: linear-gradient(135deg, var(--brand), #0ea5e9);
    color: #fff;
    box-shadow: 0 6px 16px rgb(37 99 235 / 18%);
}

.caret {
    display: inline-block;
    width: 6px;
    height: 15px;
    margin-left: 3px;
    background: currentColor;
    vertical-align: text-bottom;
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
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    font-size: 11px;
}

.sources-label {
    color: var(--muted);
}

.src {
    padding: 3px 9px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: #fff;
    color: var(--text-secondary);
}

/* ===== 输入区 ===== */
.composer {
    padding: 12px 16px 14px;
    border-top: 1px solid var(--border);
    background: var(--surface);
}

.composer-box {
    padding: 4px;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: #fff;
    transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.composer-box:focus-within {
    border-color: rgb(37 99 235 / 45%);
    box-shadow: 0 0 0 3px rgb(37 99 235 / 10%);
}

.composer :deep(.el-textarea__inner) {
    min-height: 64px !important;
    padding: 8px 10px;
    border: none;
    background: transparent;
    box-shadow: none;
    font-size: 14px;
    line-height: 1.7;
}

.composer :deep(.el-textarea__inner:focus) {
    box-shadow: none;
}

.composer-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 7px 4px;
}

.composer-hint {
    color: #9ca3af;
    font-size: 11px;
}

.send-btn,
.stop-btn {
    height: 32px;
    padding: 0 18px;
    border-radius: var(--radius-sm);
    font-size: 13px;
}

.quota {
    margin: 12px 2px 0;
    color: var(--muted);
    font-size: 12px;
    text-align: center;
}

/* ===== 未登录门禁 ===== */
.gate-wrap {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: calc(100vh - 320px);
    padding: 10px 0 20px;
}

.gate-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    max-width: 520px;
    padding: 42px 34px;
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    background:
        radial-gradient(500px 240px at 50% 0%, rgb(37 99 235 / 10%), transparent 70%),
        var(--surface);
    box-shadow: var(--shadow-md);
    text-align: center;
}

.gate-icon {
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    margin-bottom: 16px;
    border-radius: var(--radius-xl);
    background: linear-gradient(135deg, #2563eb, #0ea5e9);
    color: #fff;
    box-shadow: 0 8px 20px rgb(37 99 235 / 20%);
}

.gate-icon svg {
    width: 32px;
    height: 32px;
}

.gate-card h2 {
    margin: 0 0 8px;
    font-size: 20px;
}

.gate-card p {
    max-width: 420px;
    margin: 0 0 20px;
    color: var(--muted);
    font-size: 13px;
    line-height: 1.7;
}

.gate-actions {
    display: flex;
    gap: 10px;
}

@media (max-width: 900px) {
    .ai-head {
        align-items: flex-start;
    }

    .ai-layout {
        flex-direction: column;
    }

    .sessions {
        width: 100%;
        height: auto;
        max-height: 240px;
        padding: 14px 12px;
    }

    .sessions-list {
        max-height: 150px;
    }

    .chat-card {
        height: clamp(520px, 72vh, 700px);
    }

    .chat-title {
        max-width: 220px;
    }
}

@media (max-width: 600px) {
    .ai-head h1 {
        font-size: 24px;
    }

    .ai-head .sub {
        font-size: 12px;
    }

    .ai-mark {
        width: 40px;
        height: 40px;
        border-radius: 12px;
    }

    .ai-mark svg {
        width: 22px;
        height: 22px;
    }

    .chat-head {
        padding: 12px 14px;
    }

    .window {
        padding: 16px 14px;
    }

    .msg-body {
        max-width: 84%;
    }

    .composer {
        padding: 10px 12px 12px;
    }

    .quota {
        text-align: left;
        line-height: 1.7;
    }

    .gate-wrap {
        min-height: 0;
        padding: 20px 0;
    }

    .gate-card {
        padding: 32px 22px;
    }
}
</style>
