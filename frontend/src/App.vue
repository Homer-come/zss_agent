<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
  ElSelect,
  ElTag
} from 'element-plus'
import {
  Bell,
  Brain,
  CalendarHeart,
  ChevronRight,
  Command,
  Database,
  Heart,
  History,
  Mail,
  MessageCircle,
  PanelRight,
  Plus,
  Sparkles,
  Trash2,
  WandSparkles
} from 'lucide-vue-next'

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
const activeTokenHint = computed(() => {
  const context = buildContextPayload(messages.value)
  return `${context.length}/${MAX_CONTEXT_MESSAGES} 条上下文 · 后端自动注入记忆与摘要`
})

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
  status.value = '已开启新的会话'
}

function selectSession(sessionId) {
  activeSessionId.value = sessionId
  input.value = ''
  status.value = '已切换会话'
}

function deleteMessage(index) {
  if (streaming.value) return
  const session = activeSession.value
  if (!session) return
  session.messages.splice(index, 1)
  touchSession(session)
  status.value = '消息已删除，后续上下文会自动重算'
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

const input = ref('')
const streaming = ref(false)
const status = ref('SSE 已准备好')
const reminders = ref([])
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

const canSend = computed(() => input.value.trim().length > 0 && !streaming.value)
const currentRoute = computed(() => [...messages.value].reverse().find(message => message.role === 'assistant')?.route || 'LIFE')

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

const quickPrompts = [
  '把这篇文章整理成 PPT 大纲',
  '今天有点累，想喝奶茶',
  '帮我写一段小红书种草文案'
]

onMounted(() => {
  const events = new EventSource('/api/companion/events')
  events.onmessage = event => pushReminder(JSON.parse(event.data))
  events.addEventListener('morning', event => pushReminder(JSON.parse(event.data)))
  events.addEventListener('night', event => pushReminder(JSON.parse(event.data)))
  events.addEventListener('anniversary', event => pushReminder(JSON.parse(event.data)))
  events.addEventListener('heartbeat', () => {
    status.value = '主动陪伴通道在线'
  })
  events.onerror = () => {
    status.value = '主动陪伴通道重连中'
  }
})

function pushReminder(event) {
  reminders.value.unshift(event)
  reminders.value = reminders.value.slice(0, 5)
}

function usePrompt(prompt) {
  input.value = prompt
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
  status.value = '正在连接后端 SSE...'
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
    assistant.content += '\n亲爱的思思主人，连接有点不稳，不过我在。你可以稍后再试一次。'
  } finally {
    streaming.value = false
    status.value = 'SSE 已完成'
    touchSession(session)
  }
}

function handleSseEvent(rawEvent, assistant) {
  const dataLine = rawEvent.split('\n').find(line => line.startsWith('data:'))
  if (!dataLine) return
  const chunk = JSON.parse(dataLine.slice(5))
  assistant.route = chunk.route
  if (chunk.type === 'status') {
    status.value = chunk.content
  }
  if (chunk.type === 'text') {
    assistant.content += chunk.content
    updateLastAssistantRoute(chunk.route)
  }
  if (chunk.done) {
    status.value = `${chunk.route} 模块已完成`
  }
}

function removeSession(sessionId) {
  if (sessions.value.length === 1) {
    sessions.value = [createSession('主会话')]
    activeSessionId.value = sessions.value[0].id
    status.value = '已重置为单个新会话'
    return
  }

  const nextSessions = sessions.value.filter(session => session.id !== sessionId)
  sessions.value = nextSessions
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = nextSessions[0].id
  }
  status.value = '会话已删除'
}

async function saveMemory() {
  await fetch('/api/memory', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cleanPayload(memoryForm.value))
  })
  status.value = '长期记忆已保存'
  ElMessage.success('长期记忆已保存')
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
  status.value = '纪念日已保存'
  ElMessage.success('纪念日已保存')
  anniversaryDialogVisible.value = false
  anniversaryForm.value.title = ''
  anniversaryForm.value.description = ''
  anniversaryForm.value.date = ''
}

function cleanPayload(payload) {
  return Object.fromEntries(Object.entries(payload).filter(([, value]) => value !== ''))
}

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
</script>

