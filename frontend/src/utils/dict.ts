/**
 * 担保类型字典(V1.0 附录 A.3):后端/路由统一使用编码,页面展示中文
 * 担保方式主类型固定 4 种:CREDIT信用/GUARANTEE保证/MORTGAGE抵押/PLEDGE质押
 * (银票/信用证保证金、存单质押等不再作为主担保方式,仅存于担保措施层 measureType)
 */

export interface DictItem {
  code: string
  name: string
}

export const GUARANTEE_TYPES: DictItem[] = [
  { code: 'MORTGAGE', name: '抵押' },
  { code: 'PLEDGE', name: '质押' },
  { code: 'GUARANTEE', name: '保证' },
  { code: 'CREDIT', name: '信用' }
]

// 编码→中文展示映射(独立保留历史保证金/存单编码,保证存量分项/担保措施数据仍以中文展示;
// 下拉选项 GUARANTEE_TYPES 只保留 4 种主类型,展示映射不受影响)
const GUARANTEE_NAME_MAP: Record<string, string> = {
  MORTGAGE: '抵押', PLEDGE: '质押', GUARANTEE: '保证', CREDIT: '信用',
  BILL_MARGIN: '银票保证金', CREDIT_MARGIN: '信用证保证金',
  CERTIFICATE_DEPOSIT: '存单质押', MARGIN_PLEDGE: '保证金质押'
}

const NAME_MAP: Record<string, string> = GUARANTEE_NAME_MAP
// 兼容存量中文值(早期表单直存中文)
const LEGACY_MAP: Record<string, string> = { 抵押: 'MORTGAGE', 质押: 'PLEDGE', 保证: 'GUARANTEE', 信用: 'CREDIT' }

/** 编码→中文展示(中文原样返回,空值返回兜底) */
export function guaranteeTypeText(code?: string, fallback = '—'): string {
  if (!code) return fallback
  return NAME_MAP[code] || (LEGACY_MAP[code] ? NAME_MAP[LEGACY_MAP[code]] : code)
}

/** 中文/编码→标准编码(提交后端用) */
export function guaranteeTypeCode(v?: string): string {
  if (!v) return ''
  return LEGACY_MAP[v] || v
}

/* ========================================================================
 * 全系统展示字典:后端/接口统一使用英文编码,页面展示一律经下列函数中文化。
 * 页面禁止各自再写一套映射;缺码时原样返回编码,空值返回 fallback(默认 —)。
 * ===================================================================== */

/** 编码→中文通用查找(未收录的编码原样返回,便于发现漏配) */
function textOf(map: Record<string, string>, code?: string, fallback = '—'): string {
  if (!code) return fallback
  return map[code] || code
}

// ---------- 状态类 ----------

/** 申请主单状态(历史/档案) */
export const APP_STATUS: Record<string, string> = {
  DRAFT: '草稿', SUBMITTING: '提交中', PROCESSING: '审批中', PARTIAL_APPROVED: '部分通过',
  APPROVED: '已通过', REJECTED: '已否决', CLOSED: '已关闭'
}
export function appStatusText(code?: string, fallback = '—'): string {
  return textOf(APP_STATUS, code, fallback)
}

/** 定价分项状态(审批流转) */
export const ITEM_STATUS: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', ROUTING: '审批中', APPROVED_LEVEL: '权限内已批',
  VOTING: '小组表决', COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议',
  FINAL: '终态', VETOED: '一票否决', REJECTED: '已否决', RETURNED: '已退回', CLOSED: '已关闭'
}
export function itemStatusText(code?: string, fallback = '—'): string {
  return textOf(ITEM_STATUS, code, fallback)
}

/** 表决批次状态 */
export const ROUND_STATUS: Record<string, string> = {
  VOTING: '表决中', PASSED: '通过', FAILED: '未通过', CANCELLED: '已取消', CLOSED: '已结束'
}
export function roundStatusText(code?: string, fallback = '—'): string {
  return textOf(ROUND_STATUS, code, fallback)
}

/** 决议/执行状态 */
export const EXEC_STATUS: Record<string, string> = {
  ISSUED: '已签发', CONTRACT_PENDING: '待签合同', CONTRACT_BOUND: '已绑定合同',
  EXECUTED: '已执行', RECONCILE_EXCEPTION: '对账异常', CLOSED: '已关闭', VOID: '已作废'
}
export function execStatusText(code?: string, fallback = '—'): string {
  return textOf(EXEC_STATUS, code, fallback)
}

