import type { JsonObject, JsonValue, MinisCommand } from '../../contract/types.ts'
import { arrayOf, booleanOf, numberOf, objectOf, textOf } from '../../contract/types.ts'
import { Button, Card, CardHead, Code, Empty, Grid, KeyValues, formatDate, formatSize } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

function fileObject(value: JsonValue): JsonObject {
  return typeof value === 'string' ? { name: value } : objectOf(value)
}

export function DiagnosticsPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const info = objectOf(data.info)
  const logsData = objectOf(data.logs)
  const crashesData = objectOf(data.crashes)
  const logs = arrayOf(logsData.files ?? logsData.logs).map(fileObject)
  const crashes = arrayOf(crashesData.crashes ?? crashesData.files).map(fileObject)
  const viewer = objectOf(data.viewer)
  return (
    <Grid>
      <Card wide><h3>App 与存储</h3><KeyValues value={info} /></Card>
      <Card>
        <h3>日志</h3>
        <div className={styles.list}>{logs.length === 0 ? <Empty>没有日志</Empty> : logs.map((row, index) => {
          const name = textOf(row.name, textOf(row.fileName, String(index)))
          return <CardHead key={name} actions={<Button small disabled={busy} onClick={() => run({ kind: 'log-read', payload: { name, limit: 65_536 } })}>读取正文</Button>}><strong>{name}</strong><p>{formatSize(row.size)} · {formatDate(row.modified)}</p></CardHead>
        })}</div>
      </Card>
      <Card>
        <h3>崩溃报告</h3>
        <div className={styles.list}>{crashes.length === 0 ? <Empty>没有崩溃报告</Empty> : crashes.map((row, index) => {
          const name = textOf(row.name, textOf(row.fileName, String(index)))
          return <CardHead key={name} actions={<Button small disabled={busy} onClick={() => run({ kind: 'crash-read', payload: { name, stackOnly: true, limit: 262_144 } })}>读取正文</Button>}><strong>{name}</strong><p>{textOf(row.summary)}</p></CardHead>
        })}</div>
      </Card>
      {Object.keys(viewer).length === 0 ? null : (
        <Card wide>
          <h3>{textOf(viewer.title)}</h3>
          <p className={styles.muted}>{numberOf(viewer.bytes)} 字符 · {formatDate(viewer.modified)}{booleanOf(viewer.truncated) ? ' · 已截断' : ''}</p>
          <Code>{textOf(viewer.content)}</Code>
        </Card>
      )}
    </Grid>
  )
}
