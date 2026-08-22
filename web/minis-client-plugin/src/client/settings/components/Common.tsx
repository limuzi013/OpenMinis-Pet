import clsx from 'clsx'
import type { FormEvent, ReactNode } from 'react'
import type { JsonObject, JsonValue } from '../../contract/types.ts'
import styles from '../MinisSettings.module.css'

export function Card({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  return <section className={clsx(styles.card, wide && styles.wide)}>{children}</section>
}

export function Grid({ children }: { children: ReactNode }) {
  return <div className={styles.grid}>{children}</div>
}

export function SectionTitle({ title, meta }: { title: string; meta?: ReactNode }) {
  return <div className={styles.sectionTitle}><h3>{title}</h3>{meta === undefined ? null : <span>{meta}</span>}</div>
}

export function Note({ children, danger = false }: { children: ReactNode; danger?: boolean }) {
  return <div className={clsx(styles.note, danger && styles.dangerNote)}>{children}</div>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className={styles.empty}>{children}</div>
}

export function Badge({ on, yes = '启用', no = '停用' }: { on: boolean; yes?: string; no?: string }) {
  return <span className={clsx(styles.badge, on ? styles.badgeOk : styles.badgeOff)}>{on ? yes : no}</span>
}

export function RiskBadge({ risk, label }: { risk: string; label?: string }) {
  return (
    <span className={clsx(styles.badge, risk === 'HIGH' && styles.riskHigh, risk === 'MEDIUM' && styles.riskMedium)}>
      {label ?? risk}风险
    </span>
  )
}

export function Button({
  children, danger = false, primary = false, small = false, disabled = false, type = 'button', onClick,
}: {
  children: ReactNode
  danger?: boolean
  primary?: boolean
  small?: boolean
  disabled?: boolean
  type?: 'button' | 'submit'
  onClick?: () => void
}) {
  return (
    <button
      type={type}
      className={clsx(styles.button, primary && styles.primary, danger && styles.danger, small && styles.small)}
      disabled={disabled}
      onClick={onClick}
    >{children}</button>
  )
}

export function Actions({ children }: { children: ReactNode }) {
  return <div className={styles.actions}>{children}</div>
}

export function CardHead({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return <div className={styles.cardHead}><div>{children}</div>{actions === undefined ? null : <div className={styles.actions}>{actions}</div>}</div>
}

export function Field({ label, children, wide = false }: { label: string; children: ReactNode; wide?: boolean }) {
  return <label className={clsx(styles.field, wide && styles.wide)}><span>{label}</span>{children}</label>
}

export function Check({ children }: { children: ReactNode }) {
  return <label className={styles.check}>{children}</label>
}

export function Details({ label, value, open = false }: { label: string; value: unknown; open?: boolean }) {
  return <details className={styles.details} open={open}><summary>{label}</summary><pre className={styles.code}>{jsonText(value)}</pre></details>
}

export function Code({ children }: { children: ReactNode }) {
  return <pre className={styles.code}>{children}</pre>
}

export function KeyValues({ value, omit = [] }: { value: JsonObject; omit?: string[] }) {
  return (
    <dl className={styles.keyValues}>
      {Object.entries(value).filter(([key]) => !omit.includes(key)).map(([key, row]) => (
        <div className={styles.keyValueRow} key={key}>
          <dt>{key}</dt>
          <dd>{typeof row === 'object' && row !== null ? <code>{jsonText(row)}</code> : String(row ?? '')}</dd>
        </div>
      ))}
    </dl>
  )
}

export function Form({ children, onSubmit }: { children: ReactNode; onSubmit: (payload: JsonObject) => void }) {
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    onSubmit(formPayload(event.currentTarget))
  }
  return <form className={styles.form} onSubmit={submit}>{children}</form>
}

/** Convert a form into JSON-compatible fields, including unchecked checkboxes. */
export function formPayload(form: HTMLFormElement): JsonObject {
  const payload: JsonObject = {}
  for (const [key, value] of new FormData(form).entries()) {
    payload[key] = typeof value === 'string' ? value : value.name
  }
  for (const control of form.elements) {
    if (control instanceof HTMLInputElement && control.type === 'checkbox' && control.name !== '') {
      payload[control.name] = control.checked
    }
  }
  return payload
}

export function jsonText(value: unknown): string {
  try { return JSON.stringify(value, null, 2) }
  catch { return String(value) }
}

export function formatDate(value: JsonValue | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(numeric)) {
    const date = new Date(numeric)
    if (!Number.isNaN(date.getTime())) return date.toLocaleString()
  }
  return String(value)
}

export function formatSize(value: JsonValue | undefined): string {
  return typeof value === 'number' ? `${value} B` : String(value ?? '—')
}

export function omitBlank(value: JsonObject, keys: readonly string[]): JsonObject {
  const copy = { ...value }
  for (const key of keys) if (copy[key] === '') delete copy[key]
  return copy
}
