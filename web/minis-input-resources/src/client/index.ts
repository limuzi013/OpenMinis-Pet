/**
 * OpenMinis `@` resource mention source (P1-1).
 *
 * The stock `@deepseek-ai/dsh-client-ui-subagent` only proposes running child
 * subagents, so typing `@` shows nothing when the session has none. This
 * plugin adds a second `@` source ("resources") that proposes real backend
 * state instead of faking subagent entries:
 *
 *   - files/folders under the session workspace (`resources/list` → real
 *     directory walk on the Android host);
 *   - historical sessions (`resources/list` → real session list).
 *
 * Picking a candidate inserts a *structured reference* (not plain text): the
 * composer serializes it through `codec.serialize` at submit time into
 * `<<minis-file:…>>` / `<<minis-session:…>>`, and the Android host expands
 * those markers into real context (file body / session tail) before the
 * prompt reaches the LLM. Unreadable references keep their marker and surface
 * as a readable hint instead of silently dropping the user's request.
 *
 * The plugin rides `ctx.connection.rpc` (generic Connection RPC over
 * `POST /api/resources/list`) — the same slash-endpoint transport the bundled
 * dsh-api-remotes use; no DOM bridge, no second data source.
 */

/** Minimal shape of the generic Connection RPC caller. */
interface RpcResult<T = unknown> {
  ok: boolean
  value?: T
  error?: { code: string; message: string }
}

interface RpcCaller {
  call(channel: string, endpoint: string, payload: unknown): Promise<RpcResult>
}

interface ConnectionService {
  rpc: RpcCaller
}

interface InputTrigger {
  registerSource(source: Record<string, unknown>): () => void
}

interface SessionProjection {
  sessionId: string
}

interface CandidatesRequest {
  query: string
  position: string
  signal: AbortSignal
}

interface ResourceEntry {
  name: string
  path: string
  kind: 'file' | 'dir'
}

interface SessionEntry {
  sessionId: string
  title: string
}

interface ResourcesValue {
  files: ResourceEntry[]
  sessions: SessionEntry[]
}

interface Candidate {
  id: string
  name: string
  description?: string
  icon?: string
  ref: string
}

const FILE_PREFIX = 'file:'
const SESSION_PREFIX = 'session:'

export const inject = ['inputTriggers', 'connection']

export function apply(ctx: unknown): void {
  const scope = ctx as {
    get<T>(name: string): T | undefined
    effect(fn: () => void | (() => void), label?: string): void
  }
  const inputTriggers = scope.get<InputTrigger>('inputTriggers')
  const connection = scope.get<ConnectionService>('connection')
  if (inputTriggers === undefined || connection === undefined) {
    // Both services are part of the stock runtime; if they are absent this
    // plugin has nothing to hook and must not crash the host.
    return
  }
  const rpc = connection.rpc

  const fetchResources = async (
    session: SessionProjection,
    query: string,
    signal: AbortSignal,
  ): Promise<Candidate[]> => {
    const carried = await rpc.call('/api', 'resources/list', {
      args: { agentId: session.sessionId, query },
    })
    signal.throwIfAborted?.()
    if (!carried.ok || carried.value === undefined) return []
    const value = carried.value as ResourcesValue
    const candidates: Candidate[] = []
    for (const entry of value.files ?? []) {
      candidates.push({
        id: `${FILE_PREFIX}${entry.path}`,
        name: entry.name,
        description: entry.kind === 'dir' ? `文件夹 · ${entry.path}` : `文件 · ${entry.path}`,
        icon: entry.kind === 'dir' ? '📁' : '📄',
        ref: `${FILE_PREFIX}${entry.path}`,
      })
    }
    for (const entry of value.sessions ?? []) {
      candidates.push({
        id: `${SESSION_PREFIX}${entry.sessionId}`,
        name: entry.title,
        description: `历史会话 · ${entry.sessionId.slice(0, 8)}`,
        icon: '💬',
        ref: `${SESSION_PREFIX}${entry.sessionId}`,
      })
    }
    // Local fuzzy filter on top of the host's exact-name filter.
    const needle = query.toLowerCase()
    return (needle.length === 0
      ? candidates
      : candidates.filter(c => c.name.toLowerCase().includes(needle) ||
          (c.description ?? '').toLowerCase().includes(needle))
    ).slice(0, 60)
  }

  const source = {
    trigger: '@',
    name: 'resources',
    order: 1,
    async candidates(session: SessionProjection, req: CandidatesRequest) {
      return await fetchResources(session, req.query, req.signal)
    },
    warm(session: SessionProjection) {
      fetchResources(session, '', new AbortController().signal).catch(() => {})
    },
    onPick(pick: { candidate: Candidate }) {
      const ref = pick.candidate.ref
      return {
        insert: {
          source: 'resources',
          ref,
        },
      }
    },
    codec: {
      clipboardText: (ref: string) => ref,
      serialize: (ref: string) => {
        if (ref.startsWith(FILE_PREFIX)) {
          return Promise.resolve(`<<minis-file:${ref.slice(FILE_PREFIX.length)}>>`)
        }
        if (ref.startsWith(SESSION_PREFIX)) {
          return Promise.resolve(`<<minis-session:${ref.slice(SESSION_PREFIX.length)}>>`)
        }
        return Promise.resolve(ref)
      },
    },
  }

  scope.effect(() => inputTriggers.registerSource(source), 'openminis: resources @ source')
}

export { FILE_PREFIX, SESSION_PREFIX }
