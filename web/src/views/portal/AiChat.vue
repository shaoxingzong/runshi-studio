<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { aiApi, chatStream } from '@/api/ai';
import type { KnowledgeSource } from '@/api/types';

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
    if (sessionId.value) {
        void loadHistory();
    }
});
</script>

<template>
    <div class="page">
        <div class="page-head">
            <h1>AI 助手</h1>
            <p>基于工作室资料回答，命中资料时会在回答下方给出来源。游客有限频，登录后额度更高。</p>
        </div>

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
</template>

<style scoped>
.chat {
    display: flex;
    flex-direction: column;
    gap: 14px;
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
