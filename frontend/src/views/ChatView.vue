<template>
  <div class="chat-view">
    <!-- 会话列表 -->
    <div class="session-list">
      <div class="search-box">
        <el-input v-model="keyword" placeholder="搜索会话" prefix-icon="Search" clearable />
      </div>

      <div class="session-items">
        <div
          v-for="session in filteredSessions"
          :key="getSessionId(session)"
          class="session-item"
          :class="{ active: getSessionId(session) === currentSessionId }"
          @click="selectSession(session)"
        >
          <el-avatar :size="44" :src="session.avatar" class="avatar">
            {{ session.name?.charAt(0) }}
          </el-avatar>
          <div class="session-info">
            <div class="session-name">{{ session.name }}</div>
            <div class="session-last">{{ session.lastMessage }}</div>
          </div>
          <div class="session-meta">
            <div class="session-time">{{ formatTime(session.lastTime) }}</div>
            <el-badge v-if="session.unread > 0" :value="session.unread" :max="99" />
          </div>
        </div>

        <el-empty v-if="!filteredSessions.length" description="暂无会话" />
      </div>
    </div>

    <!-- 聊天窗口 -->
    <div class="chat-window">
      <template v-if="currentSessionId">
        <!-- 顶部栏 -->
        <div class="chat-header">
          <span class="chat-name">{{ currentSession?.name }}</span>
          <div class="chat-actions">
            <el-button size="small" @click="exportChat('html')">导出</el-button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div class="message-list" ref="msgListRef">
          <div
            v-for="msg in messages"
            :key="msg.id || `${msg.serverId || ''}-${msg.seq || ''}-${msg.timestamp || ''}`"
            class="message-item"
            :class="{ sender: msg.isSender, system: msg.msgType === 10000 }"
          >
            <el-avatar v-if="msg.msgType !== 10000" :size="36" class="msg-avatar" :src="resolveMsgAvatar(msg)">
              {{ resolveMsgDisplayName(msg)?.charAt(0) || '?' }}
            </el-avatar>
            <div class="msg-body">
              <div v-if="msg.isChatRoom && !msg.isSender && msg.msgType !== 10000" class="msg-sender-name">
                {{ resolveMsgDisplayName(msg) }}
              </div>
              <div class="msg-content">
                <!-- 文本 -->
                <template v-if="msg.msgType === 1">{{ msg.content || '[文本]' }}</template>
                <!-- 图片 -->
                <template v-else-if="msg.msgType === 3">
                  <img
                    v-if="resolveImageKey(msg)"
                    :src="getImageSrc(msg)"
                    class="msg-image"
                    @click="previewImage(resolveImageKey(msg), msg.localPath)"
                    @error="onImageError(msg, $event)"
                  />
                  <span v-else class="msg-thumb">[图片]</span>
                </template>
                <!-- 语音 -->
                <template v-else-if="msg.msgType === 34">
                  <div class="msg-voice" @click="playVoice(msg)">
                    <el-icon><VideoPlay /></el-icon>
                    <span>{{ Math.round((msg.msgVoiceDuration || 0) / 1000) }}″</span>
                  </div>
                </template>
                <!-- 视频 -->
                <template v-else-if="msg.msgType === 43">
                  <span class="msg-thumb">[视频]</span>
                </template>
                <!-- 分享 -->
                <template v-else-if="msg.msgType === 49">
                  <div class="msg-share-card">
                    <div class="msg-share-title">{{ msg.contents?.title || '[分享]' }}</div>
                    <div v-if="msg.contents?.desc" class="msg-share-desc">{{ msg.contents.desc }}</div>
                    <a
                      v-if="msg.contents?.url"
                      :href="msg.contents.url"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="msg-share-link"
                    >
                      {{ msg.contents.url }}
                    </a>
                    <div v-if="msg.contents?.fileName || msg.contents?.size" class="msg-share-file">
                      <span>{{ msg.contents?.fileName || '文件' }}</span>
                      <span v-if="msg.contents?.size"> · {{ formatFileSize(msg.contents.size) }}</span>
                    </div>
                    <div v-if="msg.shareType" class="msg-share-type">类型: {{ msg.shareType }}</div>
                  </div>
                </template>
                <!-- 表情 -->
                <template v-else-if="msg.msgType === 47">
                  <img v-if="msg.emojiUrl" :src="msg.emojiUrl" class="msg-emoji" />
                  <span v-else class="msg-thumb">[表情]</span>
                </template>
                <!-- 系统消息 -->
                <template v-else-if="msg.msgType === 10000">
                  <span class="msg-system">{{ msg.content || '[系统消息]' }}</span>
                </template>
                <!-- 其他 -->
                <template v-else>
                  <span class="msg-other">[{{ msg.msgTypeDesc }}] {{ msg.content || '' }}</span>
                </template>
              </div>
              <div class="msg-time">{{ formatTime(msg.timestamp) }}</div>
            </div>
          </div>
        </div>

        <!-- 加载更多 -->
        <div v-if="hasMore" class="load-more">
          <el-button link @click="loadMore">加载更多</el-button>
        </div>

        <!-- 加载中 -->
        <div v-if="loadingMessages" class="loading-messages">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
      </template>

      <!-- 未选中会话 -->
      <div v-else class="no-session">
        <el-empty description="选择一个会话开始查看聊天记录">
          <template #image>
            <span style="font-size:60px">💬</span>
          </template>
        </el-empty>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, VideoPlay } from '@element-plus/icons-vue'
