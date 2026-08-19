<template>
  <div>
    <div class="section-head">
      <div class="section-title">
        申请档案
        <span v-if="archive.application" class="app-no">{{ val(archive.application, 'application_no', 'applicationNo') }}</span>
      </div>
      <InfoTip content="申请档案(§14.4):展示申请材料、资料校验、审批轨迹、调价记录、表决与行长决策、决议与执行核验等审批全过程完整留痕;承诺履约等后续跟踪不在档案展示。" />
      <div class="head-actions">
        <button class="btn btn--secondary" @click="router.push('/history')">返回列表</button>
        <button v-if="hasResolution" class="btn btn--secondary" :disabled="resolving" @click="doResolutionDoc">
          {{ resolving ? '生成中…' : '下载决议书' }}
        </button>
        <button v-if="canExport" class="btn btn--primary" :disabled="exporting" @click="doExport">
          {{ exporting ? '导出中…' : '导出档案' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="card empty-block">加载中…</div>
    <template v-else-if="archive.application">
      <!-- 0. 数据来源与快照信息(§12.16-7):档案展示提交冻结快照/人工录入/人工修正/实时取数来源 -->
      <div class="card" v-if="source">
        <div class="card__head">
          <span>数据来源</span>
          <span class="badge" :class="source === 'SNAPSHOT' ? 'badge--success' : source === 'MANUAL' ? 'badge--danger' : 'badge--warning'">{{ sourceText }}</span>
        </div>
        <div class="desc-grid" v-if="source === 'SNAPSHOT'">
          <div class="desc-item"><span class="desc-label">数据日期</span>{{ snapshotInfo.dataDt || '—' }}</div>
          <div class="desc-item"><span class="desc-label">冻结时间</span>{{ fmtTime(snapshotInfo.freezeTime) }}</div>
          <div class="desc-item"><span class="desc-label">快照批次号</span>{{ snapshotInfo.bundleNo || '—' }}</div>
        </div>
        <div v-else class="empty-block">{{ sourceNote }}</div>
      </div>

      <!-- 1. 申请内容 -->
      <div class="card">
        <div class="card__head"><span>申请内容</span><span :class="badgeClass(val(archive.application, 'status'))">{{ appStatusText(val(archive.application, 'status')) }}</span></div>
        <div class="desc-grid">
          <div class="desc-item"><span class="desc-label">申请号</span>{{ val(archive.application, 'application_no', 'applicationNo') }}</div>
          <div class="desc-item"><span class="desc-label">业务类型</span>{{ businessTypeText(val(archive.application, 'business_type', 'businessType')) }}</div>
          <div class="desc-item"><span class="desc-label">客户范围</span>{{ customerScopeText(val(archive.application, 'customer_scope', 'customerScope')) }}</div>
          <div class="desc-item"><span class="desc-label">客户号</span>{{ val(archive.application, 'customer_no', 'customerNo') }}</div>
          <div class="desc-item"><span class="desc-label">集团号</span>{{ val(archive.application, 'group_no', 'groupNo') }}</div>
          <div class="desc-item"><span class="desc-label">提交时间</span>{{ fmtTime(val(archive.application, 'submit_time', 'submitTime')) }}</div>
          <div class="desc-item"><span class="desc-label">终态时间</span>{{ fmtTime(val(archive.application, 'final_time', 'finalTime')) }}</div>
          <div class="desc-item"><span class="desc-label">关联原申请</span>{{ val(archive.application, 'source_application_id', 'sourceApplicationId') }}</div>
          <div class="desc-item desc-item--full"><span class="desc-label">客户经理备注</span>{{ val(archive.application, 'application_remark', 'applicationRemark') }}</div>
        </div>
      </div>

      <!-- 1b. 客户基本信息(提交快照优先 + 人工修正/手工录入) -->
      <div class="card">
        <div class="card__head"><span>客户基本信息</span></div>
        <div class="desc-grid" v-if="hasCustomer">
          <div class="desc-item"><span class="desc-label">客户名称</span>{{ customerName }}</div>
          <div class="desc-item"><span class="desc-label">行内客户号</span>{{ customer.customerNo || '—' }}</div>
          <div class="desc-item"><span class="desc-label">客户类型</span>{{ isCorpCustomer ? '对公' : isIndivCustomer ? '个人' : '—' }}</div>
          <div v-if="isCorpCustomer" class="desc-item"><span class="desc-label">统一社会信用代码</span>{{ customer.certNo || '—' }}</div>
          <div v-if="isIndivCustomer" class="desc-item"><span class="desc-label">证件号码</span>{{ customer.certNo || '—' }}</div>
          <div v-if="customer.entpCharic" class="desc-item"><span class="desc-label">企业性质</span>{{ customerTypeText(customer.entpCharic) }}</div>
          <div v-if="customer.entpScale" class="desc-item"><span class="desc-label">企业规模</span>{{ customer.entpScale }}</div>
          <div v-if="customer.industry" class="desc-item"><span class="desc-label">所属行业</span>{{ customer.industry }}</div>
          <div v-if="customer.creditLevel" class="desc-item"><span class="desc-label">内部信用等级</span>{{ customer.creditLevel }}</div>
          <div v-if="customer.fiveLevelClass" class="desc-item"><span class="desc-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
          <div v-if="customer.empeNum != null" class="desc-item"><span class="desc-label">员工人数</span>{{ customer.empeNum }}</div>
          <div v-if="customer.totalAssets != null" class="desc-item"><span class="desc-label">总资产(万元)</span>{{ customer.totalAssets }}</div>
          <div v-if="customer.registeredCapital != null" class="desc-item"><span class="desc-label">注册资本(万元)</span>{{ customer.registeredCapital }}</div>
          <div v-if="customer.estbDate" class="desc-item"><span class="desc-label">成立日期</span>{{ customer.estbDate }}</div>
          <div v-if="customer.restAddr" class="desc-item"><span class="desc-label">注册地址</span>{{ customer.restAddr }}</div>
          <div v-if="customer.occupation" class="desc-item"><span class="desc-label">职业</span>{{ customer.occupation }}</div>
          <div v-if="customer.annualIncome != null" class="desc-item"><span class="desc-label">年收入(万元)</span>{{ customer.annualIncome }}</div>
          <div v-if="customer.maritalStatus" class="desc-item"><span class="desc-label">婚姻状况</span>{{ customer.maritalStatus }}</div>
          <div v-if="customer.address" class="desc-item"><span class="desc-label">居住地址</span>{{ customer.address }}</div>
          <div v-if="customer.phone" class="desc-item"><span class="desc-label">联系电话</span>{{ customer.phone }}</div>
          <div v-if="customer.openOrgName" class="desc-item"><span class="desc-label">开户机构</span>{{ customer.openOrgName }}</div>
          <div v-if="customer.openDate" class="desc-item"><span class="desc-label">开户日期</span>{{ customer.openDate }}</div>
          <div v-if="customer.basicAccount" class="desc-item"><span class="desc-label">基本户账户</span>{{ customer.basicAccount }}</div>
          <div v-if="customer.customerClass" class="desc-item"><span class="desc-label">客户分类</span>{{ customerClassText(customer.customerClass) }}</div>
        </div>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 2. 集团与成员 -->
      <div class="card" v-if="isGroup">
        <div class="card__head"><span>集团成员</span></div>
        <table class="table table--full" v-if="archive.members?.length">
          <thead><tr><th>成员客户号</th><th>成员角色</th><th>申请金额(万元)</th></tr></thead>
          <tbody>
            <tr v-for="(m, i) in archive.members" :key="i">
              <td>{{ val(m, 'member_customer_no', 'memberCustomerNo') }}</td>
              <td>{{ memberRoleText(val(m, 'member_role', 'memberRole')) }}</td>
              <td class="num">{{ val(m, 'request_amount', 'requestAmount') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
        <!-- 集团授信与贡献度(§12.4 集团场景) -->
        <div class="desc-grid" v-if="groupCredit.length" style="margin-top:12px">
          <div class="desc-item"><span class="desc-label">集团授信总额(万元)</span>{{ groupCredit[0].approvedTotalAmount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">已分配额度</span>{{ groupCredit[0].allocatedAmount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">已用额度</span>{{ groupCredit[0].usedAmount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">可用额度</span>{{ groupCredit[0].availableAmount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">授信到期日</span>{{ groupCredit[0].creditEnd || '—' }}</div>
          <div class="desc-item"><span class="desc-label">授信状态</span>{{ groupCredit[0].creditStatus || '—' }}</div>
          <div class="desc-item"><span class="desc-label">集团贡献度</span>{{ groupContributionText }}</div>
        </div>
      </div>

      <!-- 2b. 授信信息(补录 + 数仓协议合并去重) -->
      <div class="card">
        <div class="card__head"><span>授信信息</span></div>
        <table class="table table--full" v-if="creditAgreements.length">
          <thead><tr><th>授信协议编号</th><th>授信类型</th><th>币种</th><th>状态</th><th>开始日期</th><th>结束日期</th><th>授信额度(万元)</th><th>已用额度(万元)</th><th>可用额度(万元)</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in creditAgreements" :key="i">
              <td>
                {{ a.agreementNo || '—' }}
                <span v-if="a.source === 'APPLICATION'" class="badge badge--warning" style="margin-left:4px">补录</span>
              </td>
              <td>{{ agreementTypeText(a.agreementType) }}</td>
              <td>{{ a.currency || 'CNY' }}</td>
              <td><span :class="agreementStatusBadge(a.agreementStatus)">{{ agreementStatusText(a.agreementStatus) }}</span></td>
              <td>{{ a.startDate || '—' }}</td>
              <td>{{ a.endDate || '—' }}</td>
              <td class="num">{{ a.creditAmount ?? '—' }}</td>
              <td class="num">{{ a.usedAmount ?? '—' }}</td>
              <td class="num">{{ a.availableAmount ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无授信协议数据</div>
      </div>

      <!-- 2c. 本行融资(提交快照/数仓贷款合同) -->
      <div class="card">
        <div class="card__head"><span>本行融资</span></div>
        <table class="table table--full" v-if="financing.length">
          <thead><tr><th>合同号</th><th>授信协议号</th><th>合同金额(万元)</th><th>余额(万元)</th><th>执行利率</th><th>利率类型</th><th>期限</th><th>合同状态</th><th>担保类型</th><th>币种</th></tr></thead>
          <tbody>
            <tr v-for="f in financing" :key="f.contractNo">
              <td>{{ f.contractNo }}</td>
              <td>{{ f.agreementNo || '—' }}</td>
              <td class="num">{{ f.contractAmount ?? '—' }}</td>
              <td class="num">{{ f.loanBalance ?? '—' }}</td>
              <td class="num">{{ rateText(f.contractRate) }}</td>
              <td>{{ rateTypeText(f.rateType) }}{{ f.lprTerm ? `·${f.lprTerm}` : '' }}</td>
              <td class="nowrap">{{ f.startDate ? `${String(f.startDate).slice(0, 10)} ~ ${f.maturityDate ? String(f.maturityDate).slice(0, 10) : '—'}` : '—' }}</td>
              <td><span class="badge" :class="contractStatusBadge(f.contractStatus)">{{ contractStatusText(f.contractStatus) }}</span></td>
              <td>{{ guaranteeTypeText(f.guaranteeType) }}</td>
              <td>{{ currencyText(f.currency) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 2d. 申请材料附件(申请时上传材料元数据,下载走附件下载接口) -->
      <div class="card">
        <div class="card__head"><span>申请材料附件</span><span class="badge badge--info">{{ attachments.length }} 个附件</span></div>
        <table class="table table--full" v-if="attachments.length">
          <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in attachments" :key="i">
              <td>{{ a.fileName }}</td>
              <td class="num">{{ (a.fileSize / 1024).toFixed(1) }} KB</td>
              <td>{{ fmtTime(a.createTime) }}</td>
              <td><button class="btn btn--text" @click="downloadAttachment(a)">下载</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无附件(申请时未上传材料)</div>
      </div>

      <!-- 2e. 他行融资(申请人工补录/Excel 导入 + 数仓征信) -->
      <div class="card" v-if="isLoan">
        <div class="card__head"><span>他行融资</span></div>
        <div class="desc-grid" v-if="otherLoanSummary.length">
          <div class="desc-item"><span class="desc-label">他行机构数</span>{{ otherLoanSummary[0].lenderCount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">授信总额</span>{{ otherLoanSummary[0].creditAmountTotal ?? '—' }} 万元</div>
          <div class="desc-item"><span class="desc-label">已用总额</span>{{ otherLoanSummary[0].usedAmountTotal ?? '—' }} 万元</div>
          <div class="desc-item"><span class="desc-label">未结清笔数</span>{{ otherLoanSummary[0].loanAccountCount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">逾期账户</span>{{ otherLoanSummary[0].overdueAccountCount ?? '—' }}</div>
          <div class="desc-item"><span class="desc-label">逾期余额</span>{{ otherLoanSummary[0].overdueBalance ?? '—' }} 万元</div>
          <div class="desc-item"><span class="desc-label">不良余额</span>{{ otherLoanSummary[0].nplBalance ?? '—' }} 万元</div>
          <div class="desc-item"><span class="desc-label">关注类余额</span>{{ otherLoanSummary[0].specialMentionBalance ?? '—' }} 万元</div>
          <div class="desc-item"><span class="desc-label">对外担保余额</span>{{ otherLoanSummary[0].externalGuaranteeBalance ?? '—' }} 万元</div>
        </div>
        <table class="table table--full" v-if="otherLoans.length" style="margin-top:8px">
          <thead><tr><th>融资机构</th><th>授信额(万元)</th><th>已用额(万元)</th><th>余额(万元)</th><th>年化利率(%)</th><th>数据日期</th><th>来源</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in otherLoans" :key="i">
              <td>{{ d.lenderName }}</td>
              <td class="num">{{ d.creditAmount ?? '—' }}</td>
              <td class="num">{{ d.usedAmount ?? '—' }}</td>
              <td class="num">{{ d.balanceAmount ?? '—' }}</td>
              <td class="num">{{ d.annualRate ?? '—' }}</td>
              <td>{{ d.dataDt ? String(d.dataDt).slice(0, 10) : '—' }}</td>
              <td><span class="badge badge--neutral">{{ inputModeText(d.inputMode) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!otherLoans.length" class="empty-block">暂无他行融资记录</div>
      </div>

      <!-- 3. 贷款合同/存款账户 -->
      <div class="card">
        <div class="card__head"><span>贷款合同 / 存款账户</span></div>
        <table class="table table--full" v-if="archive.contracts?.length">
          <thead><tr><th>分项</th><th>合同业务标识</th><th>正式合同号</th><th>拟签合同</th></tr></thead>
          <tbody>
            <tr v-for="(c, i) in archive.contracts" :key="i">
              <td>{{ val(c, 'pricingItemId', 'pricing_item_id') }}</td>
              <td>{{ val(c, 'contractBusinessKey', 'contract_business_key') }}</td>
              <td>{{ val(c, 'loanContractNo', 'loan_contract_no') }}</td>
              <td>{{ plannedText(val(c, 'plannedContractFlag', 'planned_contract_flag')) }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table table--full" v-if="archive.depositAccounts?.length" :style="archive.contracts?.length ? 'margin-top:8px' : ''">
          <thead><tr><th>分项</th><th>存款账号</th><th>拟开户</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in archive.depositAccounts" :key="i">
              <td>{{ val(d, 'pricingItemId', 'pricing_item_id') }}</td>
              <td>{{ val(d, 'depositAccountNo', 'deposit_account_no') }}</td>
              <td>{{ plannedText(val(d, 'plannedAccountFlag', 'planned_account_flag')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!archive.contracts?.length && !archive.depositAccounts?.length" class="empty-block">暂无数据</div>
      </div>

      <!-- 4. 合同下借据(数仓最新批次) -->
      <div class="card">
        <div class="card__head"><span>合同下借据</span><span v-if="archive.notes?.length" class="badge badge--info">数仓批次 {{ val(archive.notes[0], 'dataDt', 'data_dt') }}</span></div>
        <table class="table table--full" v-if="archive.notes?.length">
          <thead>
            <tr><th>借据号</th><th>合同号</th><th>借据金额</th><th>借据余额</th><th>币种</th><th>执行利率</th><th>起息日</th><th>到期日</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="(n, i) in archive.notes" :key="i">
              <td>{{ val(n, 'loanNoteNo', 'loan_note_no') }}</td>
              <td>{{ val(n, 'contractNo', 'contract_no') }}</td>
              <td class="num">{{ val(n, 'loanAmount', 'loan_amount') }}</td>
              <td class="num">{{ val(n, 'loanBalance', 'loan_balance') }}</td>
              <td>{{ currencyText(val(n, 'currency')) }}</td>
              <td class="num">{{ rateText(val(n, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ fmtDate(val(n, 'startDate', 'start_date')) }}</td>
              <td>{{ fmtDate(val(n, 'maturityDate', 'maturity_date')) }}</td>
              <td><span class="badge" :class="noteStatusBadge(val(n, 'noteStatus', 'note_status'))">{{ noteStatusText(val(n, 'noteStatus', 'note_status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 5. 定价分项 -->
      <div class="card">
        <div class="card__head"><span>定价分项</span></div>
        <table class="table table--full" v-if="archive.pricingItems?.length">
          <thead>
            <tr>
              <th>分项号</th><th>定价客户</th><th>产品</th><th>金额(万元)</th><th>期限</th>
              <th>申请利率</th><th>审批利率</th><th>最终利率</th><th>当前节点</th><th>终审岗位</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in archive.pricingItems" :key="val(p, 'id')">
              <td>{{ val(p, 'pricing_item_no', 'pricingItemNo') }}</td>
              <td>{{ val(p, 'pricing_customer_no', 'pricingCustomerNo') }}</td>
              <td>{{ productName(val(p, 'product_code', 'productCode')) }}</td>
              <td class="num">{{ val(p, 'pricing_amount', 'pricingAmount') }}</td>
              <td>{{ termText(p) }}</td>
              <td class="num">{{ rateText(val(p, 'requested_rate', 'requestedRate')) }}</td>
              <td class="num">{{ rateText(val(p, 'current_approval_rate', 'currentApprovalRate')) }}</td>
              <td class="num"><b>{{ rateText(val(p, 'final_rate', 'finalRate')) }}</b></td>
              <td>{{ nodeLabel(val(p, 'current_node_code', 'currentNodeCode')) }}</td>
              <td>{{ nodeLabel(val(p, 'route_code', 'routeCode')) }}</td>
              <td><span :class="badgeClass(val(p, 'status'))">{{ itemStatusText(val(p, 'status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 5a. 担保分项明细(申请录入,按分项挂载;含担保措施扩展明细) -->
      <div class="card" v-if="hasGuarantees">
        <div class="card__head"><span>担保分项</span></div>
        <div v-for="(p, pi) in archive.pricingItems" :key="val(p, 'id')">
          <div v-if="guaranteesOf(p).length" class="plan-block" :style="pi ? 'margin-top:12px' : ''">
            <div class="plan-block__head">
              <span class="badge badge--info">{{ val(p, 'pricing_item_no', 'pricingItemNo') }}</span>
              <span class="section-tip">申请担保明细</span>
            </div>
            <table class="table table--full">
              <thead><tr><th>担保方式</th><th>担保措施</th><th>担保金额(万元)</th><th>措施明细</th></tr></thead>
              <tbody>
                <tr v-for="(g, gi) in guaranteesOf(p)" :key="gi">
                  <td>{{ guaranteeTypeText(g.guaranteeType) }}</td>
                  <td>{{ measureTypeText(g.measureType) }}</td>
                  <td class="num">{{ g.guaranteeAmount ?? '—' }}</td>
                  <td class="hash-cell">{{ extText(g) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- 5d. 贡献度参考(当前与拟达成贡献度并排;G3 定价依据,贷款场景) -->
      <div class="card" v-if="isLoan">
        <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
        <ContributionPanel :contribution="contribution" :commitments="archive.commitments || []" />
      </div>

      <!-- 5e. 机构达成(申请机构最新批次) -->
      <div class="card" v-if="orgPerformance.length">
        <div class="card__head"><span>机构达成</span></div>
        <table class="table table--full">
          <thead><tr><th>机构</th><th>统计月份</th><th>达成金额(万元)</th><th>目标金额(万元)</th><th>达成率</th><th>数据日期</th></tr></thead>
          <tbody>
            <tr v-for="o in orgPerformance" :key="o.orgCode">
              <td>{{ o.orgCode || '—' }}</td>
              <td>{{ o.statMonth }}</td>
              <td class="num">{{ o.achievedAmount ?? '—' }}</td>
              <td class="num">{{ o.expectedAmount ?? '—' }}</td>
              <td class="num">{{ o.completionRate != null ? `${o.completionRate}%` : '—' }}</td>
              <td>{{ fmtDate(o.dataDt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 5b. 关联人(申请录入,按关联客户号补全基本信息/授信信息) -->
      <div class="card" v-if="archive.relatedPersons?.length">
        <div class="card__head"><span>关联人</span></div>
        <table class="table table--full">
          <thead>
            <tr><th>姓名/名称</th><th>证件号</th><th>关系类型</th><th>行内客户号</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in archive.relatedPersons" :key="i">
              <td>{{ val(r, 'personName') }}</td>
              <td>{{ val(r, 'certNo') }}</td>
              <td>{{ relationTypeText(val(r, 'relationType')) }}</td>
              <td>{{ val(r, 'relatedCustomerNo') }}</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'entpCharic') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'industry') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'creditLevel') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'fiveLevelClass') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ val(r, 'occupation') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ val(r, 'annualIncome') }}</td>
              <td v-else>—</td>
              <td class="num">{{ val(r, 'creditAgreementCount') }}</td>
              <td class="num">{{ val(r, 'loanBalanceTotal') }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 6. 数据快照日期 -->
      <div class="card">
        <div class="card__head"><span>数据快照</span></div>
        <table class="table table--full" v-if="archive.snapshotBundles?.length">
          <thead><tr><th>快照包号</th><th>状态</th><th>冻结时间</th><th>记录数</th><th>摘要哈希</th></tr></thead>
          <tbody>
            <tr v-for="b in archive.snapshotBundles" :key="val(b, 'id')">
              <td>{{ val(b, 'bundleNo', 'bundle_no') }}</td>
              <td>{{ val(b, 'status') }}</td>
              <td>{{ fmtTime(val(b, 'freezeTime', 'freeze_time')) }}</td>
              <td class="num">{{ val(b, 'recordCount', 'record_count') }}</td>
              <td class="hash-cell">{{ val(b, 'bundleHash', 'bundle_hash') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 7. 资料校验(提交时质量校验 PASS/WARN/BLOCK 明细) -->
      <div class="card" v-if="archive.qualityResults?.length">
        <div class="card__head">
          <span>资料校验</span>
          <span :class="qualityBadge">{{ ruleLevelText(qualityOverall) }}</span>
        </div>
        <table class="table table--full">
          <thead><tr><th>校验规则</th><th>级别</th><th>对象</th><th>提示</th><th>校验时间</th></tr></thead>
          <tbody>
            <tr v-for="(q, i) in archive.qualityResults" :key="i">
              <td>{{ val(q, 'ruleCode', 'rule_code') }}</td>
              <td><span class="badge" :class="ruleLevelBadge(val(q, 'ruleLevel', 'rule_level'))">{{ ruleLevelText(val(q, 'ruleLevel', 'rule_level')) }}</span></td>
              <td>{{ val(q, 'subjectType', 'subject_type') }} · {{ val(q, 'subjectId', 'subject_id') }}</td>
              <td>{{ val(q, 'message') }}</td>
              <td>{{ fmtTime(val(q, 'checkedTime', 'checked_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 8. 审批轨迹 -->
      <div class="card">
        <div class="card__head"><span>审批轨迹</span></div>
        <table class="table table--full" v-if="archive.approvalActions?.length">
          <thead>
            <tr><th>节点</th><th>动作</th><th>操作人</th><th>操作角色</th><th>调整前利率</th><th>调整后利率</th><th>意见</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="(a, i) in archive.approvalActions" :key="i">
              <td>{{ nodeLabel(val(a, 'node_code', 'nodeCode')) }}</td>
              <td><span :class="actionBadge(val(a, 'action_type', 'actionType'))">{{ actionText(val(a, 'action_type', 'actionType')) }}</span></td>
              <td>{{ val(a, 'operator_name', 'operatorName') }}</td>
              <td>{{ roleText(val(a, 'operator_role', 'operatorRole')) }}</td>
              <td class="num">{{ rateText(val(a, 'before_rate', 'beforeRate')) }}</td>
              <td class="num">{{ rateText(val(a, 'after_rate', 'afterRate')) }}</td>
              <td>{{ val(a, 'action_comment', 'actionComment') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 8b. 审批调价记录(利率调整明细,含边界/理由/操作人) -->
      <div class="card" v-if="archive.rateAdjustments?.length">
        <div class="card__head"><span>审批调价记录</span></div>
        <table class="table table--full">
          <thead><tr><th>节点</th><th>调整前利率</th><th>调整后利率</th><th>调价理由</th><th>操作人</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in archive.rateAdjustments" :key="i">
              <td>{{ nodeLabel(val(a, 'node_code', 'nodeCode')) }}</td>
              <td class="num">{{ rateText(val(a, 'before_rate', 'beforeRate')) }}</td>
              <td class="num"><b>{{ rateText(val(a, 'after_rate', 'afterRate')) }}</b></td>
              <td>{{ val(a, 'adjust_reason', 'adjustReason') }}</td>
              <td>{{ val(a, 'operatorName', 'operator_name') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 9. 表决与行长决策(小组表决计票汇总 + 行长决策;仅行长·审计·超管可见) -->
      <div class="card" v-if="canViewVote && (archive.voteRounds?.length || archive.presidentDecisions?.length)">
        <div class="card__head"><span>表决与行长决策</span></div>
        <table class="table table--full" v-if="archive.voteRounds?.length">
          <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
          <tbody>
            <tr v-for="(v, i) in archive.voteRounds" :key="i">
              <td>{{ val(v, 'roundName', 'round_name') }}</td>
              <td><span class="badge" :class="val(v, 'status') === 'PASSED' ? 'badge--success' : val(v, 'status') === 'FAILED' ? 'badge--danger' : 'badge--warning'">{{ roundStatusText(val(v, 'status')) }}</span></td>
              <td class="num">{{ voteResultOf(val(v, 'id')) }}</td>
              <td>{{ fmtTime(val(v, 'roundStartTime', 'round_start_time')) }}</td>
              <td>{{ fmtTime(val(v, 'roundEndTime', 'round_end_time')) }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table table--full" style="margin-top:8px" v-if="archive.presidentDecisions?.length">
          <thead><tr><th>行长决策</th><th>意见</th><th>决策时间</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in archive.presidentDecisions" :key="i">
              <td><span class="badge" :class="val(d, 'decision') === 'AGREE' ? 'badge--success' : val(d, 'decision') === 'VETO' ? 'badge--danger' : 'badge--warning'">{{ decisionText(val(d, 'decision')) }}</span></td>
              <td>{{ val(d, 'opinion') }}</td>
              <td>{{ fmtTime(val(d, 'decisionTime', 'decision_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 10. 决议与执行核验(§12.7:决议日期=issue_time,无有效期周期) -->
      <div class="card" v-if="archive.resolutions?.length">
        <div class="card__head"><span>决议</span><span class="badge badge--success">已签发</span></div>
        <table class="table table--full">
          <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="(r, i) in archive.resolutions" :key="i">
              <td>{{ val(r, 'resolutionNo', 'resolution_no') }}</td>
              <td class="num"><b>{{ rateText(val(r, 'finalRate', 'final_rate')) }}</b></td>
              <td>{{ val(r, 'decisionSource', 'decision_source') }}</td>
              <td>{{ fmtDate(val(r, 'issueTime', 'issue_time')) }}</td>
              <td><span class="badge" :class="resolutionStatusBadge(val(r, 'status'))">{{ execStatusText(val(r, 'status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <table class="table table--full" style="margin-top:8px" v-if="archive.resolutionExecutions?.length">
          <thead><tr><th>贷款合同号</th><th>补充协议号</th><th>执行利率</th><th>执行状态</th><th>核验结果</th><th>核验时间</th></tr></thead>
          <tbody>
            <tr v-for="(e, i) in archive.resolutionExecutions" :key="i">
              <td>{{ val(e, 'loanContractNo', 'loan_contract_no') }}</td>
              <td>{{ val(e, 'supplementAgreementNo', 'supplement_agreement_no') }}</td>
              <td class="num">{{ rateText(val(e, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ execStatusText(val(e, 'executionStatus', 'execution_status')) }}</td>
              <td>{{ val(e, 'reconcileResult', 'reconcile_result') }}</td>
              <td>{{ fmtTime(val(e, 'reconcileTime', 'reconcile_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 11. 历史履约(该申请承诺计划 + 逐期指标完成情况;与贡献度跟踪同源,按申请挂钩) -->
      <div class="card" v-if="commitmentGroups.length">
        <div class="card__head">
          <span>历史履约 <InfoTip content="该申请审批通过后形成的承诺计划履约情况(与贡献度跟踪同一数据源,按申请挂钩;每期 = 每一次申请)。" style="margin-left:6px" /></span>
          <button class="btn btn--text" @click="router.push('/commitment')">查看贡献度跟踪</button>
        </div>
        <div v-for="(plan, pi) in commitmentGroups" :key="pi" class="plan-block" :style="pi ? 'margin-top:12px' : ''">
          <div class="plan-block__head">
            <span class="badge badge--info">{{ plan.planNo }}</span>
            <span>{{ customerScopeText(plan.scopeType) }}</span>
            <span class="badge" :class="planStatusBadge(plan.status)">{{ planStatusText(plan.status) }}</span>
            <span class="section-tip">{{ plan.startDate ? String(plan.startDate).slice(0, 10) : '' }} ~ {{ plan.endDate ? String(plan.endDate).slice(0, 10) : '' }}</span>
          </div>
          <table class="table table--full">
            <thead><tr><th>指标</th><th>目标值</th><th>单位</th><th>评估期</th><th>实际值</th><th>达成率</th><th>结论</th></tr></thead>
            <tbody>
              <template v-for="(m, mi) in plan.metrics" :key="mi">
                <tr v-for="(e, ei) in m.periods" :key="ei">
                  <td>{{ metricName(m.metricCode) }}</td>
                  <td class="num">{{ m.targetValue ?? '—' }}</td>
                  <td>{{ commitmentUnitText(m.unit) }}</td>
                  <td>{{ String(e.dataDt).slice(0, 10) }}</td>
                  <td class="num">{{ e.actualValue ?? '—' }}</td>
                  <td class="num"><span :class="ratioBadge(e.achievementRatio)">{{ e.achievementRatio != null ? `${e.achievementRatio}%` : '—' }}</span></td>
                  <td><span class="badge" :class="evalResultBadge(e.resultStatus)">{{ evalResultText(e.resultStatus) }}</span></td>
                </tr>
                <tr v-if="!m.periods.length">
                  <td>{{ metricName(m.metricCode) }}</td>
                  <td class="num">{{ m.targetValue ?? '—' }}</td>
                  <td>{{ commitmentUnitText(m.unit) }}</td>
                  <td colspan="4" class="empty-cell">暂无履约评估</td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>

    </template>
    <div v-else class="card empty-block">暂无数据</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getArchive, exportArchive, downloadResolutionDoc } from '@/api/history'
import { download } from '@/api/request'
import ContributionPanel from '@/components/ContributionPanel.vue'
import {
  appStatusText, itemStatusText, relationTypeText,
  businessTypeText, customerScopeText, nodeLabel, roleText, actionText,
  productName, metricName, memberRoleText, termUnitText, commitmentUnitText,
  currencyText, decisionText,
  roundStatusText, execStatusText, ruleLevelText,
  evalResultText, planStatusText,
  guaranteeTypeText, measureTypeText, agreementTypeText, agreementStatusText, agreementStatusBadge,
  rateTypeText, contractStatusText, contractStatusBadge, customerTypeText, customerClassText, certTypeText, inputModeText
} from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const applicationId = route.params.id as string
const archive = ref<Record<string, any>>({})
const loading = ref(true)
const exporting = ref(false)
const resolving = ref(false)

// 决议书可用性:仅已签发决议(archive.resolutions 非空)的申请提供下载
const hasResolution = computed(() => !!(archive.value.resolutions && archive.value.resolutions.length))

// 导出口径(§15):仅 admin/auditor/president 可见
const canExport = computed(() => {
  const role = userStore.userInfo?.roles?.[0] || ''
  return ['admin', 'auditor', 'president'].includes(role)
})

// 表决统计可见性(§12.7/T4-02/T4-10):表决计票/轮次/行长决策仅行长·审计·超管可见,委员与审批人隐藏
const canViewVote = computed(() => {
  const role = userStore.userInfo?.roles?.[0] || ''
  return ['admin', 'auditor', 'president'].includes(role)
})

const isGroup = computed(() => {
  const scope = val(archive.value.application || {}, 'customer_scope', 'customerScope')
  return scope === 'GROUP'
})

// ===== 申请内容留痕(§14.4):数据来源 / 客户 / 授信 / 融资 / 附件 / 担保 / 贡献度 / 机构达成 =====
const source = computed(() => archive.value.source || '')
const sourceText = computed(() =>
  source.value === 'SNAPSHOT' ? '冻结快照'
    : source.value === 'MANUAL' ? '人工录入'
    : source.value === 'MANUAL_OVERRIDE' ? '人工修正'
    : source.value === 'REALTIME' ? '实时取数' : '—')
const sourceNote = computed(() =>
  source.value === 'MANUAL' ? '未找到数仓/快照客户数据,客户信息由客户经理手工录入,以人工填写为准。'
    : source.value === 'MANUAL_OVERRIDE' ? '数仓/快照客户信息已由客户经理人工修正,以人工填写为准。'
    : source.value === 'REALTIME' ? '未找到提交时冻结快照,客户/融资/贡献度为数仓实时查询结果,可能与提交时点存在差异。'
    : '—')
const snapshotInfo = computed(() => archive.value.snapshotInfo || {})
const customer = computed(() => (archive.value.customer && archive.value.customer.length) ? archive.value.customer[0] : {})
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || val(archive.value.application || {}, 'customer_no', 'customerNo') || '—')
const isCorpCustomer = computed(() => customer.value.custType === 'CORP')
const isIndivCustomer = computed(() => customer.value.custType === 'INDIV')
const isLoan = computed(() => val(archive.value.application || {}, 'business_type', 'businessType') !== 'DEPOSIT')
const creditAgreements = computed(() => archive.value.creditAgreements || [])
const financing = computed(() => archive.value.financing || [])
const attachments = computed(() => archive.value.attachments || [])
const otherLoanSummary = computed(() => archive.value.otherLoanSummary || [])
const otherLoans = computed(() => archive.value.otherLoans || [])
const contribution = computed(() => archive.value.contribution || [])
const orgPerformance = computed(() => archive.value.orgPerformance || [])
const groupCredit = computed(() => archive.value.groupCredit || [])
const groupContributionText = computed(() => {
  const g = (archive.value.groupContribution || [])[0]
  if (!g || g.metricValue == null) return '暂无数据'
  return `${g.metricValue}${g.valueType === 'CONTRIBUTION_AMOUNT' ? ' 万元' : ''}`.trim()
})
// 担保分项明细(后端按 pricing_item_id 聚合)
const hasGuarantees = computed(() => {
  const map = archive.value.guaranteesByItem || {}
  return Object.values(map).some((list: any) => list && list.length)
})
function guaranteesOf(p: any): any[] {
  const map = archive.value.guaranteesByItem || {}
  return map[String(val(p, 'id'))] || []
}
function extOf(g: any): any {
  const j = g?.extJson
  if (!j) return null
  if (typeof j === 'object') return j
  try { return JSON.parse(j) } catch { return null }
}
// 担保措施扩展明细转可读文本(抵押物/保证人/质押/保证金/存单等关键字段)
function extText(g: any): string {
  const ext = extOf(g)
  if (!ext) return '—'
  const labels: Record<string, string> = {
    name: '名称', collateralType: '类型', specModel: '规格型号', quantity: '数量',
    plateNo: '车牌号', vin: '车架号', address: '坐落', area: '面积',
    certNo: '产权证号', owner: '权属人', pledgeType: '质押物类型',
    marginRatio: '比例', termMonths: '期限(月)', certificateNo: '存单号', maturityDate: '到期日'
  }
  const parts = Object.keys(labels)
    .filter((k) => ext[k] != null && ext[k] !== '')
    .map((k) => `${labels[k]}:${ext[k]}`)
  return parts.length ? parts.join('；') : '见明细'
}
function downloadAttachment(a: any) {
  download(`/ccr/applications/${applicationId}/attachments/${a.id}/download`)
}

const qualityOverall = computed(() => {
  const list: any[] = archive.value.qualityResults || []
  if (list.some((q) => val(q, 'ruleLevel', 'rule_level') === 'BLOCK')) return 'BLOCK'
  if (list.some((q) => val(q, 'ruleLevel', 'rule_level') === 'WARN')) return 'WARN'
  return 'PASS'
})
const qualityBadge = computed(() => ({
  BLOCK: 'badge badge--danger',
  WARN: 'badge badge--warning',
  PASS: 'badge badge--success'
}[qualityOverall.value] || 'badge badge--neutral'))

/** 多键取值(后端档案 Map 混用 snake/camel;空值显示暂无口径) */
function val(row: any, ...keys: string[]): any {
  for (const k of keys) {
    if (row && row[k] !== null && row[k] !== undefined && row[k] !== '') return row[k]
  }
  return '—'
}
function fmtTime(t: any) {
  return t && t !== '—' ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function fmtDate(t: any) {
  return t && t !== '—' ? String(t).slice(0, 10) : '—'
}
function rateText(r: any) {
  return r !== null && r !== undefined && r !== '' && r !== '—' ? `${r}%` : '—'
}
function termText(p: any) {
  const v = val(p, 'term_value', 'termValue')
  if (v === '—') return '—'
  const unit = val(p, 'term_unit', 'termUnit')
  return `${v}${termUnitText(unit === '—' ? '' : unit)}`
}
function plannedText(f: any) {
  return f === 'Y' ? '是' : f === 'N' ? '否' : f || '—'
}
function badgeClass(s: any) {
  const map: Record<string, string> = {
    APPROVED: 'badge badge--success', PARTIAL_APPROVED: 'badge badge--success', ACHIEVED: 'badge badge--success',
    REJECTED: 'badge badge--danger', EXPIRED_UNMET: 'badge badge--danger', TERMINATED: 'badge badge--neutral',
    PROCESSING: 'badge badge--info', TRACKING: 'badge badge--info', SUBMITTING: 'badge badge--info',
    AT_RISK: 'badge badge--warning', DATA_PENDING: 'badge badge--warning', PENDING: 'badge badge--warning'
  }
  return map[s] || 'badge badge--neutral'
}
function actionBadge(a: any) {
  const map: Record<string, string> = {
    APPROVE: 'badge badge--success', REJECT: 'badge badge--danger',
    ADJUST: 'badge badge--warning', RETURN: 'badge badge--warning'
  }
  return map[a] || 'badge badge--info'
}
// 借据状态(数仓 note_status):正常/逾期/已结清
function noteStatusText(code?: any) {
  return code === 'NORMAL' ? '正常' : code === 'SETTLED' ? '已结清' : code === 'OVERDUE' ? '逾期' : (code || '—')
}
function noteStatusBadge(s?: any) {
  return s === 'NORMAL' ? 'badge--success' : s === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'
}
// 校验级别徽标(简式,配合 <span class="badge">)
function ruleLevelBadge(l?: any) {
  return l === 'BLOCK' ? 'badge--danger' : l === 'WARN' ? 'badge--warning' : 'badge--success'
}
// 表决计票汇总:按轮次从 voteResults 合并 通过/否决
function voteResultOf(roundId: any) {
  const r = (archive.value.voteResults || []).find((x: any) => String(val(x, 'roundId', 'round_id')) === String(roundId))
  return r ? `${val(r, 'approveCount', 'approve_count') ?? 0} / ${val(r, 'rejectCount', 'reject_count') ?? 0}` : '—'
}
// 决议状态徽标(决议日期=issue_time,无有效期周期)
function resolutionStatusBadge(s?: any) {
  const map: Record<string, string> = {
    ISSUED: 'badge--info', CONTRACT_PENDING: 'badge--warning', EXECUTED: 'badge--success', VOID: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}
// 历史履约(承诺计划):计划 → 指标 → 逐期评估;与贡献度跟踪同源(ccr_commitment_* + ccr_tracking_evaluation),按申请挂钩
const commitmentGroups = computed(() => {
  const plans: any[] = archive.value.commitmentPlans || []
  const metrics: any[] = archive.value.commitmentMetrics || []
  const evals: any[] = archive.value.commitmentEvaluations || []
  return plans.map((p: any) => {
    const planId = val(p, 'id')
    const planMetrics = metrics
      .filter((m: any) => String(val(m, 'planId', 'plan_id')) === String(planId))
      .map((m: any) => {
        const metricId = val(m, 'metricId', 'metric_id')
        const periods = evals
          .filter((e: any) => String(val(e, 'metricId', 'metric_id')) === String(metricId))
          .map((e: any) => ({
            dataDt: val(e, 'dataDt', 'data_dt'),
            actualValue: val(e, 'actualValue', 'actual_value'),
            achievementRatio: pctOf(val(e, 'achievementRatio', 'achievement_ratio')),
            resultStatus: val(e, 'resultStatus', 'result_status')
          }))
        return {
          metricCode: val(m, 'metricCode', 'metric_code'),
          targetValue: val(m, 'targetValue', 'target_value'),
          unit: val(m, 'unit'),
          periods
        }
      })
    return {
      planNo: val(p, 'planNo', 'plan_no'),
      scopeType: val(p, 'scopeType', 'scope_type'),
      status: val(p, 'status'),
      startDate: val(p, 'startDate', 'start_date'),
      endDate: val(p, 'endDate', 'end_date'),
      metrics: planMetrics
    }
  })
})
function planStatusBadge(s?: any) {
  const map: Record<string, string> = {
    TRACKING: 'badge--info', PENDING: 'badge--warning', AT_RISK: 'badge--warning', ACHIEVED: 'badge--success',
    DATA_PENDING: 'badge--warning', EXPIRED_UNMET: 'badge--danger', TERMINATED: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}
// 达成率比率→百分比(库中 achievement_ratio 为比率 0.84,展示统一转 84)
function pctOf(r: any): any {
  const n = Number(r)
  return r != null && Number.isFinite(n) ? Number((n * 100).toFixed(1)) : (r == null ? null : r)
}
// 达成率徽标:≥100%绿 / ≥80%黄 / <80%红
function ratioBadge(r: any) {
  const n = Number(r)
  if (r == null || !Number.isFinite(n)) return 'badge--neutral'
  return n >= 100 ? 'badge--success' : n >= 80 ? 'badge--warning' : 'badge--danger'
}
function evalResultBadge(s?: any) {
  const map: Record<string, string> = {
    ACHIEVED: 'badge--success', ON_TRACK: 'badge--info', AT_RISK: 'badge--warning', DATA_PENDING: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}

async function load() {
  loading.value = true
  try {
    archive.value = await getArchive(applicationId)
  } catch {
    archive.value = {}
  } finally {
    loading.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    await exportArchive(applicationId)
    ElMessage.success('导出成功')
  } catch {
    // 错误提示由 download 封装统一处理
  } finally {
    exporting.value = false
  }
}

async function doResolutionDoc() {
  resolving.value = true
  try {
    await downloadResolutionDoc(applicationId)
    ElMessage.success('决议书已生成')
  } catch {
    // 错误提示由 download 封装统一处理
  } finally {
    resolving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.app-no { font-size: 14px; color: var(--color-text-sub); font-weight: 400; margin-left: 8px; }
.head-actions { margin-left: auto; display: flex; gap: 8px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.desc-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 16px; font-size: 14px; }
.desc-item { display: flex; flex-direction: column; gap: 2px; }
.desc-item--full { grid-column: 1 / -1; }
.desc-label { font-size: 12px; color: var(--color-text-light); }
.empty-block { text-align: center; color: var(--color-text-light); padding: 20px 0; font-size: 13px; }
.hash-cell { font-size: 12px; color: var(--color-text-sub); word-break: break-all; }
.plan-block { border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); padding: 10px 12px; }
.plan-block__head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; font-size: 13px; flex-wrap: wrap; }
</style>
