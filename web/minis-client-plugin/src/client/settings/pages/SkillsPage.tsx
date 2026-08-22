import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Badge, Button, Card, CardHead, Empty, Field, Form, Grid, SectionTitle } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function SkillsPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const skills = objectsOf(data.skills)
  const editor = Object.keys(objectOf(data.editor)).length === 0 ? undefined : objectOf(data.editor)
  return (
    <>
      <SectionTitle title="Skills" meta={`${skills.length} 个，与手机端实时同步`} />
      <Grid>
        {skills.length === 0 ? <Empty>没有 Skill</Empty> : skills.map((skill, index) => {
          const id = textOf(skill.id, String(index))
          return (
            <Card key={id}>
              <CardHead actions={<Badge on={booleanOf(skill.isEnabled)} />}>
                <h3>{textOf(skill.name)}</h3>
                <p>{textOf(skill.description, '无描述')}</p>
                <p>v{textOf(skill.version)} · 使用 {numberOf(skill.useCount)} 次{textOf(skill.sourceURL) === '' ? '' : ` · ${textOf(skill.sourceURL)}`}</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => run({ kind: 'skill-edit', payload: { skillId: id } })}>编辑</Button>
                <Button small disabled={busy} onClick={() => run({ kind: 'skill-toggle', payload: { skillId: id, enabled: !booleanOf(skill.isEnabled) } })}>{booleanOf(skill.isEnabled) ? '停用' : '启用'}</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('永久删除这个 Skill？')) run({ kind: 'skill-delete', payload: { skillId: id } }) }}>删除</Button>
              </div>
            </Card>
          )
        })}
      </Grid>
      <Card wide>
        <h3>通过链接导入 Skill</h3>
        <p>支持 GitHub Skill 目录、SKILL.md 链接或其他公开 HTTPS SKILL.md；同名 Skill 会原位更新。</p>
        <Form onSubmit={payload => run({ kind: 'skill-import-url', payload: { url: textOf(payload.url).trim() } })}>
          <Field label="HTTPS / GitHub 链接" wide><input className={styles.input} name="url" type="url" required placeholder="https://github.com/owner/repo/tree/main/skill-name" /></Field>
          <Button type="submit" primary disabled={busy}>从链接导入</Button>
        </Form>
      </Card>
      <Card wide>
        <h3>{editor === undefined ? '创建 Skill' : '编辑 Skill'}</h3>
        <Form onSubmit={payload => run(editor === undefined
          ? { kind: 'skill-create', payload }
          : { kind: 'skill-update', payload: { name: textOf(payload.name), description: textOf(payload.description), body: textOf(payload.body), skillId: textOf(editor.id) } })}>
          <Field label="名称"><input className={styles.input} name="name" required defaultValue={textOf(editor?.name)} /></Field>
          <Field label="版本"><input className={styles.input} name="version" defaultValue={textOf(editor?.version, '1.0.0')} disabled={editor !== undefined} /></Field>
          <Field label="描述" wide><input className={styles.input} name="description" defaultValue={textOf(editor?.description)} /></Field>
          <Field label="Skill 正文" wide><textarea className={styles.textarea} name="body" defaultValue={textOf(editor?.body)} /></Field>
          <div className={styles.actions}>
            <Button type="submit" primary disabled={busy}>保存</Button>
            {editor === undefined ? null : <Button disabled={busy} onClick={() => run({ kind: 'skill-cancel' })}>取消编辑</Button>}
          </div>
        </Form>
      </Card>
    </>
  )
}
