import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'

afterEach(() => vi.unstubAllGlobals())
describe('API session client', () => {
  it('accepts empty successful creation responses and sends CSRF', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ headerName:'X-CSRF-TOKEN',token:'test-token' })))
      .mockResolvedValueOnce(new Response(null,{status:201}))
    vi.stubGlobal('fetch',fetcher)
    await expect(api('/groups',{method:'POST'})).resolves.toBeUndefined()
    const request = fetcher.mock.calls[1]![1] as RequestInit
    expect(new Headers(request.headers).get('X-CSRF-TOKEN')).toBe('test-token')
    expect(request.credentials).toBe('same-origin')
  })
  it('returns data and preserves authentication status for expired sessions', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response('[{"id":1}]'))
      .mockResolvedValueOnce(new Response(null,{status:401})))
    await expect(api('/tasks')).resolves.toEqual([{id:1}])
    await expect(api('/auth/me')).rejects.toMatchObject({status:401})
  })
  it('shows a retry interval on throttled login', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(null,{status:429,headers:{'Retry-After':'120'}})))
    await expect(api('/limited')).rejects.toThrow('120 秒')
  })
})
