<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

function welcomeMessage() {
  return {
    role: 'assistant',
    content: '您好，我是房产客服。可以帮您办理认购、撤销认购，也可以查询楼盘价格和周边配套。办理认购请提供房屋坐落、购买方姓名和证件号码。',
  }
}

const conversationId = ref('')
const subscriptions = ref([])
const messages = ref([welcomeMessage()])
const input = ref('')
const sending = ref(false)
const listError = ref('')
const chatList = ref(null)

// ---- 会话列表（GET /api/auth/conversations：status=1 进行中，按 updatedAt 倒序）----
const conversations = ref([])
const conversationsLoading = ref(false)
const conversationsError = ref('')
// localStorage 只存最近一次的 conversationId，用于刷新后判断"当前会话"是否还在服务端列表里。
// 不存消息：消息在后端 Redis/PG，本阶段无历史消息回显接口，切换会话只能拿到新开场白。
const CURRENT_KEY = 'agent.current-conversation'
// 会话消息缓存（仅内存）：切换会话时暂存当前会话的消息，切回时还原。
const messagesCache = {}
// 当前流式请求的 AbortController：切走/新建会话时 abort 旧流，防止回复写错会话。
let activeController = null

// ---- 登录（P0-1 假登录：所有业务接口都要 Authorization: Bearer token）----
const token = ref(localStorage.getItem('agent.token') || '')
const authUser = ref(null)
const loginName = ref(localStorage.getItem('agent.username') || 'customer1')
const loginError = ref('')

function authHeaders(extra = {}) {
  return token.value ? { ...extra, Authorization: `Bearer ${token.value}` } : { ...extra }
}

async function login() {
  const name = loginName.value.trim()
  if (!name) {
    loginError.value = '请输入用户名'
    return
  }
  loginError.value = ''
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: name }),
    })
    if (!response.ok) {
      loginError.value = '用户名不存在（演示账号 customer1 / agent1）'
      return
    }
    const data = await response.json()
    token.value = data.token
    authUser.value = data
    localStorage.setItem('agent.token', data.token)
    localStorage.setItem('agent.username', name)
    await loadSubscriptions()
    await loadConversations()
  } catch (error) {
    loginError.value = '无法连接后端服务'
  }
}

async function logout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() })
  } catch (error) {
    // 登出失败不影响本地清理
  }
  token.value = ''
  authUser.value = null
  subscriptions.value = []
  listError.value = ''
  conversations.value = []
  conversationId.value = ''
  messages.value = [welcomeMessage()]
  input.value = ''
  localStorage.removeItem('agent.token')
  localStorage.removeItem(CURRENT_KEY)
}

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
  if (!token.value) {
    subscriptions.value = []
    listError.value = ''
    return
  }
  try {
    const response = await fetch('/api/subscriptions', { headers: authHeaders() })
    if (response.status === 401 || response.status === 403) {
      throw new Error('登录已过期，请重新登录')
    }
    if (!response.ok) {
      throw new Error('加载认购列表失败')
    }
    subscriptions.value = await response.json()
    listError.value = ''
  } catch (error) {
    listError.value = error.message || '无法连接后端服务'
  }
}

// ---- 会话列表：拉取 / 切换 / 新建 ----

async function loadConversations() {
  if (!token.value) {
    conversations.value = []
    conversationsError.value = ''
    return
  }
  conversationsLoading.value = true
  conversationsError.value = ''
  try {
    const response = await fetch('/api/auth/conversations', { headers: authHeaders() })
    if (response.status === 401 || response.status === 403) {
      throw new Error('登录已过期，请重新登录')
    }
    if (!response.ok) {
      throw new Error('加载会话列表失败')
    }
    conversations.value = await response.json()
  } catch (error) {
    conversations.value = []
    conversationsError.value = error.message || '无法连接后端服务'
  } finally {
    conversationsLoading.value = false
  }
}

