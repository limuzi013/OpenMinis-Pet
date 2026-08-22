import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { numberOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Button, Card, CardHead, Details, Empty, Field, Form, Grid, SectionTitle } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function AgentPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const settings = objectOf(data.settings)
  const jobs = objectsOf(objectOf(data.jobs).jobs)
  const approvals = objectsOf(objectOf(data.approvals).approvals)
  const questions = objectsOf(objectOf(data.questions).questions)
  return (
    <>
      <Grid>
        <Card>
          <h3>子 Agent 限制</h3>
          <Form onSubmit={payload => run({ kind: 'agent-settings', payload: { maxDepth: Number(payload.maxDepth), timeoutMinutes: Number(payload.timeoutMinutes) } })}>
            <Field label="最大深度"><input className={styles.input} type="number" min={1} max={5} name="maxDepth" defaultValue={numberOf(settings.maxDepth, 2)} /></Field>
            <Field label="超时（分钟）"><input className={styles.input} type="number" min={1} max={30} name="timeoutMinutes" defaultValue={numberOf(settings.timeoutMinutes, 10)} /></Field>
            <Button type="submit" primary disabled={busy}>保存</Button>
          </Form>
        </Card>
        <Card><h3>等待用户</h3><p>{approvals.length} 个审批 · {questions.length} 个问题</p><p className={styles.muted}>与手机端审批和问题状态共用同一 seam。</p></Card>
      </Grid>
      {approvals.length === 0 ? null : (
        <><SectionTitle title="待审批" meta={approvals.length} /><Grid>{approvals.map((approval, index) => {
          const id = textOf(approval.id, String(index))
          return <Card key={id}><h3>{textOf(approval.toolName, '危险操作')}</h3><p>{textOf(approval.summary)}</p><div className={styles.actions}><Button disabled={busy} onClick={() => run({ kind: 'approval-answer', payload: { id, allowed: true } })}>仅允许一次</Button><Button danger disabled={busy} onClick={() => run({ kind: 'approval-answer', payload: { id, allowed: false } })}>拒绝</Button></div></Card>
        })}</Grid></>
      )}
      {questions.length === 0 ? null : (
        <><SectionTitle title="待回答问题" meta={questions.length} /><Grid>{questions.map((question, index) => {
          const id = textOf(question.id, String(index))
          return <Card key={id}><h3>{textOf(question.question, 'Agent 问题')}</h3><Button disabled={busy} onClick={() => {
            const answer = prompt(textOf(question.question, '请输入回答'))
            if (answer !== null) run({ kind: 'question-answer', payload: { id, answer } })
          }}>回答</Button><Details label="结构化内容" value={question} /></Card>
        })}</Grid></>
      )}
      <SectionTitle title="后台作业" meta={jobs.length} />
      <Grid>
        {jobs.length === 0 ? <Empty>没有后台作业</Empty> : jobs.map((job, index) => {
          const id = textOf(job.id, String(index))
          const status = textOf(job.status)
          return (
            <Card key={id}>
              <CardHead actions={<span className={styles.badge}>{status}</span>}>
                <h3>{textOf(job.label, id)}</h3><p>{textOf(job.kind)} · {textOf(job.detail)}</p>
              </CardHead>
              {status === 'running' || status === 'stopping' ? <Button small danger disabled={busy} onClick={() => { if (confirm('取消这个后台作业？')) run({ kind: 'job-cancel', payload: { id, reason: 'Minis Web user' } }) }}>取消</Button> : null}
              <Details label="输出" value={job.output ?? ''} />
            </Card>
          )
        })}
      </Grid>
    </>
  )
}
