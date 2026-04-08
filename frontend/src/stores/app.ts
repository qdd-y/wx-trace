import { defineStore } from 'pinia'
import { ref } from 'vue'
import { systemApi } from '@/api'

export const useAppStore = defineStore('app', () => {
  // 状态
  const status = ref({
    wechatRunning: false,
    wechatPid: 0,
    dbKeySet: false,
    imageKeySet: false,
    workDir: 'data',
    wechatPath: '',
    dbSrcPath: '',
    dbKey: '',
    imageKey: '',
    xorKey: '',
  })

  const loading = ref(false)

  // 刷新状态
  async function refreshStatus() {
    try {
      const data = await systemApi.status()
      Object.assign(status.value, data)
    } catch (e) {
      console.error('获取状态失败', e)
    }
  }

  // 检测微信路径
  async function detectPaths() {
    try {
      const [pathRes, dbRes] = await Promise.all([
        systemApi.detectWeChatPath(),
        systemApi.detectDbPath()
      ])
      status.value.wechatPath = pathRes.path || ''
      status.value.dbSrcPath = dbRes.path || ''
    } catch (e) {
      console.error('检测路径失败', e)
    }
  }

  return { status, loading, refreshStatus, detectPaths }
})
