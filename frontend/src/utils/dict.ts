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
  APPROVED: '已通过', REJECTED: '已否决', CLOSED: '已关闭',
  ROUTING: '审批中', FINAL: '终态',
  SUBMITTED: '已提交', APPROVED_LEVEL: '权限内已批', VOTING: '小组表决',
  COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议', VETOED: '一票否决'
}
export function appStatusText(code?: string, fallback = '—'): string {
  return textOf(APP_STATUS, code, fallback)
}

/** 定价分项状态(审批流转) */
export const ITEM_STATUS: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', ROUTING: '审批中', APPROVED_LEVEL: '权限内已批',
  VOTING: '小组表决', COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议',
  FINAL: '终态', VETOED: '一票否决', REJECTED: '已否决', CLOSED: '已关闭'
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

// ---------- 节点 / 动作 / 票型 ----------

/** 审批节点编码→岗位名 */
export const NODE_LABELS: Record<string, string> = {
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组', PRESIDENT: '总行行长',
  SECRETARY: '贷审会秘书岗'
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

/** 证件类型(caps 数仓 cert_tp:USCC 对公统一社会信用代码 / IDC 对私身份证;ID_CARD 业务库编码;RDC 对私居民身份证) */
export const CERT_TYPE: Record<string, string> = {
  UNIFIED: '统一社会信用代码', ID: '身份证',
  UNIFIED_SOCIAL: '统一社会信用代码', ID_CARD: '身份证',
  IDC: '身份证', RDC: '居民身份证',
  USCC: '统一社会信用代码'
}
export function certTypeText(code?: string, fallback = '—'): string {
  return textOf(CERT_TYPE, code, fallback)
}

/** 金额档(需求三三档化:2026-08-14) */
export const AMOUNT_TIER: Record<string, string> = {
  LT_1000: '1000万以下', GE_1000_LT_5000: '1000万(含)-5000万', GE_5000: '5000万及以上'
}
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

/** 承诺目标类型(仅 BALANCE/COUNT/RATIO;旧值 TARGET_BALANCE 已收敛映射,INCREMENT/CUMULATIVE 旧行只读) */
export const TARGET_TYPE: Record<string, string> = {
  BALANCE: '余额', COUNT: '笔数', RATIO: '比例'
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
  LOWER_BETTER: '贷款(越低)', HIGHER_BETTER: '存款(越高)'
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

/** 本行融资合同状态徽标变体(配 <span class="badge"> 基类,数仓 contract_status) */
export function contractStatusBadge(code?: string): string {
  return code === 'EFFECTIVE' ? 'badge--success'
    : code === 'SETTLED' ? 'badge--neutral'
    : code === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'
}

/** 币种(ISO 4217 常用) */
export const CURRENCY_TEXT: Record<string, string> = { CNY: '人民币', USD: '美元', HKD: '港币', EUR: '欧元', JPY: '日元' }
export function currencyText(code?: string, fallback = '—'): string {
  return textOf(CURRENCY_TEXT, code, fallback)
}

// ---------- 数仓客户字段 / 快照 / 校验(后端原始码透传,页面中文展示;兼容数仓中文直存) ----------

/** 集团状态(dw_customer_group_snapshot.group_status;数仓定稿:NORMAL 正常 / DISSOLVED 解散) */
export const GROUP_STATUS: Record<string, string> = { NORMAL: '正常', DISSOLVED: '解散' }
export function groupStatusText(code?: string, fallback = '暂无数据'): string {
  return textOf(GROUP_STATUS, code, fallback)
}

/** 集团类型(dw_customer_group_snapshot.group_type;系统固定 INDUSTRY_GROUP) */
export const GROUP_TYPE: Record<string, string> = { INDUSTRY_GROUP: '产业集团' }
export function groupTypeText(code?: string, fallback = '—'): string {
  return textOf(GROUP_TYPE, code, fallback)
}

/** 集团国企属性(state_owned_flag;属性属集团本身,非旗下企业;§用户要求 2026-08-25)
 *  Y=国企集团 / N=非国企集团;数仓/手工集团快照 state_owned_flag 透传 */
export const GROUP_NATURE: Record<string, string> = { Y: '国企集团', N: '非国企集团' }
export function groupNatureText(code?: string, fallback = '—'): string {
  return textOf(GROUP_NATURE, code, fallback)
}

/** 五级分类(dw 数仓码值定稿:010 正常/020 关注/030 次级/040 可疑/050 损失;兼容旧中文直存) */
export const FIVE_LEVEL_CLASS: Record<string, string> = {
  '010': '正常', '020': '关注', '030': '次级', '040': '可疑', '050': '损失'
}
export function fiveLevelClassText(code?: string, fallback = '—'): string {
  return textOf(FIVE_LEVEL_CLASS, code, fallback)
}
/** 五级分类归一化到码值(数仓/补录可能直存中文「正常」,下拉回显/提交需码值) */
export function normalizeFiveLevelClass(v?: string): string {
  if (!v) return ''
  if (v in FIVE_LEVEL_CLASS) return v
  const hit = (Object.entries(FIVE_LEVEL_CLASS) as [string, string][]).find(([, zh]) => zh === v)
  return hit ? hit[0] : v
}
/** 五级分类下拉选项(010-050) */
export const FIVE_LEVEL_OPTIONS: DictItem[] = Object.entries(FIVE_LEVEL_CLASS).map(([code, name]) => ({ code, name }))

/** 企业规模(caps_corp_cust_basic_info.entp_scale;数仓直存中文「大型/中型/小型」,兼容英文码) */
export const ENTP_SCALE: Record<string, string> = { LARGE: '大型', MEDIUM: '中型', SMALL: '小型', MICRO: '微型' }
export function entpScaleText(code?: string, fallback = '—'): string {
  return textOf(ENTP_SCALE, code, fallback)
}

/** 性别(caps_indv_cust_basic_info.gnd;数仓直存 M/F 或中文) */
export const GENDER: Record<string, string> = { M: '男', F: '女' }
export function genderText(code?: string, fallback = '—'): string {
  return textOf(GENDER, code, fallback)
}

/** 婚姻状况(caps_indv_cust_basic_info.mrrg_sittn;数仓直存 MARRIED 等英文码或中文) */
export const MARITAL_STATUS: Record<string, string> = { MARRIED: '已婚', SINGLE: '未婚', DIVORCED: '离异', WIDOWED: '丧偶' }
export function maritalStatusText(code?: string, fallback = '—'): string {
  return textOf(MARITAL_STATUS, code, fallback)
}
/** 婚姻状况归一化到英文 code(数仓可能直存中文,下拉 v-model 需英文 code 才能选中/提交) */
export function maritalStatusCode(v?: string): string {
  if (!v) return ''
  if (v in MARITAL_STATUS) return v
  const hit = (Object.entries(MARITAL_STATUS) as [string, string][]).find(([, zh]) => zh === v)
  return hit ? hit[0] : v
}

/** 集团授信状态(dw_group_credit_snapshot.credit_status;与授信协议状态同口径) */
export const CREDIT_STATUS: Record<string, string> = { EFFECTIVE: '有效', EXPIRED: '已到期', CLOSED: '已终止' }
export function creditStatusText(code?: string, fallback = '—'): string {
  return textOf(CREDIT_STATUS, code, fallback)
}

/** 决议决策来源(ccr_resolution.decision_source;与决议书导出 ResolutionPdfExporter.decisionSourceText 口径一致) */
export const DECISION_SOURCE: Record<string, string> = {
  VOTE_APPROVED: '小组表决通过', PRESIDENT_APPROVED: '行长决策同意',
  LEVEL_APPROVED: '权限内审批通过', COMMITTEE_REJECT: '小组表决否决'
}
export function decisionSourceText(code?: string, fallback = '—'): string {
  return textOf(DECISION_SOURCE, code, fallback)
}

/** 快照记录对象类型(ccr_snapshot_record.subject_type;与 ApplicationSubmitServiceImpl 快照采集口径一致) */
export const SUBJECT_TYPE: Record<string, string> = {
  CUSTOMER: '客户', CORPORATE: '对公客户', INDIVIDUAL: '个人客户',
  CONTRIBUTION: '贡献度', CONTRACT: '贷款合同', NOTE: '借据',
  MEMBER: '集团成员', GROUP: '集团', GROUP_CREDIT: '集团授信',
  MEMBER_LIMIT: '成员额度', FINANCING: '本行融资', DEPOSIT_ACCOUNT: '存款账户'
}
export function subjectTypeText(code?: string, fallback = '—'): string {
  return textOf(SUBJECT_TYPE, code, fallback)
}

/** 资料校验规则(ccr_snapshot_quality_result.rule_code) */
export const RULE_CODE: Record<string, string> = {
  SNAPSHOT_NOT_EMPTY: '快照非空', RECORD_SOURCE_COMPLETE: '数据源完整',
  CUSTOMER_SNAPSHOT_REQUIRED: '客户快照必录', CUSTOMER_UNIQUENESS: '客户唯一性',
  DATA_TIMELINESS: '数据时效', CONTRIBUTION_STAT_CONSISTENCY: '贡献度统计一致性',
  GROUP_MEMBER_VALID: '集团成员有效', CONTRIBUTION_RECONCILE: '贡献度核对'
}
export function ruleCodeText(code?: string, fallback = '—'): string {
  return textOf(RULE_CODE, code, fallback)
}

/** 快照包状态(ccr_snapshot_bundle.status) */
export const SNAPSHOT_STATUS: Record<string, string> = { FROZEN: '已冻结' }
export function snapshotStatusText(code?: string, fallback = '—'): string {
  return textOf(SNAPSHOT_STATUS, code, fallback)
}

/** 借据状态(dw_loan_note_snapshot.note_status;供审批详情/档案复用) */
export const NOTE_STATUS: Record<string, string> = { NORMAL: '正常', SETTLED: '已结清', OVERDUE: '逾期' }
export function noteStatusText(code?: string, fallback = '—'): string {
  return textOf(NOTE_STATUS, code, fallback)
}
/** 借据状态徽标变体(配 <span class="badge"> 基类) */
export function noteStatusBadge(code?: string): string {
  return code === 'NORMAL' ? 'badge--success' : code === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'
}

/** 承诺指标单位(ccr_application_commitment.unit:WAN_YUAN/COUNT;比例型承诺(存贷款比)固定 '%') */
export const COMMITMENT_UNIT: Record<string, string> = { WAN_YUAN: '万元', COUNT: '户/笔', '%': '%' }
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
  admin: '系统管理员', auditor: '审计员', config_reviewer: '配置复核员',
  contract_operator: '合同经办岗', secretary: '贷审会秘书岗'
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

// ---------- 数仓数据集(提交前校验·数据批次差异:表名→中文) ----------
/** 数仓数据集编码→中文(覆盖 DataWarehouseService.relevantDatasets 全部表名) */
export const DATASET_NAMES: DictItem[] = [
  { code: 'caps_corp_cust_basic_info', name: '对公客户基本信息' },
  { code: 'caps_indv_cust_basic_info', name: '个人客户基本信息' },
  { code: 'dw_customer_group_snapshot', name: '集团快照' },
  { code: 'dw_customer_group_member_snapshot', name: '集团成员快照' },
  { code: 'dw_group_credit_snapshot', name: '集团授信快照' },
  { code: 'dw_member_credit_limit_snapshot', name: '成员额度快照' },
  { code: 'dw_loan_contract_snapshot', name: '贷款合同快照' },
  { code: 'dw_loan_note_snapshot', name: '贷款借据快照' },
  { code: 'dw_credit_report_snapshot', name: '征信报告快照' },
  { code: 'dw_credit_financing_summary', name: '授信融资汇总' },
  { code: 'dw_credit_financing_detail', name: '授信融资明细' },
  { code: 'dw_contribution_metric', name: '贡献度指标' },
  { code: 'dw_deposit_account_snapshot', name: '存款账户快照' }
]
const DATASET_NAME_MAP: Record<string, string> = Object.fromEntries(DATASET_NAMES.map((d) => [d.code, d.name]))

export function datasetName(code?: string, fallback = '—'): string {
  return textOf(DATASET_NAME_MAP, code, fallback)
}

// ---------- 贡献度指标 ----------

/** 比例型贡献度指标(数值即百分比量级,展示单位 %;§2026-09-04 用户确认 65=65% 直显)。
 *  数仓 dw_contribution_metric 行 value_type 现推送为 CONTRIBUTION_AMOUNT(表注释未放开 RATIO),
 *  归并/单位映射无法靠 value_type 识别比例型,前端按码特判兜底;数仓放开 RATIO 后可平滑迁移。 */
export const RATIO_METRIC_CODES: ReadonlySet<string> = new Set(['PUBLIC_DEPOSIT_LOAN_RATIO'])
export function isRatioMetric(code?: string | null): boolean {
  return !!code && RATIO_METRIC_CODES.has(code)
}

/** 启用指标下拉(§9;对公启用指标恰好 8 项,20260820 收敛)
 * store/metricDict 初始回退源:接口未加载/数仓无数据时下拉仅展示这 8 项;
 * 数仓表 ccr_metric_definition 非空时以接口返回为准(覆盖此回退)。 */
export const ACTIVE_METRIC_CODES: DictItem[] = [
  { code: 'PUBLIC_DEPOSIT_AVG', name: '存款年日均' },
  { code: 'PUBLIC_PROJECT_LOAN_AVG', name: '贷款年日均' },
  { code: 'PUBLIC_DISCOUNT_SPREAD', name: '贴现年日均' },
  { code: 'PUBLIC_DEPOSIT_LOAN_RATIO', name: '存贷款比' },
  { code: 'PUBLIC_PAYROLL_AMOUNT', name: '当年代发金额' },
  { code: 'PUBLIC_PAYROLL_CONTRIBUTION', name: '当年代发户数' },
  { code: 'PUBLIC_WEALTH_INCOME', name: '理财年日均余额' },
  { code: 'PUBLIC_EXCHANGE_SPREAD', name: '结售汇余额' }
]

/** 指标编码→中文名(§9;合并贡献度组件与申请向导两套口径)
 * 权威来源为 ccr_metric_definition(enabled 接口),此处为接口加载失败/历史码展示的回退映射。
 * 启用下拉取 ACTIVE_METRIC_CODES(恰好 8 项);历史码(如 TOTAL/GM_* 等对私码)仅保留名称回退,不进下拉。 */
export const METRIC_CODES: DictItem[] = [
  ...ACTIVE_METRIC_CODES,
  // ---- 以下为历史/系统内部码,仅作历史数据名称回退,不进启用下拉 ----
  { code: 'TOTAL', name: '综合贡献总额' },
  { code: 'GM_LOAN_CONTRIBUTION', name: '贷款贡献' },
  { code: 'GM_DEPOSIT_CONTRIBUTION', name: '存款贡献' },
  { code: 'PUBLIC_LOAN_AVG', name: '流贷日均' },
  { code: 'PUBLIC_DISCOUNT', name: '贴现利差收益' },
  { code: 'PUBLIC_INTERMEDIATE', name: '对公中间业务收入' },
  { code: 'PUBLIC_OFF_BALANCE_INCOME', name: '对公中间业务收入' },
  { code: 'PUBLIC_EXCHANGE', name: '汇兑利差收益' },
  { code: 'PUBLIC_PAYROLL', name: '代发贡献度' },
  { code: 'PUBLIC_WEALTH', name: '对公财富中收' },
  { code: 'PRIVATE_DEPOSIT_AVG', name: '对私存款日均' },
  { code: 'PRIVATE_LOAN_AVG', name: '对私贷款日均' },
  { code: 'PRIVATE_WEALTH', name: '对私财富中收' },
  { code: 'PRIVATE_WEALTH_INCOME', name: '对私财富中收' },
  { code: 'OTHER', name: '其它(手工录入,无数值达成率)' }
]
const METRIC_NAME_MAP: Record<string, string> = Object.fromEntries(METRIC_CODES.map((m) => [m.code, m.name]))

/**
 * 注册指标字典(ccr_metric_definition 接口返回):覆盖/补充静态映射。
 * metricName() 优先查注册后的映射;接口未加载/失败时回退静态 METRIC_CODES,行为与改造前一致。
 */
export function registerMetricDict(items: DictItem[]) {
  for (const item of items) {
    METRIC_NAME_MAP[item.code] = item.name
  }
}

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

/** 授信协议类型下拉选项(新增授信手工录入,与 agreementTypeText 对齐;2026-08-25 用户要求改为综合授信/临时授信) */
export const AGREEMENT_TYPES: DictItem[] = [
  { code: 'COMPREHENSIVE', name: '综合授信' },
  { code: 'TEMPORARY', name: '临时授信' }
]

/** 授信协议类型(dw_credit_agreement.agreement_type;2026-08-26 统一:只分综合授信/临时授信,SINGLE/REVOLVING 旧值归并综合授信) */
export function agreementTypeText(code?: string): string {
  const map: Record<string, string> = {
    COMPREHENSIVE: '综合授信',
    TEMPORARY: '临时授信',
    SINGLE: '综合授信',
    REVOLVING: '综合授信'
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

/** 内部合成客户号识别(MANUAL- 前缀):非我行客户手工补录成员的内部落库标识(表结构客户号列 NOT NULL 要求) */
export function isManualCustomerNo(no?: string | null): boolean {
  return !!no && no.startsWith('MANUAL-')
}

/** 占位客户号识别(NEW 前缀):新增客户无客户号,提交时/审批中按证件号反查数仓回填真实号(2026-08-20 #017) */
export function isPlaceholderCustomerNo(no?: string | null): boolean {
  return !!no && no.startsWith('NEW')
}

/** 客户号展示:内部合成号→"非我行客户";占位号→"新增客户(待回填)";其余原样;空→"—" */
export function customerNoText(no?: string | null): string {
  if (isManualCustomerNo(no)) return '非我行客户'
  if (isPlaceholderCustomerNo(no)) return '新增客户(待回填)'
  return no || '—'
}

/* ========================================================================
 * 统一状态 → 徽标类映射(全系统单一来源,2026-08-26 UI 审查 T2)
 * 解决跨页不一致(PARTIAL_APPROVED 历史页绿/贡献度页黄等)与映射缺失落灰。
 * 语义约定:success=通过/终态绿;danger=否决/异常红;warning=待办/关注黄;
 * info=流转中蓝;neutral=中性灰(草稿/关闭/弃权等无倾向态)。
 * 返回值含基类,模板直接 <span :class="appStatusBadge(s)"> 使用。
 * ===================================================================== */

function badgeOf(mod: string): string {
  return `badge badge--${mod}`
}

/** 申请主单状态徽标(历史/档案/贡献度全系统统一) */
export function appStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    APPROVED: 'success', COMMITTEE_PASS: 'success', FINAL: 'success',
    REJECTED: 'danger', VETOED: 'danger',
    PRESIDENT_DECISION: 'warning', PARTIAL_APPROVED: 'warning',
    SUBMITTING: 'info', PROCESSING: 'info', ROUTING: 'info', SUBMITTED: 'info',
    APPROVED_LEVEL: 'info', VOTING: 'info',
    DRAFT: 'neutral', CLOSED: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 定价分项状态徽标(审批流转/档案) */
export function itemStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    COMMITTEE_PASS: 'success', FINAL: 'success', APPROVED_LEVEL: 'info',
    VETOED: 'danger', REJECTED: 'danger',
    PRESIDENT_DECISION: 'warning',
    SUBMITTED: 'info', ROUTING: 'info', VOTING: 'info',
    DRAFT: 'neutral', CLOSED: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 表决批次状态徽标 */
export function roundStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    PASSED: 'success', FAILED: 'danger', VOTING: 'info',
    CANCELLED: 'neutral', CLOSED: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 决议/执行状态徽标(档案) */
export function execStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    ISSUED: 'success', EXECUTED: 'success', CONTRACT_BOUND: 'info',
    CONTRACT_PENDING: 'warning', RECONCILE_EXCEPTION: 'danger',
    VOID: 'neutral', CLOSED: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 承诺计划状态徽标(贡献度) */
export function planStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    ACHIEVED: 'success',
    AT_RISK: 'danger', EXPIRED_UNMET: 'danger',
    TRACKING: 'info',
    DATA_PENDING: 'warning',
    PENDING: 'neutral', TERMINATED: 'neutral', SUPERSEDED: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 表决票型徽标(ABSTAIN 弃权=中性灰,勿误标为否决红) */
export function voteChoiceBadge(code?: string): string {
  const map: Record<string, string> = {
    APPROVE: 'success', AGREE: 'success',
    REJECT: 'danger', DISAGREE: 'danger',
    ABSTAIN: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 行长决策徽标 */
export function decisionBadge(code?: string): string {
  const map: Record<string, string> = {
    AGREE: 'success', APPROVE: 'success', VETO: 'danger'
  }
  return badgeOf(map[code || ''] || 'neutral')
}

/** 配置状态徽标(参数管理) */
export function configStatusBadge(code?: string): string {
  const map: Record<string, string> = {
    EFFECTIVE: 'success', DRAFT: 'neutral',
    REVIEW: 'warning', INVALID: 'neutral'
  }
  return badgeOf(map[code || ''] || 'neutral')
}
