<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElScrollbar,
  ElSelect
} from 'element-plus'
import {
  Bell,
  CalendarHeart,
  ChevronRight,
  Clock,
  Copy,
  Database,
  Heart,
  History,
  MessageCircle,
  Plus,
  RefreshCw,
  Send,
  Sparkles,
  Trash2
} from 'lucide-vue-next'

/* ============================================================
   常量与业务逻辑（保留，不修改后端 API）
   ============================================================ */

const STORAGE_KEY = 'sisi-agent-sessions'
const MAX_SESSIONS = 8
const MAX_CONTEXT_MESSAGES = 20
const MAX_CONTEXT_CHARS = 12000
const MAX_CONTEXT_MESSAGE_CHARS = 900
const MAX_STORED_MESSAGES = 80
const WELCOME_TEXT = '亲爱的思思主人，你好呀！我是你的全能助手。你可以把长文章丢给我，我帮你秒出 PPT 大纲或润色文案；或者在累的时候找我聊聊，我帮你查查天气、点杯热奶茶。今天想先做点什么？'

const welcomeMessage = () => ({
  id: crypto.randomUUID(),
  role: 'assistant',
  route: 'LIFE',
  content: WELCOME_TEXT
})

const createSession = (title = '新的会话') => {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    title,
    createdAt: now,
    updatedAt: now,
    messages: [welcomeMessage()]
  }
}

const sessions = ref(loadSessions())
const activeSessionId = ref(sessions.value[0]?.id || createSession('主会话').id)
const activeSession = computed(() => sessions.value.find(session => session.id === activeSessionId.value) || sessions.value[0])
const messages = computed(() => activeSession.value?.messages ?? [])
const sessionCount = computed(() => sessions.value.length)

function loadSessions() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
    if (Array.isArray(parsed) && parsed.length > 0) {
      return parsed.slice(0, MAX_SESSIONS).map(session => ({
        ...session,
        messages: Array.isArray(session.messages) && session.messages.length > 0
          ? session.messages.map(message => ({ id: message.id || crypto.randomUUID(), ...message }))
          : [welcomeMessage()]
      }))
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY)
  }
  return [createSession('主会话')]
}

function persistSessions(value) {
  const compact = value.slice(0, MAX_SESSIONS).map(session => ({
    ...session,
    messages: session.messages.slice(-MAX_STORED_MESSAGES)
  }))
  localStorage.setItem(STORAGE_KEY, JSON.stringify(compact))
}

watch(sessions, value => {
  persistSessions(value)
}, { deep: true })

function touchSession(session = activeSession.value) {
  if (session) session.updatedAt = new Date().toISOString()
}

function newSession() {
  const session = createSession()
  sessions.value.unshift(session)
  sessions.value = sessions.value.slice(0, MAX_SESSIONS)
  activeSessionId.value = session.id
  input.value = ''
}

function selectSession(sessionId) {
  activeSessionId.value = sessionId
  input.value = ''
}

function deleteMessage(index) {
  if (streaming.value) return
  const session = activeSession.value
  if (!session) return
  session.messages.splice(index, 1)
  touchSession(session)
  ElMessage({ message: '已删除', duration: 1800, center: true })
}

function renameSessionFromMessage(session, text) {
  if (!session || session.title !== '新的会话') return
  session.title = text.length > 18 ? `${text.slice(0, 18)}...` : text
}

function buildContextPayload(sourceMessages) {
  const candidates = sourceMessages
    .filter(message => message.content?.trim())
    .filter(message => message.content !== WELCOME_TEXT)
    .slice(-MAX_CONTEXT_MESSAGES)

  let total = 0
  const context = []
  for (const message of candidates.reverse()) {
    const content = message.content.trim().slice(0, MAX_CONTEXT_MESSAGE_CHARS)
    if (total + content.length > MAX_CONTEXT_CHARS) continue
    context.unshift({
      role: message.role,
      route: message.route || 'LIFE',
      content
    })
    total += content.length
  }
  return context
}

/* ============================================================
   输入与发送（保留 SSE 逻辑）
   ============================================================ */

const input = ref('')
const streaming = ref(false
)
const companionOnline = ref(false)
const lastError = ref('')
const reminders = ref([])
const todayChatCount = ref(0)

const canSend = computed(() => input.value.trim().length > 0 && !streaming.value)

