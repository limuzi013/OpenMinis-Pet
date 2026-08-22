import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Button, Card, Details, Field, Form, Note, jsonText } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function AdvancedPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const discover = objectOf(data.discover)
  const methods = objectsOf(discover.methods)
  const caps = objectsOf(discover.capabilities)
  return (
    <>
      <Note>这里只列出当前已映射且已开启的 App RPC。服务端按能力开关逐项拦截，未登记的未来方法一律拒绝。</Note>
      <Card wide>
        <Form onSubmit={payload => {
          let params: JsonObject
          try {
            const parsed: unknown = JSON.parse(textOf(payload.params, '{}'))
            if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error('参数必须是 JSON 对象')
            params = parsed as JsonObject
          } catch (error) {
            alert(error instanceof Error ? error.message : '参数不是有效 JSON')
            return
          }
          run({ kind: 'rpc-run', payload: { method: textOf(payload.method), params } })
        }}>
          <Field label="方法" wide>
            <input className={styles.input} name="method" list="openminis-rpc-methods" defaultValue={textOf(data.method)} placeholder="例如 provider.groups.update" required />
            <datalist id="openminis-rpc-methods">{methods.map(method => <option key={textOf(method.name)} value={textOf(method.name)}>{textOf(method.description)}</option>)}</datalist>
          </Field>
          <Field label="参数 JSON" wide><textarea className={styles.textarea} name="params" defaultValue={jsonText(data.params ?? {})} /></Field>
          <Button type="submit" primary disabled={busy}>执行 RPC</Button>
        </Form>
        {data.result === undefined || data.result === null ? null : <pre className={styles.code}>{jsonText(data.result)}</pre>}
      </Card>
      {caps.length === 0 ? null : <Card wide><h3>能力目录（{caps.length}）</h3><Details label="查看能力目录" value={caps} /></Card>}
      <Card wide><h3>可用方法（{methods.length}）</h3><Details label="查看发现文档" value={methods} /></Card>
    </>
  )
}
