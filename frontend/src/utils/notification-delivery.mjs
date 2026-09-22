export const deliveryStatuses = {
  PENDING: '待发送', PROCESSING: '发送中', SUCCESS: '发送成功', FAILED: '发送失败', RETRYING: '等待重试'
}
export const deliveryChannels = { SYSTEM: '站内消息', WECHAT: '企业微信', SMS: '短信', EMAIL: '邮件' }
/** 站内回执只证明站内已读，不能推导外部渠道已读。 */
export function deliveryReceipt(row) {
  if (row.channel !== 'SYSTEM') return '无法确认已读'
  return row.receiptTime ? '已读' : '未读'
}
