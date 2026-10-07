import axios from 'axios'
import { ElMessage } from 'element-plus'
import { currentToken, clearUser } from '../utils/user'

/**
 * Axios 实例：
 * - 请求拦截：自动携带 Authorization: Bearer <token>
 * - 响应拦截：统一解包 { code, msg, data }，业务失败弹提示
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(config => {
  const token = currentToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(res)
    }
    return res.data
  },
  error => {
    // ★ P0-2：token 无效/过期由拦截器返回 HTTP 401，前端清理登录态
    if (error.response && error.response.status === 401) {
      clearUser()
      ElMessage.error('登录已失效，请重新登录')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request

// ---------------- 笔记 ----------------
export const getLatestNotes = (page = 1, size = 10) =>
  request.get('/notes/list', { params: { page, size } })

export const getFollowNotes = (page = 1, size = 10) =>
  request.get('/notes/follow', { params: { page, size } })

export const getHotNotes = () => request.get('/notes/hot')

// 游标分页：cursor 传上一页返回的 nextCursor，缺省表示第一页
export const searchNotes = (keyword, cursor, size = 20) =>
  request.get('/notes/search', { params: { keyword, cursor, size } })

export const getNoteDetail = id => request.get(`/notes/${id}`)

export const publishNote = data => request.post('/notes', data)

// ---------------- 互动 ----------------
export const likeNote = id => request.post(`/notes/${id}/like`)
export const unlikeNote = id => request.delete(`/notes/${id}/like`)
export const favoriteNote = id => request.post(`/notes/${id}/favorite`)
export const unfavoriteNote = id => request.delete(`/notes/${id}/favorite`)

// ---------------- 评论 ----------------
// 游标分页：lastId 为上一页最后一条评论 id，缺省表示第一页
export const getComments = (noteId, lastId) =>
  request.get(`/notes/${noteId}/comments`, { params: { lastId, size: 20 } })
export const addComment = (noteId, content) =>
  request.post(`/notes/${noteId}/comments`, { content })

// ---------------- 用户 ----------------
export const login = (username, password) =>
  request.post('/users/login', { username, password })

export const getUserInfo = id => request.get(`/users/${id}`)
export const getUserNotes = id => request.get(`/users/${id}/notes`)
export const getFollows = id => request.get(`/users/${id}/follows`)
export const getFans = id => request.get(`/users/${id}/fans`)
export const followUser = id => request.post(`/users/${id}/follow`)
export const unfollowUser = id => request.delete(`/users/${id}/follow`)
