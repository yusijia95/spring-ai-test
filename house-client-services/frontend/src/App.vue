<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const conversationId = crypto.randomUUID()
const subscriptions = ref([])
const messages = ref([
  {
    role: 'assistant',
    content: '您好，我是房产客服。可以帮您办理认购、撤销认购，也可以查询楼盘价格和周边配套。办理认购请提供房屋坐落、购买方姓名和证件号码。',
  },
])
const input = ref('')
const sending = ref(false)
const listError = ref('')
const chatList = ref(null)

// Drawer state
const drawerOpen = ref(false)
const drawerRef = ref(null)
const toggleBtn = ref(null)

function openDrawer() {
  drawerOpen.value = true
  nextTick(() => {
    scrollToBottom(true)
    if (drawerRef.value) {
      const textarea = drawerRef.value.querySelector('textarea')
      const fallback = textarea || drawerRef.value.querySelector('button')
      if (fallback) fallback.focus()
    }
  })
}

function closeDrawer() {
  drawerOpen.value = false
  if (toggleBtn.value) toggleBtn.value.focus()
}

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

function isNearBottom() {
  const el = chatList.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 48
}

// 用"是否跟随到底"的标记，而不是每次重新量几何位置：
// 内容变高后几何距离会瞬间超过阈值，只看几何会导致长回复永远不再跟随。
const stickToBottom = ref(true)

function onMessagesScroll() {
  stickToBottom.value = isNearBottom()
}

function scrollToBottom(force = false) {
  nextTick(() => {
    if (chatList.value && (force || stickToBottom.value)) {
      chatList.value.scrollTop = chatList.value.scrollHeight
      stickToBottom.value = true
    }
  })
}

function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text) || ''
  return DOMPurify.sanitize(String(raw))
}

// Memoized markdown HTML per message index — recomputes only when messages change
const markdownMap = computed(() => {
  const map = {}
  for (let i = 0; i < messages.value.length; i++) {
    const msg = messages.value[i]
    if (msg.role === 'assistant') {
      map[i] = renderMarkdown(msg.content)
    }
  }
  return map
})

async function sendMessage() {
  const text = input.value.trim()
  if (!text || sending.value) {
    return
  }
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  // 必须是 reactive：直接改裸对象的 content 不会触发 markdownMap 这个 computed 重算，
  // 流式过程中气泡会一直是空的。
  const assistant = reactive({ role: 'assistant', content: '' })
  messages.value.push(assistant)
  sending.value = true
  stickToBottom.value = true
  scrollToBottom(true)

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
    let dataLines = []
    for (const line of lines) {
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5))
      }
    }
    if (dataLines.length > 0) {
      payloads.push(dataLines.join('\n'))
    }
  }
  return { payloads, rest }
}

function handleKeydown(e) {
  if (e.key === 'Escape' && drawerOpen.value) {
    closeDrawer()
  }
}

onMounted(() => {
  loadSubscriptions()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="layout">
    <!-- 认购列表：始终全屏 -->
    <aside class="panel list-panel">
      <header class="panel-header">
        <h2>已生成认购</h2>
        <div class="header-right">
          <button class="btn-refresh" type="button" @click="loadSubscriptions">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21.5 2v6h-6"/>
              <path d="M2.5 22v-6h6"/>
              <path d="M2.5 12a10 10 0 0 1 17.5-6.5"/>
              <path d="M21.5 12a10 10 0 0 1-17.5 6.5"/>
            </svg>
            刷新
          </button>
          <button
            ref="toggleBtn"
            type="button"
            class="btn-agent"
            @click="openDrawer"
            :aria-expanded="drawerOpen"
            aria-label="打开房产客服智能体"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
            智能体
          </button>
        </div>
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
        <span>点击右上角「智能体」办理</span>
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
  </div>

  <!-- Drawer 抽屉：Teleport 到 body -->
  <Teleport to="body">
    <Transition name="overlay">
      <!-- 半透明遮罩 -->
      <div
        v-if="drawerOpen"
        class="drawer-overlay"
        @click="closeDrawer"
        @keydown.escape="closeDrawer"
      />
    </Transition>

    <Transition name="drawer-slide">
      <!-- 滑出抽屉 -->
      <aside
        v-if="drawerOpen"
        ref="drawerRef"
        class="drawer"
        role="dialog"
        aria-modal="true"
        aria-label="房产客服智能体"
      >
        <!-- 抽屉头部 -->
        <header class="drawer-header">
          <h2>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
            房产客服智能体
          </h2>
          <button
            type="button"
            class="btn-close"
            @click="closeDrawer"
            aria-label="关闭智能体"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </header>

        <!-- 消息列表 -->
        <div ref="chatList" class="messages" @scroll.passive="onMessagesScroll">
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="bubble"
            :class="msg.role"
          >
            <!-- 助手消息：渲染 Markdown -->
            <div v-if="msg.role === 'assistant'" class="bubble-content" v-html="markdownMap[index]" />
            <!-- 用户消息：纯文本 -->
            <span v-else>{{ msg.content }}</span>
          </div>
        </div>

        <!-- 输入区域 -->
        <form class="composer" @submit.prevent="sendMessage">
          <textarea
            v-model="input"
            rows="2"
            placeholder="例如：江宁区有哪些在售楼盘 / 华侨路37号周边环境 / 认购文一西路100号1幢101室"
            :disabled="sending"
            @keydown.enter.exact.prevent="sendMessage"
          />
          <button type="submit" :disabled="sending || !input.trim()">
            {{ sending ? '办理中' : '发送' }}
          </button>
        </form>
      </aside>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ========== Layout ========== */
.layout {
  display: grid;
  grid-template-columns: 1fr;
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

/* ========== Header ========== */
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
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ========== Buttons ========== */
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

/* 智能体按钮 */
.btn-agent {
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: #2563eb;
  color: #fff;
  border-radius: 10px;
  padding: 7px 16px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, transform 0.1s;
  font-family: inherit;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.25);
}

.btn-agent:hover {
  background: #1d4ed8;
}

.btn-agent:active {
  transform: scale(0.96);
}

.btn-agent svg {
  flex-shrink: 0;
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

/* ========== Drawer ========== */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.28);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 9998;
}

.overlay-enter-active,
.overlay-leave-active {
  transition: opacity 240ms cubic-bezier(0.22, 1, 0.36, 1);
}

.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

.drawer {
  position: fixed;
  top: 16px;
  right: 16px;
  bottom: 16px;
  width: min(460px, calc(100vw - 32px));
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 9999;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), opacity 240ms cubic-bezier(0.22, 1, 0.36, 1);
}

