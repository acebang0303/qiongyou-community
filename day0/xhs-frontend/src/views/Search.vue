<template>
  <div v-loading="loading">
    <h3 v-if="kw" style="margin-bottom: 16px">
      "{{ kw }}" 的搜索结果（共 {{ notes.length }} 篇）
    </h3>

    <div v-if="notes.length" class="note-grid">
      <NoteCard v-for="note in notes" :key="note.id" :note="note" />
    </div>
    <div v-else-if="!loading" class="empty-tip">没有找到相关笔记</div>
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
const loading = ref(false)

async function load() {
  if (!kw.value) {
    notes.value = []
    return
  }
  loading.value = true
  try {
    notes.value = await searchNotes(kw.value, 1, 20)
  } catch (e) {
    notes.value = []
  } finally {
    loading.value = false
  }
}

watch(kw, load)
onMounted(load)
</script>
