import type { JsonObject, JsonValue, MinisCommand } from '../../contract/types.ts'
import { booleanOf, numberOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Badge, Button, Card, CardHead, Details, Empty, Field, Form, Grid, Note, formatDate } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

function days(value: JsonValue | undefined): number[] {
  const source = Array.isArray(value) ? value : textOf(value).split(',')
  return source.map(item => Number(item)).filter(item => Number.isInteger(item) && item >= 1 && item <= 7)
}

function TaskForm({ task, busy, run }: { task: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const id = textOf(task.id)
  return (
    <Form onSubmit={payload => {
      const next: JsonObject = {
        ...payload,
        hour: Number(payload.hour),
        minute: Number(payload.minute),
        customDays: days(payload.customDays),
      }
      if (id !== '') next.taskId = id
      run({ kind: id === '' ? 'task-create' : 'task-update', payload: next })
    }}>
      <Field label="名称"><input className={styles.input} name="label" required defaultValue={textOf(task.label)} /></Field>
      <Field label="重复"><select className={styles.select} name="repeatMode" defaultValue={textOf(task.repeatMode, 'ONCE')}><option>ONCE</option><option>DAILY</option><option>WEEKDAYS</option><option>CUSTOM</option></select></Field>
      <Field label="小时（0-23）"><input className={styles.input} type="number" min={0} max={23} name="hour" defaultValue={numberOf(task.timeOfDayHour, numberOf(task.hour, 9))} /></Field>
      <Field label="分钟（0-59）"><input className={styles.input} type="number" min={0} max={59} name="minute" defaultValue={numberOf(task.timeOfDayMinute, numberOf(task.minute, 0))} /></Field>
      <Field label="自定义星期（1=周日…7=周六）" wide><input className={styles.input} name="customDays" defaultValue={days(task.customDays).join(',')} /></Field>
      <Field label="目标模式" wide><input className={styles.input} name="targetMode" defaultValue={textOf(task.targetMode, 'NEW_SESSION')} /></Field>
      <Field label="Prompt" wide><textarea className={styles.textarea} name="prompt" defaultValue={textOf(task.prompt)} /></Field>
      <label className={styles.check}><input type="checkbox" name="enabled" defaultChecked={task.enabled === undefined || booleanOf(task.enabled)} /> 启用</label>
      <div className={styles.actions}>
        <Button type="submit" primary disabled={busy}>{id === '' ? '创建任务' : '保存修改'}</Button>
        {id === '' ? null : <Button disabled={busy} onClick={() => run({ kind: 'task-cancel' })}>取消</Button>}
      </div>
    </Form>
  )
}

export function ScheduledPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const tasks = objectsOf(data.tasks)
  const editorObject = objectOf(data.editor)
  const editor = Object.keys(editorObject).length === 0 ? undefined : editorObject
  return (
    <>
      <Note>真正的调度、AlarmManager 注册和运行历史仍由 Android 执行；网页与手机使用同一份任务数据。</Note>
      <Grid>
        {tasks.length === 0 ? <Empty>没有定时任务</Empty> : tasks.map((task, index) => {
          const id = textOf(task.id, String(index))
          return (
            <Card key={id}>
              <CardHead actions={<Badge on={booleanOf(task.enabled)} />}>
                <h3>{textOf(task.label)}</h3>
                <p>{String(numberOf(task.timeOfDayHour, numberOf(task.hour))).padStart(2, '0')}:{String(numberOf(task.timeOfDayMinute, numberOf(task.minute))).padStart(2, '0')} · {textOf(task.repeatMode)} · {textOf(task.targetMode)}</p>
                <p>下次：{formatDate(task.nextTriggerMs)} · 已运行 {numberOf(task.runCount)} 次</p>
              </CardHead>
              <div className={styles.actions}>
                <Button small disabled={busy} onClick={() => run({ kind: 'task-edit', payload: { taskId: id } })}>编辑</Button>
                <Button small disabled={busy} onClick={() => run({ kind: 'task-run', payload: { taskId: id } })}>立即运行</Button>
                <Button small disabled={busy} onClick={() => run({ kind: 'task-toggle', payload: { taskId: id, enabled: !booleanOf(task.enabled) } })}>{booleanOf(task.enabled) ? '停用' : '启用'}</Button>
                <Button small danger disabled={busy} onClick={() => { if (confirm('永久删除该定时任务？')) run({ kind: 'task-delete', payload: { taskId: id } }) }}>删除</Button>
              </div>
              <Details label="运行历史" value={task.runHistory ?? []} />
            </Card>
          )
        })}
      </Grid>
      <Card wide><h3>{editor === undefined ? '新建定时任务' : '编辑定时任务'}</h3><TaskForm task={editor ?? {}} busy={busy} run={run} /></Card>
    </>
  )
}
