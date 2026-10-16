export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.method && options.method !== 'GET') {
    const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
    if (!response.ok) throw new ApiError(response.status, '无法验证会话，请刷新后重试')
    const csrf = await response.json()
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch('/api/v1' + path, { ...options, headers, credentials: 'same-origin' })
  if (!response.ok) {
    let message = response.status === 401 ? '账号或密码错误，或登录已失效' :
      response.status === 403 ? '没有操作权限，请刷新后重试' :
      response.status === 429 ? `尝试过于频繁，请 ${response.headers.get('Retry-After') || '稍后'} 秒后重试` : '操作失败，请稍后重试'
    const body = await response.json().catch(() => null)
    if (body?.message) message = body.message
    throw new ApiError(response.status, message)
  }
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}
export function jsonBody(method: string, data: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }
}
