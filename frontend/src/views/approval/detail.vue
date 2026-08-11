<template>
  <div v-if="loaded">
    <div class="section-head">
      <div class="section-title">
        <button class="btn btn--ghost btn--back" @click="goBackList">‹ 返回列表</button>
        审批详情
      </div>
      <div class="section-tip">基础信息只读,普通审批人仅可编辑审批利率与审批意见。</div>
    </div>

    <!-- 0. 数据来源与快照信息(§12.16-7) -->
    <div class="card">
      <div class="card__head">
        <span>数据来源</span>
        <span class="badge" :class="source === 'SNAPSHOT' ? 'badge--success' : source === 'MANUAL' ? 'badge--danger' : 'badge--warning'">
          {{ source === 'SNAPSHOT' ? '冻结快照' : source === 'MANUAL' ? '人工录入' : source === 'MANUAL_OVERRIDE' ? '人工修正' : '实时取数' }}
        </span>
      </div>
      <div class="detail-grid" v-if="source === 'SNAPSHOT'">
        <div><span class="dg-label">数据日期</span>{{ snapshotInfo.dataDt || '—' }}</div>
        <div><span class="dg-label">冻结时间</span>{{ snapshotInfo.freezeTime || '—' }}</div>
        <div><span class="dg-label">快照批次号</span>{{ snapshotInfo.bundleNo || '—' }}</div>
      </div>
      <div class="section-tip" v-if="source === 'MANUAL'">未找到数仓/快照客户数据,客户信息由客户经理手工录入,以人工填写为准。</div>
      <div class="section-tip" v-else-if="source === 'MANUAL_OVERRIDE'">数仓/快照客户信息已由客户经理人工修正,以人工填写为准。</div>
      <div class="section-tip" v-else>未找到提交时冻结快照,客户/融资/贡献度为数仓实时查询结果,可能与提交时点存在差异。</div>
    </div>


    <!-- 4. 申请内容(多分项:申请级字段 + 分项明细表;原执行利率/申请利率/期限/节点按分项展示,不再只取当前分项) -->
    <div class="card">
      <div class="card__head">
        <span>申请内容</span>
        <span class="badge badge--info">共 {{ siblingItems.length }} 个分项</span>
        <span v-if="pi.inherit_flag === 'Y' || pi.inheritFlag === 'Y'" class="badge badge--info">沿用原决议</span>
      </div>
      <div class="detail-grid">
        <div><span class="dg-label">申请号</span>{{ application.applicationNo || '—' }}</div>
        <div><span class="dg-label">业务类型</span>{{ businessTypeText }}</div>
        <div><span class="dg-label">客户号</span>{{ application.customerNo || pi.pricing_customer_no || '—' }}</div>
        <div><span class="dg-label">产品编码</span>{{ productName(pi.product_code) }}</div>
      </div>
      <table class="table" style="margin-top:12px">
        <thead><tr><th>定价分项</th><th>产品</th><th>原执行利率</th><th>申请利率</th><th>金额(万元)</th><th>期限</th><th>担保方式</th><th>部门归属</th><th>当前节点</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="it in siblingItems" :key="it.id">
            <td>{{ it.pricingItemNo || '—' }}</td>
            <td>{{ productName(it.productCode) }}</td>
            <td>{{ it.originalRate != null ? fmtRate(it.originalRate) : '新增业务' }}</td>
            <td class="num">{{ fmtRate(it.requestedRate) }}</td>
            <td class="num">{{ it.pricingAmount != null ? it.pricingAmount : '—' }}</td>
            <td>{{ it.termValue != null ? `${it.termValue}${termUnitText(it.termUnit)}` : '—' }}</td>
            <td>{{ guaranteesText(it.guarantees) }}</td>
            <td>{{ deptText(it.deptCode) }}</td>
            <td>{{ it.currentNodeCode ? nodeLabel(it.currentNodeCode) : '—' }}</td>
            <td>{{ itemStatusText(it.status) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="remark-text" style="margin-top:12px" v-if="application.applicationRemark">{{ application.applicationRemark }}</div>
    </div>

    <!-- 5. 客户基本信息 -->
    <div class="card">
      <div class="card__head"><span>客户基本信息</span>
        <span class="badge" :class="source === 'MANUAL' || source === 'MANUAL_OVERRIDE' ? 'badge--danger' : 'badge--info'">
          {{ source === 'MANUAL' ? '人工录入' : source === 'MANUAL_OVERRIDE' ? '含人工修正' : '数仓' }}
        </span>
      </div>
      <div class="detail-grid" v-if="hasCustomer">
        <!-- 对公客户(§20 ①:名称/客户号/统一社会信用代码/企业性质/行业/信用等级/五级分类等) -->
        <template v-if="isCorpCustomer">
          <div><span class="dg-label">客户名称</span>{{ customerName }}</div>
          <div><span class="dg-label">行内客户号</span>{{ customer.customerNo || '—' }}</div>
          <div><span class="dg-label">客户类型</span>对公</div>
          <div v-if="customer.certNo"><span class="dg-label">统一社会信用代码</span>{{ customer.certNo }}</div>
          <div v-if="customer.entpCharic"><span class="dg-label">企业性质</span>{{ customerTypeText(customer.entpCharic) }}</div>
          <div v-if="customer.entpScale"><span class="dg-label">企业规模</span>{{ customer.entpScale }}</div>
          <div v-if="customer.industry"><span class="dg-label">所属行业</span>{{ customer.industry }}</div>
          <div v-if="customer.creditLevel"><span class="dg-label">内部信用等级</span>{{ customer.creditLevel }}</div>
          <div v-if="customer.fiveLevelClass"><span class="dg-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
          <div v-if="customer.empeNum != null"><span class="dg-label">员工人数</span>{{ customer.empeNum }}</div>
          <div v-if="customer.totalAssets != null"><span class="dg-label">总资产(万元)</span>{{ customer.totalAssets }}</div>
          <div v-if="customer.registeredCapital != null"><span class="dg-label">注册资本(万元)</span>{{ customer.registeredCapital }}</div>
          <div v-if="customer.estbDate"><span class="dg-label">成立日期</span>{{ customer.estbDate }}</div>
          <div v-if="customer.restAddr"><span class="dg-label">注册地址</span>{{ customer.restAddr }}</div>
          <div v-if="customer.openOrgName"><span class="dg-label">开户机构</span>{{ customer.openOrgName }}</div>
          <div v-if="customer.openDate"><span class="dg-label">开户日期</span>{{ customer.openDate }}</div>
          <div v-if="customer.customerClass"><span class="dg-label">客户分类</span>{{ customerClassText(customer.customerClass) }}</div>
        </template>
        <!-- 对私客户(§20 ①:姓名/证件类型/证件号码/职业/年收入/婚姻状况/居住地址等) -->
        <template v-else-if="isIndivCustomer">
          <div><span class="dg-label">姓名</span>{{ customerName }}</div>
          <div><span class="dg-label">客户号</span>{{ customer.customerNo || '—' }}</div>
          <div><span class="dg-label">客户类型</span>个人</div>
          <div v-if="customer.certType || customer.certNo"><span class="dg-label">证件类型</span>{{ certTypeText(customer.certType) }}</div>
          <div v-if="customer.certNo"><span class="dg-label">证件号码</span>{{ customer.certNo }}</div>
          <div v-if="customer.gender"><span class="dg-label">性别</span>{{ customer.gender }}</div>
          <div v-if="customer.occupation"><span class="dg-label">职业</span>{{ customer.occupation }}</div>
          <div v-if="customer.annualIncome != null"><span class="dg-label">年收入(万元)</span>{{ customer.annualIncome }}</div>
          <div v-if="customer.maritalStatus"><span class="dg-label">婚姻状况</span>{{ customer.maritalStatus }}</div>
          <div v-if="customer.address"><span class="dg-label">居住地址</span>{{ customer.address }}</div>
          <div v-if="customer.phone"><span class="dg-label">联系电话</span>{{ customer.phone }}</div>
          <div v-if="customer.fiveLevelClass"><span class="dg-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
          <div v-if="customer.openOrgName"><span class="dg-label">开户机构</span>{{ customer.openOrgName }}</div>
          <div v-if="customer.openDate"><span class="dg-label">开户日期</span>{{ customer.openDate }}</div>
          <div v-if="customer.customerClass"><span class="dg-label">客户分类</span>{{ customerClassText(customer.customerClass) }}</div>
        </template>
        <!-- 存量数据无 custType:按字段存在性兜底 -->
        <template v-else>
          <div><span class="dg-label">客户名称</span>{{ customerName }}</div>
          <div v-if="customer.certNo"><span class="dg-label">证件号码</span>{{ customer.certNo }}</div>
          <div v-if="customer.entpCharic"><span class="dg-label">企业性质</span>{{ customerTypeText(customer.entpCharic) }}</div>
          <div v-if="customer.industry"><span class="dg-label">所属行业</span>{{ customer.industry }}</div>
          <div v-if="customer.creditLevel"><span class="dg-label">内部信用等级</span>{{ customer.creditLevel }}</div>
          <div v-if="customer.fiveLevelClass"><span class="dg-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
          <div v-if="customer.openOrgName"><span class="dg-label">开户机构</span>{{ customer.openOrgName }}</div>
          <div v-if="customer.customerClass"><span class="dg-label">客户分类</span>{{ customerClassText(customer.customerClass) }}</div>
        </template>
      </div>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 6. 集团信息(仅集团场景) -->
    <div class="card" v-if="isGroup">
      <div class="card__head"><span>集团信息</span><span class="badge badge--info">集团客户</span></div>
      <div class="detail-grid">
        <div><span class="dg-label">集团号</span>{{ application.groupNo }}</div>
        <div><span class="dg-label">成员数</span>{{ groupMembers.length }} 户</div>
        <div><span class="dg-label">合计申请金额</span>{{ groupTotalAmount }} 万元</div>
        <!-- P1-2:集团贡献度(数仓 GROUP 口径综合贡献总额) -->
        <div><span class="dg-label">集团贡献度</span>{{ groupContributionText }}</div>
      </div>
      <el-collapse v-if="groupMembers.length" style="margin-top:12px">
        <el-collapse-item v-for="(m, i) in groupMembers" :key="i" :title="`成员 ${m.memberCustomerNo}(${memberRoleText(m.memberRole, '成员')})`" :name="i">
          <div class="detail-grid">
            <div><span class="dg-label">成员客户号</span>{{ m.memberCustomerNo }}</div>
            <div><span class="dg-label">成员角色</span>{{ memberRoleText(m.memberRole) }}</div>
            <div><span class="dg-label">申请金额(万元)</span>{{ m.requestAmount ?? '—' }}</div>
          </div>
          <table class="table" style="margin-top:8px" v-if="memberCommitments(m.memberCustomerNo).length">
            <thead><tr><th>承诺指标</th><th>基线</th><th>目标</th><th>单位</th></tr></thead>
            <tbody>
              <tr v-for="(c, j) in memberCommitments(m.memberCustomerNo)" :key="j">
                <td>{{ metricName(c.metricCode) }}</td>
                <td class="num">{{ c.baselineValue ?? '—' }}</td>
                <!-- 承诺类型"其它"(§6.4):无数值目标,以 commitment_desc 手工描述展示 -->
                <td class="num">{{ c.metricCode === 'OTHER' ? (c.commitmentDesc || '—') : (c.targetValue ?? '—') }}</td>
                <td>{{ c.unit || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty" style="padding:8px">该成员暂无承诺指标</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 6c. 授信信息(授信协议:编号/类型/币种/状态/起止/额度/已用/可用) -->
    <div class="card">
      <div class="card__head"><span>授信信息</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="creditAgreements.length">
        <thead><tr><th>授信协议编号</th><th>授信类型</th><th>币种</th><th>状态</th><th>开始日期</th><th>结束日期</th><th>授信额度(万元)</th><th>已用额度(万元)</th><th>可用额度(万元)</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in creditAgreements" :key="i">
            <td>{{ a.agreementNo }}</td>
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
      <div v-else class="empty">暂无授信协议数据</div>
    </div>

    <!-- 7. 授信/账户与本行融资(贷款合同快照;合同状态/期限/利率类型等全字段展示,避免大段空白) -->
    <div class="card">
      <div class="card__head"><span>本行融资</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="financing.length">
        <thead><tr><th>合同号</th><th>授信协议号</th><th>合同金额(万元)</th><th>余额(万元)</th><th>执行利率</th><th>利率类型</th><th>期限</th><th>合同状态</th><th>担保类型</th><th>币种</th></tr></thead>
        <tbody>
          <tr v-for="f in financing" :key="f.contractNo">
            <td>{{ f.contractNo }}</td>
            <td>{{ f.agreementNo || '—' }}</td>
            <td class="num">{{ f.contractAmount ?? '—' }}</td>
            <td class="num">{{ f.loanBalance ?? '—' }}</td>
            <td class="num">{{ fmtRate(f.contractRate) }}</td>
            <td>{{ rateTypeText(f.rateType) }}{{ f.lprTerm ? `·${f.lprTerm}` : '' }}</td>
            <td class="nowrap">{{ f.startDate ? `${String(f.startDate).slice(0, 10)} ~ ${f.maturityDate ? String(f.maturityDate).slice(0, 10) : '—'}` : '—' }}</td>
            <td><span class="badge" :class="contractStatusBadge(f.contractStatus)">{{ contractStatusText(f.contractStatus) }}</span></td>
            <td>{{ guaranteeTypeText(f.guaranteeType) }}</td>
            <td>{{ currencyText(f.currency) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 6b. 申请材料附件(始终展示区块;有附件列表,无附件提示,避免审批人误以为无此功能) -->
    <div class="card">
      <div class="card__head"><span>申请材料附件</span><span class="badge badge--info">{{ attachments.length }} 个附件</span></div>
      <table class="table" v-if="attachments.length">
        <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in attachments" :key="i">
            <td>{{ a.fileName }}</td>
            <td class="num">{{ (a.fileSize / 1024).toFixed(1) }} KB</td>
            <td>{{ a.createTime ? String(a.createTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
            <td><button class="btn btn--text" @click="downloadAttachment(a)">下载</button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无附件(申请时未上传材料)</div>
    </div>

    <!-- 7b. 他行融资(申请人工补录/Excel 导入 + 数仓征信) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>他行融资</span></div>
      <div class="detail-grid" v-if="otherLoanSummary.length">
        <div><span class="dg-label">他行机构数</span>{{ otherLoanSummary[0].lenderCount ?? '—' }}</div>
        <div><span class="dg-label">授信总额</span>{{ otherLoanSummary[0].creditAmountTotal ?? '—' }} 万元</div>
        <div><span class="dg-label">已用总额</span>{{ otherLoanSummary[0].usedAmountTotal ?? '—' }} 万元</div>
        <div><span class="dg-label">未结清笔数</span>{{ otherLoanSummary[0].loanAccountCount ?? '—' }}</div>
        <div><span class="dg-label">逾期账户</span>{{ otherLoanSummary[0].overdueAccountCount ?? '—' }}</div>
        <div><span class="dg-label">逾期余额</span>{{ otherLoanSummary[0].overdueBalance ?? '—' }} 万元</div>
        <div><span class="dg-label">不良余额</span>{{ otherLoanSummary[0].nplBalance ?? '—' }} 万元</div>
        <div><span class="dg-label">关注类余额</span>{{ otherLoanSummary[0].specialMentionBalance ?? '—' }} 万元</div>
        <div><span class="dg-label">对外担保余额</span>{{ otherLoanSummary[0].externalGuaranteeBalance ?? '—' }} 万元</div>
      </div>
      <table class="table" v-if="otherLoans.length" style="margin-top:8px">
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
      <div v-else class="empty">暂无他行融资记录</div>
    </div>

    <!-- 7c. 关联人情况(数仓客户关系 + 申请录入) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>关联人情况</span></div>
      <table class="table" v-if="relatedPersons.length">
        <thead>
          <tr><th>姓名/名称</th><th>证件号码</th><th>关系类型</th><th>行内客户号</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in relatedPersons" :key="i">
            <td>{{ r.personName }}</td>
            <td>{{ r.certNo || '—' }}</td>
            <td>{{ relationTypeText(r.relationType) }}</td>
            <td>{{ r.relatedCustomerNo || '—' }}</td>
            <td v-if="r.custType === 'CORP'">{{ r.entpCharic || '—' }}</td>
            <td v-else>—</td>
            <td v-if="r.custType === 'CORP'">{{ r.industry || '—' }}</td>
            <td v-else>—</td>
            <td v-if="r.custType === 'CORP'">{{ r.creditLevel || '—' }}</td>
            <td v-else>—</td>
            <td v-if="r.custType === 'CORP'">{{ r.fiveLevelClass || '—' }}</td>
            <td v-else>—</td>
            <td v-if="r.custType === 'INDIV'">{{ r.occupation || '—' }}</td>
            <td v-else>—</td>
            <td v-if="r.custType === 'INDIV'">{{ r.annualIncome ?? '—' }}</td>
            <td v-else>—</td>
            <td class="num">{{ r.creditAgreementCount ?? '—' }}</td>
            <td class="num">{{ r.loanBalanceTotal ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <template v-else>
        <table class="table" v-if="relations.length">
          <thead>
            <tr><th>关联人</th><th>关系类型</th><th>关联强度</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in relations" :key="i">
              <td>{{ r.relatedCustomerNo }}</td>
              <td>{{ relationTypeText(r.relationType) }}</td>
              <td>{{ r.relationStrength === 'STRONG' ? '强' : r.relationStrength === 'WEAK' ? '弱' : (r.relationStrength || '—') }}</td>
              <td v-if="r.custType === 'CORP'">{{ r.entpCharic || '—' }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ r.industry || '—' }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ r.creditLevel || '—' }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ r.fiveLevelClass || '—' }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ r.occupation || '—' }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ r.annualIncome ?? '—' }}</td>
              <td v-else>—</td>
              <td class="num">{{ r.creditAgreementCount ?? '—' }}</td>
              <td class="num">{{ r.loanBalanceTotal ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无关联人记录</div>
      </template>
    </div>

    <!-- 8. 存款账户(仅存款申请:存款额度与利率优惠挂钩;贷款场景合同/借据/担保已并入审批决定区,不展示存款账户) -->
    <div class="card" v-if="!isLoan">
      <div class="card__head"><span>存款账户</span></div>
      <template>
        <table class="table" v-if="depositAccounts.length">
          <thead>
            <tr><th>存款账号</th><th>产品</th><th>余额(万元)</th><th>当前执行利率(%)</th><th>期限</th><th>开户日</th><th>到期日</th><th>标识</th></tr>
          </thead>
          <tbody>
            <tr v-for="(a, i) in depositAccounts" :key="i">
              <td>{{ a.accountNoMasked || '—' }}</td>
              <td>{{ productName(a.productCode || pi.product_code) }}</td>
              <td class="num">{{ a.accountBalance ?? '—' }}</td>
              <td class="num">{{ a.executionRate ?? '—' }}</td>
              <td>{{ a.termValue ? `${a.termValue}${termUnitText(a.termUnit || pi.term_unit)}` : '—' }}</td>
              <td>{{ a.openDate || '—' }}</td>
              <td>{{ a.maturityDate || '—' }}</td>
              <td><span class="badge" :class="a.plannedAccountFlag === 'Y' ? 'badge--neutral' : 'badge--success'">{{ a.plannedAccountFlag === 'Y' ? '拟开户' : '存量账户' }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty" style="padding:8px">暂无账户数据</div>
        <div class="section-tip" style="margin-top:8px" v-if="pi.boundary_rate != null">
          本节点权限上限 {{ pi.boundary_rate }}%(存款越高越优惠,超过上限将上送小组表决)
          <span v-if="pi.requested_rate != null"> · 申请利率较上限 {{ ((pi.requested_rate - pi.boundary_rate) * 100).toFixed(0) }} BP</span>
        </div>
      </template>
    </div>

    <!-- 9. 当前与拟达成贡献度(双概念并排;存款场景不涉贡献度,仅贷款展示) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
      <ContributionPanel :contribution="contribution" :commitments="commitments" />
    </div>

    <!-- 10. 历史履约:该客户每一次申请的履约比例 + 总额(按申请聚合,Σ实际/Σ目标,口径同承诺跟踪页) -->
    <div class="card">
      <div class="card__head"><span>历史履约</span><span class="badge badge--info">按申请聚合</span></div>
      <template v-if="tracking.length">
        <div v-if="unmetTracking.length" class="warn-bar">
          {{ unmetTracking.length }} 项承诺指标未达成({{ unmetTracking.map((m) => metricName(m.metricCode)).join('、') }}),请关注履约风险。
        </div>
        <!-- 总览:全部申请合计 -->
        <div class="overall">
          <div class="overall__sum">
            <div class="overall__sum-item"><span class="dg-label">累计贡献总额</span><b class="metric-val__num">{{ overall.sumActual }}</b><span class="unit">万元</span></div>
            <div class="overall__sum-item"><span class="dg-label">累计承诺目标</span><b class="metric-val__num">{{ overall.sumTarget }}</b><span class="unit">万元</span></div>
            <div class="overall__sum-item"><span class="dg-label">整体达成率</span><b :class="ratioClass(overall.ratio)">{{ overall.ratio }}%</b></div>
            <div class="overall__sum-item"><span class="dg-label">涉及申请</span><b class="metric-val__num">{{ tracking.length }}</b><span class="unit">笔</span></div>
          </div>
          <el-progress :percentage="progressPct(overall.ratio)" :color="progressColor(overall.ratio)" :format="() => `${overall.ratio}%`" :stroke-width="10" style="margin-top:10px" />
        </div>
        <!-- 按申请列表(点击行展开该申请指标明细) -->
        <table class="table" style="margin-top:14px">
          <thead><tr><th>申请号</th><th>申请时间</th><th>承诺计划</th><th>履约比例</th><th>贡献总额</th><th>指标数</th><th class="num"></th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in tracking" :key="i" class="app-row" @click="t.open = !t.open">
              <td>{{ t.applicationNo || '—' }}</td>
              <td>{{ t.submitTime ? fmtDate(t.submitTime) : '—' }}</td>
              <td><span class="badge badge--info">{{ t.planNo }}</span></td>
              <td class="num">
                <span v-if="t.ratio != null" :class="ratioClass(t.ratio)">{{ t.ratio }}%</span>
                <span v-else class="muted">暂无评估</span>
              </td>
              <td class="num">{{ t.sumActual != null ? t.sumActual + ' 万' : '—' }}</td>
              <td class="num">{{ (t.metrics || []).length }}</td>
              <td class="num muted">{{ t.open ? '收起 ▲' : '展开 ▼' }}</td>
            </tr>
          </tbody>
        </table>
        <!-- 展开的申请指标明细 -->
        <template v-for="(t, i) in tracking.filter((x) => x.open)" :key="'d' + i">
          <div class="sub-table">
            <div class="sub-table__title">申请 {{ t.applicationNo }} · {{ t.planNo }} 指标明细</div>
            <table class="table">
              <thead><tr><th>指标</th><th>目标值</th><th>实际值</th><th>完成率</th><th>评估结论</th><th>数据日期</th></tr></thead>
              <tbody>
                <tr v-for="(m, j) in t.metrics" :key="j">
                  <td>{{ metricName(m.metricCode) }}</td>
                  <td class="num">{{ m.targetValue ?? '—' }}</td>
                  <td class="num">{{ m.actualValue ?? '暂无数据' }}</td>
                  <td class="num">
                    <span v-if="m.achievementRatio != null" :class="ratioClass(m.achievementRatio)">{{ m.achievementRatio }}%</span>
                    <span v-else>暂无数据</span>
                  </td>
                  <td>
                    <span class="badge" :class="statusClass(m.resultStatus)">{{ evalResultText(m.resultStatus) }}</span>
                  </td>
                  <td>{{ m.dataDt || '—' }}</td>
                </tr>
                <tr v-if="!(t.metrics || []).length"><td colspan="6" class="empty">暂无评估数据</td></tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
      <div v-else class="empty">该客户暂无历史承诺申请</div>
    </div>

    <!-- 11. 机构达成(orgPerformance:申请机构最新批次) -->
    <div class="card">
      <div class="card__head"><span>机构达成</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="orgPerformance.length">
        <thead><tr><th>机构</th><th>统计月份</th><th>达成金额</th><th>目标金额</th><th>达成率</th><th>数据日期</th></tr></thead>
        <tbody>
          <tr v-for="(o, i) in orgPerformance" :key="i">
            <td>{{ o.orgCode || '—' }}</td>
            <td>{{ o.statMonth || '—' }}</td>
            <td class="num">{{ o.achievedAmount ?? '—' }}</td>
            <td class="num">{{ o.expectedAmount ?? '—' }}</td>
            <td class="num">
              <span v-if="o.completionRate != null" :class="Number(o.completionRate) >= 100 ? 'rate-ok' : 'rate-bad'">
                {{ o.completionRate }}%
              </span>
              <span v-else>暂无数据</span>
            </td>
            <td>{{ o.dataDt || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 11b. 决议与执行核验(§12.7 ⑪:决议日期=issue_time,无有效期周期) -->
    <div class="card" v-if="resolutions.length">
      <div class="card__head"><span>决议</span><span class="badge badge--success">已签发</span></div>
      <table class="table">
        <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in resolutions" :key="i">
            <td>{{ r.resolutionNo || '—' }}</td>
            <td class="num">{{ fmtRate(r.finalRate) }}</td>
            <td>{{ r.decisionSource || '—' }}</td>
            <td>{{ fmtDate(r.issueTime) }}</td>
            <td><span class="badge" :class="resolutionStatusBadge(r.status)">{{ execStatusText(r.status) }}</span></td>
          </tr>
        </tbody>
      </table>
      <table class="table" style="margin-top:8px" v-if="resolutionExecutions.length">
        <thead><tr><th>贷款合同号</th><th>补充协议号</th><th>执行利率</th><th>执行状态</th><th>核验结果</th><th>核验时间</th></tr></thead>
        <tbody>
          <tr v-for="(e, i) in resolutionExecutions" :key="i">
            <td>{{ e.loanContractNo || '—' }}</td>
            <td>{{ e.supplementAgreementNo || '—' }}</td>
            <td class="num">{{ fmtRate(e.executionRate) }}</td>
            <td>{{ execStatusText(e.executionStatus) }}</td>
            <td>{{ e.reconcileResult || '—' }}</td>
            <td>{{ fmtDate(e.reconcileTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 11c. 表决与行长决策(§12.7:小组表决计票汇总 + 行长决策) -->
    <div class="card" v-if="voteRounds.length || presidentDecisions.length">
      <div class="card__head"><span>表决与行长决策</span></div>
      <table class="table" v-if="voteRounds.length">
        <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
        <tbody>
          <tr v-for="(v, i) in voteRounds" :key="i">
            <td>{{ v.roundName || v.roundNo || '—' }}</td>
            <td><span class="badge" :class="v.status === 'PASSED' ? 'badge--success' : v.status === 'FAILED' ? 'badge--danger' : 'badge--warning'">{{ roundStatusText(v.status) }}</span></td>
            <td class="num">{{ voteResultOf(v.id) }}</td>
            <td>{{ fmtDate(v.roundStartTime) }}</td>
            <td>{{ fmtDate(v.roundEndTime) }}</td>
          </tr>
        </tbody>
      </table>
      <table class="table" style="margin-top:8px" v-if="presidentDecisions.length">
        <thead><tr><th>行长决策</th><th>意见</th><th>决策时间</th></tr></thead>
        <tbody>
          <tr v-for="(d, i) in presidentDecisions" :key="i">
            <td><span class="badge" :class="d.decision === 'AGREE' ? 'badge--success' : d.decision === 'VETO' ? 'badge--danger' : 'badge--warning'">{{ decisionText(d.decision) }}</span></td>
            <td>{{ d.opinion || '—' }}</td>
            <td>{{ fmtDate(d.decisionTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 12. 流程轨迹(流程路由链 + 审批动作) -->
    <div class="card">
      <div class="card__head"><span>流程轨迹</span></div>
      <!-- 当前执行状态:卡在哪个节点、什么状态、什么时候到达(客户经理/审批人一眼看到进度) -->
      <div class="flow-status" v-if="flowStatus">
        <div class="flow-status__item"><span class="dg-label">当前节点</span><b>{{ nodeLabel(flowStatus.currentNodeCode) }}</b></div>
        <div class="flow-status__item"><span class="dg-label">执行状态</span><b>{{ itemStatusText(flowStatus.currentStatus) }}</b></div>
        <div class="flow-status__item"><span class="dg-label">到达时间</span><b>{{ fmtDate(flowStatus.nodeReachTime) }}</b></div>
      </div>
      <!-- 流程路由链:首节点→当前节点(高亮)→终审,已过=完成/当前=处理中/后续=待办 -->
      <div v-if="routeChain.length" class="flow-steps">
        <template v-for="(code, idx) in routeChain" :key="code">
          <div class="flow-step" :class="{
            'flow-step--done': idx < currentNodeIndex,
            'flow-step--current': idx === currentNodeIndex,
            'flow-step--todo': idx > currentNodeIndex
          }">
            <span class="flow-step__label">{{ nodeLabel(code) }}</span>
          </div>
          <span v-if="idx < routeChain.length - 1" class="flow-step__arrow">→</span>
        </template>
      </div>
      <!-- 轨迹从客户经理提交开始显示;每条含动作/节点/处理人/状态变迁/利率变化/时间 -->
      <el-timeline v-if="flowTrace.length" style="margin-top:12px">
        <el-timeline-item v-for="(t, i) in flowTrace" :key="i" :timestamp="fmtDate(t.operationTime)" placement="top">
          <div>
            <span class="badge" :class="traceBadge(t.actionType)">
              {{ actionText(t.actionType) }}
            </span>
            <span class="dg-label" style="margin-left:8px">{{ nodeLabel(t.nodeCode) }}</span>
            <span v-if="t.operatorName" style="margin-left:8px">处理人:{{ t.operatorName }}</span>
            <span v-if="t.fromStatus || t.toStatus" class="badge badge--neutral" style="margin-left:8px">
              {{ itemStatusText(t.fromStatus) }} → {{ itemStatusText(t.toStatus) }}
            </span>
            <span v-if="t.beforeRate != null && t.afterRate != null && t.beforeRate !== t.afterRate" style="margin-left:8px">
              利率 {{ fmtRate(t.beforeRate) }} → {{ fmtRate(t.afterRate) }}
            </span>
          </div>
          <div class="stat-card__sub" v-if="t.actionComment">{{ t.actionComment }}</div>
        </el-timeline-item>
      </el-timeline>
      <div v-else-if="routeChain.length" class="empty">尚无审批动作,申请已流转至当前节点。</div>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 12. 审批决定(按担保分项;参照 demo 逐分项同意/否决 + 一键通过/一键否决,仅当前节点待办可操作) -->
    <div class="card" v-if="actionable">
      <div class="card__head">
        <span>审批决定(按担保分项)</span>
        <span class="badge badge--processing">当前节点:{{ nodeLabel(pi.current_node_code) }} · 已处理 {{ approvedCount + passedCount }} / {{ opItems.length }} 项</span>
      </div>
      <div class="op-form">
        <div v-for="it in opItems" :key="it.id" class="op-item"
             :class="{ 'op-item--done': itemApproved(it), 'op-item--passed': itemPassed(it), 'op-item--lock': !canOperate(it) && !itemPassed(it) }">
          <div class="op-item__head">
            <span class="op-item__name">{{ itemName(it) }}</span>
            <strong>
              <template v-if="itemPassed(it)"><span class="badge badge--info">上级节点已通过 · 仅展示</span></template>
              <template v-else-if="itemApproved(it)"><span class="badge badge--success">已同意 · {{ fmtRate(opRates[it.id]) }}</span></template>
              <template v-else-if="canOperate(it)"><span class="badge badge--warning">待处理</span></template>
              <template v-else><span class="badge badge--neutral">{{ itemStatusText(it.status) }}</span></template>
            </strong>
          </div>
          <!-- 贷款合同信息(要审批的内容:合同号/拟签订 + 金额/期限/产品;借据仅作参考,链接弹窗查看,不占主表空间) -->
          <div class="op-item__subhead">贷款合同</div>
          <table class="table">
            <thead><tr><th>贷款合同</th><th>金额(万元)</th><th>期限</th><th>产品</th><th>借据</th></tr></thead>
            <tbody>
              <tr>
                <td>{{ it.contractNo ? it.contractNo : '拟签订(尚未签订正式合同)' }}</td>
                <td class="num">{{ it.pricingAmount != null ? it.pricingAmount : '—' }}</td>
                <td>{{ it.termValue != null ? `${it.termValue}${termUnitText(it.termUnit)}` : '—' }}</td>
                <td>{{ productName(it.productCode) }}</td>
                <td>
                  <template v-if="it.contractNo && itemNotes(it).length">
                    <a class="link" href="javascript:;" @click.prevent="openNotes(it)">查看借据({{ itemNotes(it).length }} 笔)</a>
                  </template>
                  <template v-else-if="it.contractNo"><span class="text-sub">暂无借据</span></template>
                  <template v-else><span class="text-sub">拟签订 · 尚未放款</span></template>
                </td>
              </tr>
            </tbody>
          </table>
          <!-- 担保信息(按分项挂载,审批端完整展示申请录入内容) -->
          <div class="op-item__subhead">担保信息</div>
          <table class="table" v-if="(it.guarantees || []).length">
            <thead><tr><th>担保方式</th><th>担保措施</th><th>担保金额(万元)</th></tr></thead>
            <tbody>
              <template v-for="(g, gi) in it.guarantees" :key="gi">
                <tr>
                  <td>{{ guaranteeTypeText(g.guaranteeType) }}</td>
                  <td>{{ measureTypeText(g.measureType) }}</td>
                  <td class="num">{{ g.guaranteeAmount ?? '—' }}</td>
                </tr>
                <!-- 担保措施明细(抵押物/保证人/质押/保证金/存单,取快照 extJson;键值表格与贷款区风格一致) -->
                <tr class="measure-detail" v-if="extOf(g)">
                  <td colspan="3">
                    <template v-if="g.measureType === 'MORTGAGE'">
                      <table class="measure-table">
                        <tbody>
                          <tr>
                            <th>类型</th><td>{{ extOf(g).collateralType || '—' }}</td>
                            <th>名称</th><td>{{ extOf(g).name || '—' }}</td>
                          </tr>
                          <template v-if="extOf(g).collateralType === '土地'">
                            <tr><th>坐落</th><td>{{ extOf(g).address || '—' }}</td><th>面积</th><td>{{ extOf(g).area ? extOf(g).area + '㎡' : '—' }}</td></tr>
                            <tr><th>使用权</th><td colspan="3">{{ extOf(g).landUseType || '—' }}{{ extOf(g).landUseExpiry ? '至' + extOf(g).landUseExpiry : '' }}</td></tr>
                          </template>
                          <template v-else-if="extOf(g).collateralType === '设备'">
                            <tr><th>规格型号</th><td>{{ extOf(g).specModel || '—' }}</td><th>数量</th><td>{{ extOf(g).quantity || '—' }}</td></tr>
                            <tr><th>购置日期</th><td colspan="3">{{ extOf(g).purchaseDate || '—' }}</td></tr>
                          </template>
                          <template v-else-if="extOf(g).collateralType === '车辆'">
                            <tr><th>车牌号</th><td>{{ extOf(g).plateNo || '—' }}</td><th>车架号</th><td>{{ extOf(g).vin || '—' }}</td></tr>
                            <tr><th>登记日期</th><td colspan="3">{{ extOf(g).regDate || '—' }}</td></tr>
                          </template>
                          <template v-else>
                            <tr><th>坐落</th><td>{{ extOf(g).address || '—' }}</td><th>面积</th><td>{{ extOf(g).area ? extOf(g).area + '㎡' : '—' }}</td></tr>
                            <tr><th>产权证号</th><td colspan="3">{{ extOf(g).certNo || '—' }}</td></tr>
                          </template>
                          <tr>
                            <th>估值(万元)</th><td>{{ g.guaranteeAmount ?? '—' }}</td>
                            <th>权属人</th><td>{{ extOf(g).owner || '—' }}</td>
                          </tr>
                          <tr><th>抵押率</th><td colspan="3">{{ extOf(g).mortgageRatio ? extOf(g).mortgageRatio + '%' : '—' }}</td></tr>
                        </tbody>
                      </table>
                    </template>
                    <template v-else-if="g.measureType === 'GUARANTOR'">
                      <table class="measure-table">
                        <tbody>
                          <tr><th>保证人名称</th><td colspan="3">{{ extOf(g).name || '—' }}</td></tr>
                          <tr><th>证件号码</th><td>{{ extOf(g).certNo || '—' }}</td><th>担保余额(万元)</th><td>{{ extOf(g).balance ?? '—' }}</td></tr>
                        </tbody>
                      </table>
                    </template>
                    <template v-else-if="g.measureType === 'PLEDGE'">
                      <table class="measure-table">
                        <tbody>
                          <tr><th>质押物类型</th><td>{{ extOf(g).pledgeType || '—' }}</td><th>名称</th><td>{{ extOf(g).name || '—' }}</td></tr>
                          <tr><th>估值(万元)</th><td>{{ g.guaranteeAmount ?? '—' }}</td><th>权属人</th><td>{{ extOf(g).owner || '—' }}</td></tr>
                        </tbody>
                      </table>
                    </template>
                    <template v-else-if="g.measureType === 'BILL_MARGIN' || g.measureType === 'CREDIT_MARGIN' || g.measureType === 'MARGIN_PLEDGE'">
                      <table class="measure-table">
                        <tbody>
                          <tr><th>保证金(万元)</th><td>{{ g.guaranteeAmount ?? '—' }}</td><th>比例</th><td>{{ extOf(g).marginRatio ? extOf(g).marginRatio + '%' : '—' }}</td></tr>
                          <tr><th>期限(月)</th><td colspan="3">{{ extOf(g).termMonths || '—' }}</td></tr>
                        </tbody>
                      </table>
                    </template>
                    <template v-else-if="g.measureType === 'CERTIFICATE_DEPOSIT'">
                      <table class="measure-table">
                        <tbody>
                          <tr><th>存单号</th><td>{{ extOf(g).certificateNo || '—' }}</td><th>金额(万元)</th><td>{{ g.guaranteeAmount ?? '—' }}</td></tr>
                          <tr><th>到期日</th><td colspan="3">{{ extOf(g).maturityDate || '—' }}</td></tr>
                        </tbody>
                      </table>
                    </template>
                    <span v-else class="dg-label">暂无措施明细数据</span>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
          <div v-else class="op-item__empty-tip">无担保明细</div>
          <!-- 利率对比:原执行 / 申请 / 审批后(可调),要审批的利率一目了然 -->
          <div class="op-item__rates">
            <span class="op-rate"><span class="dg-label">原执行利率</span><b>{{ fmtRate(it.originalRate) }}</b></span>
            <span class="op-rate"><span class="dg-label">申请利率</span><b>{{ fmtRate(it.requestedRate) }}</b></span>
            <span class="op-rate" v-if="!itemPassed(it)">
              <span class="dg-label">审批后利率</span>
              <el-input-number v-model="opRates[it.id]" :min="0" :max="36" :precision="4" :step="0.01" controls-position="right" :disabled="itemApproved(it) || !canOperate(it)" style="width:140px" />
            </span>
          </div>
          <div class="stat-card__sub" v-if="!itemPassed(it) && canOperate(it)">
            {{ isLoan
              ? '审批利率不低于本节点下限方可权限内通过;低于下限将保留利率随整单上送下一节点。'
              : '审批利率不高于本节点上限方可权限内通过;超出将保留利率上送小组表决。' }}
          </div>
          <div class="op-item__actions" v-if="!itemPassed(it)">
            <button class="btn btn--primary" :disabled="submitting || itemApproved(it) || !canOperate(it)" @click="doApproveItem(it)">同意本项</button>
            <button class="btn btn--danger" :disabled="submitting || itemApproved(it) || !canOperate(it)" @click="doRejectItem(it)">否决本项</button>
          </div>
          <div class="op-item__passed-tip" v-else>该分项已由上级节点审批通过,当前节点仅展示,无需重复审批。</div>
        </div>
        <div class="op-form__row">
          <label class="op-form__label">审批意见</label>
          <el-input v-model="opComment" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请输入审批意见(否决时建议说明原因)" />
        </div>
        <div style="display:flex;gap:12px;flex-wrap:wrap">
          <button class="btn btn--primary" :disabled="submitting || !pendingItems.length" @click="doApproveAll">一键通过审批</button>
          <button class="btn btn--danger" :disabled="submitting" @click="doRejectAll">一键否决</button>
          <button class="btn btn--secondary" @click="goBack">返回待办列表</button>
        </div>
      </div>

      <!-- 借据弹窗:借据仅作参考(数仓快照,灌数未必能匹配放款状态),点击「查看借据」链接弹出 -->
      <el-dialog v-model="notesDialog.show" title="借据信息" width="760px">
        <div class="dlg-tip">以下借据信息来自数仓快照,仅作参考,不作为本次审批的主要依据。</div>
        <table class="table">
          <thead><tr><th>合同号</th><th>借据号</th><th>余额(万元)</th><th>执行利率(%)</th><th>利率类型</th><th>放款日</th><th>到期日</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="(n, ni) in notesDialog.items" :key="ni">
              <td>{{ n.contractNo || '—' }}</td>
              <td>{{ n.loanNoteNo || '—' }}</td>
              <td class="num">{{ n.loanBalance ?? '—' }}</td>
              <td class="num">{{ n.executionRate != null ? `${n.executionRate}%` : '—' }}</td>
              <td>{{ rateTypeText(n.rateType) }}{{ n.lprTerm ? `·${n.lprTerm}` : '' }}</td>
              <td>{{ n.startDate || '—' }}</td>
              <td>{{ n.maturityDate || '—' }}</td>
              <td><span class="badge" :class="n.noteStatus === 'NORMAL' ? 'badge--success' : n.noteStatus === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'">{{ noteStatusText(n.noteStatus) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!notesDialog.items.length" class="empty">暂无借据数据</div>
      </el-dialog>
    </div>
    <div class="card" v-else-if="pi.status === 'ROUTING'">
      <div class="empty">该分项当前节点为「{{ nodeLabel(pi.current_node_code) }}」,不在本人审批范围,仅可查看。</div>
    </div>
  </div>
  <div v-else class="empty">加载中...</div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApprovalDetail, approveTask, rejectTask, newIdempotencyKey } from '@/api/approval'
import { download } from '@/api/request'
import { useUserStore } from '@/store/user'
import ContributionPanel from '@/components/ContributionPanel.vue'
import {
  guaranteeTypeText, nodeLabel, itemStatusText, actionText, decisionText,
  execStatusText, roundStatusText, evalResultText, ruleLevelText,
  productName, metricName, termUnitText, carrierTypeText, measureTypeText,
  customerTypeText, memberRoleText, rateTypeText,
  customerClassText, certTypeText, contractStatusText, currencyText
} from '@/utils/dict'
// eslint-disable-next-line no-duplicate-imports
import { inputModeText, relationTypeText, agreementTypeText, agreementStatusText, agreementStatusBadge } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loaded = ref(false)
const submitting = ref(false)
const pricingItemId = computed(() => route.params.id as string)

const pi = ref<any>({})
const application = ref<any>({})
const customer = ref<any>({})
const financing = ref<any[]>([])
const contribution = ref<any[]>([])
const commitments = ref<any[]>([])
const guarantees = ref<any[]>([])
const groupMembers = ref<any[]>([])
const routeChain = ref<string[]>([])
const flowTrace = ref<any[]>([])
// 当前执行状态(流程轨迹增强):当前节点/状态/到达时间
const flowStatus = ref<any>(null)
const source = ref('')
const snapshotInfo = ref<any>({})
const tracking = ref<any[]>([])
const orgPerformance = ref<any[]>([])
const depositAccounts = ref<any[]>([])
const otherLoanSummary = ref<any[]>([])
const otherLoans = ref<any[]>([])
const relations = ref<any[]>([])
const relatedPersons = ref<any[]>([])
const creditAgreements = ref<any[]>([])
// P1-2:合同下借据(数仓借据快照最新批次) / 集团贡献度(数仓 GROUP 口径 TOTAL)
const loanNotes = ref<any[]>([])
const groupContribution = ref<any>(null)
const attachments = ref<any[]>([])
const resolutions = ref<any[]>([])
const resolutionExecutions = ref<any[]>([])
const voteRounds = ref<any[]>([])
const voteResults = ref<any[]>([])
const presidentDecisions = ref<any[]>([])

const opComment = ref('')
// 借据弹窗(审批决定区「合同下借据」改为链接弹出;借据仅作参考,不作为主要审批依据)
const notesDialog = ref<{ show: boolean; items: any[] }>({ show: false, items: [] })

function openNotes(it: any) {
  notesDialog.value = { show: true, items: itemNotes(it) }
}

// 分项审批(参照 demo):同申请分项摘要、每分项审批利率、本次会话内已同意分项
const siblingItems = ref<any[]>([])
const opRates = ref<Record<string, number>>({})
const locallyApproved = ref<Set<string>>(new Set())

const ROLE_NODE: Record<string, string> = {
  branch_manager: 'BRANCH_MANAGER', dept_gm: 'DEPT_GENERAL_MANAGER', vice_president: 'VICE_PRESIDENT'
}

const isLoan = computed(() => application.value.businessType !== 'DEPOSIT')
const businessTypeText = computed(() => application.value.businessType === 'DEPOSIT' ? '存款' : '贷款')
const isGroup = computed(() => !!application.value.groupNo)
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || pi.value.pricing_customer_no || '—')
// 客户主体类型(对公 CORP/对私 INDIV),决定客户基本信息卡片的字段分组
const isCorpCustomer = computed(() => customer.value.custType === 'CORP')
const isIndivCustomer = computed(() => customer.value.custType === 'INDIV')
const statusText = computed(() => itemStatusText(pi.value.status))

// 当前节点在路由链中的位置(高亮)
const currentNodeIndex = computed(() => {
  const idx = routeChain.value.indexOf(pi.value.current_node_code)
  return idx < 0 ? 0 : idx
})

// 仅当分项在审批中且当前节点与登录人角色节点一致时可操作
const actionable = computed(() => {
  if (pi.value.status !== 'ROUTING') return false
  const role = userStore.userInfo?.roles?.[0] || ''
  return pi.value.current_node_code && ROLE_NODE[role] === pi.value.current_node_code
})

// 分项审批决定区(参照 demo):同申请分项逐项同意/否决 + 一键通过/一键否决
const opItems = computed(() => siblingItems.value)
// 本节点可操作且未同意的分项(「一键通过」处理对象)
const pendingItems = computed(() => siblingItems.value.filter(it => canOperate(it) && !itemApproved(it)))
const approvedCount = computed(() => siblingItems.value.filter(it => itemApproved(it)).length)
// 上级节点已通过、当前节点仅展示的分项(不参与本节点审批,也不计入「一键通过」)
const passedCount = computed(() => siblingItems.value.filter(it => itemPassed(it)).length)

// 上级已通过(passed=true 且本节点未同意)→ 只读展示,不重复审批;agreed 含于 passed,需用 !itemApproved 排除本节点已同意
function itemPassed(it: any): boolean {
  return it.passed === true && !itemApproved(it)
}

// 登录人当前角色节点
const currentRoleNode = computed(() => ROLE_NODE[userStore.userInfo?.roles?.[0] || ''])

function canOperate(it: any): boolean {
  return it.status === 'ROUTING' && !!it.currentNodeCode && currentRoleNode.value === it.currentNodeCode
}

function itemApproved(it: any): boolean {
  if (locallyApproved.value.has(it.id)) return true
  // 终态分项(上送/终审/否决)视为已处理;仍 ROUTING 时以后端 agreed(本节点已同意待齐套)为准
  return it.status !== 'ROUTING' || it.agreed === true
}

function itemName(it: any): string {
  const carrier = carrierTypeText(it.carrierType)
  const amount = it.pricingAmount != null ? `${it.pricingAmount} 万` : ''
  return `${it.pricingItemNo || '定价分项'}${carrier ? ' · ' + carrier : ''}${amount ? ' · ' + amount : ''}`
}

// 分项调整利率相对基线是否变化(决定是否传 adjustRate)
function rateChanged(it: any): boolean {
  const base = it.currentApprovalRate ?? it.requestedRate
  return opRates.value[it.id] != null && base != null && Number(opRates.value[it.id]) !== Number(base)
}


const groupTotalAmount = computed(() =>
  groupMembers.value.reduce((sum, m) => sum + (Number(m.requestAmount) || 0), 0))

// P1-2:集团贡献度(数仓 GROUP 口径综合贡献总额,万元)
const groupContributionText = computed(() => {
  const g = groupContribution.value
  if (!g || g.metricValue == null) return '暂无数据'
  return `${g.metricValue}${g.valueType === 'CONTRIBUTION_AMOUNT' ? ' 万元' : ''}`.trim()
})

// P1-2:借据状态文案(数仓 note_status)
function noteStatusText(code?: string) {
  return code === 'NORMAL' ? '正常' : code === 'SETTLED' ? '已结清' : code === 'OVERDUE' ? '逾期' : (code || '—')
}

// 本行融资合同状态徽标(数仓 contract_status)
function contractStatusBadge(s?: string) {
  return s === 'EFFECTIVE' ? 'badge--success' : s === 'SETTLED' ? 'badge--neutral' : s === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'
}

// 部门归属文案(§D16a 矩阵透出:GSB/SXSB/LSB)
function deptText(code?: string) {
  const map: Record<string, string> = { GSB: '公司金融部', SXSB: '授信评审部', LSB: '零售金融部' }
  return code ? (map[code] || code) : '—'
}

// 分项担保方式合并(多担保方式逗号分隔去重)
function guaranteesText(list?: any[]): string {
  if (!list || !list.length) return '—'
  const types = Array.from(new Set(list.map((g) => g.guaranteeType).filter(Boolean)))
  return types.map(guaranteeTypeText).join('、')
}

function extOf(g: any): any {
  const j = g?.extJson
  if (!j) return null
  if (typeof j === 'object') return j
  try { return JSON.parse(j) } catch { return null }
}

// 分项对应合同下的借据(拟签订合同无合同号 → 无借据)
function itemNotes(it: any): any[] {
  if (!it.contractNo) return []
  return loanNotes.value.filter((n) => n.contractNo === it.contractNo)
}

// 流程轨迹动作徽标:否决/终审红,提交灰蓝,通过绿
function traceBadge(t?: string) {
  if (t === 'REJECT' || t === 'VETO') return 'badge--rejected'
  if (t === 'SUBMIT') return 'badge--info'
  return 'badge--approved'
}

function fmtRate(v: any) {
  return v == null || v === '' ? '—' : `${v}%`
}

function memberCommitments(memberNo: string) {
  return commitments.value.filter((c) => c.memberCustomerNo === memberNo)
}

// 历史履约:未达成指标(用于警示条;tracking 现为「按申请聚合」,展平各申请指标)
const unmetTracking = computed(() =>
  tracking.value.flatMap((t) => t.metrics || []).filter((m) => m.resultStatus && m.resultStatus !== 'ACHIEVED'))

// 历史履约总览:全部申请合计(Σ实际 / Σ目标,口径同承诺跟踪页「总体进度」)
const overall = computed(() => {
  let sumActual = 0
  let sumTarget = 0
  for (const t of tracking.value) {
    sumActual += Number(t.sumActual || 0)
    sumTarget += Number(t.sumTarget || 0)
  }
  return {
    sumActual: Number(sumActual.toFixed(2)),
    sumTarget: Number(sumTarget.toFixed(2)),
    ratio: sumTarget ? Number(((sumActual / sumTarget) * 100).toFixed(1)) : 0,
  }
})

// 达成率配色:≥100 绿 / ≥80 黄 / <80 红(与贡献度页一致)
function ratioClass(ratio: any): string {
  const r = Number(ratio)
  if (!Number.isFinite(r)) return 'muted'
  if (r >= 100) return 'rate-ok'
  if (r >= 80) return 'rate-warn'
  return 'rate-bad'
}

function progressPct(ratio: any): number {
  const r = Number(ratio)
  return Number.isFinite(r) ? Math.min(100, Math.max(0, r)) : 0
}

function progressColor(ratio: any): string {
  const r = Number(ratio)
  if (!Number.isFinite(r)) return '#909399'
  if (r >= 100) return '#52c41a'
  if (r >= 80) return '#faad14'
  return '#f56c6c'
}

// 评估结论徽标:ACHIEVED/ON_TRACK 达标绿、AT_RISK 红、其余黄
function statusClass(s?: string): string {
  return s === 'ACHIEVED' || s === 'ON_TRACK' ? 'badge--success' : s === 'AT_RISK' ? 'badge--danger' : 'badge--warning'
}

function fmtDate(v: any) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

// §12.7 ⑪ 决议状态徽标:决议日期=issue_time,无有效期周期
function resolutionStatusBadge(s?: string) {
  const map: Record<string, string> = {
    ISSUED: 'badge--info', CONTRACT_PENDING: 'badge--warning', EXECUTED: 'badge--success', VOID: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}

function voteResultOf(roundId: any) {
  const r = voteResults.value.find((x) => x.roundId === roundId)
  return r ? `${r.approveCount ?? 0} / ${r.rejectCount ?? 0}` : '—'
}

async function load() {
  try {
    const data = await getApprovalDetail(pricingItemId.value)
    pi.value = data.pricingItem || {}
    application.value = data.application?.[0] || {}
    customer.value = data.customer?.[0] || {}
    financing.value = data.financing || []
    contribution.value = data.contribution || []
    commitments.value = data.commitments || []
    guarantees.value = data.guarantees || []
    groupMembers.value = data.groupMembers || []
    routeChain.value = data.routeChain || []
    flowTrace.value = data.flowTrace || []
    flowStatus.value = {
      currentNodeCode: data.currentNodeCode,
      currentStatus: data.currentStatus,
      nodeReachTime: data.nodeReachTime
    }
    source.value = data.source || ''
    snapshotInfo.value = data.snapshotInfo || {}
    tracking.value = data.tracking || []
    orgPerformance.value = data.orgPerformance || []
    depositAccounts.value = data.depositAccounts || []
    otherLoanSummary.value = data.otherLoanSummary || []
    otherLoans.value = [...(data.otherLoans || []), ...(data.appOtherLoans || [])]
    relations.value = data.relations || []
    relatedPersons.value = data.relatedPersons || []
    creditAgreements.value = data.creditAgreements || []
    loanNotes.value = data.loanNotes || []
    groupContribution.value = data.groupContribution || null
    attachments.value = data.attachments || []
    resolutions.value = data.resolutions || []
    resolutionExecutions.value = data.resolutionExecutions || []
    voteRounds.value = data.voteRounds || []
    voteResults.value = data.voteResults || []
    presidentDecisions.value = data.presidentDecisions || []
    // 分项审批决定区:同申请分项摘要(后端未返回时回退为当前分项单元素),每分项预填审批利率
    siblingItems.value = (data.siblingItems && data.siblingItems.length) ? data.siblingItems : [data.pricingItem || {}]
    const rates: Record<string, number> = {}
    for (const it of siblingItems.value) {
      const base = it.currentApprovalRate ?? it.requestedRate
      rates[it.id] = base != null ? Number(base) : undefined
    }
    opRates.value = rates
    loaded.value = true
  } catch {
    ElMessage.error('审批详情加载失败')
  }
}

function downloadAttachment(a: any) {
  download(`/ccr/applications/${application.value.id}/attachments/${a.id}/download`)
}

function goBack() {
  router.push('/approval')
}

/** 返回审批列表(顶部「返回列表」按钮) */
function goBackList() {
  router.push('/approval')
}

// 逐分项「同意本项」(参照 demo):该分项本次同意,同申请全部分项齐套后整单终审/上送
async function doApproveItem(it: any) {
  if (opRates.value[it.id] == null) {
    ElMessage.warning('请填写审批利率')
    return
  }
  submitting.value = true
  try {
    await approveTask({
      pricingItemId: it.id, // 雪花 id 传字符串,避免 JS 精度丢失
      nodeCode: it.currentNodeCode,
      adjustRate: rateChanged(it) ? opRates.value[it.id] : null,
      comment: opComment.value || undefined,
      versionNo: it.versionNo
    }, newIdempotencyKey())
    locallyApproved.value.add(it.id)
    ElMessage.success(`已同意分项 ${it.pricingItemNo || it.id}`)
    await load() // 整单齐套终审/上送后刷新最新状态
  } catch {
    load() // 版本冲突/已处理等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

// 一键通过审批:一次性同意所有未处理分项(参照 demo),随后按整单办结规则处理
async function doApproveAll() {
  const pending = pendingItems.value
  if (!pending.length) {
    ElMessage.warning('没有待审批的分项')
    return
  }
  for (const it of pending) {
    if (opRates.value[it.id] == null) {
      ElMessage.warning(`请填写分项 ${it.pricingItemNo || it.id} 的审批利率`)
      return
    }
  }
  submitting.value = true
  try {
    for (const it of pending) {
      await approveTask({
        pricingItemId: it.id, // 雪花 id 传字符串,避免 JS 精度丢失
        nodeCode: it.currentNodeCode,
        adjustRate: rateChanged(it) ? opRates.value[it.id] : null,
        comment: opComment.value || undefined,
        versionNo: it.versionNo
      }, newIdempotencyKey())
    }
    ElMessage.success('已一键通过全部待审分项')
    goBack()
  } catch {
    load()
  } finally {
    submitting.value = false
  }
}

// 否决(逐项否决/一键否决均整单否决:后端将同申请其余 ROUTING 分项一并置 REJECTED,§14.7 整单流转)
function doReject() {
  // P2-1:否决必填意见(否决后为终态,客户经理凭意见重提,§7.8 退回语义)
  if (!opComment.value?.trim()) {
    ElMessage.warning('否决必须填写审批意见,以便客户经理了解否决原因')
    return
  }
  ElMessageBox.confirm('确认否决该申请?否决后为终态,同申请全部分项一并否决退回。', '一键否决', { type: 'warning', confirmButtonText: '确认否决', cancelButtonText: '取消' })
    .then(async () => {
      submitting.value = true
      try {
        await rejectTask({
          pricingItemId: pricingItemId.value, // 雪花 id 传字符串,避免 JS 精度丢失
          nodeCode: pi.value.current_node_code,
          comment: opComment.value || undefined,
          versionNo: pi.value.version_no
        }, newIdempotencyKey())
        ElMessage.warning('已否决')
        goBack()
      } catch {
        load()
      } finally {
        submitting.value = false
      }
    })
    .catch(() => undefined)
}
function doRejectItem(_it?: any) { doReject() }
function doRejectAll() { doReject() }

onMounted(load)
</script>

<style scoped>
/* 返回列表按钮(section-title 内嵌,ghost 小按钮) */
.btn--back {
  margin-right: 10px;
  padding: 6px 12px;
  font-size: 13px;
  border: 1px solid var(--color-border);
}
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
/* 表格铺满容器:design-system .table 为 display:block(为横向滚动),列少时表格不占满、右侧留白;
   宽屏恢复 table 布局让列自动拉伸占满,窄屏回退 block 保持横向滚动 */
.table { border-radius: var(--radius-sm); }
@media (min-width: 1101px) {
  .card .table { display: table; width: 100%; }
}
.remark-text { font-size: 14px; background: var(--color-bg); border-radius: 6px; padding: 12px; line-height: 1.6; }
.op-form__row { margin-bottom: 12px; }
.op-form__label { display: block; font-size: 13px; color: var(--color-text-sub); margin-bottom: 6px; }
.stat-card__sub { margin-top: 4px; }
/* 流程轨迹:当前执行状态条(当前节点/状态/到达时间) */
.flow-status { display: flex; flex-wrap: wrap; gap: 6px 24px; background: var(--color-bg); border-radius: 8px; padding: 10px 14px; font-size: 14px; margin-bottom: 10px; }
.flow-status__item .dg-label { margin-right: 6px; }
/* 分项审批决定区(参照 demo:逐项同意/否决 + 一键通过/一键否决;上级已通过分项仅展示) */
.op-item { border: 1px solid var(--color-border); border-radius: 12px; padding: 12px; margin: 10px 0; }
.op-item--done { background: var(--color-success-light, #f0fdf4); }
.op-item--passed { background: var(--color-bg); opacity: .85; }
.op-item--lock { background: var(--color-bg); opacity: .75; }
.op-item__head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 14px; }
.op-item__name { font-weight: 600; }
.op-item__subhead { margin-top: 12px; font-size: 13px; font-weight: 600; color: var(--color-text-sub); }
.op-item__empty-tip { margin-top: 8px; font-size: 12px; color: var(--color-text-sub); background: var(--color-bg); border-radius: 6px; padding: 8px 12px; }
.op-item__rates { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; margin-top: 10px; font-size: 13px; }
.op-rate b { font-size: 14px; margin-left: 2px; }
.op-item__actions { display: flex; gap: 8px; margin-top: 8px; }
.op-item__passed-tip { margin-top: 8px; font-size: 12px; color: var(--color-text-sub); }
/* 流程路由链步骤条(首节点→当前节点→终审) */
.flow-steps { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; padding: 10px 2px 4px; }
.flow-step { display: inline-flex; align-items: center; padding: 4px 12px; border-radius: 999px; font-size: 13px; border: 1px solid var(--color-border); color: var(--color-text-sub); }
.flow-step--done { border-color: var(--color-success); color: var(--color-success); background: var(--color-success-light, #f0fdf4); }
.flow-step--current { border-color: var(--color-warning); color: var(--color-warning); background: var(--color-warning-light, #fef3c7); font-weight: 600; }
.flow-step--todo { opacity: .6; }
.flow-step__arrow { color: var(--color-border); margin: 0 2px; }
.warn-bar { background: var(--color-warning-light, #fef3c7); color: var(--color-warning); border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-bottom: 10px; }
/* 借据链接与弹窗提示(借据仅作参考) */
.link { color: var(--color-primary); cursor: pointer; text-decoration: underline; }
.link:hover { opacity: .8; }
.text-sub { color: var(--color-text-sub); }
.dlg-tip { margin-bottom: 10px; font-size: 13px; color: var(--color-text-sub); }
.rate-ok { color: var(--color-success); font-weight: 600; }
.rate-bad { color: var(--color-danger); font-weight: 600; }
.rate-warn { color: #faad14; font-weight: 600; }
.muted { color: var(--color-text-secondary, #909399); }

/* 历史履约:按申请聚合总览 */
.overall { margin-bottom: 2px; }
.overall__sum { display: flex; flex-wrap: wrap; gap: 28px; }
.overall__sum-item { display: inline-flex; align-items: baseline; gap: 6px; font-size: 13px; color: var(--color-text-secondary, #606266); }
.overall__sum-item .metric-val__num { font-size: 18px; font-weight: 700; color: var(--color-text-primary, #303133); }
.overall__sum-item .unit { font-size: 12px; }

/* 历史履约:按申请行(可点击展开)+ 明细表 */
.app-row { cursor: pointer; }
.app-row:hover td { background: var(--color-fill, #f5f7fa); }
.sub-table { margin: 10px 0 4px; padding: 12px; background: var(--color-fill, #f8f9fa); border-radius: 6px; }
.sub-table__title { font-size: 13px; font-weight: 600; margin-bottom: 10px; }
/* 担保措施明细:嵌套键值表格(与贷款合同/担保表同色系,圆角细边框) */
.measure-detail > td { background: #fafbfc; padding: 4px 8px 8px; }
.measure-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.measure-table th {
  width: 96px; padding: 6px 10px; text-align: left; white-space: nowrap;
  color: var(--color-text-sub); font-weight: 600; font-size: 12px;
  background: #f5f7fa; border: 1px solid var(--color-border-light);
}
.measure-table td {
  padding: 6px 12px; color: var(--color-text-main);
  border: 1px solid var(--color-border-light); background: var(--color-surface);
  word-break: break-word;
}
.measure-table td:empty { color: var(--color-text-sub); }
/* 中间断点:中等宽度下网格降列(页面自适应增强) */
@media (max-width: 1100px) {
  .detail-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