/** 承诺计划状态 */
export const PLAN_STATUS: Record<string, string> = {
  PENDING: '待生效', TRACKING: '跟踪中', AT_RISK: '有风险', ACHIEVED: '已达成',
  EXPIRED_UNMET: '到期未达成', DATA_PENDING: '数据待齐', TERMINATED: '已终止', SUPERSEDED: '已被替代'
}
export function planStatusText(code?: string, fallback = '—'): string {
  return textOf(PLAN_STATUS, code, fallback)
}

/** 承诺评估结论 */
export const EVAL_RESULT: Record<string, string> = {
  ON_TRACK: '正常', AT_RISK: '有风险', ACHIEVED: '已达成',
  DATA_PENDING: '数据待齐', NO_EVALUATION: '暂无评估'
}
export function evalResultText(code?: string, fallback = '—'): string {
  return textOf(EVAL_RESULT, code, fallback)
}

/** 配置(LPR/矩阵/规则集/产品边界/策略)状态 */
export const CONFIG_STATUS: Record<string, string> = {
  DRAFT: '草稿', REVIEW: '待复核', EFFECTIVE: '已生效', INVALID: '已停用'
}
export function configStatusText(code?: string, fallback = '—'): string {
  return textOf(CONFIG_STATUS, code, fallback)
}

/** 消息发送状态 */
export const MSG_STATUS: Record<string, string> = {
  PENDING: '待发送', SENT: '已发送', SUCCESS: '发送成功', FAILED: '发送失败', RETRYING: '重试中'
}
export function msgStatusText(code?: string, fallback = '—'): string {
  return textOf(MSG_STATUS, code, fallback)
}

/** 数仓批次落地状态 */
export const BATCH_STATUS: Record<string, string> = {
  SUCCESS: '成功', OK: '正常', DONE: '完成', FAILED: '失败', ERROR: '错误', RUNNING: '运行中', PROCESSING: '处理中'
}
export function batchStatusText(code?: string, fallback = '—'): string {
  return textOf(BATCH_STATUS, code ? code.toUpperCase() : code, fallback)
}

// ---------- 节点 / 动作 / 票型 ----------

/** 审批节点编码→岗位名 */
export const NODE_LABELS: Record<string, string> = {
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组', PRESIDENT: '总行行长'
}
export function nodeLabel(code?: string, fallback = '—'): string {
  return textOf(NODE_LABELS, code, fallback)
}

/** 审批动作 */
export const ACTION_TEXT: Record<string, string> = {
  APPROVE: '通过', REJECT: '否决', ADJUST: '调价', SUBMIT: '提交',
  RETURN: '退回', VETO: '一票否决', AGREE: '同意', COUNT_PASS: '计票通过',
  ESCALATE: '上送'
}
export function actionText(code?: string, fallback = '—'): string {
  return textOf(ACTION_TEXT, code, fallback)
}

/** 表决票型 */
export const VOTE_CHOICE: Record<string, string> = {
  APPROVE: '赞成', REJECT: '反对', AGREE: '赞成', DISAGREE: '反对', ABSTAIN: '弃权'
}
export function voteChoiceText(code?: string, fallback = '—'): string {
  return textOf(VOTE_CHOICE, code, fallback)
}

/** 行长决策 */
export const DECISION_TEXT: Record<string, string> = {
  AGREE: '同意', APPROVE: '同意', VETO: '一票否决'
}
export function decisionText(code?: string, fallback = '—'): string {
  return textOf(DECISION_TEXT, code, fallback)
}

/** 配置变更动作(变更日志) */
export const CONFIG_ACTION: Record<string, string> = {
  CREATE: '新增', SUBMIT: '送审', PUBLISH: '复核发布', DISABLE: '停用', REJECT: '复核驳回'
}
export function configActionText(code?: string, fallback = '—'): string {
  return textOf(CONFIG_ACTION, code, fallback)
}

// ---------- 业务维度 ----------

/** 业务类型 */
export const BUSINESS_TYPE: Record<string, string> = { LOAN: '贷款', DEPOSIT: '存款' }
export function businessTypeText(code?: string, fallback = '—'): string {
  return textOf(BUSINESS_TYPE, code, fallback)
}

/** 业务大类(权限矩阵/路由试算) */
export const BUSINESS_BIG_TYPE: Record<string, string> = {
  LOAN_PUBLIC: '对公贷款', LOAN_PERSONAL: '个人贷款', DEPOSIT: '存款', MARGIN: '保证金'
}
export function businessBigTypeText(code?: string, fallback = '—'): string {
  return textOf(BUSINESS_BIG_TYPE, code, fallback)
}

