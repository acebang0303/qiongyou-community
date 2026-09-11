<template>
  <div>
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="推荐" name="latest" />
      <el-tab-pane label="关注" name="follow" />
      <el-tab-pane label="热门" name="hot" />
    </el-tabs>

    <div v-loading="loading">
      <div v-if="notes.length" class="note-grid">
        <NoteCard v-for="note in notes" :key="note.id" :note="note" />
      </div>
      <div v-else-if="!loading" class="empty-tip">
        {{ activeTab === 'follow' ? '请先登录，或关注更多博主后查看' : '暂无内容' }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import NoteCard from '../components/NoteCard.vue'
import { getLatestNotes, getFollowNotes, getHotNotes } from '../api'
import { isLoggedIn } from '../utils/user'

const activeTab = ref('latest')
const notes = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    if (activeTab.value === 'latest') {
      notes.value = await getLatestNotes(1, 20)
    } else if (activeTab.value === 'hot') {
      notes.value = await getHotNotes()
    } else {
      notes.value = isLoggedIn() ? await getFollowNotes(1, 20) : []
    }
  } catch (e) {
    notes.value = []
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  load()
}

onMounted(load)
</script>
