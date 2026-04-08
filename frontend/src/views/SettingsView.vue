<template>
  <div class="settings-view">
    <div class="settings-container">

      <!-- 1. 初始配置向导 -->
      <div class="setup-wizard">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            首次使用需要配置：获取微信密钥 → 解密数据库 → 开始使用
          </template>
        </el-alert>

        <el-card class="step-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>Step 1 — 路径设置</span>
            </div>
          </template>

          <el-form label-width="120px">
            <el-form-item label="微信安装路径">
              <el-input v-model="form.wechatPath" placeholder="自动检测或手动选择" clearable @input="onWechatPathInput">
                <template #append>
                  <el-button @click="detectWeChatPath">自动探测</el-button>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item label="微信数据路径">
              <el-input v-model="form.dbSrcPath" placeholder="微信账号数据目录" clearable @input="onDbSrcPathInput">
                <template #append>
                  <el-button @click="detectDbPath">自动探测</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card class="step-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>Step 2 — 获取数据库密钥</span>
              <el-tag type="info">通过 DLL Hook 自动提取</el-tag>
            </div>
          </template>

          <div class="key-status" v-if="dbKeyLoading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ dbKeyStatus }}</span>
          </div>

          <div class="key-status success" v-else-if="status.dbKeySet">
            <el-icon><CircleCheck /></el-icon>
            <div class="key-display">
              <div>数据库密钥：<code>{{ status.dbKey || '(未获取)' }}</code></div>
            </div>
          </div>

          <div v-else>
            <p style="color:#666;margin-bottom:12px;font-size:13px">
              点击后程序会关闭并重启微信，请确保已保存微信中的重要操作。
            </p>
            <el-button type="primary" @click="getDbKey" :loading="dbKeyLoading">
              获取数据库密钥
            </el-button>
          </div>
        </el-card>

        <el-card class="step-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>Step 3 — 获取图片密钥</span>
              <el-tag type="info">内存扫描 + 模板分析</el-tag>
            </div>
          </template>

          <div class="key-status" v-if="imageKeyLoading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ imageKeyStatus }}</span>
          </div>

          <div class="key-status success" v-else-if="status.imageKeySet">
            <el-icon><CircleCheck /></el-icon>
            <div class="key-display">
              <div>XOR 密钥：<code>{{ status.xorKey || '' }}</code></div>
              <div>AES 密钥：<code>{{ status.imageKey || '' }}</code></div>
            </div>
          </div>

          <div v-else>
            <p style="color:#666;margin-bottom:12px;font-size:13px">
              请确保微信已登录，并在获取期间打开一张聊天图片。
            </p>
            <el-button @click="getImageKey" :loading="imageKeyLoading">
              获取图片密钥
            </el-button>
          </div>
        </el-card>

        <el-card class="step-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>Step 4 — 解密数据库</span>
              <el-tag type="info">SQLCipher → SQLite</el-tag>
            </div>
          </template>

          <div class="key-status" v-if="decryptLoading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ decryptStatus }}</span>
          </div>

          <div v-else>
            <p style="color:#666;margin-bottom:12px;font-size:13px">
              解密后的数据库将保存在 <code>data/</code> 目录。
            </p>
            <el-button type="success" @click="decryptDb"
              :disabled="!status.dbKeySet || !getDbSourcePath()">
              开始解密
            </el-button>
          </div>
        </el-card>
      </div>

      <!-- 2. 正常模式 -->
      <div v-if="status.dbKeySet" class="normal-mode">
        <el-tabs>
          <!-- 密钥管理 -->
          <el-tab-pane label="密钥管理">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="数据库密钥">
                <code>{{ status.dbKey?.substring(0, 24) }}...</code>
                <el-tag size="small" type="success" style="margin-left:8px">已设置</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="图片密钥">
                <code>{{ status.imageKey?.substring(0, 24) || '未设置' }}...</code>
                <el-tag v-if="status.imageKeySet" size="small" type="success" style="margin-left:8px">已设置</el-tag>
                <el-tag v-else size="small" type="warning" style="margin-left:8px">未设置</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="微信数据路径">{{ form.dbSrcPath || '未设置' }}</el-descriptions-item>
              <el-descriptions-item label="工作目录">data/</el-descriptions-item>
            </el-descriptions>

            <div style="margin-top:20px">
              <el-button @click="reGetDbKey">重新获取数据库密钥</el-button>
              <el-button @click="reGetImageKey">重新获取图片密钥</el-button>
              <el-button type="warning" @click="reDecrypt">重新解密</el-button>
            </div>
          </el-tab-pane>

          <!-- AI 配置 -->
          <el-tab-pane label="AI 配置">
            <el-form label-width="100px" style="max-width:500px">
              <el-form-item label="启用 AI">
                <el-switch v-model="aiConfig.enabled" />
              </el-form-item>
              <el-form-item label="服务商">
                <el-select v-model="aiConfig.provider" style="width:100%">
                  <el-option label="OpenAI" value="openai" />
                  <el-option label="DeepSeek" value="deepseek" />
                  <el-option label="智谱 GLM" value="zhipu" />
                  <el-option label="Ollama (本地)" value="ollama" />
                </el-select>
              </el-form-item>
              <el-form-item label="API Key">
                <el-input v-model="aiConfig.apiKey" type="password" show-password />
              </el-form-item>
              <el-form-item label="Base URL">
                <el-input v-model="aiConfig.baseUrl" placeholder="https://api.openai.com/v1" />
              </el-form-item>
              <el-form-item label="模型">
                <el-input v-model="aiConfig.model" placeholder="gpt-4o-mini" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="saveAiConfig">保存</el-button>
                <el-button @click="testAi">测试连接</el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- 监控告警 -->
          <el-tab-pane label="监控告警">
            <el-empty description="配置关键词/AI 监控规则，通过飞书或 Webhook 推送告警">
              <el-button type="primary">添加监控规则</el-button>
            </el-empty>
          </el-tab-pane>
        </el-tabs>
      </div>

    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, CircleCheck } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import axios from 'axios'

