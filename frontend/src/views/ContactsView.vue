<template>
  <div class="contacts-view">
    <div class="contacts-header">
      <el-input v-model="keyword" placeholder="搜索联系人" prefix-icon="Search" clearable />
      <el-select v-model="filterType" style="width:120px">
        <el-option label="全部" :value="0" />
        <el-option label="个人" :value="1" />
        <el-option label="公众号" :value="2" />
        <el-option label="企业" :value="3" />
      </el-select>
    </div>

    <el-table :data="filteredContacts" stripe @row-click="viewChat" style="cursor:pointer">
      <el-table-column width="60">
        <template #default="{ row }">
          <el-avatar :size="40" :src="row.smallHeadImgUrl || row.smallHeadURL || row.bigHeadImgUrl || row.bigHeadURL">
            {{ row.nickName?.charAt(0) || row.userName?.charAt(0) || '?' }}
          </el-avatar>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="备注/昵称" min-width="120">
        <template #default="{ row }">
          <div>{{ row.remark || row.nickName || row.userName }}</div>
          <div style="font-size:12px;color:#999">
            {{ row.alias || row.nickName || '' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="localType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTag(row)">
            {{ typeName(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column width="80">
        <template #default>
          <el-button size="small" link type="primary">发消息</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-button @click="loadMore" :loading="loading">加载更多</el-button>
      <el-button @click="exportContacts">导出通讯录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()
const keyword = ref('')
const filterType = ref(0)
const contacts = ref<any[]>([])
const loading = ref(false)
const page = ref(0)
const size = 50

async function loadContacts(append = false) {
  loading.value = true
  try {
    const params: any = { limit: size, offset: page.value * size }
    if (filterType.value > 0) params.type = filterType.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()

    const res = await axios.get('http://127.0.0.1:8080/api/v1/contacts', { params })
    const list = res.data?.data || res.data?.list || res.data || []
    if (append) {
      contacts.value.push(...list)
    } else {
      contacts.value = list
    }
    page.value++
  } finally {
    loading.value = false
  }
}

function loadMore() {
  loadContacts(true)
}

async function exportContacts() {
  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/contacts/export', { responseType: 'blob' })
    const blob = new Blob([res.data], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'contacts.csv'
    a.click()
    URL.revokeObjectURL(url)
  } catch {}
}

function viewChat(row: any) {
  const id = row.userName || row.username
  if (id) router.push({ name: 'chat', query: { sessionId: id } })
}

function typeName(row: any) {
  const type = Number(row.localType || row.reserved1 || 0)
  if (type > 0) return ({ 1: '个人', 2: '公众号', 3: '企业' } as any)[type] || '其他'
  return String(row.userName || '').includes('@chatroom') ? '群聊' : '联系人'
}

function typeTag(row: any) {
  const type = Number(row.localType || row.reserved1 || 0)
  if (type > 0) return ({ 1: '', 2: 'success', 3: 'warning' } as any)[type] || 'info'
  return String(row.userName || '').includes('@chatroom') ? 'warning' : ''
}

const filteredContacts = computed(() => {
  if (!keyword.value) return contacts.value
  const kw = keyword.value.toLowerCase()
  return contacts.value.filter(c =>
    (c.remark || c.nickName || c.userName || '').toLowerCase().includes(kw)
  )
})

let searchTimer: number | undefined
watch([keyword, filterType], () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 0
    loadContacts(false)
  }, 300)
})

onMounted(() => { loadContacts() })
</script>

<style scoped>
.contacts-view {
  padding: 20px;
  height: 100%;
  overflow-y: auto;
}
.contacts-header {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.contacts-header .el-input { flex: 1; }
.pagination {
  display: flex;
  gap: 10px;
  justify-content: center;
  margin-top: 16px;
}
</style>
