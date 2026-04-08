<template>
  <div class="app-container">
    <router-view />
  </div>
</template>

<script setup lang="ts">
// 根组件，路由视图
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #f0f2f5;
  color: #333;
}

.app-container {
  min-height: 100vh;
}
</style>
