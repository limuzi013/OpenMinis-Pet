import type { TabId } from '../contract/types.ts'

/** Stable navigation roster; ids match the original Minis console pages. */
export const NAVIGATION: ReadonlyArray<{ id: TabId; label: string }> = [
  { id: 'overview', label: '概览' },
  { id: 'providers', label: '供应商与模型' },
  { id: 'skills', label: 'Skills' },
  { id: 'mcp', label: 'MCP' },
  { id: 'memory', label: '记忆与 SOUL' },
  { id: 'system', label: '环境与存储' },
  { id: 'scheduled', label: '定时任务' },
  { id: 'agent', label: 'Agent' },
  { id: 'web', label: 'Web 远程' },
  { id: 'device', label: '设备' },
  { id: 'diagnostics', label: '诊断' },
  { id: 'advanced', label: '高级操作' },
]

export function navigationLabel(id: TabId): string {
  return NAVIGATION.find(row => row.id === id)?.label ?? 'Minis 控制台'
}
