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

function formatPrice(value) {
  if (value === null || value === undefined) {
    return '—'
  }
  return '¥' + Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function formatTime(value) {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString('zh-CN')
}

function statusClass(status) {
  switch (status) {
    case 1: return 'status-active'
    case 2: return 'status-done'
    case 3: return 'status-revoked'
    default: return 'status-draft'
  }
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
    <!-- 左侧：认购列表 -->
    <aside class="panel list-panel">
      <header class="panel-header">
        <h2>已生成认购</h2>
        <button class="btn-refresh" type="button" @click="loadSubscriptions">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21.5 2v6h-6"/>
            <path d="M2.5 22v-6h6"/>
            <path d="M2.5 12a10 10 0 0 1 17.5-6.5"/>
            <path d="M21.5 12a10 10 0 0 1-17.5 6.5"/>
          </svg>
          刷新
        </button>
      </header>

      <div v-if="listError" class="empty-state" role="alert">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#d44" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <p>{{ listError }}</p>
      </div>

      <div v-else-if="subscriptions.length === 0" class="empty-state">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="3" width="18" height="18" rx="3"/>
          <line x1="9" y1="9" x2="15" y2="15"/>
          <line x1="15" y1="9" x2="9" y2="15"/>
        </svg>
        <p>暂无认购业务</p>
        <span>可通过右侧智能体办理</span>
      </div>

      <ul v-else class="cards">
        <li v-for="item in subscriptions" :key="item.id" class="card">
          <div class="card-head">
            <span class="house-location">{{ item.houseLocations?.join('、') || '未填写房屋坐落' }}</span>
            <span class="status-badge" :class="statusClass(item.status)">
              {{ item.statusText || '草稿' }}
            </span>
          </div>

          <div class="card-body">
            <div class="price-row">
              <span class="price-label">总价</span>
              <span class="price-value">{{ formatPrice(item.totalPrice) }}</span>
            </div>

            <div class="info-row" v-if="item.developerNames?.length">
              <span class="info-label">开发企业</span>
              <span class="info-value">{{ item.developerNames.join('、') }}</span>
            </div>

            <div class="buyers-section" v-if="item.buyers?.length">
              <template v-for="(buyer, bi) in item.buyers" :key="bi">
                <div class="buyer-block" :class="{ 'buyer-separator': bi > 0 }">
                  <span class="buyer-name">{{ buyer.name }}</span>
                  <span class="buyer-id" v-if="buyer.idNo">
                    {{ buyer.idType === null ? '信用代码' : '证件号' }}：{{ maskIdNo(buyer.idNo) }}
                  </span>
                  <span class="buyer-phone" v-if="buyer.phone">{{ buyer.phone }}</span>
                </div>
              </template>
            </div>
          </div>

          <div class="card-footer">
            <span>编号 {{ item.bizNo }}</span>
            <span class="dot"></span>
            <span>{{ formatTime(item.createdAt) }}</span>
          </div>
        </li>
      </ul>
    </aside>

    <!-- 右侧：聊天窗口 -->
    <section class="panel chat-panel">
      <header class="panel-header">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          房产客服智能体
        </h2>
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
/* ========== Layout ========== */
.layout {
  display: grid;
  grid-template-columns: 60% 40%;
  height: 100%;
  gap: 16px;
  padding: 16px;
}

.panel {
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06), 0 6px 16px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ========== Panel Header ========== */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px 14px;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.panel-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  display: flex;
  align-items: center;
  gap: 7px;
}

.panel-header h2 svg {
  flex-shrink: 0;
  opacity: 0.6;
}

/* ========== Refresh Button ========== */
.btn-refresh {
  display: flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: #f0f2f5;
  color: #555;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  font-family: inherit;
}

.btn-refresh:hover {
  background: #e4e6ea;
  color: #333;
}

/* ========== Empty / Error State ========== */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  gap: 10px;
  text-align: center;
}

.empty-state p {
  margin: 0;
  font-size: 15px;
  color: #555;
}

.empty-state span {
  font-size: 13px;
  color: #999;
}

/* ========== Card List ========== */
.cards {
  list-style: none;
  margin: 0;
  padding: 16px;
  overflow-y: auto;
  flex: 1;
}

.card {
  background: #fafbfd;
  border: 1px solid #e9ecf0;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 12px;
}

.card:last-child {
  margin-bottom: 0;
}

/* --- Card Head: location + status badge --- */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.house-location {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  line-height: 1.4;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-badge {
  display: inline-block;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 999px;
  line-height: 1.5;
}

.status-active {
  background: #dcfce7;
  color: #166534;
}

.status-done {
  background: #dbeafe;
  color: #1e40af;
}

.status-revoked {
  background: #fee2e2;
  color: #b91c1c;
}

.status-draft {
  background: #fef3c7;
  color: #92400e;
}

/* --- Price Row --- */
.price-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 10px;
  margin-bottom: 10px;
  border-bottom: 1px dashed #e5e7eb;
}

.price-label {
  font-size: 13px;
  color: #888;
}

.price-value {
  font-size: 17px;
  font-weight: 700;
  color: #d93025;
  font-variant-numeric: tabular-nums;
}

/* --- Info Row --- */
.info-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding-bottom: 6px;
  font-size: 13px;
}

.info-label {
  color: #888;
  flex-shrink: 0;
}

.info-value {
  color: #444;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* --- Buyers Section --- */
.buyers-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.buyer-block {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  flex-wrap: wrap;
}

.buyer-separator {
  border-top: 1px dotted #ddd;
  padding-top: 6px;
  margin-top: 2px;
}

.buyer-name {
  font-weight: 600;
  color: #222;
}

.buyer-id {
  color: #666;
}

.buyer-phone {
  color: #444;
}

/* --- Card Footer --- */
.card-footer {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e5e7eb;
  font-size: 12px;
  color: #999;
}

.card-footer .dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #bbb;
  flex-shrink: 0;
}

/* ========== Chat Panel ========== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 14px;
  white-space: pre-wrap;
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
}

.bubble.user {
  align-self: flex-end;
  background: #2563eb;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble.assistant {
  align-self: flex-start;
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}

.composer {
  display: flex;
  gap: 10px;
  padding: 14px 18px 16px;
  border-top: 1px solid #f0f2f5;
  flex-shrink: 0;
}

textarea {
  flex: 1;
  resize: none;
  border: 1px solid #dfe3ea;
  border-radius: 10px;
  padding: 10px 12px;
  font: inherit;
  font-size: 14px;
  transition: border-color 0.15s;
  outline: none;
}

textarea:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

textarea:disabled {
  background: #fafafa;
  opacity: 0.7;
}

button[type='submit'] {
  border: none;
  background: #2563eb;
  color: #fff;
  border-radius: 10px;
  padding: 0 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s;
  font-family: inherit;
  height: 42px;
}

button[type='submit']:hover:not(:disabled) {
  background: #1d4ed8;
}

button[type='submit']:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ========== Responsive ========== */
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .list-panel,
  .chat-panel {
    min-height: 420px;
  }
}
</style>
