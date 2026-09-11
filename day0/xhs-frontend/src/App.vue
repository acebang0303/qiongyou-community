<template>
  <div>
    <!-- 顶部导航 -->
    <header class="app-header">
      <div class="header-inner">
        <div class="logo" @click="goHome">红薯社区</div>

        <div class="header-search">
          <el-input
            v-model="keyword"
            placeholder="搜索海南旅游、三亚攻略、海口美食..."
            :prefix-icon="Search"
            clearable
            @keyup.enter="doSearch"
          />
        </div>

        <div class="header-right">
          <span class="nav-link" @click="goHome">首页</span>
          <span class="nav-link" @click="goPublish">发布</span>
          <template v-if="user">
            <span class="nav-link" @click="goMyHome">{{ user.nickname }}</span>
            <el-button size="small" @click="logout">退出</el-button>
          </template>
          <template v-else>
            <el-button type="danger" size="small" @click="goLogin">登录</el-button>
          </template>
        </div>
      </div>
    </header>

    <!-- 页面内容 -->
    <main class="page-container">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { currentUser, clearUser } from './utils/user'

const router = useRouter()
const keyword = ref('')
const user = ref(currentUser())

function refreshUser() {
  user.value = currentUser()
}

function goHome() {
  router.push('/')
}

function goLogin() {
  router.push('/login')
}

function goMyHome() {
  if (user.value) router.push(`/user/${user.value.id}`)
}

function goPublish() {
  if (!currentUser()) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  router.push('/publish')
}

function doSearch() {
  if (!keyword.value.trim()) return
  router.push({ path: '/search', query: { kw: keyword.value.trim() } })
}

function logout() {
  clearUser()
  user.value = null
  ElMessage.success('已退出登录')
  router.push('/')
}

// 登录页保存用户后通过自定义事件刷新导航栏
window.addEventListener('xhs-user-changed', refreshUser)
</script>
