import { describe, expect, it, vi } from 'vitest'
import { MinisApiService, MinisTransportError } from '../src/client/service/MinisApiService.ts'

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

describe('MinisApiService', () => {
  it('wraps Android JSON-RPC with same-origin credentials', async () => {
    const fetcher = vi.fn<FetchLike>(async () => response({ jsonrpc: '2.0', id: 'x', result: { ok: true } }))
    const service = new MinisApiService(fetcher)
    await expect(service.rpc('debug.appInfo', { compact: true })).resolves.toEqual({ ok: true })
    expect(fetcher).toHaveBeenCalledOnce()
    const [path, init] = fetcher.mock.calls[0]!
    expect(path).toBe('/api/rpc')
    expect(init?.credentials).toBe('same-origin')
    expect(JSON.parse(String(init?.body))).toMatchObject({ method: 'debug.appInfo', params: { compact: true } })
  })

  it('surfaces structured RPC errors', async () => {
    const fetcher = vi.fn<FetchLike>(async () => response({ error: { message: 'denied' } }))
    const service = new MinisApiService(fetcher)
    await expect(service.rpc('debug.screenshot')).rejects.toThrow('denied')
  })

  it('classifies auth expiry and malformed JSON', async () => {
    const expired = new MinisApiService(vi.fn<FetchLike>(async () => response({ error: 'no' }, 401)))
    await expect(expired.request('/api/status')).rejects.toMatchObject({ status: 401 } satisfies Partial<MinisTransportError>)

    const malformed = new MinisApiService(vi.fn<FetchLike>(async () => new Response('not-json')))
    await expect(malformed.request('/api/status')).rejects.toThrow('无效 JSON')
  })
})