/* —— 动态 Placeholder（按时间） —— */
const dynamicPlaceholder = computed(() => {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 12) return '早安呀，今天有什么安排？'
  if (hour >= 12 && hour < 18) return '今天过得还顺利吗？'
  if (hour >= 18 && hour < 23) return '今天有没有什么想和思思说的？'
  return '睡不着的话，思思陪你聊会儿。'
})

/* —— 今日陪伴任务（本地） —— */
const dailyCompanion = ref([
  { id: 'morning', text: '早安问候', status: 'pending' },
  { id: 'water', text: '记得喝水', status: 'completed' },
  { id: 'night', text: '晚上聊聊天', status: 'upcoming' }
])

/* —— 情绪互动 —— */
const emotionActions = [
  { label: '抱抱', icon: '♡', prompt: '想抱抱，今天有点累' },
  { label: '奶茶', icon: '☕', prompt: '想喝奶茶了，陪我聊会儿' },
  { label: '陪我聊聊', icon: '🌙', prompt: '陪我聊聊吧，今天有点想说说话' }
]

function useEmotion(prompt) {
  if (streaming.value) return
  input.value = prompt
  send()
}

/* —— 快捷 Prompt —— */
const quickPrompts = [
  '把这篇文章整理成 PPT 大纲',
  '今天有点累，想喝奶茶',
  '帮我写一段小红书种草文案'
]

function usePrompt(prompt) {
  input.value = prompt
}

/* —— 复制消息 —— */
async function copyMessage(content) {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage({ message: '已复制', duration: 1800, center: true })
  } catch {
    ElMessage({ message: '复制失败，请手动选择', duration: 1800, center: true })
  }
}

/* —— 重新生成 —— */
function regenerateMessage(index) {
  if (streaming.value) return
  const session = activeSession.value
  if (!session) return
  const target = session.messages[index]
  if (!target || target.role !== 'assistant') return
  // 找到上一条用户消息
  let userText = ''
  for (let i = index - 1; i >= 0; i--) {
    if (session.messages[i].role === 'user') {
      userText = session.messages[i].content
      break
    }
  }
  if (!userText) return
  // 删除当前 AI 回复
  session.messages.splice(index, 1)
  touchSession(session)
  // 重发
  input.value = userText
  send()
}

let eventSource = null

onMounted(() => {
  eventSource = new EventSource('/api/companion/events')
  eventSource.onmessage = event => pushReminder(JSON.parse(event.data))
  eventSource.addEventListener('morning', event => pushReminder(JSON.parse(event.data)))
  eventSource.addEventListener('night', event => pushReminder(JSON.parse(event.data)))
  eventSource.addEventListener('anniversary', event => pushReminder(JSON.parse(event.data)))
  eventSource.addEventListener('heartbeat', () => {
    companionOnline.value = true
  })
  eventSource.onerror = () => {
    companionOnline.value = false
  }
})

onUnmounted(() => {
  if (eventSource) eventSource.close()
})

function pushReminder(event) {
  reminders.value.unshift(event)
  reminders.value = reminders.value.slice(0, 5)
  if (event.type === 'morning') {
    dailyCompanion.value[0].status = 'completed'
  }
}

function updateLastAssistantRoute(route) {
  const session = activeSession.value
  if (!session) return
  const lastAssistant = [...session.messages].reverse().find(message => message.role === 'assistant')
  if (lastAssistant) {
    lastAssistant.route = route
  }
}

function normalizeSseBuffer(buffer, assistant) {
  const events = buffer.split('\n\n')
  const rest = events.pop() || ''
  for (const rawEvent of events) {
    handleSseEvent(rawEvent, assistant)
  }
  return rest
}

async function send() {
  if (!canSend.value) return
  const text = input.value.trim()
  const session = activeSession.value
  if (!session) return

  input.value = ''
  streaming.value = true
  lastError.value = ''
  todayChatCount.value += 1
  const context = buildContextPayload(session.messages)
  const userMessage = { id: crypto.randomUUID(), role: 'user', route: 'USER', content: text }
  const assistant = reactive({ id: crypto.randomUUID(), role: 'assistant', route: 'LIFE', content: '' })
  session.messages.push(userMessage, assistant)
  renameSessionFromMessage(session, text)
  touchSession(session)

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: text,
        sessionId: session.id,
        context
      })
    })
    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = normalizeSseBuffer(buffer, assistant)
    }
  } catch (error) {
    lastError.value = String(error.message || error)
    assistant.content = '思思刚刚好像走神了一下...\n\n要不要再试一次？'
  } finally {
    streaming.value = false
    touchSession(session)
  }
}

