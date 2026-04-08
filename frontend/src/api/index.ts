import axios from 'axios'
import { ElMessage } from 'element-plus'

const BASE_URL = 'http://127.0.0.1:8080/api/v1'

const http = axios.create({
  baseURL: BASE_URL,
  timeout: 300_000, // 5 分钟（SSE 解密可能很久）
  headers: { 'Content-Type': 'application/json' }
})

// 响应拦截
http.interceptors.response.use(
  res => res.data,
  err => {
    if (err.response?.status === 401) {
      ElMessage.warning('请先输入访问密码')
    } else if (!err.response) {
      ElMessage.error('网络错误，请检查后端服务是否启动')
    } else {
      ElMessage.error(err.response?.data?.error || '请求失败')
    }
    return Promise.reject(err)
  }
)

export default http

// ==================== API 方法 ====================

// --- 系统 ---
export const systemApi = {
  status: () => http.get('/system/status'),
  detectWeChatPath: () => http.get('/system/detect/wechat_path'),
  detectDbPath: () => http.get('/system/detect/db_path'),
  // SSE: 获取数据库密钥（带实时进度）
  getDbKey: (wechatPath?: string) => {
    return http.get('/system/wxkey/db', {
      params: { wechatPath },
      responseType: 'text',
      headers: { Accept: 'text/event-stream' }
    })
  },
  getImageKey: (dataPath?: string) => http.get('/system/wxkey/image', { params: { dataPath } }),
  decrypt: (srcPath: string, dbKey: string) =>
    http.post('/system/decrypt', { srcPath, dbKey }, {
      responseType: 'text',
      headers: { Accept: 'text/event-stream' }
    }),
}

// --- 会话 ---
export const sessionApi = {
  list: (params?: { type?: number; keyword?: string; page?: number; size?: number }) =>
    http.get('/sessions', { params }),
  delete: (id: string) => http.delete(`/sessions/${id}`),
}

// --- 消息 ---
export const messageApi = {
  list: (params: { sessionId: string; page?: number; size?: number; before?: number }) =>
    http.get('/messages', { params }),
  replay: (params: { sessionId: string; start?: number; end?: number }) =>
    http.get('/messages/replay', { params }),
}

// --- 联系人 ---
export const contactApi = {
  list: (params?: { type?: number; keyword?: string; page?: number; size?: number }) =>
    http.get('/contacts', { params }),
  export: (params?: Record<string, unknown>) =>
    http.get('/contacts/export', { params, responseType: 'blob' }),
}

// --- 媒体 ---
export const mediaApi = {
  imageList: (params?: { sessionId?: string; page?: number; size?: number }) =>
    http.get('/media/images', { params }),
  get: (type: string, key: string) =>
    `${BASE_URL}/media/${type}/${key}?token=`, // 直接返回图片 URL
}

// --- 分析 ---
export const analysisApi = {
  dashboard: () => http.get('/dashboard'),
  topContacts: (params?: { limit?: number }) => http.get('/analysis/personal/top_contacts', { params }),
  hourly: (id: string) => http.get(`/analysis/hourly/${id}`),
  daily: (id: string) => params => http.get(`/analysis/daily/${id}`, { params }),
  wordcloud: (id?: string) => http.get(id ? `/analysis/wordcloud/${id}` : '/analysis/wordcloud/global'),
  repeat: (id: string) => http.get(`/analysis/repeat/${id}`),
}

// --- 导出 ---
export const exportApi = {
  chat: (params: { sessionId: string; format: string }) =>
    http.get('/export/chat', { params, responseType: 'blob' }),
  forensic: (params: { sessionId: string }) =>
    http.get('/export/forensic', { params, responseType: 'blob' }),
  voices: (params: { sessionId: string }) =>
    http.get('/export/voices', { params, responseType: 'blob' }),
}

// ==================== SSE 工具 ====================

/**
 * 解析 SSE 流
 */
export function parseSSE<T>(emitter: any, callbacks: {
  onStatus?: (msg: string) => void
  onKey?: (key: string) => void
  onDone?: (data: T) => void
  onError?: (err: string) => void
}) {
  emitter.on('status', (msg: string) => callbacks.onStatus?.(msg))
  emitter.on('key', (key: string) => callbacks.onKey?.(key))
  emitter.on('done', (data: any) => callbacks.onDone?.(data))
  emitter.on('error', (err: string) => callbacks.onError?.(err))
  emitter.on('error', (err: any) => callbacks.onError?.(err.message || String(err)))
}
