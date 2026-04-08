<template>
  <div class="analysis-view">
    <div class="analysis-header">
      <h3>数据分析</h3>
    </div>

    <el-tabs>
      <!-- 时段分析 -->
      <el-tab-pane label="时段分析">
        <div class="chart-container" ref="hourlyChart"></div>
      </el-tab-pane>

      <!-- 词云 -->
      <el-tab-pane label="词云">
        <div v-if="wordcloud.length" class="wordcloud">
          <span
            v-for="item in wordcloud"
            :key="item.word"
            class="word-item"
            :style="{ fontSize: Math.max(12, Math.min(48, item.frequency / 2)) + 'px' }"
          >{{ item.word }}</span>
        </div>
        <el-empty v-else description="暂无数据，请先解密数据库" />
      </el-tab-pane>

      <!-- 消息类型 -->
      <el-tab-pane label="消息类型">
        <div class="chart-container" ref="typeChart"></div>
      </el-tab-pane>
    </el-tabs>

    <!-- 顶部联系人 -->
    <el-card class="top-contacts" style="margin-top:16px">
      <template #header>活跃联系人 TOP10</template>
      <el-table :data="topContacts" stripe>
        <el-table-column prop="name" label="联系人" />
        <el-table-column prop="messageCount" label="消息数" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'

const hourlyChart = ref<HTMLElement>()
const typeChart = ref<HTMLElement>()
const wordcloud = ref<any[]>([])
const topContacts = ref<any[]>([])

async function loadAnalysis() {
  try {
    // Dashboard
    const dash = await axios.get('http://127.0.0.1:8080/api/v1/dashboard')

    // Top contacts
    const top = await axios.get('http://127.0.0.1:8080/api/v1/analysis/personal/top_contacts', {
      params: { limit: 10 }
    })
    topContacts.value = top.data || []
  } catch {}
}

onMounted(() => {
  loadAnalysis()
})
</script>

<style scoped>
.analysis-view { padding: 20px; height: 100%; overflow-y: auto; }
.analysis-header { margin-bottom: 16px; }
.chart-container { height: 300px; background: #fff; border-radius: 8px; }
.wordcloud { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; padding: 20px; background: #fff; border-radius: 8px; min-height: 200px; align-items: center; }
.word-item { color: #576b95; transition: color 0.2s; cursor: default; }
.word-item:hover { color: #f56c6c; }
</style>