function handleSseEvent(rawEvent, assistant) {
  const dataLine = rawEvent.split('\n').find(line => line.startsWith('data:'))
  if (!dataLine) return
  const chunk = JSON.parse(dataLine.slice(5))
  assistant.route = chunk.route
  if (chunk.type === 'text') {
    assistant.content += chunk.content
    updateLastAssistantRoute(chunk.route)
  }
}

function removeSession(sessionId) {
  if (sessions.value.length === 1) {
    sessions.value = [createSession('主会话')]
    activeSessionId.value = sessions.value[0].id
    return
  }
  const nextSessions = sessions.value.filter(session => session.id !== sessionId)
  sessions.value = nextSessions
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = nextSessions[0].id
  }
}

/* ============================================================
   记忆 / 纪念日（保留 API）
   ============================================================ */

const memoryDialogVisible = ref(false)
const anniversaryDialogVisible = ref(false)
const memoryForm = ref({
  type: 'PREFERENCE',
  title: '',
  content: '',
  eventDate: '',
  emotionalTone: 'warm',
  importance: 8
})
const anniversaryForm = ref({
  title: '',
  date: '',
  description: '',
  importance: 9
})

const memoryTypes = [
  { label: '基础信息', value: 'BASIC_INFO' },
  { label: '喜好', value: 'PREFERENCE' },
  { label: '忌口', value: 'TABOO' },
  { label: '愿望清单', value: 'WISH' },
  { label: '旅行记录', value: 'TRAVEL' },
  { label: '情绪记录', value: 'EMOTION' },
  { label: '重要事件', value: 'IMPORTANT_EVENT' },
  { label: '恋爱记录', value: 'RELATIONSHIP' },
  { label: '人生目标', value: 'GOAL' },
  { label: '情绪轨迹', value: 'EMOTION_TRACE' },
  { label: '用户画像', value: 'PROFILE' }
]

async function saveMemory() {
  await fetch('/api/memory', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cleanPayload(memoryForm.value))
  })
  ElMessage({ message: '♡ 思思已经记住啦', duration: 2500, center: true })
  memoryDialogVisible.value = false
  memoryForm.value.title = ''
  memoryForm.value.content = ''
  memoryForm.value.eventDate = ''
}

async function saveAnniversary() {
  await fetch('/api/anniversaries', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cleanPayload(anniversaryForm.value))
  })
  ElMessage({ message: '♡ 纪念日添加成功', duration: 2500, center: true })
  anniversaryDialogVisible.value = false
  anniversaryForm.value.title = ''
  anniversaryForm.value.description = ''
  anniversaryForm.value.date = ''
}

function cleanPayload(payload) {
  return Object.fromEntries(Object.entries(payload).filter(([, value]) => value !== ''))
}

/* ============================================================
   Markdown 渲染（保留）
   ============================================================ */

function escapeHtml(value = '') {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function renderInlineMarkdown(value = '') {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
}

function renderMessageContent(content = '') {
  const lines = String(content || '').replace(/\r\n/g, '\n').split('\n')
  const blocks = []
  let paragraph = []
  let listType = ''
  let listItems = []

  const flushParagraph = () => {
    if (!paragraph.length) return
    blocks.push(`<p>${paragraph.map(line => renderInlineMarkdown(line)).join('<br>')}</p>`)
    paragraph = []
  }

  const flushList = () => {
    if (!listType || listItems.length === 0) return
    blocks.push(`<${listType}>${listItems.map(item => `<li>${item}</li>`).join('')}</${listType}>`)
    listType = ''
    listItems = []
  }

  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) {
      flushParagraph()
      flushList()
      continue
    }

    const heading = line.match(/^(#{1,3})\s+(.*)$/)
    if (heading) {
      flushParagraph()
      flushList()
      blocks.push(`<h${heading[1].length}>${renderInlineMarkdown(heading[2])}</h${heading[1].length}>`)
      continue
    }

    const quote = line.match(/^>\s?(.*)$/)
    if (quote) {
      flushParagraph()
      flushList()
      blocks.push(`<blockquote>${renderInlineMarkdown(quote[1])}</blockquote>`)
      continue
    }

    const ordered = line.match(/^\d+\.\s+(.*)$/)
    if (ordered) {
      flushParagraph()
      if (listType !== 'ol') {
        flushList()
        listType = 'ol'
      }
      listItems.push(renderInlineMarkdown(ordered[1]))
      continue
    }

    const bullet = line.match(/^[-*•]\s+(.*)$/)
    if (bullet) {
      flushParagraph()
      if (listType !== 'ul') {
        flushList()
        listType = 'ul'
      }
      listItems.push(renderInlineMarkdown(bullet[1]))
      continue
    }

    flushList()
    paragraph.push(line)
  }

  flushParagraph()
  flushList()

  return blocks.length > 0 ? blocks.join('') : `<p>${escapeHtml(content)}</p>`
}

/* —— 是否仅欢迎消息（显示 Intro 卡） —— */
const showIntro = computed(() => messages.value.length === 1 && messages.value[0].content === WELCOME_TEXT)

/* —— 今日问候语 —— */
const todayGreeting = computed(() => {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 12) return '早安呀，今天也陪着你'
  if (hour >= 12 && hour < 18) return '下午好，今天辛苦啦'
  if (hour >= 18 && hour < 23) return '晚上好，今天有没有想说的'
  return '夜深了，思思还在'
})
</script>

