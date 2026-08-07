import { get, post } from './request'

// 消息中心接口封装(§12.2 消息抽屉;§11.4 通知日志/回执)

/** 通知日志(ccr_notification_log);receiptTime 非空即已读 */
export interface NotificationLog {
  id: number
  evaluationId?: number
  ruleVersionId?: number
  recipientType?: string
  recipientId?: string
  channel?: string
  messageKey?: string
  messageContent?: string
  /** PENDING/SUCCESS/FAILED/RETRYING */
  sendStatus?: string
  retryCount?: number
  sendTime?: string
  /** 回执时间(已读标记) */
  receiptTime?: string
  errorMessage?: string
  createTime?: string
}

/** 通知日志查询(按接收人过滤;后端按创建时间倒序,上限 200) */
export const listNotificationLogs = (params?: { recipientId?: string; sendStatus?: string }) =>
  get<NotificationLog[]>('/ccr/notification/logs', params || {})

/** 登记回执(标记已读) */
export const receiptNotification = (logId: number) =>
  post<NotificationLog>(`/ccr/notification/logs/${logId}/receipt`)
