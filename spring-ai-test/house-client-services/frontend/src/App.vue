<script setup>
import { nextTick, onMounted, ref } from 'vue'

const conversationId = crypto.randomUUID()
const subscriptions = ref([])
const messages = ref([
  {
    role: 'assistant',
    content: '您好，我是房产客服。可以帮您办理认购或撤销认购。办理认购请提供房屋坐落、购买方姓名和证件号码。',
  },
])
const input = ref('')
const sending = ref(false)
const listError = ref('')
const chatList = ref(null)

function maskIdNo(idNo) {
  if (!idNo) {
    return ''
  }
  if (idNo.length <= 8) {
    return idNo[0] + '****' + idNo.slice(-1)
  }
  return idNo.slice(0, 4) + '********' + idNo.slice(-4)
}

function formatTime(value) {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString('zh-CN')
}

async function loadSubscriptions() {
  try {
    const response = await fetch('/api/subscriptions')
    if (!response.ok) {
      throw new Error('加载认购列表失败')
    }
    subscriptions.value = await response.json()
    listError.value = ''
  } catch (error) {
    listError.value = error.message || '无法连接后端服务'
  }
}

async function scrollToBottom() {
  await nextTick()
  if (chatList.value) {
    chatList.value.scrollTop = chatList.value.scrollHeight
  }
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || sending.value) {
    return
  }
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  const assistant = { role: 'assistant', content: '' }
  messages.value.push(assistant)
  sending.value = true
  await scrollToBottom()

  try {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, message: text }),
    })
    if (!response.ok || !response.body) {
      throw new Error('智能体暂时无法回复')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const chunks = parseSse(buffer)
      buffer = chunks.rest
      for (const piece of chunks.payloads) {
        assistant.content += piece
        await scrollToBottom()
      }
    }
    if (!assistant.content) {
      assistant.content = '未收到回复，请稍后重试。'
    }
  } catch (error) {
    assistant.content = error.message || '请求失败，请确认后端已启动。'
  } finally {
    sending.value = false
    await loadSubscriptions()
    await scrollToBottom()
  }
}

function parseSse(buffer) {
  const payloads = []
  const parts = buffer.split('\n\n')
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    const lines = part.split('\n')
    for (const line of lines) {
      if (line.startsWith('data:')) {
        payloads.push(line.slice(5).trimStart())
      }
    }
  }
  return { payloads, rest }
}

onMounted(loadSubscriptions)
</script>

<template>
  <div class="layout">
    <aside class="panel list-panel">
      <header class="panel-header">
        <h1>已生成认购</h1>
        <button class="ghost" type="button" @click="loadSubscriptions">刷新</button>
      </header>
      <p v-if="listError" class="hint error">{{ listError }}</p>
      <p v-else-if="subscriptions.length === 0" class="hint">暂无认购业务，可通过右侧智能体办理。</p>
      <ul v-else class="cards">
        <li v-for="item in subscriptions" :key="item.id" class="card">
          <div class="card-title">{{ item.houseLocation }}</div>
          <div>购买方：{{ item.buyerName }}</div>
          <div>证件号：{{ maskIdNo(item.buyerIdNo) }}</div>
          <div class="meta">{{ formatTime(item.createdAt) }}</div>
        </li>
      </ul>
    </aside>
    <section class="panel chat-panel">
      <header class="panel-header">
        <h1>房产客服智能体</h1>
      </header>
      <div ref="chatList" class="messages">
        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="bubble"
          :class="msg.role"
        >
          {{ msg.content }}
        </div>
      </div>
      <form class="composer" @submit.prevent="sendMessage">
        <textarea
          v-model="input"
          rows="2"
          placeholder="例如：帮我认购朝阳路88号2单元301，购买人张三，身份证 110101199001011234"
          :disabled="sending"
          @keydown.enter.exact.prevent="sendMessage"
        />
        <button type="submit" :disabled="sending || !input.trim()">
          {{ sending ? '办理中' : '发送' }}
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: minmax(280px, 36%) 1fr;
  height: 100%;
  gap: 16px;
  padding: 16px;
}

.panel {
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 8px;
}

h1 {
  margin: 0;
  font-size: 18px;
}

.ghost {
  border: 0;
  background: #e8eef5;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}

.hint {
  margin: 0 20px 12px;
  color: #6b7280;
}

.error {
  color: #b42318;
}

.cards {
  list-style: none;
  margin: 0;
  padding: 0 16px 16px;
  overflow: auto;
}

.card {
  background: #f7fafc;
  border-radius: 12px;
  padding: 12px;
  margin-bottom: 10px;
  line-height: 1.6;
}

.card-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.meta {
  color: #6b7280;
  font-size: 12px;
  margin-top: 6px;
}

.messages {
  flex: 1;
  overflow: auto;
  padding: 8px 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bubble {
  max-width: 80%;
  padding: 10px 12px;
  border-radius: 12px;
  white-space: pre-wrap;
  line-height: 1.5;
}

.bubble.user {
  align-self: flex-end;
  background: #2563eb;
  color: #fff;
}

.bubble.assistant {
  align-self: flex-start;
  background: #f3f4f6;
}

.composer {
  display: flex;
  gap: 8px;
  padding: 12px 16px 16px;
  border-top: 1px solid #eef2f6;
}

textarea {
  flex: 1;
  resize: none;
  border: 1px solid #d5dee7;
  border-radius: 10px;
  padding: 10px;
  font: inherit;
}

button[type='submit'] {
  border: 0;
  background: #2563eb;
  color: #fff;
  border-radius: 10px;
  padding: 0 16px;
  cursor: pointer;
}

button[type='submit']:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 800px) {
  .layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .list-panel,
  .chat-panel {
    min-height: 360px;
  }
}
</style>