const appStore = useAppStore()
const { status } = appStore

const form = reactive({
  wechatPath: '',
  dbSrcPath: ''
})

const aiConfig = reactive({
  enabled: false,
  provider: 'openai',
  apiKey: '',
  baseUrl: '',
  model: 'gpt-4o-mini'
})

const dbKeyLoading = ref(false)
const imageKeyLoading = ref(false)
const decryptLoading = ref(false)
const dbKeyStatus = ref('')
const imageKeyStatus = ref('')
const decryptStatus = ref('')
const userEditedWechatPath = ref(false)
const userEditedDbSrcPath = ref(false)

function getDbSourcePath(): string {
  return (form.dbSrcPath || '').trim()
}

function onWechatPathInput() {
  userEditedWechatPath.value = true
}

function onDbSrcPathInput() {
  userEditedDbSrcPath.value = true
}

// 自动探测
async function detectWeChatPath() {
  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/system/detect/wechat_path')
    form.wechatPath = res.data?.path || ''
    userEditedWechatPath.value = false
    if (!form.wechatPath) ElMessage.warning('未找到微信安装路径')
  } catch {
    ElMessage.error('探测失败，请手动选择路径')
  }
}

async function detectDbPath() {
  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/system/detect/db_path')
    form.dbSrcPath = res.data?.path || ''
    userEditedDbSrcPath.value = false
    if (!form.dbSrcPath) ElMessage.warning('未找到微信数据目录')
  } catch {
    ElMessage.error('探测失败')
  }
}

// SSE 解析
function parseSSEStream(response: any, callbacks: {
  onStatus?: (msg: string) => void
  onKey?: (key: string) => void
  onDone?: () => void
  onError?: (err: string) => void
}) {
  const reader = response.data.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  function process() {
    reader.read().then(({ done, value }) => {
      if (done) {
        callbacks.onDone?.()
        return
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (!data) continue
          try {
            const event = JSON.parse(data)
            if (event.name === 'status' || event.event === 'status') {
              callbacks.onStatus?.(event.data || event.message)
            } else if (event.name === 'key' || event.event === 'key') {
              callbacks.onKey?.(event.data)
            } else if (event.name === 'done' || event.event === 'done') {
              callbacks.onDone?.()
            } else if (event.name === 'error' || event.event === 'error') {
              callbacks.onError?.(event.data || event.message)
            }
          } catch {
            callbacks.onStatus?.(data)
          }
        }
      }
      process()
    })
  }
  process()
}

