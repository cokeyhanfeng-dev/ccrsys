import test from 'node:test'
import assert from 'node:assert/strict'
import { deliveryReceipt, deliveryStatuses } from '../src/utils/notification-delivery.mjs'

test('外部渠道即使有发送成功或回执时间也不宣称已读', () => {
  assert.equal(deliveryReceipt({ channel: 'WECHAT', sendStatus: 'SUCCESS', receiptTime: '2026-09-22' }), '无法确认已读')
  assert.equal(deliveryReceipt({ channel: 'EMAIL', sendStatus: 'FAILED' }), '无法确认已读')
  assert.equal(deliveryReceipt({ channel: 'SYSTEM', sendStatus: 'SUCCESS' }), '未读')
  assert.equal(deliveryReceipt({ channel: 'SYSTEM', receiptTime: '2026-09-22' }), '已读')
  assert.equal(deliveryStatuses.PROCESSING, '发送中')
})