/** 客户主体范围 */
export const CUSTOMER_SCOPE: Record<string, string> = {
  INDIVIDUAL: '个人', CORPORATE_SINGLE: '企业单户', MEMBER: '集团成员', GROUP: '集团'
}
export function customerScopeText(code?: string, fallback = '—'): string {
  return textOf(CUSTOMER_SCOPE, code, fallback)
}

/** 企业性质 */
export const CUSTOMER_TYPE: Record<string, string> = { SOE: '国企', NON_SOE: '非国企', PERSONAL: '个人' }
export function customerTypeText(code?: string, fallback = '—'): string {
  return textOf(CUSTOMER_TYPE, code, fallback)
}

/** 客户分类(审批详情数仓字段,EXISTING 存量/NEW 新增) */
export const CUSTOMER_CLASS: Record<string, string> = { EXISTING: '存量客户', NEW: '新增客户' }
export function customerClassText(code?: string, fallback = '—'): string {
  return textOf(CUSTOMER_CLASS, code, fallback)
}

/** 证件类型(caps 数仓 cert_tp,对公统一社会信用代码/对私身份证) */
export const CERT_TYPE: Record<string, string> = {
  UNIFIED: '统一社会信用代码', ID: '身份证',
  UNIFIED_SOCIAL: '统一社会信用代码', ID_CARD: '身份证'
}
export function certTypeText(code?: string, fallback = '—'): string {
  return textOf(CERT_TYPE, code, fallback)
}

/** 金额档 */
export const AMOUNT_TIER: Record<string, string> = { LT_5000: '5000万以下', GE_5000: '5000万及以上' }
export function amountTierText(code?: string, fallback = '—'): string {
  return textOf(AMOUNT_TIER, code, fallback)
}

/** 期限档 */
export const TERM_TIER: Record<string, string> = {
  '3M': '3个月', '6M': '6个月', '1Y': '1年', '2Y': '2年', '3Y': '3年', '5Y': '5年'
}
export function termTierText(code?: string, fallback = '—'): string {
  return textOf(TERM_TIER, code, fallback)
}

/** 期限单位(兼容 D/M/Y 简写) */
export const TERM_UNIT: Record<string, string> = { DAY: '天', MONTH: '个月', YEAR: '年', D: '天', M: '个月', Y: '年' }
export function termUnitText(code?: string, fallback = ''): string {
  if (!code) return fallback
  return TERM_UNIT[code] || code
}

/** 定价载体类型 */
export const CARRIER_TYPE: Record<string, string> = { LOAN_CONTRACT: '贷款合同', DEPOSIT_ACCOUNT: '存款账户' }
export function carrierTypeText(code?: string, fallback = '—'): string {
  return textOf(CARRIER_TYPE, code, fallback)
}

/** 担保措施类型 */
export const MEASURE_TYPE: Record<string, string> = {
  MORTGAGE: '抵押物', PLEDGE: '质押物', GUARANTOR: '保证人', CREDIT: '信用',
  BILL_MARGIN: '银票保证金', CREDIT_MARGIN: '信用证保证金', CERTIFICATE_DEPOSIT: '存单质押'
}
export function measureTypeText(code?: string, fallback = '—'): string {
  return textOf(MEASURE_TYPE, code, fallback)
}

/** 资料校验级别 */
export const RULE_LEVEL: Record<string, string> = { BLOCK: '阻断', WARN: '预警', PASS: '通过' }
export function ruleLevelText(code?: string, fallback = '—'): string {
  return textOf(RULE_LEVEL, code, fallback)
}

/** 承诺目标类型 */
export const TARGET_TYPE: Record<string, string> = {
  TARGET_BALANCE: '目标余额', INCREMENT: '承诺新增', CUMULATIVE: '期间累计'
}
export function targetTypeText(code?: string, fallback = '—'): string {
  return textOf(TARGET_TYPE, code, fallback)
}

/** 集团成员角色 */
export const MEMBER_ROLE: Record<string, string> = { CORE: '核心', GENERAL: '一般', MEMBER: '一般' }
export function memberRoleText(code?: string, fallback = '—'): string {
  return textOf(MEMBER_ROLE, code, fallback)
}

/** 节点指派方式 */
export const ASSIGNEE_TYPE: Record<string, string> = { PERSON: '按人', ROLE: '按角色', DEPT: '按部门', GROUP: '按人员组' }
export function assigneeTypeText(code?: string, fallback = '—'): string {
  return textOf(ASSIGNEE_TYPE, code, fallback)
}

