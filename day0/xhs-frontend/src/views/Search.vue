<template>
  <div v-loading="loading">
    <h3 v-if="kw" style="margin-bottom: 16px">
      "{{ kw }}" 的搜索结果（共 {{ notes.length }} 篇）
    </h3>

    <div v-if="notes.length" class="note-grid">
      <NoteCard v-for="note in notes" :key="note.id" :note="note" />
    </div>
    <div v-else-if="!loading" class="empty-tip">没有找到相关笔记</div>

    <div v-if="nextCursor" style="text-align: center; margin-top: 16px">
      <el-button :loading="moreLoading" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import NoteCard from '../components/NoteCard.vue'
import { searchNotes } from '../api'

const route = useRoute()
const kw = computed(() => route.query.kw || '')

const notes = ref([])
const nextCursor = ref(null)
const loading = ref(false)
const moreLoading = ref(false)

async function load() {
  if (!kw.value) {
    notes.value = []
    nextCursor.value = null
    return
  }
  loading.value = true
  try {
    const res = await searchNotes(kw.value)
    notes.value = res.list
    nextCursor.value = res.nextCursor
  } catch (e) {
    notes.value = []
    nextCursor.value = null
  } finally {
    loading.value = false
  }
}

// 用后端返回的 nextCursor 续拉下一页
async function loadMore() {
  if (!nextCursor.value) return
  moreLoading.value = true
  try {
    const res = await searchNotes(kw.value, nextCursor.value)
    notes.value = notes.value.concat(res.list)
    nextCursor.value = res.nextCursor
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    moreLoading.value = false
  }
}

watch(kw, load)
onMounted(load)
</script>