import axios from 'axios'

const keyword = ref('')
const currentSessionId = ref<string | null>(null)
const currentSession = ref<any>(null)
const sessions = ref<any[]>([])
const messages = ref<any[]>([])
const loadingMessages = ref(false)
const hasMore = ref(true)
const page = ref(1)
const msgListRef = ref<HTMLElement>()

// API
async function loadSessions() {
  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/sessions')
    const raw = res.data?.data || res.data?.list || res.data || []
    sessions.value = (Array.isArray(raw) ? raw : []).map((s: any) => {
      const id = s.userName ?? s.username ?? s.id ?? ''
      const displayName = s.nickName ?? s.name ?? id
      const lastTs = normalizeUnixTimestamp(
        s.nOrder ?? s.lastTime ?? s.lastTimestamp ?? (s.nTime ? Math.floor(new Date(s.nTime).getTime() / 1000) : 0)
      )
      return {
        ...s,
        id,
        username: id,
        name: displayName,
        avatar: s.smallHeadURL ?? s.smallHeadUrl ?? '',
        lastMessage: s.content ?? s.summary ?? s.lastMessage ?? '',
        lastTime: lastTs
      }
    })
  } catch {
    // 服务未启动或未解密
  }
}

async function selectSession(session: any) {
  const sid = getSessionId(session)
  if (!sid) {
    ElMessage.error('会话ID为空，无法加载消息')
    return
  }
  currentSessionId.value = sid
  currentSession.value = session
  page.value = 1
  messages.value = []
  hasMore.value = true
  await loadMessages(true)
}

