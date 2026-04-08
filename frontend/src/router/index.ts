import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/views/Layout.vue'),
      children: [
        { path: '', redirect: '/chat' },
        { path: 'chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
        { path: 'contacts', name: 'contacts', component: () => import('@/views/ContactsView.vue') },
        { path: 'gallery', name: 'gallery', component: () => import('@/views/GalleryView.vue') },
        { path: 'search', name: 'search', component: () => import('@/views/SearchView.vue') },
        { path: 'analysis', name: 'analysis', component: () => import('@/views/AnalysisView.vue') },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
      ]
    },
    // 未匹配的路径回退到首页
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

export default router
