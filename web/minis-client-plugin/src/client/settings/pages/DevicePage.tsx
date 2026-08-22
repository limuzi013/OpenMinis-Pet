import { useState, type MouseEvent } from 'react'
import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, textOf } from '../../contract/types.ts'
import { Button, Card, Empty, Field, Form, Grid, Note, formatSize } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function DevicePage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const shot = objectOf(data.shot)
  const hasShot = textOf(shot.base64) !== ''
  const tapMode = booleanOf(data.tapMode)
  const [display, setDisplay] = useState('—')
  const point = (event: MouseEvent<HTMLImageElement>): { x: number; y: number } | undefined => {
    const image = event.currentTarget
    if (image.naturalWidth <= 0 || image.naturalHeight <= 0) return undefined
    const rect = image.getBoundingClientRect()
    return {
      x: Math.max(0, Math.round((event.clientX - rect.left) * image.naturalWidth / rect.width)),
      y: Math.max(0, Math.round((event.clientY - rect.top) * image.naturalHeight / rect.height)),
    }
  }
  return (
    <>
      <Note>“查看手机画面 / 设备操作”由独立能力开关保护（默认关闭）。截图只在点击按钮时抓取，不会自动轮询；操作需要 Minis App 停留在前台。</Note>
      <Grid>
        <Card wide>
          <div className={styles.actions}>
            <Button primary disabled={busy} onClick={() => run({ kind: 'device-shot' })}>{hasShot ? '刷新截图' : '获取手机截图'}</Button>
            <label className={styles.check}><input type="checkbox" checked={tapMode} disabled={busy} onChange={event => run({ kind: 'device-tapmode', payload: { enabled: event.currentTarget.checked } })} /> 点击模式（点图片=点手机）</label>
          </div>
          {hasShot ? (
            <div className={styles.deviceStage}>
              <img
                className={styles.deviceShot}
                src={`data:image/png;base64,${textOf(shot.base64)}`}
                alt="手机画面"
                onLoad={event => setDisplay(`${event.currentTarget.clientWidth} × ${event.currentTarget.clientHeight}`)}
                onClick={event => {
                  const location = point(event)
                  if (location === undefined) return
                  if (tapMode) {
                    if (confirm(`点击手机 (${location.x}, ${location.y})？`)) run({ kind: 'device-tap', payload: location })
                  } else run({ kind: 'device-point', payload: location })
                }}
              />
              <p className={`${styles.muted} ${styles.center}`}>显示尺寸：{display} · 原始像素：{numberOf(shot.originalWidth, numberOf(shot.scaledWidth))} × {numberOf(shot.originalHeight, numberOf(shot.scaledHeight))} · {formatSize(shot.size ?? shot.sizeBytes)} · 坐标：{numberOf(data.x)}, {numberOf(data.y)}</p>
            </div>
          ) : <Empty>还没有截图。先点击“获取手机截图”。</Empty>}
          <details className={styles.details}>
            <summary>手动点击（不依赖图片坐标）</summary>
            <Form onSubmit={payload => run({ kind: 'device-tap', payload: { x: Number(payload.x), y: Number(payload.y) } })}>
              <Field label="X（原始像素）"><input className={styles.input} type="number" name="x" required /></Field>
              <Field label="Y（原始像素）"><input className={styles.input} type="number" name="y" required /></Field>
              <Button type="submit" primary disabled={busy}>点击</Button>
            </Form>
          </details>
          <details className={styles.details}>
            <summary>滚动</summary>
            <Form onSubmit={payload => {
              const next = { x: Number(payload.x), y: Number(payload.y), deltaX: Number(payload.deltaX), deltaY: Number(payload.deltaY) }
              if (next.deltaX === 0 && next.deltaY === 0) { alert('ΔX 与 ΔY 至少一个非零'); return }
              run({ kind: 'device-scroll', payload: next })
            }}>
              <Field label="起点 X"><input className={styles.input} type="number" name="x" required /></Field>
              <Field label="起点 Y"><input className={styles.input} type="number" name="y" required /></Field>
              <Field label="ΔX"><input className={styles.input} type="number" name="deltaX" defaultValue={0} /></Field>
              <Field label="ΔY"><input className={styles.input} type="number" name="deltaY" required /></Field>
              <Button type="submit" primary disabled={busy}>滚动</Button>
            </Form>
          </details>
          <details className={styles.details}>
            <summary>输入文本</summary>
            <Form onSubmit={payload => run({ kind: 'device-input', payload: { text: textOf(payload.text) } })}>
              <Field label="文本（优先由 Accessibility ACTION_SET_TEXT 处理）" wide><input className={styles.input} name="text" required /></Field>
              <Button type="submit" primary disabled={busy}>输入</Button>
            </Form>
          </details>
        </Card>
      </Grid>
    </>
  )
}
