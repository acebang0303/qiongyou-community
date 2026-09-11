/**
 * 登录态管理（基线版：localStorage 保存用户信息）
 * 后续请求通过 X-User-Id 请求头携带用户身份
 */
const KEY = 'xhs-user'

export function currentUser() {
  const raw = localStorage.getItem(KEY)
  return raw ? JSON.parse(raw) : null
}

export function saveUser(user) {
  localStorage.setItem(KEY, JSON.stringify(user))
}

export function clearUser() {
  localStorage.removeItem(KEY)
}

export function isLoggedIn() {
  return !!currentUser()
}
