<template>
  <el-card style="max-width: 720px; margin: 0 auto; border-radius: 12px">
    <h2 style="margin-bottom: 24px">发布笔记</h2>
    <el-form label-width="70px">
      <el-form-item label="标题">
        <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="填写标题，让更多人看到你" />
      </el-form-item>
      <el-form-item label="正文">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="8"
          maxlength="2000"
          show-word-limit
          placeholder="分享你的生活经验..."
        />
      </el-form-item>
      <el-form-item label="标签">
        <el-input v-model="form.tags" placeholder="多个标签用逗号分隔，如：三亚,旅游,攻略" />
      </el-form-item>
      <el-form-item>
        <el-button type="danger" :loading="loading" @click="submit">发布</el-button>
        <el-button @click="$router.push('/')">取消</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { publishNote } from '../api'

const router = useRouter()
const loading = ref(false)
const form = reactive({ title: '', content: '', tags: '' })

async function submit() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('标题和正文不能为空')
    return
  }
  loading.value = true
  try {
    const id = await publishNote(form)
    ElMessage.success('发布成功')
    router.push(`/note/${id}`)
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}
</script>
