<template>
  <div class="gallery-view">
    <div class="gallery-header">
      <span>图片画廊</span>
      <el-switch v-model="autoDecrypt" active-text="自动解密" />
    </div>
    <div class="gallery-grid" v-if="images.length">
      <div v-for="img in images" :key="img.key" class="gallery-item" @click="preview(img)">
        <img :src="img.thumbUrl" :alt="img.key" loading="lazy" />
        <div class="gallery-overlay">{{ formatTime(img.time) }}</div>
      </div>
    </div>
    <el-empty v-else description="暂无图片" />
    <div v-if="hasMore" class="load-more">
      <el-button @click="loadMore" :loading="loading">加载更多</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import axios from 'axios'

const autoDecrypt = ref(true)
const images = ref<any[]>([])
const loading = ref(false)
const hasMore = ref(true)
const page = ref(1)

async function loadImages() {
  loading.value = true
  try {
    const res = await axios.get('http://127.0.0.1:8080/api/v1/media/images', {
      params: { page: page.value, size: 50 }
    })
    const list = res.data?.list || []
    images.value.push(...list)
    hasMore.value = list.length === 50
    page.value++
  } finally {
    loading.value = false
  }
}

function preview(img: any) {
  window.open(
    `http://127.0.0.1:8080/api/v1/media/image/${img.key}`,
    '_blank'
  )
}

function loadMore() { loadImages() }
function formatTime(ts: number) {
  if (!ts) return ''
  return new Date(ts * 1000).toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.gallery-view { padding: 20px; height: 100%; overflow-y: auto; }
.gallery-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.gallery-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px; }
.gallery-item { position: relative; aspect-ratio: 1; overflow: hidden; border-radius: 4px; cursor: pointer; }
.gallery-item img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.2s; }
.gallery-item:hover img { transform: scale(1.05); }
.gallery-overlay { position: absolute; bottom: 0; left: 0; right: 0; background: rgba(0,0,0,0.5); color: #fff; font-size: 11px; padding: 4px 8px; }
.load-more { text-align: center; margin-top: 16px; }
</style>
