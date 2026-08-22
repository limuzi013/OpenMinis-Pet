import { createSnapshotStore, type SnapshotStore } from '@deepseek-ai/dsh-client-runtime/client'
import type {
  JsonObject, JsonValue, MinisCommand, MinisSettingsState, TabId,
} from '../contract/types.ts'
import {
  arrayOf, booleanOf, cloneJson, numberOf, objectOf, textOf,
} from '../contract/types.ts'
import { MinisApiService } from '../service/MinisApiService.ts'

const POLL_INTERVAL_MS = 8_000
const TOAST_DURATION_MS = 4_200

/** Android-authoritative page controller and lifecycle-owned polling fallback. */
export class MinisSettingsController {
  readonly store: SnapshotStore<MinisSettingsState> = createSnapshotStore<MinisSettingsState>({
    tab: 'overview',
    phase: 'idle',
    busy: false,
    pages: {},
  })

  private readonly generations = new Map<TabId, number>()
  private readonly requests = new Map<TabId, AbortController>()
  private retainCount = 0
  private pollTimer: ReturnType<typeof setTimeout> | undefined
  private toastTimer: ReturnType<typeof setTimeout> | undefined
  private disposed = false
  private toastNonce = 0

  constructor(readonly api: MinisApiService) {}

  /** Retain active page synchronization; the returned disposer is idempotent. */
  activate(): () => void {
    if (this.disposed) return () => {}
    this.retainCount += 1
    if (this.retainCount === 1) {
      if (this.store.getSnapshot().phase === 'idle') void this.load(this.store.getSnapshot().tab)
      this.schedulePoll()
    }
    let active = true
    return () => {
      if (!active) return
      active = false
      this.retainCount = Math.max(0, this.retainCount - 1)
      if (this.retainCount === 0) this.stopPoll()
    }
  }

