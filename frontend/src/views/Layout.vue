<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <span class="logo-icon">💬</span>
        <span class="logo-text">WeTrace</span>
      </div>

      <el-menu :default-active="route.path" router class="nav-menu">
        <el-menu-item index="/chat">
          <el-icon><ChatLineSquare /></el-icon>
          <span>聊天记录</span>
        </el-menu-item>
        <el-menu-item index="/contacts">
          <el-icon><User /></el-icon>
          <span>联系人</span>
        </el-menu-item>
        <el-menu-item index="/gallery">
          <el-icon><Picture /></el-icon>
          <span>图片画廊</span>
        </el-menu-item>
        <el-menu-item index="/search">
          <el-icon><Search /></el-icon>
          <span>全局搜索</span>
        </el-menu-item>
        <el-menu-item index="/analysis">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据分析</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>设置</span>
        </el-menu-item>
      </el-menu>

      <!-- 状态栏 -->
      <div class="status-bar">
        <div class="status-item">
          <span class="dot" :class="appStore.status.wechatRunning ? 'online' : 'offline'"></span>
          微信 {{ appStore.status.wechatRunning ? '运行中' : '未运行' }}
        </div>
        <div class="status-item">
          <span class="tag" :class="appStore.status.dbKeySet ? 'success' : 'warning'">
            {{ appStore.status.dbKeySet ? 'DB Key ✓' : 'DB Key ✗' }}
          </span>
        </div>
      </div>
    </el-aside>

    <!-- 主内容 -->
    <el-main class="main-content">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ChatLineSquare, User, Picture, Search, DataAnalysis, Setting } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()

onMounted(() => {
  appStore.refreshStatus()
  appStore.detectPaths()
})
</script>

<style scoped>
.layout {
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  background: #1a1a2e;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.logo {
  padding: 20px 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}

.logo-icon { font-size: 24px; }
.logo-text { color: #fff; font-size: 18px; font-weight: 600; }

.nav-menu {
  flex: 1;
  border-right: none;
  background: transparent;
}

.nav-menu .el-menu-item {
  color: #aaa;
  height: 50px;
  line-height: 50px;
}

.nav-menu .el-menu-item:hover,
.nav-menu .el-menu-item.is-active {
  background: rgba(255,255,255,0.1);
  color: #fff;
}

.status-bar {
  padding: 12px 16px;
  border-top: 1px solid rgba(255,255,255,0.1);
  font-size: 12px;
  color: #888;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.dot.online { background: #67c23a; }
.dot.offline { background: #909399; }

.tag {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
}
.tag.success { background: rgba(103,194,58,0.2); color: #67c23a; }
.tag.warning { background: rgba(230,162,60,0.2); color: #e6a23c; }

.main-content {
  padding: 0;
  overflow: hidden;
  background: #f0f2f5;
}
</style>
