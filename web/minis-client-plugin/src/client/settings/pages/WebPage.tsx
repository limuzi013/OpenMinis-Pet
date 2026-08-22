import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, textOf } from '../../contract/types.ts'
import { Button, Card, Field, Form, Grid, Note, omitBlank } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function WebPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const settings = objectOf(data.settings)
  const status = objectOf(data.status)
  const tunnel = objectOf(status.tunnel)
  return (
    <>
      <Note danger>端口或局域网监听变更需要重启 Web 远程服务。修改账号或密码会注销所有网页会话。</Note>
      <Grid>
        <Card>
          <h3>服务</h3>
          <Form onSubmit={payload => run({ kind: 'web-service', payload: { port: Number(payload.port), lanAccess: booleanOf(payload.lanAccess) } })}>
            <Field label="端口"><input className={styles.input} type="number" min={1024} max={65535} name="port" defaultValue={numberOf(settings.port, 8765)} /></Field>
            <label className={styles.check}><input type="checkbox" name="lanAccess" defaultChecked={booleanOf(settings.lanAccess)} /> 开放局域网监听</label>
            <Button type="submit" primary disabled={busy}>保存服务设置</Button>
          </Form>
        </Card>
        <Card>
          <h3>账号</h3>
          <Form onSubmit={payload => run({ kind: 'web-identity', payload: omitBlank(payload, ['newPassword']) })}>
            <Field label="用户名"><input className={styles.input} name="username" defaultValue={textOf(settings.username)} /></Field>
            <Field label="当前密码"><input className={styles.input} type="password" name="currentPassword" autoComplete="current-password" required /></Field>
            <Field label="新密码（可选）"><input className={styles.input} type="password" name="newPassword" autoComplete="new-password" /></Field>
            <Button type="submit" primary disabled={busy}>更新账号</Button>
          </Form>
        </Card>
        <Card wide>
          <h3>Cloudflare Tunnel</h3>
          <Form onSubmit={payload => run({ kind: 'web-tunnel', payload: omitBlank(payload, ['cloudflareTunnelToken']) })}>
            <Field label="公开域名"><input className={styles.input} name="cloudflareHostname" defaultValue={textOf(settings.cloudflareHostname)} /></Field>
            <Field label="新 Tunnel Token（留空保留）"><input className={styles.input} type="password" name="cloudflareTunnelToken" autoComplete="new-password" /></Field>
            <label className={styles.check}><input type="checkbox" name="cloudflareTunnelEnabled" defaultChecked={booleanOf(settings.cloudflareTunnelEnabled)} /> 启用隧道</label>
            <Button type="submit" primary disabled={busy}>保存隧道设置</Button>
          </Form>
          <p>{textOf(tunnel.phase)} · {textOf(tunnel.detail)}</p>
        </Card>
      </Grid>
      <div className={styles.actions}>
        <Button disabled={busy} onClick={() => { if (confirm('重启 Web 服务？当前连接会短暂断开。')) run({ kind: 'web-restart' }) }}>重启 Web 服务</Button>
        <Button danger disabled={busy} onClick={() => run({ kind: 'logout' })}>退出登录</Button>
      </div>
    </>
  )
}
