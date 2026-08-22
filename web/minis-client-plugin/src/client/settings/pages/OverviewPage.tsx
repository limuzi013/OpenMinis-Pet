import type { JsonObject } from '../../contract/types.ts'
import { booleanOf, objectOf, objectsOf, textOf } from '../../contract/types.ts'
import { Badge, Card, CardHead, Details, Empty, Grid, KeyValues, SectionTitle, formatDate } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

export function OverviewPage({ data }: { data: JsonObject }) {
  const status = objectOf(data.status)
  const remote = objectOf(data.remote)
  const info = objectOf(data.appInfo)
  const sessions = objectsOf(objectOf(data.sessions).sessions)
  const tunnel = objectOf(status.tunnel)
  return (
    <Grid>
      <Card>
        <h3>连接状态</h3>
        <p><Badge on={booleanOf(status.ok)} yes="已连接" no="未连接" /> Android · {textOf(status.bindHost)} : {textOf(status.port)}</p>
        <p>Cloudflare Tunnel：{booleanOf(tunnel.running) ? '运行中' : '未运行'}</p>
      </Card>
      <Card>
        <h3>Minis Web</h3>
        <p>账号：{textOf(remote.username, '—')}</p>
        <p>局域网：{booleanOf(remote.lanAccess) ? '已开放' : '仅本机/隧道'}</p>
        <p>公开域名：{textOf(remote.cloudflareHostname, '未配置')}</p>
      </Card>
      <Card wide>
        <SectionTitle title="最近会话" meta={`${sessions.length} 个`} />
        <div className={styles.list}>
          {sessions.length === 0 ? <Empty>还没有会话</Empty> : sessions.slice(0, 8).map((session, index) => (
            <CardHead
              key={textOf(session.id, String(index))}
              actions={<Badge on={booleanOf(session.isRunning)} yes="运行中" no="空闲" />}
            >
              <strong>{textOf(session.title, '新会话')}</strong>
              <p>{textOf(session.modelName, textOf(session.modelId, '未选择模型'))} · {textOf(session.lastMessagePreview, '暂无消息')} · {formatDate(session.updatedAt)}</p>
            </CardHead>
          ))}
        </div>
      </Card>
      <Card wide>
        <h3>App 信息</h3>
        <KeyValues value={info} />
        <Details label="完整信息" value={info} />
      </Card>
    </Grid>
  )
}