<template>
  <main class="app-shell min-h-screen">
    <aside class="hidden xl:flex app-sidebar glass-panel animate__animated animate__fadeInLeft">
      <div class="brand-lockup">
        <div class="kitty-mark" aria-hidden="true"></div>
        <div>
          <p class="micro-label">Sisi Agent</p>
          <h1>思思助手</h1>
        </div>
      </div>

      <nav class="nav-stack">
        <button class="nav-item active" type="button">
          <MessageCircle :size="18" />
          <span>对话</span>
        </button>
        <button class="nav-item" type="button" :disabled="streaming" @click="newSession">
          <Plus :size="18" />
          <span>新会话</span>
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
        <div class="session-stack">
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
        <div class="mini-card">
          <Sparkles :size="18" />
          <span>{{ currentRoute }} 模块</span>
        </div>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar glass-panel animate__animated animate__fadeInDown">
        <div class="topbar-brand">
          <div class="hello-kitty-bow topbar-bow" aria-hidden="true">
            <span></span>
          </div>
          <div class="topbar-brand-copy">
            <p class="micro-label">Dual Agent Workspace</p>
            <h2>思思助手</h2>
          </div>
        </div>

        <div class="top-actions">
          <el-tag effect="plain" round>{{ status }}</el-tag>
          <el-tag effect="plain" round>{{ activeTokenHint }}</el-tag>
          <el-button round :disabled="streaming" @click="newSession">
            <template #icon><Plus :size="16" /></template>
            新会话
          </el-button>
          <el-button round @click="memoryDialogVisible = true">
            <template #icon><Brain :size="16" /></template>
            记忆
          </el-button>
          <el-button round type="primary" @click="anniversaryDialogVisible = true">
            <template #icon><CalendarHeart :size="16" /></template>
            纪念日
          </el-button>
        </div>
      </header>

      <div class="content-grid">
        <section class="chat-card glass-panel animate__animated animate__fadeInUp">
          <div class="chat-heading">
            <div>
              <p class="micro-label">Conversation</p>
              <h3>{{ activeSession?.title || '和我说吧' }}</h3>
            </div>
            <div class="kitty-face" aria-hidden="true">
              <span class="kitty-ear left"></span>
              <span class="kitty-ear right"></span>
              <span class="kitty-bow"></span>
              <span class="kitty-eye left"></span>
              <span class="kitty-eye right"></span>
              <span class="kitty-nose"></span>
              <span class="kitty-whisker left top"></span>
              <span class="kitty-whisker left bottom"></span>
              <span class="kitty-whisker right top"></span>
              <span class="kitty-whisker right bottom"></span>
            </div>
            <div class="route-badge">
              <Command :size="15" />
              <span>{{ currentRoute }} · {{ sessionCount }} 个会话</span>
            </div>
          </div>

          <div class="quick-row">
            <button v-for="prompt in quickPrompts" :key="prompt" class="quick-chip" type="button" @click="usePrompt(prompt)">
              {{ prompt }}
            </button>
          </div>

          <el-scrollbar class="message-scroll">
            <div class="message-list">
              <article v-for="(message, index) in messages" :key="message.id || index" :class="['message-row', message.role]">
                <div class="avatar">
                  <Heart v-if="message.role === 'assistant'" :size="17" />
                  <span v-else>你</span>
                </div>
                <div class="message-shell">
                  <div class="message-bubble">
                    <div class="message-bubble-head">
                      <div v-if="message.role === 'assistant'" class="route-line">
                        <WandSparkles :size="14" />
                        <span>{{ message.route }}</span>
                      </div>
                      <div v-else class="route-line user-line">
                        <MessageCircle :size="14" />
                        <span>你</span>
                      </div>
                      <button class="message-action" type="button" title="删除消息" :disabled="streaming" @click="deleteMessage(index)">
                        <Trash2 :size="14" />
                      </button>
                    </div>
                    <div class="message-content" v-html="renderMessageContent(message.content)"></div>
                  </div>
                </div>
              </article>
            </div>
          </el-scrollbar>

          <form class="composer glass-inner" @submit.prevent="send">
            <el-input
              v-model="input"
              class="composer-input"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
              resize="none"
              placeholder="发给我：长文章、PPT 需求、今天的心情、纪念日安排..."
            />
            <el-button class="send-button" type="primary" circle :disabled="!canSend" native-type="submit" title="发送">
              <span class="send-bow" aria-hidden="true"></span>
              <Mail :size="19" />
            </el-button>
          </form>
        </section>

        <aside class="insight-panel animate__animated animate__fadeInRight">
          <section class="glass-panel side-card kitty-note">
            <div class="kitty-note-figure" aria-hidden="true">
              <span></span>
            </div>
            <div>
              <p class="micro-label">Kitty Mood</p>
              <strong>陪思思把小事也认真接住</strong>
            </div>
          </section>

          <section class="glass-panel side-card">
            <div class="side-title">
              <PanelRight :size="18" />
              <h3>状态</h3>
            </div>
            <div class="metric-grid">
              <div class="metric">
                <span>当前路由</span>
                <strong>{{ currentRoute }}</strong>
              </div>
              <div class="metric">
                <span>流式输出</span>
                <strong>{{ streaming ? '进行中' : '就绪' }}</strong>
              </div>
              <div class="metric">
                <span>上下文预算</span>
                <strong>{{ activeTokenHint }}</strong>
              </div>
              <div class="metric">
                <span>本地会话</span>
                <strong>{{ sessionCount }}</strong>
              </div>
            </div>
          </section>

          <section class="glass-panel side-card">
            <div class="side-title">
              <Bell :size="18" />
              <h3>主动陪伴</h3>
            </div>
            <p v-if="reminders.length === 0" class="empty-copy">早晚安、纪念日提醒会在这里出现。</p>
            <div v-else class="reminder-stack">
              <article v-for="item in reminders" :key="item.createdAt" class="reminder-card">
                <strong>{{ item.title }}</strong>
                <span>{{ item.message }}</span>
              </article>
            </div>
          </section>

          <section class="glass-panel side-card">
            <div class="side-title">
              <History :size="18" />
              <h3>快捷入口</h3>
            </div>
            <button class="action-row" type="button" @click="memoryDialogVisible = true">
              <span>新增长期记忆</span>
              <ChevronRight :size="17" />
            </button>
            <button class="action-row" type="button" @click="anniversaryDialogVisible = true">
              <span>新增纪念日</span>
              <ChevronRight :size="17" />
            </button>
          </section>
        </aside>
      </div>
    </section>

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