async function loadMessages(prepend = false) {
  if (!currentSessionId.value) return
  loadingMessages.value = true

  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/messages', {
      params: {
        sessionId: currentSessionId.value,
        limit: 0,
        offset: 0
      }
    })

    const rawList = res.data?.data || res.data?.list || res.data || []
    const list = (Array.isArray(rawList) ? rawList : []).map((m: any) => {
      const contents = m.contents || {}
      return {
        ...m,
        contents,
        msgType: m.msgType ?? m.type,
        msgTypeDesc: m.msgTypeDesc ?? m.typeDesc,
        fromWxid: m.fromWxid ?? m.sender,
        isSender: m.isSender ?? m.isSelf ?? m.self ?? false,
        isChatRoom: m.isChatRoom ?? m.chatRoom ?? false,
        timestamp: normalizeUnixTimestamp(m.seq ?? m.timestamp ?? (m.time ? Math.floor(new Date(m.time).getTime() / 1000) : 0)),
        thumb: m.thumb ?? contents?.md5 ?? extractMd5FromPackedInfo(m.packedInfoData) ?? extractMd5FromPath(m.localPath ?? contents?.path),
        localPath: m.localPath ?? contents?.path,
        msgVoiceDuration: m.msgVoiceDuration ?? contents?.duration,
        shareType: resolveShareType(m.subType ?? contents?.appMsgType),
        emojiUrl: getEmojiUrl(contents)
      }
    }).sort((a: any, b: any) => {
      const t = (a.timestamp || 0) - (b.timestamp || 0)
      if (t !== 0) return t
      const s = (a.sortSeq || 0) - (b.sortSeq || 0)
      if (s !== 0) return s
      return (a.serverId || 0) - (b.serverId || 0)
    })
    if (prepend) {
      messages.value = list
    } else {
      messages.value = [...messages.value, ...list]
    }

    hasMore.value = false
    page.value = 1
    await nextTick()
    if (msgListRef.value) {
      msgListRef.value.scrollTop = msgListRef.value.scrollHeight
    }
  } catch {
    // ignore
  } finally {
    loadingMessages.value = false
  }
}

async function loadMore() {
  await loadMessages(false)
}