.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(110%);
  opacity: 0;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px 14px;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.drawer-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  display: flex;
  align-items: center;
  gap: 7px;
}

.drawer-header h2 svg {
  flex-shrink: 0;
  opacity: 0.6;
}

.btn-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: #f0f2f5;
  color: #666;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.btn-close:hover {
  background: #e4e6ea;
  color: #333;
}

/* ========== Messages ========== */
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
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
}

.bubble.user {
  align-self: flex-end;
  background: #2563eb;
  color: #fff;
  border-bottom-right-radius: 4px;
  white-space: pre-wrap;
}

.bubble.assistant {
  align-self: flex-start;
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
  max-width: 92%;
}

/* Markdown rendered content inside assistant bubbles */
.bubble-content {
  overflow-wrap: break-word;
}

.bubble-content :deep(p) {
  margin: 0 0 0.6em;
}

.bubble-content :deep(p:last-child) {
  margin-bottom: 0;
}

.bubble-content :deep(ul),
.bubble-content :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.4em;
}

.bubble-content :deep(li) {
  margin-bottom: 0.2em;
}

/* marked 对"松散列表"会在 li 里包 <p>，默认段落间距会把列表拉得很散 */
.bubble-content :deep(li > p) {
  margin: 0 0 0.2em;
}

.bubble-content :deep(li > ul),
.bubble-content :deep(li > ol) {
  margin: 0.2em 0 0.3em;
}

.bubble-content :deep(strong) {
  font-weight: 600;
}

.bubble-content :deep(h1),
.bubble-content :deep(h2),
.bubble-content :deep(h3) {
  margin: 0.6em 0 0.3em;
  font-weight: 600;
  line-height: 1.3;
}

.bubble-content :deep(h1) { font-size: 1.15em; }
.bubble-content :deep(h2) { font-size: 1.05em; }
.bubble-content :deep(h3) { font-size: 1em; }

.bubble-content :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}

.bubble-content :deep(pre) {
  background: rgba(0, 0, 0, 0.06);
  padding: 10px 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 0.5em 0;
}

.bubble-content :deep(pre code) {
  background: none;
  padding: 0;
  border-radius: 0;
}

.bubble-content :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.5em 0;
  font-size: 13px;
}

.bubble-content :deep(th),
.bubble-content :deep(td) {
  border: 1px solid #d1d5db;
  padding: 5px 8px;
  text-align: left;
}

.bubble-content :deep(th) {
  background: rgba(0, 0, 0, 0.04);
  font-weight: 600;
}

.bubble-content :deep(tr:nth-child(even)) {
  background: rgba(0, 0, 0, 0.02);
}

.bubble-content :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}

/* ========== Composer ========== */
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
  flex-shrink: 0;
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
    height: auto;
    min-height: 100%;
  }

  .list-panel {
    min-height: 420px;
  }

  .drawer {
    top: 0;
    right: 0;
    bottom: 0;
    left: 0;
    width: 100vw;
    border-radius: 0;
  }
}
</style>
