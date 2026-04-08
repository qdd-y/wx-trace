<template>
  <div class="search-view">
    <el-input
      v-model="keyword"
      placeholder="搜索聊天内容"
      size="large"
      clearable
      @keyup.enter="doSearch"
    >
      <template #append>
        <el-button @click="doSearch" :loading="searching">搜索</el-button>
      </template>
    </el-input>

    <div class="search-filters">
      <el-select v-model="msgType" style="width:120px" placeholder="消息类型">
        <el-option label="全部" :value="0" />
        <el-option label="文本" :value="1" />
        <el-option label="图片" :value="3" />
        <el-option label="语音" :value="34" />
        <el-option label="视频" :value="43" />
      </el-select>
    </div>

    <div class="search-results" v-if="results.length">
      <div v-for="(group, talker) in groupedResults" :key="talker" class="result-group">
        <div class="group-header">
          <el-avatar :size="32">{{ String(talker).charAt(0) }}</el-avatar>
          <span class="group-name">{{ group.name || talker }}</span>
          <span class="group-count">{{ group.messages.length }} 条结果</span>
        </div>
        <div
          v-for="msg in group.messages"
          :key="msg.seq"
          class="result-item"
          @click="goToChat(talker, msg)"
        >
          <span class="result-sender">{{ msg.senderName || msg.sender }}</span>
          <span class="result-content">{{ msg.content }}</span>
          <span class="result-time">{{ formatTime(msg.timestamp) }}</span>
        </div>
      </div>
    </div>

    <el-empty v-else-if="searched && !results.length" description="未找到结果" />
    <el-empty v-else description="输入关键词开始搜索" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const keyword = ref('')
const msgType = ref(0)
const searching = ref(false)
const searched = ref(false)
const results = ref<any[]>([])

async function doSearch() {
  if (!keyword.value.trim()) return
  searching.value = true
  searched.value = true
  try {
    // 调用搜索 API（预留）
    results.value = []
  } finally {
    searching.value = false
  }
}

const groupedResults = computed(() => {
  const groups: Record<string, any> = {}
  for (const msg of results.value) {
    const talker = msg.talker
    if (!groups[talker]) {
      groups[talker] = { name: msg.talkerName, messages: [] }
    }
    groups[talker].messages.push(msg)
  }
  return groups
})

function goToChat(talker: string, msg: any) {
  router.push({ name: 'chat', query: { sessionId: talker } })
}

function formatTime(ts: number) {
  if (!ts) return ''
  return new Date(ts * 1000).toLocaleString('zh-CN')
}
</script>

<style scoped>
.search-view { padding: 20px; height: 100%; overflow-y: auto; }
.search-filters { margin-top: 12px; display: flex; gap: 10px; }
.search-results { margin-top: 20px; }
.result-group { margin-bottom: 16px; }
.group-header { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #eee; }
.group-name { font-weight: 600; }
.group-count { color: #999; font-size: 12px; margin-left: auto; }
.result-item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; cursor: pointer; transition: background 0.15s; }
.result-item:hover { background: #f5f5f5; }
.result-sender { color: #576b95; font-size: 13px; min-width: 80px; }
.result-content { flex: 1; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-time { color: #bbb; font-size: 12px; min-width: 140px; }
</style>