/** 数据来源/录入方式 */
export const INPUT_MODE: Record<string, string> = { DW: '数仓', EXCEL: 'Excel导入', MANUAL: '人工' }
export function inputModeText(code?: string, fallback = '—'): string {
  return textOf(INPUT_MODE, code, fallback)
}

/** 利率比较方向 */
export const RATE_DIRECTION: Record<string, string> = {
  LOWER_BETTER: '越低越优惠(贷款)', HIGHER_BETTER: '越高越优惠(存款)'
}
export function rateDirectionText(code?: string, fallback = '—'): string {
  return textOf(RATE_DIRECTION, code, fallback)
}

/** 利率类型 */
export const RATE_TYPE: Record<string, string> = { FIXED: '固定', LPR_PLUS: 'LPR加点' }
export function rateTypeText(code?: string, fallback = '—'): string {
  return textOf(RATE_TYPE, code, fallback)
}

/** 贷款合同状态(数仓 dw_loan_contract_snapshot) */
export const CONTRACT_STATUS: Record<string, string> = { EFFECTIVE: '有效', SETTLED: '结清', OVERDUE: '逾期' }
export function contractStatusText(code?: string, fallback = '—'): string {
  return textOf(CONTRACT_STATUS, code, fallback)
}

/** 币种 */
export const CURRENCY_TEXT: Record<string, string> = { CNY: '人民币', USD: '美元', HKD: '港币' }
export function currencyText(code?: string, fallback = '—'): string {
  return textOf(CURRENCY_TEXT, code, fallback)
}

/** 承诺指标单位(ccr_application_commitment.unit:WAN_YUAN/COUNT) */
export const COMMITMENT_UNIT: Record<string, string> = { WAN_YUAN: '万元', COUNT: '户/笔' }
export function commitmentUnitText(code?: string, fallback = '—'): string {
  return textOf(COMMITMENT_UNIT, code, fallback)
}

/** 配置域 */
export const CONFIG_TYPE: Record<string, string> = {
  LPR: 'LPR 阈值', MATRIX: '权限矩阵', RULE_SET: '利率规则集', PRODUCT_LIMIT: '产品硬边界'
}
export function configTypeText(code?: string, fallback = '—'): string {
  return textOf(CONFIG_TYPE, code, fallback)
}

/** 档案导出类型 */
export const EXPORT_TYPE: Record<string, string> = { ARCHIVE: '申请档案', RESOLUTION: '决议档案' }
export function exportTypeText(code?: string, fallback = '—'): string {
  return textOf(EXPORT_TYPE, code, fallback)
}

// ---------- 角色 ----------

export const ROLE_TEXT: Record<string, string> = {
  customer_manager: '客户经理', branch_manager: '支行行长', dept_gm: '部门总经理',
  vice_president: '分管行长', committee_member: '审批小组成员', president: '总行行长',
  admin: '系统管理员', auditor: '审计员', config_reviewer: '配置复核员'
}
export function roleText(code?: string, fallback = '—'): string {
  return textOf(ROLE_TEXT, code, fallback)
}

// ---------- 产品 ----------

/** 产品编码→产品名(与规则/硬边界配置中的 product_code 对齐) */
export const PRODUCTS: DictItem[] = [
  { code: 'LOAN_A', name: '对公贷款' },
  { code: 'LOAN_P', name: '个人经营性贷款' },
  { code: 'CORP_TIME_DEPOSIT', name: '对公定期存款' },
  { code: 'AGREEMENT_DEPOSIT', name: '协定存款' },
  { code: 'NOTICE_DEPOSIT', name: '通知存款' },
  { code: 'BILL_MARGIN', name: '银票保证金' },
  { code: 'CREDIT_MARGIN', name: '信用证保证金' },
  { code: 'BANK_ACCEPTANCE_MARGIN', name: '银票保证金' },
  { code: 'LC_MARGIN', name: '信用证保证金' }
]
const PRODUCT_NAME_MAP: Record<string, string> = Object.fromEntries(PRODUCTS.map((p) => [p.code, p.name]))

/** 贷款产品下拉选项(option value 保持编码) */
export const LOAN_PRODUCTS: DictItem[] = PRODUCTS.filter((p) => ['LOAN_A', 'LOAN_P'].includes(p.code))
/** 存款产品下拉选项(option value 保持编码) */
export const DEPOSIT_PRODUCTS: DictItem[] = PRODUCTS.filter((p) =>
  ['CORP_TIME_DEPOSIT', 'AGREEMENT_DEPOSIT', 'NOTICE_DEPOSIT', 'BANK_ACCEPTANCE_MARGIN', 'LC_MARGIN'].includes(p.code))