// 刷新后"当前会话"的高亮判断：
// 1) conversationId 有值且命中列表 → 高亮；
// 2) 否则取 updatedAt 最新的一条（后端已倒序）；
// 3) 都没有（从未聊过）→ 空，不选任何会话。
async function selectCurrentFromList() {
  const restored = conversationId.value
    ? conversations.value.find(item => String(item.id) === conversationId.value)
    : null
  const nextId = restored
    ? String(restored.id)
    : (conversations.value[0] ? String(conversations.value[0].id) : '')
  conversationId.value = nextId
  if (!nextId) {
    return
  }
  localStorage.setItem(CURRENT_KEY, nextId)
  // 刚聊完的会话（messages 里已有对话）不要被历史覆盖：
  // 服务端落库可能比前端渲染慢一拍，用历史盖回去会丢掉刚收到的回复。
  if (messages.value.length > 1) {
    messagesCache[nextId] = messages.value
    return
  }
  const history = await loadHistory(nextId)
  if (history && history.length && conversationId.value === nextId) {
    messages.value = history
    stickToBottom.value = true
    scrollToBottom(true)
  }
}

/**
 * 从 PG 拉历史消息（Redis TTL 到期后仍能还原）。
 * 失败返回 null，调用方保留现有视图，不把"加载失败"显示成"没有历史"。
 */
async function loadHistory(idStr) {
  if (!token.value) {
    return null
  }
  try {
    const response = await fetch(
      `/api/agent/conversations/${encodeURIComponent(idStr)}/messages?limit=100`,
      { headers: authHeaders() },
    )
    if (!response.ok) {
      return null
    }
    return await response.json()
  } catch (error) {
    return null
  }
}

function currentConversation() {
  return conversations.value.find(item => String(item.id) === conversationId.value) || null
}

// 会话没有标题（title 目前为 null）时的显示文案：按最近更新时间显示相对时间
function conversationLabel(item) {
  if (item.title) {
    return item.title
  }
  const date = new Date(item.updatedAt)
  const now = new Date()
  const diffMs = now - date
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diffMs < minute) {
    return '刚刚'
  }
  if (diffMs < hour) {
    return `${Math.floor(diffMs / minute)} 分钟前`
  }
  if (diffMs < day && now.getDate() === date.getDate()) {
    return `${Math.floor(diffMs / hour)} 小时前`
  }
  if (diffMs < day * 7) {
    return `${Math.floor(diffMs / day)} 天前`
  }
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mi = String(date.getMinutes()).padStart(2, '0')
  return date.getFullYear() === now.getFullYear()
    ? `${mm}-${dd} ${hh}:${mi}`
    : `${date.getFullYear()}-${mm}-${dd}`
}

async function openConversation(id) {
  const idStr = String(id)
  if (idStr === conversationId.value) {
    return
  }
  // 消息是单例的：切走前把当前会话的消息挪到缓存，切回时再还原，避免重复加载时丢失。
  if (conversationId.value) {
    messagesCache[conversationId.value] = messages.value
  }
  // 有旧会话还在流式回复：停掉它，避免回复继续追加到已切走的视图/旧气泡里
  if (sending.value && activeController) {
    activeController.abort()
  }
  conversationId.value = idStr
  localStorage.setItem(CURRENT_KEY, idStr)
  const cached = messagesCache[idStr]
  if (cached) {
    messages.value = cached
    delete messagesCache[idStr]
  } else {
    // 内存里没有这个会话（刷新过页面 / 换设备 / Redis 已过期）：从 PG 拉历史
    messages.value = [welcomeMessage()]
    const history = await loadHistory(idStr)
    // 等待期间用户可能又切走了：只有仍停在这个会话时才覆盖视图
    if (conversationId.value !== idStr) {
      return
    }
    if (history && history.length) {
      messages.value = history
    }
  }
  stickToBottom.value = true
  scrollToBottom(true)
}

