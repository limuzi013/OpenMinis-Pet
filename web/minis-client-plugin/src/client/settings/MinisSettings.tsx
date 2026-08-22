import { useEffect } from 'react'
import type { InjectFace, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type { MinisCommand, MinisSettingsState, TabId } from '../contract/types.ts'
import type { MinisSettingsController } from '../store/MinisSettingsController.ts'
import { NAVIGATION, navigationLabel } from './navigation.ts'
import { OverviewPage } from './pages/OverviewPage.tsx'
import { ProvidersPage } from './pages/ProvidersPage.tsx'
import { SkillsPage } from './pages/SkillsPage.tsx'
import { McpPage } from './pages/McpPage.tsx'
import { MemoryPage } from './pages/MemoryPage.tsx'
import { SystemPage } from './pages/SystemPage.tsx'
import { ScheduledPage } from './pages/ScheduledPage.tsx'
import { AgentPage } from './pages/AgentPage.tsx'
import { WebPage } from './pages/WebPage.tsx'
import { DevicePage } from './pages/DevicePage.tsx'
import { DiagnosticsPage } from './pages/DiagnosticsPage.tsx'
import { AdvancedPage } from './pages/AdvancedPage.tsx'
import styles from './MinisSettings.module.css'

/** Narrow business face injected by the plugin's apply closure. */
export interface MinisSettingsInjected {
  hooks: {
    /** Controller snapshot bound by ui-renderer as useSnapshot. */
    snapshot: MinisSettingsController['store']
  }
  activate: () => () => void
  selectTab: (tab: TabId) => void
  refresh: () => void
  run: (command: MinisCommand) => Promise<void>
}

export type MinisSettingsProps = PropsRuntime<'settings.section'> & InjectFace<MinisSettingsInjected>

function Page({ state, run }: { state: MinisSettingsState; run: (command: MinisCommand) => void }) {
  const data = state.pages[state.tab] ?? {}
  const common = { data, busy: state.busy, run }
  switch (state.tab) {
    case 'overview': return <OverviewPage data={data} />
    case 'providers': return <ProvidersPage {...common} />
    case 'skills': return <SkillsPage {...common} />
    case 'mcp': return <McpPage {...common} />
    case 'memory': return <MemoryPage {...common} />
    case 'system': return <SystemPage {...common} />
    case 'scheduled': return <ScheduledPage {...common} />
    case 'agent': return <AgentPage {...common} />
    case 'web': return <WebPage {...common} />
    case 'device': return <DevicePage {...common} />
    case 'diagnostics': return <DiagnosticsPage {...common} />
    case 'advanced': return <AdvancedPage {...common} />
  }
}

/** React projection of Android-authoritative Minis settings. */
export function MinisSettings(props: MinisSettingsProps) {
  const state = props.useSnapshot(value => value)
  useEffect(() => props.activate(), [props.activate])
  const run = (command: MinisCommand): void => { void props.run(command) }
  return (
    <section className={styles.root} aria-labelledby="openminis-settings-title">
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h2 id="openminis-settings-title">{navigationLabel(state.tab)}</h2>
          <p>与 Android App 共用同一份设置</p>
        </div>
        <span className={styles.spacer} />
        <a className={styles.repo} href="https://github.com/limuzi013/OpenMinis-Pet" target="_blank" rel="noopener noreferrer">项目与反馈</a>
        <button type="button" className={styles.iconButton} disabled={state.busy} onClick={props.refresh} title="刷新" aria-label="刷新">↻</button>
      </header>
      <select className={`${styles.select} ${styles.mobileNavigation}`} aria-label="控制台页面" value={state.tab} onChange={event => props.selectTab(event.currentTarget.value as TabId)}>
        {NAVIGATION.map(row => <option key={row.id} value={row.id}>{row.label}</option>)}
      </select>
      <nav className={styles.navigation} aria-label="Minis 控制台">
        {NAVIGATION.map(row => (
          <button key={row.id} type="button" className={`${styles.navButton} ${state.tab === row.id ? styles.navActive : ''}`} aria-current={state.tab === row.id ? 'page' : undefined} onClick={() => props.selectTab(row.id)}>{row.label}</button>
        ))}
      </nav>
      <div className={styles.content}>
        {state.phase === 'loading' && state.pages[state.tab] === undefined ? <div className={styles.loading}>正在从手机读取…</div> : null}
        {state.phase === 'error' && state.pages[state.tab] === undefined ? <div className={styles.errorState}><p>{state.error}</p><button type="button" className={styles.button} onClick={props.refresh}>重试</button></div> : null}
        {state.pages[state.tab] === undefined ? null : <Page state={state} run={run} />}
      </div>
      {state.toast === undefined ? null : <div className={`${styles.toast} ${state.toast.error ? styles.toastError : ''}`} role={state.toast.error ? 'alert' : 'status'} aria-live="polite">{state.toast.message}</div>}
    </section>
  )
}
