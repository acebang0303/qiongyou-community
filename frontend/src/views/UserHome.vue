<template>
  <div v-loading="loading">
    <template v-if="user">
      <div class="user-header">
        <span class="avatar-huge">{{ (user.nickname || '?').charAt(0) }}</span>
        <div style="flex: 1">
          <h2>{{ user.nickname }}</h2>
          <div style="color: #999; font-size: 13px; margin-top: 4px">{{ user.signature }}</div>
          <div class="user-stats">
            <span><b>{{ user.noteCount }}</b> 笔记</span>
            <span class="stat-link" @click="showList('follows')"><b>{{ user.followCount }}</b> 关注</span>
            <span class="stat-link" @click="showList('fans')"><b>{{ user.fansCount }}</b> 粉丝</span>
          </div>
        </div>
        <el-button
          v-if="!isSelf"
          :type="user.followed ? 'default' : 'danger'"
          @click="toggleFollow"
        >
          {{ user.followed ? '已关注' : '关注' }}
        </el-button>
      </div>

      <!-- 关注/粉丝列表弹窗 -->
      <el-dialog v-model="dialogVisible" :title="dialogTitle" width="420px">
        <div v-if="userList.length">
          <div
            v-for="u in userList"
            :key="u.id"
            style="display: flex; align-items: center; gap: 12px; padding: 10px 0; cursor: pointer"
            @click="goUser(u.id)"
          >
            <span class="avatar-circle">{{ (u.nickname || '?').charAt(0) }}</span>
            <div>
              <div style="font-weight: 600; font-size: 14px">{{ u.nickname }}</div>
              <div style="font-size: 12px; color: #999">{{ u.signature }}</div>
            </div>
          </div>
        </div>
        <div v-else class="empty-tip">暂无数据</div>
      </el-dialog>

      <!-- 该用户的笔记 -->
      <div v-if="notes.length" class="note-grid">
        <NoteCard v-for="note in notes" :key="note.id" :note="note" />
      </div>
      <div v-else class="empty-tip">还没有发布笔记</div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NoteCard from '../components/NoteCard.vue'
import { getUserInfo, getUserNotes, getFollows, getFans, followUser, unfollowUser } from '../api'
import { currentUser, isLoggedIn } from '../utils/user'

const route = useRoute()
const router = useRouter()
const userId = computed(() => route.params.id)

const user = ref(null)
const notes = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const userList = ref([])

const isSelf = computed(() => {
  const me = currentUser()
  return me && String(me.id) === String(userId.value)
})

async function load() {
  loading.value = true
  try {
    user.value = await getUserInfo(userId.value)
    notes.value = await getUserNotes(userId.value)
  } catch (e) {
    user.value = null
  } finally {
    loading.value = false
  }
}

async function showList(type) {
  dialogTitle.value = type === 'follows' ? '关注列表' : '粉丝列表'
  userList.value = type === 'follows'
    ? await getFollows(userId.value)
    : await getFans(userId.value)
  dialogVisible.value = true
}

async function toggleFollow() {
  if (!isLoggedIn()) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    if (user.value.followed) {
      await unfollowUser(userId.value)
      user.value.followed = false
      user.value.fansCount--
    } else {
      await followUser(userId.value)
      user.value.followed = true
      user.value.fansCount++
    }
  } catch (e) { /* 拦截器已提示 */ }
}

function goUser(id) {
  dialogVisible.value = false
  router.push(`/user/${id}`)
}

watch(userId, load)
onMounted(load)
</script>

<style scoped>
.stat-link {
  cursor: pointer;
}
.stat-link:hover {
  color: #ff2442;
}
</style>
