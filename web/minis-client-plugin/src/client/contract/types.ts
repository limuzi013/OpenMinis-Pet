/** JSON values accepted by the Android RPC bridge. */
export type JsonPrimitive = string | number | boolean | null
export type JsonValue = JsonPrimitive | JsonObject | JsonValue[]
export interface JsonObject { [key: string]: JsonValue }

/** Settings pages retained from the pre-React Minis console. */
export const TAB_IDS = [
  'overview', 'providers', 'skills', 'mcp', 'memory', 'system',
  'scheduled', 'agent', 'web', 'device', 'diagnostics', 'advanced',
] as const

export type TabId = (typeof TAB_IDS)[number]

/** One command issued by a presentation component to the controller. */
export interface MinisCommand {
  kind: string
  payload?: JsonObject
}

/** Toast projection owned by the controller rather than by a component timer. */
export interface ToastState {
  message: string
  error: boolean
  nonce: number
}

/** Immutable snapshot projected by the settings component. */
export interface MinisSettingsState {
  tab: TabId
  phase: 'idle' | 'loading' | 'ready' | 'error'
  busy: boolean
  error?: string
  pages: Partial<Record<TabId, JsonObject>>
  toast?: ToastState
}

/** Convert unknown wire data to a JSON object without granting it a prototype API. */
export function objectOf(value: unknown): JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as JsonObject
    : {}
}

/** Convert an unknown wire value to an array. */
export function arrayOf(value: unknown): JsonValue[] {
  return Array.isArray(value) ? value as JsonValue[] : []
}

/** Convert an unknown wire value to an object array. */
export function objectsOf(value: unknown): JsonObject[] {
  return arrayOf(value).map(objectOf)
}

/** String projection with a deterministic fallback. */
export function textOf(value: unknown, fallback = ''): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return fallback
}

/** Finite-number projection with a deterministic fallback. */
export function numberOf(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/** Boolean projection with a deterministic fallback. */
export function booleanOf(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback
}

/** Deeply clone a JSON-compatible value for immutable controller updates. */
export function cloneJson<T extends JsonValue>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