// 获取数据库密钥
async function getDbKey() {
  dbKeyLoading.value = true
  dbKeyStatus.value = '正在准备...'

  try {
    const response = await fetch(
      `http://127.0.0.1:8080/api/v1/system/wxkey/db?wechatPath=${encodeURIComponent(form.wechatPath)}`,
      { headers: { Accept: 'text/event-stream' } }
    )

    if (!response.ok) {
      const err = await response.json()
      throw new Error(err.error || '请求失败')
    }

    parseSSEStream({ data: response.body?.getReader() }, {
      onStatus: (msg) => { dbKeyStatus.value = msg },
      onKey: (key) => {
        status.dbKey = key
        appStore.refreshStatus()
        ElMessage.success('数据库密钥获取成功！')
      },
      onDone: () => {
        dbKeyLoading.value = false
        appStore.refreshStatus()
      },
      onError: (err) => {
        ElMessage.error(err || '获取失败')
        dbKeyLoading.value = false
      }
    })
  } catch (e: any) {
    ElMessage.error(e.message || '获取密钥失败')
    dbKeyLoading.value = false
  }
}

async function reGetDbKey() {
  await getDbKey()
}

// 获取图片密钥
async function getImageKey() {
  imageKeyLoading.value = true
  imageKeyStatus.value = '正在扫描...'

  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/system/wxkey/image', {
      params: { dataPath: form.dbSrcPath }
    })

    if (res.data.success) {
      status.imageKey = res.data.aesKey
      status.xorKey = res.data.xorKey
      appStore.refreshStatus()
      ElMessage.success('图片密钥获取成功！')
    } else {
      ElMessage.warning(res.data.error || '获取失败')
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '获取失败')
  } finally {
    imageKeyLoading.value = false
  }
}

async function reGetImageKey() {
  await getImageKey()
}

// 解密数据库
async function decryptDb() {
  const srcPath = getDbSourcePath()
  if (!srcPath) {
    ElMessage.warning('请先设置微信数据路径')
    return
  }

  decryptLoading.value = true
  decryptStatus.value = '正在准备...'

  try {
    const response = await fetch('http://127.0.0.1:8080/api/v1/system/decrypt', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({
        srcPath,
        dbKey: status.dbKey || ''
      })
    })

    parseSSEStream({ data: response.body?.getReader() }, {
      onStatus: (msg) => { decryptStatus.value = msg },
      onDone: () => {
        decryptLoading.value = false
        ElMessage.success('数据库解密完成！')
      },
      onError: (err) => {
        ElMessage.error(err || '解密失败')
        decryptLoading.value = false
      }
    })
  } catch (e: any) {
    ElMessage.error(e.message || '解密失败')
    decryptLoading.value = false
  }
}

async function reDecrypt() {
  await decryptDb()
}

function saveAiConfig() {
  ElMessage.success('AI 配置已保存')
}

function testAi() {
  ElMessage.info('正在测试 AI 连接...')
}

onMounted(async () => {
  await appStore.refreshStatus()
  await appStore.detectPaths()
  if (!userEditedWechatPath.value && !form.wechatPath) {
    form.wechatPath = appStore.status.wechatPath || ''
  }
  if (!userEditedDbSrcPath.value && !form.dbSrcPath) {
    form.dbSrcPath = appStore.status.dbSrcPath || ''
  }
})
</script>

<style scoped>
.settings-view {
  height: 100%;
  overflow-y: auto;
  padding: 24px;
}

.settings-container {
  max-width: 700px;
  margin: 0 auto;
}

.step-card {
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.key-status {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #666;
  font-size: 14px;
}

.key-status.success {
  color: #67c23a;
}

.normal-mode {
  padding: 0;
}

code {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
}
</style>
