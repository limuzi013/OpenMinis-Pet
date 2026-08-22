import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, objectsOf, textOf } from '../../contract/types.ts'
import { Badge, Button, Card, CardHead, Details, Empty, Field, Form, Grid, Note, omitBlank } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function McpPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const servers = objectsOf(data.servers)
  return (
    <>
      <Note>MCP 配置写入 App 原生仓库。HTTP 与命令传输二选一；敏感 Header/环境值不会从网页响应中回显。</Note>
      <Grid>
        {servers.length === 0 ? <Empty>没有 MCP Server</Empty> : servers.map((server, index) => {
          const id = textOf(server.id, String(index))
          return (
            <Card key={id}>
              <CardHead actions={<Badge on={booleanOf(server.enabled)} />}>
                <h3>{id}</h3>
                <p>{textOf(server.url, textOf(server.command, '未配置传输'))}</p>
                <p>{textOf(server.note)}</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => run({ kind: 'mcp-toggle', payload: { serverId: id, enabled: !booleanOf(server.enabled) } })}>{booleanOf(server.enabled) ? '停用' : '启用'}</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('删除该 MCP Server？')) run({ kind: 'mcp-delete', payload: { serverId: id } }) }}>删除</Button>
              </div>
              <Details label="完整配置（已脱敏）" value={server} />
            </Card>
          )
        })}
      </Grid>
      <Card wide>
        <h3>导入 MCP 配置</h3>
        <Form onSubmit={payload => run({ kind: 'mcp-import-url', payload: { url: textOf(payload.url).trim() } })}>
          <Field label="公开 HTTPS JSON 链接" wide><input className={styles.input} name="url" type="url" required placeholder="https://raw.githubusercontent.com/owner/repo/main/mcp.json" /></Field>
          <Button type="submit" primary disabled={busy}>从链接导入</Button>
        </Form>
        <details className={styles.details}>
          <summary>或粘贴 JSON</summary>
          <Form onSubmit={payload => run({ kind: 'mcp-import-json', payload: { configJson: textOf(payload.configJson) } })}>
            <Field label="MCP JSON" wide><textarea className={styles.textarea} name="configJson" required placeholder='{"mcpServers":{"name":{"url":"https://…"}}}' /></Field>
            <Button type="submit" primary disabled={busy}>导入 JSON</Button>
          </Form>
        </details>
      </Card>
      <Card wide>
        <h3>添加 MCP Server</h3>
        <Form onSubmit={payload => {
          const next = omitBlank(payload, ['url', 'command', 'note'])
          try { next.args = JSON.parse(textOf(next.args, '[]')) as JsonObject[] }
          catch { alert('参数必须是 JSON 数组'); return }
          if (Boolean(next.url) === Boolean(next.command)) { alert('HTTP URL 与本地命令必须且只能填写一个'); return }
          run({ kind: 'mcp-create', payload: next })
        }}>
          <Field label="Server ID"><input className={styles.input} name="serverId" required pattern="[A-Za-z0-9_-]{1,128}" /></Field>
          <Field label="备注"><input className={styles.input} name="note" /></Field>
          <Field label="HTTP URL"><input className={styles.input} name="url" placeholder="https://…" /></Field>
          <Field label="或本地命令"><input className={styles.input} name="command" placeholder="npx …" /></Field>
          <Field label="参数 JSON 数组" wide><input className={styles.input} name="args" defaultValue="[]" /></Field>
          <label className={styles.check}><input type="checkbox" name="enabled" defaultChecked /> 启用</label>
          <Button type="submit" primary disabled={busy}>创建</Button>
        </Form>
      </Card>
    </>
  )
}
