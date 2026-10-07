/**
 * 登录态管理：localStorage 保存 token 与用户信息
 * P0-2：后续请求通过 Authorization: Bearer <token> 携带身份，不再使用 X-User-Id
 */
const USER_KEY = 'qiongyou-user'
const TOKEN_KEY = 'qiongyou-token'

export function currentUser() {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : null
}

export function currentToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function saveUser(user, token) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  }
}

export function clearUser() {
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(TOKEN_KEY)
}

export function isLoggedIn() {
  return !!currentToken()
}