// 新建会话：conversationId 置空，下一条消息由后端 /api/agent/chat 的 getOrCreate 新建并返回权威 id
function startNewConversation() {
  if (conversationId.value) {
    messagesCache[conversationId.value] = messages.value
  }
  if (sending.value && activeController) {
    activeController.abort()
  }
  conversationId.value = ''
  localStorage.removeItem(CURRENT_KEY)
  messages.value = [welcomeMessage()]
  input.value = ''
  stickToBottom.value = true
  scrollToBottom(true)
  if (drawerRef.value) {
    const textarea = drawerRef.value.querySelector('textarea')
    if (textarea) textarea.focus()
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
  if (!token.value) {
    messages.value.push({ role: 'assistant', content: '请先在右上角登录（演示账号 customer1 或 agent1）后再咨询。' })
    input.value = ''
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

  // 解构快照：避免流式读取期间用户切会话/新建，把消息追加到错误会话的气泡里
  const cid = conversationId.value
  const controller = new AbortController()
  activeController = controller
  try {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ conversationId: cid, message: text }),
      signal: controller.signal,
    })
    if (response.status === 401 || response.status === 403) {
      throw new Error('登录已过期或无权限，请重新登录')
    }
    if (!response.ok || !response.body) {
      throw new Error('智能体暂时无法回复')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    // 流空闲超时：qwen-turbo 短回复时部分代理/链路下 reader.read() 可能不返回 done，
    // 导致 sending 永久卡死、按钮一直"办理中"。连续 30s 没新数据就主动停读。
    const IDLE_TIMEOUT = 30_000
    let lastDataAt = Date.now()
    while (true) {
      const { done, value } = await Promise.race([
        reader.read(),
        new Promise((resolve) =>
          setTimeout(() => resolve({ done: Date.now() - lastDataAt >= IDLE_TIMEOUT, value: undefined }), IDLE_TIMEOUT)
        ),
      ])
      if (done) {
        // 自然结束或空闲超时，都收尾
        break
      }
      lastDataAt = Date.now()
      buffer += decoder.decode(value, { stream: true })
      const chunks = parseSse(buffer)
      buffer = chunks.rest
      for (const piece of chunks.payloads) {
        assistant.content += piece
        await scrollToBottom()
      }
    }
    // done 之后 buffer 可能残留最后一个不带 \n\n 尾巴的事件，补解析一次
    const tail = parseSse(buffer + '\n\n')
    for (const piece of tail.payloads) {
      assistant.content += piece
      await scrollToBottom()
    }
    if (!assistant.content) {
      assistant.content = '未收到回复，请稍后重试。'
    }
  } catch (error) {
    // 切走/新建会话会 abort 旧流：此时消息已被挪进缓存或已切换视图，
    // 不覆盖气泡文案（避免切回的会话里残留"已停止"提示）。
    if (error.name !== 'AbortError') {
      assistant.content = error.message || '请求失败，请确认后端已启动。'
    }
  } finally {
    if (activeController === controller) {
      activeController = null
    }
    sending.value = false
    await loadSubscriptions()
    // 首条消息走"新建会话"路径：服务端 getOrCreate 会落一条新 conversation，
    // 刷新列表并选中它，让左侧列表立刻出现这个会话（updatedAt 最新，排最前）。
    if (!cid) {
      await loadConversations()
      await selectCurrentFromList()
    }
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

onMounted(async () => {
  window.addEventListener('keydown', handleKeydown)
  // 有本地 token 先校验，避免过期 token 让列表一直报"登录已过期"
  if (token.value) {
    try {
      const response = await fetch('/api/auth/me', { headers: authHeaders() })
      if (response.ok) {
        authUser.value = await response.json()
        // 恢复上次的当前会话（localStorage），再拉会话列表
        conversationId.value = localStorage.getItem(CURRENT_KEY) || ''
        await loadSubscriptions()
        await loadConversations()
        await selectCurrentFromList()
        return
      }
      token.value = ''
      localStorage.removeItem('agent.token')
      localStorage.removeItem(CURRENT_KEY)
    } catch (error) {
      // 后端未就绪：保持未登录态，不阻塞页面
    }
  }
  subscriptions.value = []
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
          <template v-if="authUser">
            <span class="user-chip">
              {{ authUser.displayName || authUser.username }}
              <em>{{ authUser.role === 'agent' ? '坐席' : '客户' }}</em>
            </span>
            <button class="btn-refresh" type="button" @click="logout">退出</button>
          </template>
          <div v-else class="login-box">
            <input
              v-model="loginName"
              class="login-input"
              type="text"
              placeholder="customer1 / agent1"
              aria-label="登录用户名"
              @keydown.enter="login"
            />
            <button class="btn-refresh" type="button" @click="login">登录</button>
          </div>
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

      <div v-if="!authUser" class="empty-state">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        <p>请先登录</p>
        <span>演示账号：customer1（客户） / agent1（坐席）</span>
        <p v-if="loginError" class="login-error">{{ loginError }}</p>
      </div>

      <div v-else-if="listError" class="empty-state" role="alert">
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
          <div class="header-actions">
            <button
              type="button"
              class="btn-new-conv"
              @click="startNewConversation"
              aria-label="新建会话"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              新会话
            </button>
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
          </div>
        </header>

        <!-- 会话列表：一行 chip，当前会话置顶高亮，其余按更新时间倒序 -->
        <div v-if="authUser" class="convs">
          <button
            type="button"
            class="conv-chip"
            :class="{ active: !conversationId }"
            @click="startNewConversation"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            新会话
          </button>
          <button
            v-for="item in conversations"
            :key="item.id"
            type="button"
            class="conv-chip"
            :class="{ active: String(item.id) === conversationId }"
            :title="formatTime(item.updatedAt)"
            @click="openConversation(item.id)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
            <span class="conv-chip-label">
              {{ item.title || conversationLabel(item) }}
            </span>
          </button>
          <span v-if="conversationsLoading && conversations.length === 0" class="convs-hint">加载中…</span>
          <span v-else-if="conversationsError" class="convs-hint">{{ conversationsError }}</span>
        </div>

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

/* ========== 登录态 ========== */
.login-box {
  display: flex;
  align-items: center;
  gap: 6px;
}

.login-input {
  width: 140px;
  border: 1px solid #dfe3ea;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
  font-size: 13px;
  color: #1a1a2e;
  outline: none;
  transition: border-color 0.15s;
}

.login-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #eef2ff;
  color: #3730a3;
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 13px;
  white-space: nowrap;
}

.user-chip em {
  font-style: normal;
  font-size: 12px;
  color: #6366f1;
  background: #e0e7ff;
  border-radius: 5px;
  padding: 1px 5px;
}

.login-error {
  color: #d44;
  font-size: 13px;
  margin: 6px 0 0;
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
  /* 抽屉不再铺满宽屏，左侧列表需要可辨认：降低压暗与模糊强度 */
  background: rgba(15, 23, 42, 0.2);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
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
  /* 宽屏抽屉：足够宽以容纳表格/长文，但左侧仍留出列表页可视区域 */
  width: min(880px, calc(100vw - 24rem));
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
  gap: 12px;
  padding: 18px 28px 14px;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}

/* 头部右侧按钮组：新会话 + 关闭 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 新会话按钮：浅色描边，与关闭按钮并列 */
.btn-new-conv {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #dfe3ea;
  background: #fff;
  color: #2563eb;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  font-family: inherit;
}

.btn-new-conv:hover {
  background: #eef2ff;
  border-color: #2563eb;
}

.btn-new-conv svg {
  flex-shrink: 0;
}

/* ========== 会话列表（一行 chip）========== */
.convs {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 28px;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

/* 隐藏横向滚动条：chip 一行放不下时仍可拖动，视觉上不出现滚动条 */
.convs::-webkit-scrollbar {
  display: none;
}

.conv-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #555;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 13px;
  white-space: nowrap;
  flex-shrink: 0;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s, box-shadow 0.15s;
  font-family: inherit;
}

.conv-chip:hover {
  background: #f3f4f6;
  border-color: #d1d5db;
  color: #333;
}

/* 当前会话：蓝色主色高亮 */
.conv-chip.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
  font-weight: 500;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.25);
}

.conv-chip-label {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.convs-hint {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
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
  /* 居中列：正文锁定 40rem（640px），宽屏下两侧留白对称，而不是全挤在左边 */
  padding: 18px max(28px, calc((100% - 40rem) / 2));
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bubble {
  max-width: min(80%, 34rem);
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
  /* 列宽已由 .messages 的内边距锁在 40rem，这里只需不超过列宽（约 45 个中文字/行） */
  max-width: min(100%, 40rem);
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
  /* 输入区与消息列同宽同基线，宽屏下一起居中 */
  padding: 14px max(28px, calc((100% - 40rem) / 2)) 16px;
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
/* 小桌面 / 笔记本：抽屉收窄，避免挤掉左侧列表 */
@media (max-width: 1280px) {
  .drawer {
    width: min(640px, calc(100vw - 96px));
  }

  .drawer-header {
    padding: 14px 20px 12px;
  }

  .convs {
    padding: 10px 20px;
    gap: 6px;
  }

  .messages {
    padding: 16px 22px;
    gap: 12px;
  }
}

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

  .drawer-header {
    padding: 14px 16px 12px;
  }

  .convs {
    padding: 10px 16px;
  }
}
</style>
