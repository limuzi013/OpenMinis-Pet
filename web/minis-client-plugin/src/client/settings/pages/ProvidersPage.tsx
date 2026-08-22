import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import {
  Actions, Badge, Button, Card, CardHead, Details, Empty, Field, Form, Grid, Note, SectionTitle, omitBlank,
} from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

interface ProvidersPageProps {
  data: JsonObject
  busy: boolean
  run: (command: MinisCommand) => void
}

export function ProvidersPage({ data, busy, run }: ProvidersPageProps) {
  const types = objectsOf(data.types)
  const instances = objectsOf(data.instances)
  const groups = objectsOf(data.groups)
  const models = objectOf(data.models)
  return (
    <>
      <Note>凭据不会从手机回传；这里只显示“已配置”。提交新 Key 时会写入 App 的安全存储。</Note>
      <SectionTitle title="供应商实例" meta={instances.length} />
      <div className={styles.list}>
        {instances.length === 0 ? <Empty>尚未配置供应商</Empty> : instances.map((provider, index) => {
          const id = textOf(provider.id, String(index))
          const modelPage = objectOf(models[id])
          const entries = objectsOf(modelPage.entries)
          return (
            <Card key={id}>
              <CardHead actions={(
                <>
                  <Badge on={booleanOf(provider.isEnabled)} />
                  <Button small disabled={busy} onClick={() => run({ kind: 'provider-models', payload: { instanceId: id } })}>模型</Button>
                  <Button small disabled={busy} onClick={() => run({ kind: 'provider-test', payload: { instanceId: id } })}>测试</Button>
                  <Button small disabled={busy} onClick={() => run({ kind: 'provider-toggle', payload: { instanceId: id, isEnabled: !booleanOf(provider.isEnabled) } })}>{booleanOf(provider.isEnabled) ? '停用' : '启用'}</Button>
                  <Button small danger disabled={busy} onClick={() => {
                    if (confirm('删除该供应商及其模型？')) run({ kind: 'provider-delete', payload: { instanceId: id } })
                  }}>删除</Button>
                </>
              )}>
                <h3>{textOf(provider.label)}</h3>
                <p>{textOf(provider.providerType)} · {booleanOf(provider.hasCredential) ? '凭据已配置' : '未配置凭据'} · {numberOf(provider.modelEntryCount)} 个模型</p>
                <p>{textOf(provider.customBaseURL, '默认 API 地址')}</p>
              </CardHead>
              {Object.keys(modelPage).length === 0 ? null : (
                <details className={styles.details} open>
                  <summary>模型目录（{entries.length}）</summary>
                  <div className={styles.list}>
                    {entries.length === 0 ? <Empty>没有模型</Empty> : entries.map((model, modelIndex) => {
                      const entryId = textOf(model.id, String(modelIndex))
                      return (
                        <CardHead key={entryId} actions={(
                          <>
                            <Button small disabled={busy} onClick={() => run({ kind: 'model-loop', payload: { entryId, inLoop: !booleanOf(model.inAgentLoop) } })}>{booleanOf(model.inAgentLoop) ? '移出 Agent' : '加入 Agent'}</Button>
                            <Button small danger disabled={busy} onClick={() => {
                              if (confirm('删除该模型？')) run({ kind: 'model-delete', payload: { entryId } })
                            }}>删除</Button>
                          </>
                        )}>
                          <strong>{textOf(model.displayName)}</strong>
                          <p>{textOf(model.modelId)} · {booleanOf(model.supportsReasoning) ? '支持推理' : '普通模型'}</p>
                        </CardHead>
                      )
                    })}
                    <Form onSubmit={payload => run({ kind: 'model-add', payload: { instanceId: id, modelId: textOf(payload.modelId) } })}>
                      <Field label="新增模型 ID" wide><input className={styles.input} name="modelId" required placeholder="provider/model-name" /></Field>
                      <Button type="submit" primary disabled={busy}>添加模型</Button>
                    </Form>
                  </div>
                </details>
              )}
              <details className={styles.details}>
                <summary>编辑实例 / 更新凭据</summary>
                <Form onSubmit={payload => run({ kind: 'provider-update', payload: { ...omitBlank(payload, ['apiKey']), instanceId: id } })}>
                  <Field label="名称"><input className={styles.input} name="label" defaultValue={textOf(provider.label)} /></Field>
                  <Field label="新 API Key（留空则保留）"><input className={styles.input} type="password" name="apiKey" autoComplete="new-password" /></Field>
                  <Field label="自定义 Base URL" wide><input className={styles.input} name="customBaseURL" defaultValue={textOf(provider.customBaseURL)} /></Field>
                  <Button type="submit" primary disabled={busy}>保存</Button>
                </Form>
              </details>
            </Card>
          )
        })}
      </div>

      <Card wide>
        <h3>添加供应商</h3>
        <Form onSubmit={payload => {
          const type = types.find(row => textOf(row.id) === textOf(payload.providerType))
          const next = omitBlank(payload, ['apiKey', 'customBaseURL'])
          if (!booleanOf(type?.customBaseURLSupported)) delete next.customBaseURL
          run({ kind: 'provider-create', payload: next })
        }}>
          <Field label="类型"><select className={styles.select} name="providerType">{types.map(row => <option key={textOf(row.id)} value={textOf(row.id)}>{textOf(row.displayName, textOf(row.id))}</option>)}</select></Field>
          <Field label="名称"><input className={styles.input} name="label" required /></Field>
          <Field label="API Key / Token"><input className={styles.input} type="password" name="apiKey" autoComplete="new-password" /></Field>
          <Field label="Base URL（兼容类型可选）"><input className={styles.input} name="customBaseURL" /></Field>
          <label className={styles.check}><input type="checkbox" name="seedBuiltInModels" defaultChecked /> 添加内置模型</label>
          <Button type="submit" primary disabled={busy}>创建</Button>
        </Form>
      </Card>

      <SectionTitle title="模型组" meta={groups.length} />
      <Grid>
        {groups.length === 0 ? <Empty>没有模型组</Empty> : groups.map((group, index) => {
          const id = textOf(group.id, String(index))
          return (
            <Card key={id}>
              <CardHead actions={<>{booleanOf(group.isDefault) && <span className={styles.badge}>主默认</span>}{booleanOf(group.isSub) && <span className={styles.badge}>子 Agent</span>}</>}>
                <h3>{textOf(group.name)}</h3>
                <p>{textOf(group.strategy)} · {objectsOf(group.members).length || (Array.isArray(group.memberEntryIds) ? group.memberEntryIds.length : 0)} 个模型</p>
              </CardHead>
              <Actions>
                <Button small disabled={busy} onClick={() => run({ kind: 'group-default', payload: { groupId: id } })}>设为主默认</Button>
                <Button small disabled={busy} onClick={() => run({ kind: 'group-sub-default', payload: { groupId: id } })}>设为子默认</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('删除该模型组？')) run({ kind: 'group-delete', payload: { groupId: id } }) }}>删除</Button>
              </Actions>
              <Details label="成员" value={group.members ?? group.memberEntryIds} />
            </Card>
          )
        })}
      </Grid>
      <Card wide>
        <Form onSubmit={payload => run({ kind: 'group-create', payload: { name: textOf(payload.name) } })}>
          <Field label="新模型组名称"><input className={styles.input} name="name" required /></Field>
          <Button type="submit" primary disabled={busy}>创建模型组</Button>
        </Form>
      </Card>
    </>
  )
}
