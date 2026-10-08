<template>
  <div v-loading="loading">
    <template v-if="note">
      <div class="detail-layout">
        <div class="detail-main">
          <h1 class="detail-title">{{ note.title }}</h1>
          <div class="detail-content">{{ note.content }}</div>

          <div class="detail-tags" v-if="note.tags">
            <el-tag
              v-for="tag in note.tags.split(',')"
              :key="tag"
              type="danger"
              effect="plain"
              style="margin-right: 8px"
            >
              # {{ tag }}
            </el-tag>
          </div>

          <div class="detail-meta">发布时间：{{ note.createTime }}</div>

          <div class="detail-actions">
            <span class="action-btn" :class="{ active: note.liked }" @click="toggleLike">
              ❤ {{ note.liked ? '已点赞' : '点赞' }} {{ note.likeCount }}
            </span>
            <span class="action-btn" :class="{ active: note.favorited }" @click="toggleFavorite">
              ★ {{ note.favorited ? '已收藏' : '收藏' }} {{ note.favoriteCount }}
            </span>
            <span class="action-btn">💬 评论 {{ note.commentCount }}</span>
          </div>
        </div>

        <div class="detail-side">
          <div class="author-card">
            <span class="avatar-large">{{ (note.authorName || '?').charAt(0) }}</span>
            <div style="flex: 1; cursor: pointer" @click="goAuthor">
              <div style="font-weight: 600">{{ note.authorName }}</div>
            </div>
            <el-button
              size="small"
              :type="note.followed ? 'default' : 'danger'"
              @click="toggleFollow"
            >
              {{ note.followed ? '已关注' : '关注' }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 评论区 -->
      <div class="comment-section">
        <h3 style="margin-bottom: 16px">评论 {{ note.commentCount }}</h3>

        <div style="display: flex; gap: 12px; margin-bottom: 16px">
          <el-input
            v-model="commentText"
            placeholder="说点什么..."
            @keyup.enter="submitComment"
          />
          <el-button type="danger" :disabled="!commentText.trim()" @click="submitComment">
            发送
          </el-button>
        </div>

        <div v-if="comments.length">
          <div v-for="c in comments" :key="c.id" class="comment-item">
            <span class="avatar-circle">{{ (c.nickname || '?').charAt(0) }}</span>
            <div class="comment-body">
              <div class="comment-nickname">{{ c.nickname }}</div>
              <div class="comment-content">{{ c.content }}</div>
              <div class="comment-time">{{ c.createTime }}</div>
            </div>
          </div>
          <div v-if="hasMore" style="text-align: center; margin-top: 16px">
            <el-button :loading="moreLoading" @click="loadMore">加载更多</el-button>
          </div>
        </div>
        <div v-else class="empty-tip">还没有评论，来抢沙发~</div>
      </div>
    </template>
    <div v-else-if="!loading" class="empty-tip">笔记不存在</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getNoteDetail, getComments, addComment,
  likeNote, unlikeNote, favoriteNote, unfavoriteNote,
  followUser, unfollowUser
} from '../api'
import { isLoggedIn } from '../utils/user'

const route = useRoute()
const router = useRouter()
const noteId = route.params.id

const PAGE_SIZE = 20
const note = ref(null)
const comments = ref([])
const commentText = ref('')
const loading = ref(false)
const hasMore = ref(false)
const moreLoading = ref(false)

async function load() {
  loading.value = true
  try {
    note.value = await getNoteDetail(noteId)
    await loadFirstPage()
  } catch (e) {
    note.value = null
  } finally {
    loading.value = false
  }
}

// 拉第一页（新→旧），重置列表
async function loadFirstPage() {
  comments.value = await getComments(noteId)
  hasMore.value = comments.value.length === PAGE_SIZE
}

// 用最后一条 id 作游标追加下一页
async function loadMore() {
  const last = comments.value[comments.value.length - 1]
  if (!last) return
  moreLoading.value = true
  try {
    const more = await getComments(noteId, last.id)
    comments.value = comments.value.concat(more)
    hasMore.value = more.length === PAGE_SIZE
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    moreLoading.value = false
  }
}

function requireLogin() {
  if (!isLoggedIn()) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return false
  }
  return true
}

async function toggleLike() {
  if (!requireLogin()) return
  try {
    if (note.value.liked) {
      await unlikeNote(noteId)
      note.value.liked = false
      note.value.likeCount--
    } else {
      await likeNote(noteId)
      note.value.liked = true
      note.value.likeCount++
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function toggleFavorite() {
  if (!requireLogin()) return
  try {
    if (note.value.favorited) {
      await unfavoriteNote(noteId)
      note.value.favorited = false
      note.value.favoriteCount--
    } else {
      await favoriteNote(noteId)
      note.value.favorited = true
      note.value.favoriteCount++
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function toggleFollow() {
  if (!requireLogin()) return
  try {
    if (note.value.followed) {
      await unfollowUser(note.value.userId)
      note.value.followed = false
    } else {
      await followUser(note.value.userId)
      note.value.followed = true
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function submitComment() {
  if (!requireLogin() || !commentText.value.trim()) return
  try {
    await addComment(noteId, commentText.value)
    commentText.value = ''
    // 新评论在最前，重载首页
    await loadFirstPage()
    note.value.commentCount++
  } catch (e) { /* 拦截器已提示 */ }
}

function goAuthor() {
  router.push(`/user/${note.value.userId}`)
}

onMounted(load)
</script>
