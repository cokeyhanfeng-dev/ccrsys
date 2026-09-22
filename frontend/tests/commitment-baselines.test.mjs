import test from 'node:test'
import assert from 'node:assert/strict'
import { syncCommitmentBaselines } from '../src/utils/commitment-baselines.mjs'

test('恢复旧草稿及新增关联人后，基线 0 更新为 253，目标 50 保留供修改', () => {
  const rows = [{ metricCode: 'PUBLIC_PAYROLL_CONTRIBUTION', baselineValue: '0', targetValue: '50', unit: 'COUNT' }]
  syncCommitmentBaselines(rows, [{ metricCode: 'PUBLIC_PAYROLL_CONTRIBUTION', metricValue: 253 }])
  assert.equal(rows[0].baselineValue, '253')
  assert.equal(rows[0].targetValue, '50')
  assert.equal(rows[0].unit, 'COUNT')
  assert.ok(Number(rows[0].targetValue) <= Number(rows[0].baselineValue))
})
test('移除关联人后回填零，比例保留小数，其他承诺无数值基线', () => {
  const rows = [
    { metricCode: 'PUBLIC_PAYROLL_CONTRIBUTION', baselineValue: '253', targetValue: '300' },
    { metricCode: 'RATIO', baselineValue: '0', targetValue: '80' },
    { metricCode: 'OTHER', baselineValue: '99', commitmentDesc: '模拟承诺' }
  ]
  syncCommitmentBaselines(rows, [{ metricCode: 'RATIO', metricValue: '65.25' }])
  assert.deepEqual(rows.map(row => row.baselineValue), ['0', '65.25', ''])
  assert.equal(rows[0].targetValue, '300')
  assert.equal(rows[2].commitmentDesc, '模拟承诺')
})