  /** Tear down timers, listeners, and pending requests on plugin unload. */
  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.stopPoll()
    if (this.toastTimer !== undefined) clearTimeout(this.toastTimer)
    for (const request of this.requests.values()) request.abort()
    this.requests.clear()
  }

  selectTab(tab: TabId): void {
    if (this.store.getSnapshot().tab === tab) return
    this.store.update((draft) => {
      draft.tab = tab
      delete draft.error
    })
    void this.load(tab)
  }

  refresh(): void {
    void this.load(this.store.getSnapshot().tab, true)
  }

  /** Run a named mutation; components never see fetch, connection, or ctx. */
  async run(command: MinisCommand): Promise<void> {
    if (this.disposed || this.store.getSnapshot().busy) return
    this.store.update((draft) => { draft.busy = true })
    let refresh = true
    let message = '已完成'
    try {
      const p = command.payload ?? {}
      switch (command.kind) {
        case 'provider-models': {
          const instanceId = textOf(p.instanceId)
          const models = await this.api.rpc('provider.models.list', { instanceId })
          this.updatePage('providers', page => {
            const current = objectOf(page.models)
            page.models = { ...current, [instanceId]: models }
          })
          refresh = false
          message = '已读取模型'
          break
        }
        case 'provider-test': {
          const result = objectOf(await this.api.rpc('provider.instances.test', p))
          if (!booleanOf(result.ok)) throw new Error(textOf(objectOf(result.error).message, '连接测试失败'))
          refresh = false
          message = '连接测试成功'
          break
        }
        case 'provider-toggle': await this.api.rpc('provider.instances.update', p); break
        case 'provider-delete': await this.api.rpc('provider.instances.delete', { ...p, confirm: true }); break
        case 'provider-create': await this.api.rpc('provider.instances.create', p); break
        case 'provider-update': await this.api.rpc('provider.instances.update', p); break
        case 'model-add': await this.api.rpc('provider.models.add', p); break
        case 'model-delete': await this.api.rpc('provider.models.delete', { ...p, confirm: true }); break
        case 'model-loop': await this.api.rpc('provider.models.setAgentLoop', p); break
        case 'group-create': await this.api.rpc('provider.groups.create', p); break
        case 'group-default': await this.api.rpc('provider.groups.setDefault', p); break
        case 'group-sub-default': await this.api.rpc('provider.groups.setSubDefault', p); break
        case 'group-delete': await this.api.rpc('provider.groups.delete', { ...p, confirm: true }); break

        case 'skill-toggle': await this.api.rpc('skills.toggle', p); break
        case 'skill-delete': await this.api.rpc('skills.delete', p); break
        case 'skill-import-url': await this.api.rpc('skills.importUrl', p); message = 'Skill 已从链接导入'; break
        case 'skill-create': await this.api.rpc('skills.create', p); break
        case 'skill-update': await this.api.rpc('skills.update', p); break
        case 'skill-edit': {
          const editor = await this.api.rpc('skills.get', p)
          this.updatePage('skills', page => { page.editor = editor })
          refresh = false
          message = '已载入'
          break
        }
        case 'skill-cancel': this.deletePageKey('skills', 'editor'); refresh = false; message = '已取消'; break

        case 'mcp-toggle': await this.api.rpc('mcp.toggle', p); break
        case 'mcp-delete': await this.api.rpc('mcp.delete', p); break
        case 'mcp-create': await this.api.rpc('mcp.create', p); break
        case 'mcp-import-url': await this.api.rpc('mcp.importUrl', p); message = 'MCP 配置已从链接导入'; break
        case 'mcp-import-json': await this.api.rpc('mcp.import', p); message = 'MCP JSON 已导入'; break

        case 'memory-global': await this.api.rpc('memory.setGlobalEnabled', p); break
        case 'memory-edit': {
          const editor = await this.api.rpc('memory.files.read', p)
          this.updatePage('memory', page => { page.editor = editor })
          refresh = false
          message = '已载入'
          break
        }
        case 'memory-cancel': this.deletePageKey('memory', 'editor'); refresh = false; message = '已取消'; break
        case 'memory-delete': await this.api.rpc('memory.files.delete', p); break
        case 'memory-save': await this.api.rpc('memory.files.write', p); break
        case 'soul-save': await this.api.rpc('soul.save', p); break

        case 'capability-toggle': {
          await this.api.rpc('settings.capabilities.set', p)
          const caps = await this.api.rpc('settings.capabilities.get')
          this.updatePage('system', page => { page.caps = caps })
          refresh = false
          message = '能力已更新（两端同步）'
          break
        }
        case 'permission-set': {
          await this.api.rpc('settings.permissionPreset.set', p)
          break
        }
        case 'env-create': await this.api.rpc('environments.create', p); break
        case 'env-update': await this.api.rpc('environments.update', p); break
        case 'env-delete': await this.api.rpc('environments.delete', p); break
        case 'mount-rename': await this.api.rpc('storage.mounts.rename', p); break
        case 'mount-write': await this.api.rpc('storage.mounts.setWritable', p); break
        case 'mount-remove': await this.api.rpc('storage.mounts.remove', { ...p, confirm: true }); break

        case 'task-edit': {
          const response = objectOf(await this.api.rpc('scheduled.get', p))
          this.updatePage('scheduled', page => { page.editor = response.task ?? null })
          refresh = false
          message = '已载入'
          break
        }
        case 'task-cancel': this.deletePageKey('scheduled', 'editor'); refresh = false; message = '已取消'; break
        case 'task-create': await this.api.rpc('scheduled.create', p); break
        case 'task-update': await this.api.rpc('scheduled.update', p); break
        case 'task-run': await this.api.rpc('scheduled.run', p); message = '任务已交给 Android 后台运行'; break
        case 'task-toggle': await this.api.rpc('scheduled.toggle', p); break
        case 'task-delete': await this.api.rpc('scheduled.delete', p); break

        case 'agent-settings': await this.api.rpc('agent.settings.set', p); break
        case 'job-cancel': await this.api.rpc('agent.jobs.cancel', p); break
        case 'approval-answer': await this.api.rpc('agent.approval.answer', p); break
        case 'question-answer': await this.api.rpc('chat.question.answer', p); break

        case 'web-service': await this.api.patch('/api/settings', p); break
        case 'web-identity': await this.api.patch('/api/settings', p); message = '账号已更新，请重新登录'; break
        case 'web-tunnel': await this.api.patch('/api/settings', p); break
        case 'web-restart': await this.api.post('/api/settings/restart'); refresh = false; message = '正在重启'; break
        case 'logout':
          await this.api.post('/api/auth/logout')
          refresh = false
          message = '已退出'
          if (typeof location !== 'undefined') location.reload()
          break

        case 'device-shot': {
          const shot = await this.api.rpc('debug.screenshot', { scale: 0.4 })
          this.updatePage('device', page => {
            page.shot = shot
            page.x = 0
            page.y = 0
          })
          refresh = false
          message = '截图已刷新'
          break
        }
        case 'device-point':
          this.updatePage('device', page => { page.x = numberOf(p.x); page.y = numberOf(p.y) })
          refresh = false
          message = `坐标已记录：${numberOf(p.x)}, ${numberOf(p.y)}`
          break
        case 'device-tapmode':
          this.updatePage('device', page => { page.tapMode = booleanOf(p.enabled) })
          refresh = false
          message = booleanOf(p.enabled) ? '点击模式已开启' : '点击模式已关闭'
          break
        case 'device-tap': await this.api.rpc('debug.tap', p); refresh = false; message = '已发送点击'; break
        case 'device-scroll': await this.api.rpc('debug.scroll', p); refresh = false; message = '已发送滚动'; break
        case 'device-input': await this.api.rpc('debug.inputText', p); refresh = false; message = '已发送文本'; break

        case 'log-read': {
          const data = objectOf(await this.api.rpc('debug.logs.read', p))
          this.updatePage('diagnostics', page => {
            page.viewer = {
              title: `日志：${textOf(data.name)}`,
              content: textOf(data.content),
              bytes: numberOf(data.bytesRead),
              modified: data.modified ?? null,
              truncated: booleanOf(data.truncated),
            }
          })
          refresh = false
          message = '已读取日志'
          break
        }
        case 'crash-read': {
          const data = objectOf(await this.api.rpc('debug.crash.read', p))
          this.updatePage('diagnostics', page => {
            page.viewer = {
              title: `崩溃：${textOf(data.name)}`,
              content: textOf(data.content),
              bytes: textOf(data.content).length,
              modified: data.modified ?? null,
              truncated: booleanOf(data.truncated),
            }
          })
          refresh = false
          message = '已读取崩溃报告'
          break
        }
        case 'rpc-run': {
          const result = await this.api.rpc(textOf(p.method), objectOf(p.params))
          this.updatePage('advanced', page => {
            page.method = textOf(p.method)
            page.params = p.params ?? {}
            page.result = result
          })
          refresh = false
          message = 'RPC 已执行'
          break
        }
        default: throw new Error(`未知 Minis 操作：${command.kind}`)
      }
      this.showToast(message, false)
      if (refresh) await this.load(this.store.getSnapshot().tab, true)
    } catch (error) {
      this.showToast(error instanceof Error ? error.message : String(error), true)
      if (this.isAuthFailure(error) && typeof location !== 'undefined') {
        setTimeout(() => location.reload(), 250)
      }
    } finally {
      if (!this.disposed) this.store.update((draft) => { draft.busy = false })
    }
  }

  /** Pull one page from Android. A per-page generation fence drops stale answers. */
  async load(tab: TabId, silent = false): Promise<void> {
    if (this.disposed) return
    const generation = (this.generations.get(tab) ?? 0) + 1
    this.generations.set(tab, generation)
    this.requests.get(tab)?.abort()
    const request = new AbortController()
    this.requests.set(tab, request)
    if (!silent) {
      this.store.update((draft) => {
        draft.phase = 'loading'
        delete draft.error
      })
    }
    try {
      const page = await this.fetchPage(tab, request.signal)
      if (this.disposed || this.generations.get(tab) !== generation) return
      this.store.update((draft) => {
        draft.pages[tab] = page
        if (draft.tab === tab) draft.phase = 'ready'
        delete draft.error
      })
    } catch (error) {
      if (request.signal.aborted || this.disposed || this.generations.get(tab) !== generation) return
      const message = error instanceof Error ? error.message : String(error)
      this.store.update((draft) => {
        if (draft.tab === tab) draft.phase = 'error'
        draft.error = message
      })
      if (silent) this.showToast(message, true)
    } finally {
      if (this.requests.get(tab) === request) this.requests.delete(tab)
    }
  }

  private async fetchPage(tab: TabId, signal: AbortSignal): Promise<JsonObject> {
    switch (tab) {
      case 'overview': {
        const [status, remote, appInfo, sessions] = await Promise.all([
          this.api.request('/api/status', {}, signal),
          this.api.request('/api/settings', {}, signal),
          this.api.rpc('debug.appInfo', {}, signal),
          this.api.rpc('chat.sessions.list', { limit: 20, includeEmpty: true }, signal),
        ])
        return { status, remote, appInfo, sessions }
      }
      case 'providers': {
        const previous = this.store.getSnapshot().pages.providers
        const [types, instances, groups] = await Promise.all([
          this.api.rpc('provider.types', {}, signal),
          this.api.rpc('provider.instances.list', { includeDisabled: true }, signal),
          this.api.rpc('provider.groups.list', { includeMembers: true }, signal),
        ])
        return { ...objectOf(types), ...objectOf(instances), ...objectOf(groups), models: previous?.models ?? {} }
      }
      case 'skills': {
        const page = objectOf(await this.api.rpc('skills.list', {}, signal))
        const editor = this.store.getSnapshot().pages.skills?.editor
        return editor === undefined ? page : { ...page, editor }
      }
      case 'mcp': return objectOf(await this.api.rpc('mcp.list', {}, signal))
      case 'memory': {
        const [files, global, soul] = await Promise.all([
          this.api.rpc('memory.files.list', {}, signal),
          this.api.rpc('memory.globalToggle', {}, signal),
          this.api.rpc('soul.get', {}, signal),
        ])
        const editor = this.store.getSnapshot().pages.memory?.editor
        return { ...objectOf(files), global, soul, ...(editor === undefined ? {} : { editor }) }
      }
      case 'system': {
        const [env, shared, mounts, permission, sandbox, caps] = await Promise.all([
          this.api.rpc('environments.list', {}, signal),
          this.api.rpc('storage.shared.list', {}, signal),
          this.api.rpc('storage.mounts.list', {}, signal),
          this.api.rpc('settings.permissionPreset.get', {}, signal),
          this.api.rpc('settings.sandbox.get', {}, signal),
          this.api.rpc('settings.capabilities.get', {}, signal),
        ])
        return { env, shared, mounts, permission, sandbox, caps }
      }
      case 'scheduled': {
        const page = objectOf(await this.api.rpc('scheduled.list', {}, signal))
        const editor = this.store.getSnapshot().pages.scheduled?.editor
        return editor === undefined ? page : { ...page, editor }
      }
      case 'agent': {
        const [settings, jobs, approvals, questions] = await Promise.all([
          this.api.rpc('agent.settings.get', {}, signal),
          this.api.rpc('agent.jobs.list', {}, signal),
          this.api.rpc('agent.approval.list', {}, signal),
          this.api.rpc('chat.question.pending', {}, signal),
        ])
        return { settings, jobs, approvals, questions }
      }
      case 'web': {
        const [settings, status] = await Promise.all([
          this.api.request('/api/settings', {}, signal),
          this.api.request('/api/status', {}, signal),
        ])
        return { settings, status }
      }
      case 'device': return this.store.getSnapshot().pages.device ?? { tapMode: false, x: 0, y: 0 }
      case 'diagnostics': {
        const [info, logs, crashes] = await Promise.all([
          this.api.rpc('debug.appInfo', {}, signal),
          this.api.rpc('debug.logs.list', {}, signal),
          this.api.rpc('debug.crash.list', { limit: 50 }, signal),
        ])
        const viewer = this.store.getSnapshot().pages.diagnostics?.viewer
        return { info, logs, crashes, ...(viewer === undefined ? {} : { viewer }) }
      }
      case 'advanced': {
        const existing = this.store.getSnapshot().pages.advanced
        if (existing !== undefined) return existing
        const discover = await this.api.rpc('rpc.discover', {}, signal)
        return { discover, method: '', params: {}, result: null }
      }
    }
  }

  private updatePage(tab: TabId, mutate: (draft: JsonObject) => void): void {
    this.store.update((draft) => {
      const page = cloneJson((draft.pages[tab] ?? {}) as JsonObject)
      mutate(page)
      draft.pages[tab] = page
    })
  }

  private deletePageKey(tab: TabId, key: string): void {
    this.updatePage(tab, page => { delete page[key] })
  }

  private showToast(message: string, error: boolean): void {
    if (this.disposed) return
    this.toastNonce += 1
    const nonce = this.toastNonce
    this.store.update((draft) => { draft.toast = { message, error, nonce } })
    if (this.toastTimer !== undefined) clearTimeout(this.toastTimer)
    this.toastTimer = setTimeout(() => {
      if (this.disposed) return
      this.store.update((draft) => {
        if (draft.toast?.nonce === nonce) delete draft.toast
      })
    }, TOAST_DURATION_MS)
  }

  private schedulePoll(): void {
    this.stopPoll()
    if (this.disposed || this.retainCount === 0) return
    this.pollTimer = setTimeout(async () => {
      this.pollTimer = undefined
      if (!this.disposed && this.retainCount > 0 && !this.store.getSnapshot().busy) {
        await this.load(this.store.getSnapshot().tab, true)
      }
      this.schedulePoll()
    }, POLL_INTERVAL_MS)
  }

  private stopPoll(): void {
    if (this.pollTimer !== undefined) clearTimeout(this.pollTimer)
    this.pollTimer = undefined
  }

  private isAuthFailure(error: unknown): boolean {
    return error instanceof Error && /登录已过期/.test(error.message)
  }
}

/** Helpers exported only for focused controller tests. */
export const controllerTestHelpers = {
  arrayOf,
  objectOf,
  textOf,
}
