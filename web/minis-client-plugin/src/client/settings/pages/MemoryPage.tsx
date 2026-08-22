import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Badge, Button, Card, CardHead, Empty, Field, Form, Grid, SectionTitle, formatDate, formatSize } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function MemoryPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const files = objectsOf(data.files)
  const global = objectOf(data.global)
  const soul = objectOf(data.soul)
  const editorObject = objectOf(data.editor)
  const editor = Object.keys(editorObject).length === 0 ? undefined : editorObject
  return (
    <>
      <Grid>
        <Card>
          <CardHead actions={<Badge on={booleanOf(global.enabled)} />}>
            <h3>全局记忆</h3><p>GLOBAL.md 是否注入所有会话</p>
          </CardHead>
          <Button disabled={busy} onClick={() => run({ kind: 'memory-global', payload: { enabled: !booleanOf(global.enabled) } })}>{booleanOf(global.enabled) ? '关闭' : '开启'}全局记忆</Button>
        </Card>
        <Card><h3>SOUL</h3><p>{textOf(soul.name, 'Minis')} · {textOf(soul.style)} · {textOf(soul.lang)}</p></Card>
      </Grid>
      <SectionTitle title="记忆文件" meta={files.length} />
      <Grid>
        {files.length === 0 ? <Empty>没有记忆文件</Empty> : files.map((file, index) => {
          const name = textOf(file.name, String(index))
          return (
            <Card key={name}>
              <CardHead actions={booleanOf(file.isGlobal) ? <span className={styles.badge}>全局</span> : null}>
                <h3>{name}</h3>
                <p>{textOf(file.preview)}</p>
                <p>{formatSize(file.fileSize)} · {formatDate(file.modifiedDate)}</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => run({ kind: 'memory-edit', payload: { name } })}>编辑</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm(`删除记忆文件 ${name}？`)) run({ kind: 'memory-delete', payload: { name } }) }}>删除</Button>
              </div>
            </Card>
          )
        })}
      </Grid>
      <Card wide>
        <h3>{editor === undefined ? '新建记忆文件' : '编辑记忆'}</h3>
        <Form onSubmit={payload => run({ kind: 'memory-save', payload: { name: textOf(payload.name), content: textOf(payload.content) } })}>
          <Field label="文件名"><input className={styles.input} name="name" required defaultValue={textOf(editor?.name, 'MEMORY.md')} readOnly={editor !== undefined} /></Field>
          <Field label="内容" wide><textarea className={styles.textarea} name="content" defaultValue={textOf(editor?.content)} /></Field>
          <div className={styles.actions}>
            <Button type="submit" primary disabled={busy}>保存</Button>
            {editor === undefined ? null : <Button disabled={busy} onClick={() => run({ kind: 'memory-cancel' })}>取消</Button>}
          </div>
        </Form>
      </Card>
      <Card wide>
        <h3>编辑 SOUL</h3>
        <Form onSubmit={payload => run({ kind: 'soul-save', payload })}>
          <Field label="名称"><input className={styles.input} name="name" defaultValue={textOf(soul.name)} /></Field>
          <Field label="语言"><input className={styles.input} name="lang" defaultValue={textOf(soul.lang)} /></Field>
          <Field label="风格" wide><input className={styles.input} name="style" defaultValue={textOf(soul.style)} /></Field>
          <Field label="正文" wide><textarea className={styles.textarea} name="body" defaultValue={textOf(soul.body)} /></Field>
          <Button type="submit" primary disabled={busy}>保存 SOUL</Button>
        </Form>
      </Card>
    </>
  )
}
