import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@deepseek-ai/dsh-client-runtime/client', () => ({
  createSnapshotStore<T>(initial: T) {
    let state = structuredClone(initial)
    const listeners = new Set<() => void>()
    return {
      getSnapshot: () => state,
      subscribe: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener) },
      update: (mutator: (draft: T) => void) => {
        const next = structuredClone(state)
        mutator(next)
        state = next
        for (const listener of listeners) listener()
      },
      set: (next: T) => { state = next; for (const listener of listeners) listener() },
    }
  },
}))

import type { JsonObject, JsonValue } from '../src/client/contract/types.ts'
import { MinisApiService } from '../src/client/service/MinisApiService.ts'
import { MinisSettingsController } from '../src/client/store/MinisSettingsController.ts'
import { NAVIGATION } from '../src/client/settings/navigation.ts'

class FakeApi extends MinisApiService {
  readonly rpcCalls: string[] = []
  rpcImpl: (method: string) => Promise<JsonValue> = async () => ({})
  requestImpl: (path: string) => Promise<JsonValue> = async () => ({})

  constructor() { super(vi.fn() as unknown as typeof fetch) }

  override rpc(method: string): Promise<JsonValue> {
    this.rpcCalls.push(method)
    return this.rpcImpl(method)
  }

  override request(path: string): Promise<JsonValue> {
    return this.requestImpl(path)
  }
}

afterEach(() => vi.useRealTimers())

describe('MinisSettingsController', () => {
  it('retains all migrated pages', () => {
    expect(NAVIGATION.map(row => row.id)).toEqual([
      'overview', 'providers', 'skills', 'mcp', 'memory', 'system',
      'scheduled', 'agent', 'web', 'device', 'diagnostics', 'advanced',
    ])
  })

  it('drops a stale response after a concurrent refresh', async () => {
    const api = new FakeApi()
    let resolveFirst: ((value: JsonValue) => void) | undefined
    let resolveSecond: ((value: JsonValue) => void) | undefined
    let call = 0
    api.rpcImpl = async method => {
      expect(method).toBe('rpc.discover')
      call += 1
      return new Promise<JsonValue>(resolve => {
        if (call === 1) resolveFirst = resolve
        else resolveSecond = resolve
      })
    }
    const controller = new MinisSettingsController(api)
    const first = controller.load('advanced')
    const second = controller.load('advanced')
    resolveSecond?.({ methods: [{ name: 'new' }] })
    await second
    resolveFirst?.({ methods: [{ name: 'old' }] })
    await first
    const discover = controller.store.getSnapshot().pages.advanced?.discover as JsonObject
    expect((discover.methods as JsonObject[])[0]?.name).toBe('new')
    controller.dispose()
  })

  it('owns and disposes its polling timer', async () => {
    vi.useFakeTimers()
    const api = new FakeApi()
    api.rpcImpl = async () => ({})
    api.requestImpl = async () => ({})
    const controller = new MinisSettingsController(api)
    const release = controller.activate()
    await vi.runAllTicks()
    expect(vi.getTimerCount()).toBeGreaterThan(0)
    release()
    expect(vi.getTimerCount()).toBe(0)
    controller.dispose()
  })
})
