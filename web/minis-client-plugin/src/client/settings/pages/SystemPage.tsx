import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import {
  Badge, Button, Card, CardHead, Details, Empty, Field, Form, Grid, KeyValues, Note, RiskBadge, SectionTitle,
} from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function SystemPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const env = objectsOf(objectOf(data.env).entries)
  const mountsData = objectOf(data.mounts)
  const mounts = objectsOf(mountsData.mounts)
  const shared = objectsOf(objectOf(data.shared).folders)
  const permission = objectOf(data.permission)
  const sandbox = objectOf(data.sandbox)
  const caps = objectsOf(objectOf(data.caps).capabilities)
  const permissionManage = caps.find(cap => textOf(cap.id) === 'permission.manage')
  const enabledCount = caps.filter(cap => booleanOf(cap.enabled)).length
  return (
    <>
      <Note>权限从“预设”细化为逐能力开关；手机与网页两端看到的是同一份状态。预设按钮只是批量应用。</Note>
      <Grid>
        <Card wide>
          <h3>远程权限预设</h3>
          <p>当前预设：{textOf(permission.label, textOf(permission.preset, '—'))}</p>
          <div className={styles.actions}>
            <Button disabled={busy} onClick={() => run({ kind: 'permission-set', payload: { preset: 'workspace-write' } })}>恢复默认（工作区写入）</Button>
            <Button danger disabled={busy} onClick={() => {
              if (confirm('全部开启会放开所有能力（含设备控制与凭据导出）。确认？')) run({ kind: 'permission-set', payload: { preset: 'danger-full-access' } })
            }}>全部开启（危险）</Button>
          </div>
          {permissionManage !== undefined && !booleanOf(permissionManage.enabled) ? <p className={styles.muted}>⚠ 权限管理已在 Web 端关闭：下面的开关只读，必须在手机上重新开启。</p> : null}
        </Card>
        <Card><h3>沙箱</h3><KeyValues value={sandbox} omit={['capabilities']} /></Card>
      </Grid>
      <SectionTitle title={`逐能力开关（已开启 ${enabledCount}/${caps.length}）`} meta={caps.length} />
      <Grid>
        {caps.length === 0 ? <Empty>读取能力状态失败（权限管理是否被关闭？）</Empty> : caps.map(cap => {
          const id = textOf(cap.id)
          return (
            <Card key={id}>
              <CardHead actions={(
                <label className={styles.check}>
                  <input
                    type="checkbox"
                    checked={booleanOf(cap.enabled)}
                    disabled={busy || (permissionManage !== undefined && !booleanOf(permissionManage.enabled))}
                    onChange={event => {
                      const enabled = event.currentTarget.checked
                      if (id === 'permission.manage' && !enabled && !confirm('关闭“权限管理”后，本网页无法重新开启任何能力，只能回手机恢复。确定关闭？')) return
                      run({ kind: 'capability-toggle', payload: { capability: id, enabled } })
                    }}
                  /> {booleanOf(cap.enabled) ? '开启' : '关闭'}
                </label>
              )}>
                <h3>{textOf(cap.label)} <RiskBadge risk={textOf(cap.risk)} label={textOf(cap.riskLabel, textOf(cap.risk))} /></h3>
                <p>{textOf(cap.description)}</p>
                <p className={styles.muted}>{booleanOf(cap.defaultEnabled) ? '默认开启' : '默认关闭'}</p>
              </CardHead>
            </Card>
          )
        })}
      </Grid>
      <SectionTitle title="环境变量" meta="值只写不读" />
      <Grid>
        {env.length === 0 ? <Empty>没有环境变量</Empty> : env.map((row, index) => {
          const id = textOf(row.id, String(index))
          const key = textOf(row.key)
          return (
            <Card key={id}>
              <CardHead actions={<Badge on={booleanOf(row.hasValue)} yes="已设值" no="空值" />}>
                <h3>{key}</h3><p>{textOf(row.note)}</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => {
                  const value = prompt(`输入 ${key} 的新值（不会回显旧值）`)
                  if (value !== null) run({ kind: 'env-update', payload: { id, value } })
                }}>更新值</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('删除该环境变量？')) run({ kind: 'env-delete', payload: { id } }) }}>删除</Button>
              </div>
            </Card>
          )
        })}
      </Grid>
      <Card wide>
        <Form onSubmit={payload => run({ kind: 'env-create', payload })}>
          <Field label="变量名"><input className={styles.input} name="key" required pattern="[A-Za-z_][A-Za-z0-9_]*" /></Field>
          <Field label="值"><input className={styles.input} type="password" name="value" required autoComplete="new-password" /></Field>
          <Field label="备注" wide><input className={styles.input} name="note" /></Field>
          <Button type="submit" primary disabled={busy}>添加</Button>
        </Form>
      </Card>
      <SectionTitle title="外部挂载" meta={`${mounts.length}/${textOf(mountsData.capacity, '—')} · 新增目录需在手机授权`} />
      <Grid>
        {mounts.length === 0 ? <Empty>没有外部挂载</Empty> : mounts.map((mount, index) => {
          const id = textOf(mount.id, String(index))
          return (
            <Card key={id}>
              <CardHead actions={<Badge on={booleanOf(mount.effectiveWritable)} yes="可写" no="只读" />}>
                <h3>{textOf(mount.name)}</h3><p>{textOf(mount.path)} · {textOf(mount.sourceDisplayName)}</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => {
                  const name = prompt('新名称', textOf(mount.name))
                  if (name) run({ kind: 'mount-rename', payload: { id, name } })
                }}>重命名</Button>
                <Button small disabled={busy} onClick={() => run({ kind: 'mount-write', payload: { id, allowWrite: !booleanOf(mount.userAllowWrite) } })}>{booleanOf(mount.userAllowWrite) ? '设为只读' : '允许写入'}</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('移除该外部目录挂载？手机中的原文件不会删除。')) run({ kind: 'mount-remove', payload: { id } }) }}>移除</Button>
              </div>
            </Card>
          )
        })}
      </Grid>
      <Details label="共享目录" value={shared} />
    </>
  )
}
