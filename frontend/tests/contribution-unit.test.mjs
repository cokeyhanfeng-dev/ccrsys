import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { transformSync } from 'esbuild'
import { runInNewContext } from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import { contributionUnit } from '../mobile/units.mjs'

const dictionary = readFileSync(new URL('../src/utils/dict.ts', import.meta.url), 'utf8')
const dictModule = { exports: {} }
runInNewContext(transformSync(dictionary, { loader: 'ts', format: 'cjs' }).code,
  { module: dictModule, exports: dictModule.exports })
const { isCountMetric, isRatioMetric } = dictModule.exports
const pc = parse(readFileSync(new URL('../src/components/ContributionPanel.vue', import.meta.url), 'utf8')).descriptor
const unitFunction = compileScript(pc, { id: 'unit-test' }).scriptSetupAst
  .find(node => node.type === 'FunctionDeclaration' && node.id.name === 'unitOf')
const pcUnit = runInNewContext(transformSync(pc.scriptSetup.content.slice(unitFunction.start, unitFunction.end),
  { loader: 'ts' }).code + '\nunitOf', { isCountMetric, isRatioMetric })

test('PC及移动代发户数优先于数仓金额类型，原值不做金额换算', () => {
  for (const valueType of ['CONTRIBUTION_AMOUNT', 'AVG_BALANCE', undefined]) {
    const row = { metricCode: 'PUBLIC_PAYROLL_CONTRIBUTION', metricValue: 1234, valueType }
    assert.equal(pcUnit(row), '户')
    assert.equal(contributionUnit(row, isRatioMetric(row.metricCode), isCountMetric(row.metricCode)), '户')
    assert.equal(row.metricValue, 1234)
  }
})

test('代发金额仍为万元，存贷款比仍为百分比', () => {
  for (const [metricCode, unit] of [['PUBLIC_PAYROLL_AMOUNT', '万元'], ['PUBLIC_DEPOSIT_LOAN_RATIO', '%']]) {
    const row = { metricCode, valueType: 'CONTRIBUTION_AMOUNT' }
    assert.equal(pcUnit(row), unit)
    assert.equal(contributionUnit(row, isRatioMetric(metricCode), isCountMetric(metricCode)), unit)
  }
})