// 工具
function formatTime(ts: number): string {
  if (!ts) return ''
  const d = new Date(normalizeUnixTimestamp(ts) * 1000)
  const now = new Date()
  const diff = now.getTime() - d.getTime()

  if (diff < 86400000 && d.getDate() === now.getDate()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  if (diff < 604800000) {
    return ['周日','周一','周二','周三','周四','周五','周六'][d.getDay()] + ' ' +
      d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function normalizeUnixTimestamp(ts: any): number {
  const n = Number(ts || 0)
  if (!Number.isFinite(n) || n <= 0) return 0
  return n > 9_999_999_999 ? Math.floor(n / 1000) : Math.floor(n)
}

function getImageUrl(key?: string, path?: string, thumb = false): string {
  if (!key) return ''
  const base = `/api/v1/media/image/${encodeURIComponent(key)}`
  const params = new URLSearchParams()
  if (thumb) params.set('thumb', '1')
  if (path) params.set('path', path)
  const qs = params.toString()
  return qs ? `${base}?${qs}` : base
}

function getImageSrc(msg: any): string {
  const key = resolveImageKey(msg)
  if (!key) return ''
  return getImageUrl(key, msg?.localPath, !msg?._originImage)
}

function previewImage(key?: string, path?: string) {
  if (!key) return
  window.open(getImageUrl(key, path, false), '_blank')
}

function resolveImageKey(msg: any): string {
  return msg?.thumb || extractMd5FromPath(msg?.localPath) || ''
}

function onImageError(msg: any, e: Event) {
  const key = resolveImageKey(msg)
  if (!key) return
  const target = e.target as HTMLImageElement
  if (!msg._originImage) {
    msg._originImage = true
    target.src = getImageUrl(key, msg?.localPath, false)
  }
}

function resolveMsgDisplayName(msg: any): string {
  if (msg.isSender) {
    return '我'
  }
  return msg.senderName || msg.fromWxid || msg.talkerName || msg.talker || ''
}

function resolveMsgAvatar(msg: any): string {
  return msg.smallHeadURL || msg.bigHeadURL || ''
}

function extractMd5FromPackedInfo(packedInfoData?: string): string {
  if (!packedInfoData) return ''
  try {
    const decoded = atob(packedInfoData)
    const m = decoded.match(/[a-fA-F0-9]{32}/)
    return m ? m[0].toLowerCase() : ''
  } catch {
    return ''
  }
}

function extractMd5FromPath(path?: string): string {
  if (!path) return ''
  const m = path.match(/[a-fA-F0-9]{32}/g)
  return m && m.length > 0 ? m[m.length - 1].toLowerCase() : ''
}

function getEmojiUrl(contents: any): string {
  const url = contents?.cdnurl || contents?.url
  const key = contents?.aeskey || contents?.key
  if (!url || !key) return ''
  const params = new URLSearchParams({ url, key })
  return `/api/v1/media/emoji?${params.toString()}`
}

function resolveShareType(subType?: number): string {
  const t = Number(subType || 0)
  switch (t) {
    case 3: return '音乐'
    case 4: return '视频'
    case 5: return '链接'
    case 6: return '文件'
    case 8: return '附件'
    case 19: return '合并转发'
    case 33: return '小程序'
    case 57: return '引用消息'
    default: return ''
  }
}

function formatFileSize(size?: number): string {
  if (!size || size <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let value = size
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

function playVoice(msg: any) {
  ElMessage.info('语音播放功能开发中')
}

async function exportChat(format: string) {
  if (!currentSessionId.value) return
  try {
    const res = await axios.get(
      `http://127.0.0.1:8080/api/v1/export/chat?sessionId=${currentSessionId.value}&format=${format}`,
      { responseType: 'blob' }
    )
    const blob = new Blob([res.data])
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${currentSession.value?.name || 'chat'}.${format}`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('导出失败')
  }
}

const filteredSessions = computed(() => {
  if (!keyword.value) return sessions.value
  const kw = keyword.value.toLowerCase()
  return sessions.value.filter(s =>
    s.name?.toLowerCase().includes(kw) || getSessionId(s).toLowerCase().includes(kw)
  )
})

function getSessionId(session: any): string {
  return session?.id || session?.username || session?.userName || ''
}

onMounted(() => {
  loadSessions()
})
</script>

<style scoped>
.chat-view {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.session-list {
  width: 280px;
  background: #fff;
  border-right: 1px solid #eee;
  display: flex;
  flex-direction: column;
}

.search-box {
  padding: 12px;
  border-bottom: 1px solid #eee;
}

.session-items {
  flex: 1;
  overflow-y: auto;
}

.session-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  gap: 10px;
  transition: background 0.2s;
}

.session-item:hover { background: #f5f5f5; }
.session-item.active { background: #e8f4ff; }

.avatar { flex-shrink: 0; }

.session-info {
  flex: 1;
  min-width: 0;
}

.session-name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-last {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}

.session-meta {
  text-align: right;
  flex-shrink: 0;
}

.session-time {
  font-size: 11px;
  color: #bbb;
}

.chat-window {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
  overflow: hidden;
}

.chat-header {
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-name { font-size: 16px; font-weight: 600; }

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.message-item.system {
  justify-content: center;
}

.message-item.sender {
  flex-direction: row-reverse;
}

.msg-body {
  max-width: 65%;
}

.msg-content {
  background: #fff;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-all;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.msg-sender-name {
  font-size: 11px;
  color: #999;
  margin: 0 0 4px 4px;
}

.message-item.sender .msg-content {
  background: #95ec69;
  color: #1a1a1a;
}

.msg-time {
  font-size: 11px;
  color: #bbb;
  margin-top: 3px;
}

.message-item.sender .msg-time { text-align: right; }

.msg-image {
  max-width: 300px;
  max-height: 200px;
  border-radius: 4px;
  cursor: pointer;
}

.msg-emoji {
  width: 96px;
  height: 96px;
  object-fit: contain;
}

.msg-share-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 220px;
}

.msg-share-title {
  font-weight: 600;
}

.msg-share-desc {
  color: #666;
  font-size: 12px;
}

.msg-share-link {
  color: #576b95;
  text-decoration: none;
  word-break: break-all;
}

.msg-share-file {
  color: #444;
  font-size: 12px;
}

.msg-share-type {
  color: #999;
  font-size: 11px;
}

.msg-voice {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #576b95;
}

.msg-system {
  color: #999;
  font-size: 12px;
  text-align: center;
  display: block;
}

.message-item.system .msg-content {
  background: #efefef;
  color: #666;
}

.msg-other {
  color: #666;
  font-style: italic;
}

.load-more, .loading-messages {
  text-align: center;
  padding: 12px;
}

.no-session {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
