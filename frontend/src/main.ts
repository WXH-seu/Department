import { createApp } from 'vue'
import App from './App.vue'
import { createRouter, createWebHistory } from 'vue-router'

const Empty = { template: '<span />' }
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/workspace' },
  { path: '/workspace', component: Empty },
  { path: '/admin/:section?', component: Empty },
  { path: '/:pathMatch(.*)*', redirect: '/workspace' }
] })
createApp(App).use(router).mount('#app')