<template>
  <main class="app-shell">
    <!-- ===================== 左侧 Sidebar ===================== -->
    <aside class="app-sidebar glass-panel">
      <div class="brand-lockup">
        <div class="kitty-mark" aria-hidden="true"></div>
        <div>
          <h1>思思助手</h1>
          <p class="brand-subtitle">今天也陪着你</p>
        </div>
      </div>

      <nav class="nav-stack">
        <button class="nav-item active" type="button">
          <Heart :size="18" />
          <span>和思思聊天</span>
        </button>
        <button class="nav-item" type="button" :disabled="streaming" @click="newSession">
          <Plus :size="18" />
          <span>新对话</span>
        </button>
        <button class="nav-item" type="button" @click="memoryDialogVisible = true">
          <Database :size="18" />
          <span>记忆</span>
        </button>
        <button class="nav-item" type="button" @click="anniversaryDialogVisible = true">
          <CalendarHeart :size="18" />
          <span>纪念日</span>
        </button>
      </nav>

      <div class="sidebar-foot">
        <div v-if="sessions.length > 1" class="session-stack">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session-row"
          >
            <button
              :class="['session-item', { active: session.id === activeSessionId }]"
              type="button"
              :disabled="streaming"
              @click="selectSession(session.id)"
            >
              <span>{{ session.title }}</span>
            </button>
            <button class="session-delete" type="button" title="删除会话" :disabled="streaming" @click.stop="removeSession(session.id)">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>

        <div class="workspace-switcher">
          <div class="ws-label">
            <Heart :size="15" />
            <span>主会话</span>
          </div>
          <div class="ws-sub">✦ LIFE · 生活陪伴</div>
        </div>
      </div>
    </aside>

    <!-- ===================== 中间工作区 ===================== -->
    <section class="workspace">
      <header class="topbar glass-panel">
        <div class="topbar-brand">
          <div class="topbar-bow" aria-hidden="true"></div>
          <div class="topbar-brand-copy">
            <h2>思思助手</h2>
            <p>今天也陪着你</p>
          </div>
        </div>

        <div class="top-actions">
          <span :class="['companion-online', { 'is-off': !companionOnline }]">
            <span class="pulse-dot"></span>
            {{ companionOnline ? '思思正在陪着你' : '思思稍后就到' }}
          </span>
          <el-button round @click="memoryDialogVisible = true">
            <template #icon><Database :size="16" /></template>
            记忆
          </el-button>
          <el-button round @click="anniversaryDialogVisible = true">
            <template #icon><CalendarHeart :size="16" /></template>
            纪念日
          </el-button>
          <el-button round type="primary" :disabled="streaming" @click="newSession">
            <template #icon><Plus :size="16" /></template>
            新对话
          </el-button>
        </div>
      </header>

      <section class="chat-card glass-panel">
        <div class="chat-heading">
          <div>
            <h3>主会话</h3>
            <p class="route-sub">LIFE · 生活陪伴</p>
          </div>
          <div class="route-badge">
            <Sparkles :size="13" />
            <span>LIFE · {{ sessionCount }} 个会话</span>
          </div>
        </div>

        <div class="quick-row">
          <button v-for="prompt in quickPrompts" :key="prompt" class="quick-chip" type="button" @click="usePrompt(prompt)">
            {{ prompt }}
          </button>
        </div>

        <el-scrollbar class="message-scroll" ref="scrollbar">
          <div class="message-list">
            <!-- AI 角色介绍卡（首条欢迎） -->
            <div v-if="showIntro" class="ai-intro">
              <div class="kitty-avatar-lg" aria-hidden="true">
                <span class="ka-ear left"></span>
                <span class="ka-ear right"></span>
                <span class="ka-bow"></span>
                <span class="ka-face"></span>
              </div>
              <div class="ai-name">思思 · LIFE</div>
              <div class="ai-greet">{{ todayGreeting }}</div>
            </div>

            <article
              v-for="(message, index) in messages"
              :key="message.id || index"
              :class="['message-row', message.role]"
            >
              <div class="avatar">
                <Heart v-if="message.role === 'assistant'" :size="17" />
                <span v-else>你</span>
              </div>
              <div class="message-shell">
                <div class="message-bubble-head">
                  <span class="ai-name-label">{{ message.role === 'assistant' ? '思思' : '你' }}</span>
                </div>

                <div
                  v-if="message.role === 'assistant'"
                  class="message-content ai-message-bubble"
                  v-html="renderMessageContent(message.content)"
                ></div>
                <div v-else class="user-message-bubble">
                  <div class="message-content" v-html="renderMessageContent(message.content)"></div>
                </div>

                <!-- AI 消息操作（Hover 显示） -->
                <div v-if="message.role === 'assistant' && message.content && !streaming" class="message-actions">
                  <button class="msg-action" type="button" title="复制" @click="copyMessage(message.content)">
                    <Copy :size="15" />
                  </button>
                  <button class="msg-action" type="button" title="重新生成" @click="regenerateMessage(index)">
                    <RefreshCw :size="15" />
                  </button>
                  <button class="msg-action" type="button" title="删除" @click="deleteMessage(index)">
                    <Trash2 :size="15" />
                  </button>
                </div>

                <!-- 情绪互动（仅最后一条 AI 消息 Hover 显示） -->
                <div
                  v-if="message.role === 'assistant' && index === messages.length - 1 && message.content && !streaming"
                  class="emotion-row"
                >
                  <button
                    v-for="emo in emotionActions"
                    :key="emo.label"
                    class="emotion-chip"
                    type="button"
                    @click="useEmotion(emo.prompt)"
                  >
                    <span>{{ emo.icon }}</span>
                    <span>{{ emo.label }}</span>
                  </button>
                </div>
              </div>
            </article>

            <!-- AI Loading 状态 -->
            <div v-if="streaming" class="message-row assistant">
              <div class="avatar">
                <Heart :size="17" />
              </div>
              <div class="message-shell">
                <div class="message-bubble-head">
                  <span class="ai-name-label">思思</span>
                </div>
                <div class="thinking-row">
                  <span class="thinking-label">思思正在想</span>
                  <span class="thinking-dots">
                    <span></span>
                    <span></span>
                    <span></span>
                  </span>
                </div>
              </div>
            </div>

            <!-- 错误状态 -->
            <div v-if="lastError" class="error-state">
              <p class="es-copy">思思刚刚好像走神了一下...</p>
              <p class="es-detail">{{ lastError }}</p>
            </div>
          </div>
        </el-scrollbar>

        <form class="composer" @submit.prevent="send">
          <el-input
            v-model="input"
            class="composer-input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            resize="none"
            :placeholder="dynamicPlaceholder"
          />
          <el-button
            class="send-button"
            type="primary"
            circle
            :disabled="!canSend"
            native-type="submit"
            title="发送"
          >
            <Send :size="19" />
          </el-button>
        </form>
      </section>
    </section>

    <!-- ===================== 右侧 Companion Sidebar ===================== -->
    <aside class="companion-panel">
      <!-- 今日的思思 Hero Card -->
      <section class="side-card hero-card">
        <div class="kitty-avatar-lg" aria-hidden="true">
          <span class="ka-ear left"></span>
          <span class="ka-ear right"></span>
          <span class="ka-bow"></span>
          <span class="ka-face"></span>
        </div>
        <p class="hero-title">今天的思思</p>
        <p class="hero-sub">陪思思把小事也认真接住</p>
        <div class="hero-heart">♡</div>
      </section>

      <!-- 陪伴状态 -->
      <section class="side-card glass-panel">
        <div class="side-title">
          <Heart :size="15" />
          <h3>陪伴状态</h3>
        </div>
        <div class="companion-status-grid">
          <div class="status-row">
            <span class="status-label">当前模式</span>
            <span class="status-value">LIFE · 生活陪伴</span>
          </div>
          <div class="status-row">
            <span class="status-label">记忆</span>
            <span class="status-value is-connected">已连接</span>
          </div>
          <div class="status-row">
            <span class="status-label">今日对话</span>
            <span class="status-value">{{ todayChatCount }} 次</span>
          </div>
        </div>
      </section>

      <!-- 今日陪伴 -->
      <section class="side-card glass-panel">
        <div class="side-title">
          <Clock :size="15" />
          <h3>今日陪伴</h3>
        </div>
        <div class="daily-list">
          <div
            v-for="item in dailyCompanion"
            :key="item.id"
            :class="['daily-item', item.status]"
          >
            <span class="di-mark">
              <template v-if="item.status === 'completed'">✓</template>
              <template v-else-if="item.status === 'pending'">♡</template>
              <template v-else>○</template>
            </span>
            <span class="di-text">{{ item.text }}</span>
          </div>
        </div>
        <div class="anniversary-countdown">
          距离下一个纪念日还有 <strong>12</strong> 天
        </div>
      </section>

      <!-- 主动陪伴事件 -->
      <section v-if="reminders.length > 0" class="side-card glass-panel">
        <div class="side-title">
          <Bell :size="15" />
          <h3>思思有话想和你说</h3>
        </div>
        <div class="reminder-stack">
          <article v-for="item in reminders" :key="item.createdAt" class="reminder-card">
            <strong>{{ item.title }}</strong>
            <span>{{ item.message }}</span>
          </article>
        </div>
      </section>

      <!-- 快捷入口 -->
      <section class="side-card glass-panel">
        <div class="side-title">
          <History :size="15" />
          <h3>快捷入口</h3>
        </div>
        <button class="action-row" type="button" @click="memoryDialogVisible = true">
          <span class="ar-icon"><Plus :size="15" /></span>
          <span class="ar-label">新增长期记忆</span>
          <ChevronRight :size="16" />
        </button>
        <button class="action-row" type="button" @click="anniversaryDialogVisible = true">
          <span class="ar-icon"><Plus :size="15" /></span>
          <span class="ar-label">新增纪念日</span>
          <ChevronRight :size="16" />
        </button>
        <button class="action-row" type="button" @click="memoryDialogVisible = true">
          <span class="ar-icon"><History :size="15" /></span>
          <span class="ar-label">查看我们的回忆</span>
          <ChevronRight :size="16" />
        </button>
      </section>
    </aside>

    <!-- ===================== 记忆弹窗 ===================== -->
    <el-dialog v-model="memoryDialogVisible" class="product-dialog" width="520px" title="新增长期记忆" align-center>
      <el-form :model="memoryForm" label-position="top" size="large">
        <el-form-item label="类型">
          <el-select v-model="memoryForm.type" class="w-full" placeholder="选择记忆类型">
            <el-option v-for="item in memoryTypes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="memoryForm.title" placeholder="例如：奶茶偏好" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="memoryForm.content" type="textarea" :rows="4" resize="none" placeholder="例如：喜欢三分糖热乌龙奶茶" />
        </el-form-item>
        <div class="dialog-grid">
          <el-form-item label="日期">
            <el-date-picker v-model="memoryForm.eventDate" class="w-full" type="date" value-format="YYYY-MM-DD" placeholder="可选" />
          </el-form-item>
          <el-form-item label="重要度">
            <el-input-number v-model="memoryForm.importance" :min="1" :max="10" class="w-full" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button round @click="memoryDialogVisible = false">取消</el-button>
          <el-button round type="primary" @click="saveMemory">
            <template #icon><Plus :size="16" /></template>
            保存记忆
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ===================== 纪念日弹窗 ===================== -->
    <el-dialog v-model="anniversaryDialogVisible" class="product-dialog" width="520px" title="新增纪念日" align-center>
      <el-form :model="anniversaryForm" label-position="top" size="large">
        <el-form-item label="标题">
          <el-input v-model="anniversaryForm.title" placeholder="例如：恋爱纪念日" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="anniversaryForm.date" class="w-full" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="anniversaryForm.description" type="textarea" :rows="4" resize="none" placeholder="例如：第一次一起去海边" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-input-number v-model="anniversaryForm.importance" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button round @click="anniversaryDialogVisible = false">取消</el-button>
          <el-button round type="primary" @click="saveAnniversary">
            <template #icon><Plus :size="16" /></template>
            保存纪念日
          </el-button>
        </div>
      </template>
    </el-dialog>
  </main>
</template>
