<template>
  <div class="note-card" @click="$router.push(`/note/${note.id}`)">
    <!-- 封面：无图时按笔记ID生成渐变色块 -->
    <div class="note-cover" :style="{ background: coverGradient }">
      <el-icon size="40"><Picture /></el-icon>
    </div>
    <div class="note-card-body">
      <div class="note-card-title">{{ note.title }}</div>
      <div class="note-card-footer">
        <div class="note-card-author">
          <span class="avatar-circle">{{ avatarChar }}</span>
          <span>{{ note.authorName }}</span>
        </div>
        <span>❤ {{ note.likeCount }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Picture } from '@element-plus/icons-vue'

const props = defineProps({
  note: { type: Object, required: true }
})

// 根据笔记ID生成固定渐变色，离线环境也有封面效果
const gradients = [
  'linear-gradient(135deg, #ff9a9e, #fad0c4)',
  'linear-gradient(135deg, #a18cd1, #fbc2eb)',
  'linear-gradient(135deg, #84fab0, #8fd3f4)',
  'linear-gradient(135deg, #f6d365, #fda085)',
  'linear-gradient(135deg, #5ee7df, #b490ca)',
  'linear-gradient(135deg, #c471f5, #fa71cd)'
]
const coverGradient = computed(() => gradients[(props.note.id || 0) % gradients.length])
const avatarChar = computed(() => (props.note.authorName || '?').charAt(0))
</script>
