import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
  { path: '/note/:id', name: 'NoteDetail', component: () => import('../views/NoteDetail.vue') },
  { path: '/publish', name: 'Publish', component: () => import('../views/Publish.vue') },
  { path: '/user/:id', name: 'UserHome', component: () => import('../views/UserHome.vue') },
  { path: '/search', name: 'Search', component: () => import('../views/Search.vue') },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
