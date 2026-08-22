import type { JsonObject, JsonValue } from '../contract/types.ts'
import { objectOf, textOf } from '../contract/types.ts'

/** Transport failure returned before an Android business response exists. */
export class MinisTransportError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'MinisTransportError'
  }
}

/** Authenticated Android JSON-RPC transport, intentionally React-free. */
export class MinisApiService {
  constructor(
    private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis),
    private readonly rpcPath = '/api/rpc',
  ) {}

  /** Issue one same-origin JSON request and validate that JSON was returned. */
  async request(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<JsonValue> {
    const response = await this.fetcher(path, {
      credentials: 'same-origin',
      ...init,
      ...(signal === undefined ? {} : { signal }),
    })
    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new MinisTransportError(`服务器返回了无效 JSON（HTTP ${response.status}）`, response.status)
    }
    if (response.status === 401) {
      throw new MinisTransportError('登录已过期，请重新登录', 401)
    }
    if (!response.ok) {
      const object = objectOf(body)
      throw new MinisTransportError(
        textOf(object.error, `请求失败（HTTP ${response.status}）`),
        response.status,
      )
    }
    return body as JsonValue
  }

  /** Call an Android management RPC without exposing its HTTP path to React. */
  async rpc(method: string, params: JsonObject = {}, signal?: AbortSignal): Promise<JsonValue> {
    const envelope = objectOf(await this.request(this.rpcPath, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: this.rpcId(),
        method,
        params,
      }),
    }, signal))
    const error = objectOf(envelope.error)
    if (Object.keys(error).length > 0) {
      throw new MinisTransportError(textOf(error.message, `${method} 执行失败`))
    }
    if (!Object.prototype.hasOwnProperty.call(envelope, 'result')) {
      throw new MinisTransportError(`${method} 返回缺少 result`)
    }
    return envelope.result ?? null
  }

  /** POST one Android HTTP control action through the same authenticated seam. */
  post(path: string, body: JsonObject = {}, signal?: AbortSignal): Promise<JsonValue> {
    return this.request(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }, signal)
  }

  /** PATCH one Android HTTP settings document through the same authenticated seam. */
  patch(path: string, body: JsonObject, signal?: AbortSignal): Promise<JsonValue> {
    return this.request(path, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }, signal)
  }

  private rpcId(): string {
    return globalThis.crypto?.randomUUID?.()
      ?? `minis_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`
  }
}