export function productName(code?: string, fallback = '—'): string {
  return textOf(PRODUCT_NAME_MAP, code, fallback)
}

// ---------- 贡献度指标 ----------

/** 指标编码→中文名(§9;合并贡献度组件与申请向导两套口径) */
export const METRIC_CODES: DictItem[] = [
  { code: 'TOTAL', name: '综合贡献总额' },
  { code: 'GM_LOAN_CONTRIBUTION', name: '贷款贡献' },
  { code: 'GM_DEPOSIT_CONTRIBUTION', name: '存款贡献' },
  { code: 'PUBLIC_DEPOSIT_AVG', name: '存款日均' },
  { code: 'PUBLIC_LOAN_AVG', name: '流贷日均' },
  { code: 'PUBLIC_PROJECT_LOAN_AVG', name: '项目贷日均' },
  { code: 'PUBLIC_DISCOUNT', name: '贴现利差收益' },
  { code: 'PUBLIC_DISCOUNT_SPREAD', name: '贴现规模' },
  { code: 'PUBLIC_INTERMEDIATE', name: '对公中间业务收入' },
  { code: 'PUBLIC_OFF_BALANCE_INCOME', name: '对公中间业务收入' },
  { code: 'PUBLIC_EXCHANGE', name: '汇兑利差收益' },
  { code: 'PUBLIC_EXCHANGE_SPREAD', name: '结售汇业务总量' },
  { code: 'PUBLIC_PAYROLL', name: '代发贡献度' },
  { code: 'PUBLIC_PAYROLL_CONTRIBUTION', name: '代发客户数' },
  { code: 'PUBLIC_PAYROLL_AMOUNT', name: '代发金额' },
  { code: 'PUBLIC_WEALTH', name: '对公财富中收' },
  { code: 'PUBLIC_WEALTH_INCOME', name: '对公财富中收' },
  { code: 'PRIVATE_DEPOSIT_AVG', name: '对私存款日均' },
  { code: 'PRIVATE_LOAN_AVG', name: '对私贷款日均' },
  { code: 'PRIVATE_WEALTH', name: '对私财富中收' },
  { code: 'PRIVATE_WEALTH_INCOME', name: '对私财富中收' },
  // §6.4 承诺类型"其它":手工录入(金额或文本),数仓无指标,无数值达成率、不参与机构达成率(D19)
  { code: 'OTHER', name: '其它(手工录入,无数值达成率)' }
]
const METRIC_NAME_MAP: Record<string, string> = Object.fromEntries(METRIC_CODES.map((m) => [m.code, m.name]))

export function metricName(code?: string, fallback = '—'): string {
  return textOf(METRIC_NAME_MAP, code, fallback)
}

/** 指标适用范围 */
export const METRIC_SCOPE: Record<string, string> = {
  PUBLIC: '对公', PRIVATE_SELF: '本人', RELATED: '关联人', GROUP: '集团', GROUP_MEMBER: '集团成员'
}
export function metricScopeText(code?: string, fallback = '—'): string {
  return textOf(METRIC_SCOPE, code, fallback)
}

/** 关联关系类型(V1.0 dw_customer_relation) */
export function relationTypeText(code?: string): string {
  const map: Record<string, string> = {
    GROUP_MEMBER: '集团成员',
    SAME_CONTROLLER: '同一实际控制人',
    CONTROLLER: '实际控制人',
    GUARANTOR: '担保人',
    SPOUSE: '配偶',
    DIRECT_RELATIVE: '直系亲属',
    RELATIVE: '亲属',
    INVESTEE: '被投资企业'
  }
  return code ? (map[code] || code) : '—'
}

/** 授信协议类型(dw_credit_agreement.agreement_type) */
export function agreementTypeText(code?: string): string {
  const map: Record<string, string> = {
    COMPREHENSIVE: '综合授信',
    SINGLE: '单笔单批',
    REVOLVING: '循环授信'
  }
  return code ? (map[code] || code) : '—'
}

/** 授信协议状态(dw_credit_agreement_snapshot.agreement_status) */
export function agreementStatusText(code?: string): string {
  const map: Record<string, string> = {
    EFFECTIVE: '有效',
    EXPIRED: '已到期',
    CLOSED: '已终止'
  }
  return code ? (map[code] || code) : '—'
}

/** 授信协议状态徽标样式 */
export function agreementStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    EFFECTIVE: 'badge badge--success',
    EXPIRED: 'badge badge--warning',
    CLOSED: 'badge badge--neutral'
  }
  return map[code || ''] || 'badge badge--neutral'
}
