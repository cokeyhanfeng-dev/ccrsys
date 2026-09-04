<template>
  <div>
    <div class="section-head">
      <div class="section-title">
        申请档案
        <span v-if="archive.application" class="app-no">{{ val(archive.application, 'application_no', 'applicationNo') }}</span>
      </div>
      <InfoTip content="申请档案:展示申请材料、审批轨迹、调价记录、表决与行长决策、决议与执行核验等审批全过程完整留痕;承诺履约等后续跟踪不在档案展示。" />
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

    <div v-loading="loading">
      <div v-if="loadError" class="empty">加载失败，请刷新</div>
      <template v-else-if="archive.application">
      <!-- 1. 申请内容 -->
      <div class="card">
        <div class="card__head"><span>申请内容</span><span :class="appStatusBadge(val(archive.application, 'status'))">{{ appStatusText(val(archive.application, 'status')) }}</span></div>
        <div class="desc-grid">
          <div class="desc-item"><div class="desc-item__label">申请号</div><div class="desc-item__value">{{ val(archive.application, 'application_no', 'applicationNo') }}</div></div>
          <div class="desc-item"><div class="desc-item__label">业务类型</div><div class="desc-item__value">{{ businessTypeText(val(archive.application, 'business_type', 'businessType')) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">客户范围</div><div class="desc-item__value">{{ customerScopeText(val(archive.application, 'customer_scope', 'customerScope')) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">客户号</div><div class="desc-item__value">{{ val(archive.application, 'customer_no', 'customerNo') }}</div></div>
          <div class="desc-item"><div class="desc-item__label">集团号</div><div class="desc-item__value">{{ val(archive.application, 'group_no', 'groupNo') }}</div></div>
          <div class="desc-item"><div class="desc-item__label">提交时间</div><div class="desc-item__value">{{ fmtTime(val(archive.application, 'submit_time', 'submitTime')) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">终态时间</div><div class="desc-item__value">{{ fmtTime(val(archive.application, 'final_time', 'finalTime')) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">关联原申请</div><div class="desc-item__value"><span v-if="sourceApplicationId"><a class="archive-link" @click="goSourceArchive">查看原申请</a></span><span v-else>—</span></div></div>
          <div class="desc-item desc-item--full"><div class="desc-item__label">客户经理备注</div><div class="desc-item__value">{{ val(archive.application, 'application_remark', 'applicationRemark') }}</div></div>
        </div>
      </div>

      <!-- 1b. 客户基本信息(提交快照优先 + 人工修正/手工录入) -->
      <div class="card">
        <div class="card__head"><span>客户基本信息</span></div>
        <div class="desc-grid" v-if="hasCustomer">
          <div class="desc-item"><div class="desc-item__label">客户名称</div><div class="desc-item__value">{{ customerName }}</div></div>
          <div class="desc-item"><div class="desc-item__label">{{ isGroup ? '集团编号' : '行内客户号' }}</div><div class="desc-item__value">{{ customer.customerNo || '—' }}</div></div>
          <div class="desc-item"><div class="desc-item__label">客户类型</div><div class="desc-item__value">{{ isGroup ? '集团' : (isCorpCustomer ? '对公' : isIndivCustomer ? '个人' : '—') }}</div></div>
          <div v-if="isGroup && customer.groupType" class="desc-item"><div class="desc-item__label">集团类型</div><div class="desc-item__value">{{ groupTypeText(customer.groupType) }}</div></div>
          <div v-if="isGroup && customer.currency" class="desc-item"><div class="desc-item__label">币种</div><div class="desc-item__value">{{ currencyText(customer.currency) }}</div></div>
          <div v-if="isGroup && customer.applyAmount != null" class="desc-item"><div class="desc-item__label">本次申请额度(万元)</div><div class="desc-item__value">{{ fmtAmount(customer.applyAmount) }}</div></div>
          <div v-if="isCorpCustomer" class="desc-item"><div class="desc-item__label">统一社会信用代码</div><div class="desc-item__value">{{ customer.certNo || '—' }}</div></div>
          <div v-if="isIndivCustomer" class="desc-item"><div class="desc-item__label">证件号码</div><div class="desc-item__value">{{ customer.certNo || '—' }}</div></div>
          <div v-if="isCorpCustomer" class="desc-item"><div class="desc-item__label">企业性质</div><div class="desc-item__value">{{ customer.entpCharic === 'SOE' ? '国企' : '非国企' }}</div></div>
          <div v-if="customer.entpScale" class="desc-item"><div class="desc-item__label">企业规模</div><div class="desc-item__value">{{ entpScaleText(customer.entpScale) }}</div></div>
          <div v-if="customer.industry" class="desc-item"><div class="desc-item__label">所属行业</div><div class="desc-item__value">{{ customer.industry }}</div></div>
          <div v-if="customer.creditLevel" class="desc-item"><div class="desc-item__label">内部信用等级</div><div class="desc-item__value">{{ customer.creditLevel }}</div></div>
          <div v-if="customer.fiveLevelClass" class="desc-item"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(customer.fiveLevelClass) }}</div></div>
          <div v-if="customer.empeNum != null" class="desc-item"><div class="desc-item__label">员工人数</div><div class="desc-item__value">{{ customer.empeNum }}</div></div>
          <div v-if="customer.totalAssets != null" class="desc-item"><div class="desc-item__label">总资产(万元)</div><div class="desc-item__value">{{ fmtAmount(customer.totalAssets) }}</div></div>
          <div v-if="customer.registeredCapital != null" class="desc-item"><div class="desc-item__label">注册资本(万元)</div><div class="desc-item__value">{{ fmtAmount(customer.registeredCapital) }}</div></div>
          <div v-if="customer.estbDate" class="desc-item"><div class="desc-item__label">成立日期</div><div class="desc-item__value">{{ customer.estbDate }}</div></div>
          <div v-if="customer.restAddr" class="desc-item"><div class="desc-item__label">注册地址</div><div class="desc-item__value">{{ customer.restAddr }}</div></div>
          <div v-if="customer.occupation" class="desc-item"><div class="desc-item__label">职业</div><div class="desc-item__value">{{ customer.occupation }}</div></div>
          <div v-if="customer.annualIncome != null" class="desc-item"><div class="desc-item__label">年收入(万元)</div><div class="desc-item__value">{{ fmtAmount(customer.annualIncome) }}</div></div>
          <div v-if="customer.maritalStatus" class="desc-item"><div class="desc-item__label">婚姻状况</div><div class="desc-item__value">{{ maritalStatusText(customer.maritalStatus) }}</div></div>
          <div v-if="customer.address" class="desc-item"><div class="desc-item__label">居住地址</div><div class="desc-item__value">{{ customer.address }}</div></div>
          <div v-if="customer.phone" class="desc-item"><div class="desc-item__label">联系电话</div><div class="desc-item__value">{{ customer.phone }}</div></div>
          <div v-if="customer.openOrgName" class="desc-item"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ customer.openOrgName }}</div></div>
          <div v-if="customer.openDate" class="desc-item"><div class="desc-item__label">开户日期</div><div class="desc-item__value">{{ customer.openDate }}</div></div>
          <div v-if="customer.basicAccount" class="desc-item"><div class="desc-item__label">基本户账户</div><div class="desc-item__value">{{ customer.basicAccount }}</div></div>
          <div v-if="customer.customerClass" class="desc-item"><div class="desc-item__label">客户分类</div><div class="desc-item__value">{{ customerClassText(customer.customerClass) }}</div></div>
        </div>
        <div v-else class="empty-line">暂无数据</div>
      </div>

      <!-- 2. 集团与成员 -->
      <div class="card" v-if="isGroup">
        <div class="card__head"><span>集团成员</span></div>
        <table class="table" v-if="archive.members?.length">
          <thead><tr><th>成员名称</th><th>成员客户号</th><th>成员角色</th><th>申请金额(万元)</th></tr></thead>
          <tbody>
            <tr v-for="(m, i) in archive.members" :key="i">
              <td>{{ val(m, 'member_name', 'memberName') || '—' }}</td>
              <td>{{ val(m, 'member_customer_no', 'memberCustomerNo') }}</td>
              <td>{{ memberRoleText(val(m, 'member_role', 'memberRole')) }}</td>
              <td class="num">{{ fmtAmount(val(m, 'request_amount', 'requestAmount')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无数据</div>
        <!-- 每个成员完整基本信息(对公要素:统一社会信用代码/五级分类/信用等级/行业/注册资本/开户机构/开户日期/基本户) -->
        <div v-for="(m, i) in archive.members" :key="'info-' + i" style="margin-top:12px">
          <div class="plan-block__head">
            <span class="badge badge--info">成员 {{ memberName(m) }}</span>
            <span class="section-tip">基本信息</span>
          </div>
          <div class="desc-grid">
            <div class="desc-item"><div class="desc-item__label">成员名称</div><div class="desc-item__value">{{ memberName(m) }}</div></div>
            <div class="desc-item"><div class="desc-item__label">成员客户号</div><div class="desc-item__value">{{ val(m, 'member_customer_no', 'memberCustomerNo') }}</div></div>
            <div class="desc-item"><div class="desc-item__label">成员角色</div><div class="desc-item__value">{{ memberRoleText(val(m, 'member_role', 'memberRole')) }}</div></div>
            <div class="desc-item"><div class="desc-item__label">申请金额(万元)</div><div class="desc-item__value">{{ fmtAmount(val(m, 'request_amount', 'requestAmount')) }}</div></div>
            <div v-if="m.certNo" class="desc-item"><div class="desc-item__label">统一社会信用代码</div><div class="desc-item__value">{{ m.certNo }}</div></div>
            <div v-if="m.fiveLevelClass" class="desc-item"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(m.fiveLevelClass) }}</div></div>
            <div v-if="m.creditLevel" class="desc-item"><div class="desc-item__label">内部信用等级</div><div class="desc-item__value">{{ m.creditLevel }}</div></div>
            <div v-if="m.industry" class="desc-item"><div class="desc-item__label">所属行业</div><div class="desc-item__value">{{ m.industry }}</div></div>
            <div v-if="m.registeredCapital != null" class="desc-item"><div class="desc-item__label">注册资本(万元)</div><div class="desc-item__value">{{ m.registeredCapital }}</div></div>
            <div v-if="m.openOrgName" class="desc-item"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ m.openOrgName }}</div></div>
            <div v-if="m.openDate" class="desc-item"><div class="desc-item__label">开户日期</div><div class="desc-item__value">{{ m.openDate }}</div></div>
            <div v-if="m.basicAccount" class="desc-item"><div class="desc-item__label">基本户账户</div><div class="desc-item__value">{{ m.basicAccount }}</div></div>
          </div>
        </div>
        <!-- 集团授信与贡献度(§12.4 集团场景) -->
        <div class="desc-grid" v-if="groupCredit.length" style="margin-top:12px">
          <div class="desc-item"><div class="desc-item__label">集团授信总额(万元)</div><div class="desc-item__value">{{ fmtAmount(groupCredit[0].approvedTotalAmount) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">已分配额度(万元)</div><div class="desc-item__value">{{ fmtAmount(groupCredit[0].allocatedAmount) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">已用额度(万元)</div><div class="desc-item__value">{{ fmtAmount(groupCredit[0].usedAmount) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">可用额度(万元)</div><div class="desc-item__value">{{ fmtAmount(groupCredit[0].availableAmount) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">授信到期日</div><div class="desc-item__value">{{ groupCredit[0].creditEnd || '—' }}</div></div>
          <div class="desc-item"><div class="desc-item__label">授信状态</div><div class="desc-item__value">{{ creditStatusText(groupCredit[0].creditStatus || '—') }}</div></div>
          <div class="desc-item"><div class="desc-item__label">集团贡献度</div><div class="desc-item__value">{{ groupContributionText }}</div></div>
        </div>
      </div>

      <!-- 2b. 授信信息(补录 + 数仓协议合并去重) -->
      <div class="card">
        <div class="card__head"><span>授信信息</span></div>
        <table class="table" v-if="creditAgreements.length">
          <thead><tr><th>授信协议编号</th><th>授信类型</th><th>币种</th><th>状态</th><th>开始日期</th><th>结束日期</th><th>授信额度(万元)</th><th>已用额度(万元)</th><th>可用额度(万元)</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in creditAgreements" :key="i">
              <td>
                {{ a.agreementNo || '—' }}
                <span v-if="a.source === 'APPLICATION'" class="badge badge--warning" style="margin-left:4px">补录</span>
              </td>
              <td>{{ agreementTypeText(a.agreementType) }}</td>
              <td>{{ currencyText(a.currency || 'CNY') }}</td>
              <td><span :class="agreementStatusBadge(a.agreementStatus)">{{ agreementStatusText(a.agreementStatus) }}</span></td>
              <td>{{ a.startDate || '—' }}</td>
              <td>{{ a.endDate || '—' }}</td>
              <td class="num">{{ fmtAmount(a.creditAmount) }}</td>
              <td class="num">{{ fmtAmount(a.usedAmount) }}</td>
              <td class="num">{{ fmtAmount(a.availableAmount) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无授信协议数据</div>
      </div>

      <!-- 2d. 申请材料附件(申请时上传材料元数据,下载走附件下载接口) -->
      <div class="card">
        <div class="card__head"><span>申请材料附件</span><span class="badge badge--info">{{ attachments.length }} 个附件</span></div>
        <table class="table" v-if="attachments.length">
          <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in attachments" :key="i">
              <td class="file-name" :title="a.fileName">{{ a.fileName }} <span v-if="a.sourceType === 'MINIAPP_CREDIT_RESOLUTION'" class="badge badge--info">授信决议 {{ a.sourceResolutionNo }}</span></td>
              <td class="num">{{ fmtSize(a.fileSize) }}</td>
              <td>{{ fmtTime(a.createTime) }}</td>
              <td><button class="btn btn--text" @click="downloadAttachment(a)">下载</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无附件（申请时未上传材料）</div>
      </div>

      <!-- 2e. 他行融资(申请人工补录/Excel 导入 + 数仓征信) -->
      <div class="card" v-if="isLoan">
        <div class="card__head"><span>他行融资</span></div>
        <div class="desc-grid" v-if="otherLoanSummary.length">
          <div class="desc-item"><div class="desc-item__label">他行机构数</div><div class="desc-item__value">{{ otherLoanSummary[0].lenderCount ?? '—' }}</div></div>
          <div class="desc-item"><div class="desc-item__label">授信总额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].creditAmountTotal) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">已用总额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].usedAmountTotal) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">未结清笔数</div><div class="desc-item__value">{{ otherLoanSummary[0].loanAccountCount ?? '—' }}</div></div>
          <div class="desc-item"><div class="desc-item__label">逾期账户</div><div class="desc-item__value">{{ otherLoanSummary[0].overdueAccountCount ?? '—' }}</div></div>
          <div class="desc-item"><div class="desc-item__label">逾期余额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].overdueBalance) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">不良余额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].nplBalance) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">关注类余额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].specialMentionBalance) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">对外担保余额(万元)</div><div class="desc-item__value">{{ fmtAmount(otherLoanSummary[0].externalGuaranteeBalance) }}</div></div>
          <div class="desc-item"><div class="desc-item__label">报告日期</div><div class="desc-item__value">{{ otherLoanSummary[0].reportDate ? String(otherLoanSummary[0].reportDate).slice(0, 10) : '—' }}</div></div>
        </div>
        <table class="table" v-if="otherLoans.length" style="margin-top:8px">
          <thead><tr><th>融资机构</th><th>授信额(万元)</th><th>已用额(万元)</th><th>余额(万元)</th><th>年化利率(%)</th><th>数据日期</th><th>来源</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in otherLoans" :key="i">
              <td>{{ d.lenderName }}</td>
              <td class="num">{{ fmtAmount(d.creditAmount) }}</td>
              <td class="num">{{ fmtAmount(d.usedAmount) }}</td>
              <td class="num">{{ fmtAmount(d.balanceAmount) }}</td>
              <td class="num">{{ d.annualRate ?? '—' }}</td>
              <td>{{ d.dataDt ? String(d.dataDt).slice(0, 10) : '—' }}</td>
              <td><span class="badge badge--neutral">{{ inputModeText(d.inputMode) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!otherLoans.length" class="empty-line">暂无他行融资记录</div>
      </div>

      <!-- 5. 授信分项(担保明细并入行内展开:点击「担保方式」展开;信用/未录措施无内容不可展开,§2026-09-04) -->
      <div class="card">
        <div class="card__head"><span>授信分项</span></div>
        <table class="table" v-if="archive.pricingItems?.length">
          <thead>
            <tr>
              <th>{{ isGroup ? '成员' : '定价客户' }}</th><th>产品</th><th>原执行利率</th><th>授信协议编号</th><th>担保方式</th><th>金额(万元)</th><th>期限</th>
              <th>申请利率</th><th>审批利率</th><th>最终利率</th><th>当前节点</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="p in archive.pricingItems" :key="val(p, 'id')">
              <tr>
                <td>{{ isGroup ? pricingMemberLabel(p) : val(p, 'pricing_customer_no', 'pricingCustomerNo') }}</td>
                <td>{{ productName(val(p, 'product_code', 'productCode')) }}</td>
                <!-- 原执行利率与审批详情页「申请内容」表口径一致(§2026-08-26 档案/审批保持一致;新增业务无原利率) -->
                <td :class="val(p, 'original_rate', 'originalRate') != null ? 'num' : ''">{{ val(p, 'original_rate', 'originalRate') != null ? rateText(val(p, 'original_rate', 'originalRate')) : '新增业务' }}</td>
                <td :class="itemAgreementNo(p) === '新增业务' ? '' : 'num'">{{ itemAgreementNo(p) }}</td>
                <!-- 担保方式:有担保措施可点开行内明细;信用/未录措施纯文本(无内容可展开) -->
                <td>
                  <span v-if="hasMeasureRows(p)" class="expand-toggle" role="button" tabindex="0" @click.stop="toggleExpand(p)" @keydown.enter="toggleExpand(p)">{{ itemGuaranteeText(p) }}<span class="chev">{{ isExpanded(p) ? '▲' : '▼' }}</span></span>
                  <span v-else>{{ itemGuaranteeText(p) }}</span>
                </td>
                <td class="num">{{ fmtAmount(val(p, 'pricing_amount', 'pricingAmount')) }}</td>
                <td>{{ termText(p) }}</td>
                <td class="num">{{ rateText(val(p, 'requested_rate', 'requestedRate')) }}</td>
                <td class="num">{{ rateText(val(p, 'current_approval_rate', 'currentApprovalRate')) }}</td>
                <td class="num"><b>{{ rateText(val(p, 'final_rate', 'finalRate')) }}</b></td>
                <td>{{ nodeLabel(val(p, 'current_node_code', 'currentNodeCode')) }}</td>
                <td><span :class="itemStatusBadge(val(p, 'status'))">{{ itemStatusText(val(p, 'status')) }}</span></td>
              </tr>
              <!-- 展开:该分项的担保措施明细(抵押物/保证人等),申请录入按分项挂载 -->
              <tr v-if="isExpanded(p)" class="expand-row">
                <td :colspan="12">
                  <div class="expand-panel">
                    <div class="expand-title">担保明细<span class="section-tip">申请录入</span></div>
                    <table class="table">
                      <thead><tr><th>担保措施</th><th>担保金额(万元)</th><th>担保物信息</th></tr></thead>
                      <tbody>
                        <tr v-for="(g, gi) in measureRows(p)" :key="gi">
                          <td>{{ measureTypeText(g.measureType) }}</td>
                          <td class="num">{{ fmtAmount(g.guaranteeAmount) }}</td>
                          <td class="hash-cell">{{ extText(g) }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无数据</div>
      </div>

      <!-- 5d. 贡献度参考(当前与拟达成贡献度并排;G3 定价依据,贷款场景) -->
      <div class="card" v-if="isLoan">
        <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
        <ContributionPanel :contribution="contribution" :commitments="archive.commitments || []" />
      </div>

      <!-- 5e. 机构达成(仅贷款场景;存款无机构达成概念,§2026-08-26 用户要求删除)
           2026-09-04 两版承诺计划合并改造:切 v2 track 到期终态口径,与审批详情统一——只展示达成率+进度条,
           达成金额/目标金额/统计月份/数据日期已移除;badge 由「数仓」改「承诺」 -->
      <div class="card" v-if="isLoan && orgPerformance.length">
        <div class="card__head"><span>机构达成</span><span class="badge badge--info">承诺</span></div>
        <div class="org-perf" v-if="orgPerfRow">
          <div class="org-perf__main">
            <div class="org-perf__title">
              <span class="org-perf__name">{{ orgPerfRow.orgName || orgPerfRow.orgCode || '—' }}</span>
            </div>
            <div class="org-perf__rate" :class="rateCls(orgPerfRow.completionRate)">{{ fmtPct(orgPerfRow.completionRate) }}</div>
            <div class="org-perf__rate-label">到期承诺达成率</div>
          </div>
          <div class="org-perf__bar">
            <div class="org-perf__bar-inner" :class="rateCls(orgPerfRow.completionRate)" :style="{ width: progressWidth(orgPerfRow.completionRate) }"></div>
          </div>
        </div>
      </div>

      <!-- 5b. 关联人(申请录入,按关联客户号补全基本信息/授信信息) -->
      <div class="card" v-if="archive.relatedPersons?.length">
        <div class="card__head"><span>关联人</span></div>
        <table class="table">
          <thead>
            <tr><th>姓名/名称</th><th>证件号</th><th>关系类型</th><th>行内客户号</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入(万元)</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in archive.relatedPersons" :key="i">
              <td>{{ val(r, 'personName') }}</td>
              <td>{{ val(r, 'certNo') }}</td>
              <td>{{ relationTypeText(val(r, 'relationType')) }}</td>
              <td>{{ val(r, 'relatedCustomerNo') }}</td>
              <td v-if="r.custType === 'CORP'">{{ customerTypeText(val(r, 'entpCharic')) }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'industry') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'creditLevel') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ fiveLevelClassText(val(r, 'fiveLevelClass')) }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ val(r, 'occupation') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ fmtAmount(val(r, 'annualIncome')) }}</td>
              <td v-else>—</td>
              <td class="num">{{ val(r, 'creditAgreementCount') }}</td>
              <td class="num">{{ fmtAmount(val(r, 'loanBalanceTotal')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 6. 数据快照日期 -->
      <div class="card">
        <div class="card__head"><span>数据快照</span></div>
        <table class="table" v-if="archive.snapshotBundles?.length">
          <thead><tr><th>快照包号</th><th>状态</th><th>冻结时间</th><th>记录数</th><th>摘要哈希</th></tr></thead>
          <tbody>
            <tr v-for="b in archive.snapshotBundles" :key="val(b, 'id')">
              <td>{{ val(b, 'bundleNo', 'bundle_no') }}</td>
              <td>{{ snapshotStatusText(val(b, 'status')) }}</td>
              <td>{{ fmtTime(val(b, 'freezeTime', 'freeze_time')) }}</td>
              <td class="num">{{ val(b, 'recordCount', 'record_count') }}</td>
              <td class="hash-cell">{{ val(b, 'bundleHash', 'bundle_hash') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无数据</div>
      </div>

      <!-- 8. 审批轨迹 -->
      <div class="card">
        <div class="card__head"><span>审批轨迹</span></div>
        <table class="table" v-if="archive.approvalActions?.length">
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
              <td class="text-ellipsis" :title="String(val(a, 'action_comment', 'actionComment'))">{{ val(a, 'action_comment', 'actionComment') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">暂无数据</div>
      </div>

      <!-- 8b. 审批调价记录(利率调整明细,含边界/理由/操作人) -->
      <div class="card" v-if="archive.rateAdjustments?.length">
        <div class="card__head"><span>审批调价记录</span></div>
        <table class="table">
          <thead><tr><th>节点</th><th>调整前利率</th><th>调整后利率</th><th>调价理由</th><th>操作人</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in archive.rateAdjustments" :key="i">
              <td>{{ nodeLabel(val(a, 'node_code', 'nodeCode')) }}</td>
              <td class="num">{{ rateText(val(a, 'before_rate', 'beforeRate')) }}</td>
              <td class="num"><b>{{ rateText(val(a, 'after_rate', 'afterRate')) }}</b></td>
              <td class="text-ellipsis" :title="String(val(a, 'adjust_reason', 'adjustReason'))">{{ val(a, 'adjust_reason', 'adjustReason') }}</td>
              <td>{{ val(a, 'operatorName', 'operator_name') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 9. 表决与行长决策(小组表决计票汇总 + 行长决策;仅行长·审计·超管可见) -->
      <div class="card" v-if="canViewVote && (archive.voteRounds?.length || archive.presidentDecisions?.length)">
        <div class="card__head"><span>表决与行长决策</span></div>
        <table class="table" v-if="archive.voteRounds?.length">
          <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
          <tbody>
            <tr v-for="(v, i) in archive.voteRounds" :key="i">
              <td>{{ val(v, 'roundName', 'round_name') }}</td>
              <td><span :class="roundStatusBadge(val(v, 'status'))">{{ roundStatusText(val(v, 'status')) }}</span></td>
              <td class="num">{{ voteResultOf(val(v, 'id')) }}</td>
              <td>{{ fmtTime(val(v, 'roundStartTime', 'round_start_time')) }}</td>
              <td>{{ fmtTime(val(v, 'roundEndTime', 'round_end_time')) }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table" style="margin-top:8px" v-if="archive.presidentDecisions?.length">
          <thead><tr><th>行长决策</th><th>意见</th><th>决策时间</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in archive.presidentDecisions" :key="i">
              <td><span :class="decisionBadge(val(d, 'decision'))">{{ decisionText(val(d, 'decision')) }}</span></td>
              <td>{{ val(d, 'opinion') }}</td>
              <td>{{ fmtTime(val(d, 'decisionTime', 'decision_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 10. 决议与执行核验(§12.7:决议日期=issue_time,无有效期周期) -->
      <div class="card" v-if="archive.resolutions?.length">
        <div class="card__head"><span>决议</span><span class="badge" :class="execStatusBadge(firstResolutionStatus)">{{ execStatusText(firstResolutionStatus) }}</span></div>
        <table class="table">
          <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="(r, i) in archive.resolutions" :key="i">
              <td>{{ val(r, 'resolutionNo', 'resolution_no') }}</td>
              <td class="num"><b>{{ rateText(val(r, 'finalRate', 'final_rate')) }}</b></td>
              <td>{{ decisionSourceText(val(r, 'decisionSource', 'decision_source')) }}</td>
              <td>{{ fmtDate(val(r, 'issueTime', 'issue_time')) }}</td>
              <td><span :class="execStatusBadge(val(r, 'status'))">{{ execStatusText(val(r, 'status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <table class="table" style="margin-top:8px" v-if="archive.resolutionExecutions?.length">
          <thead><tr><th>贷款合同号</th><th>补充协议号</th><th>执行利率</th><th>执行状态</th><th>核验结果</th><th>核验时间</th></tr></thead>
          <tbody>
            <tr v-for="(e, i) in archive.resolutionExecutions" :key="i">
              <td>{{ val(e, 'loanContractNo', 'loan_contract_no') }}</td>
              <td>{{ val(e, 'supplementAgreementNo', 'supplement_agreement_no') }}</td>
              <td class="num">{{ rateText(val(e, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ execStatusText(val(e, 'executionStatus', 'execution_status')) }}</td>
              <td class="text-ellipsis" :title="String(val(e, 'reconcileResult', 'reconcile_result'))">{{ val(e, 'reconcileResult', 'reconcile_result') }}</td>
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
            <span :class="planStatusBadge(plan.status)">{{ planStatusText(plan.status) }}</span>
            <span class="section-tip">{{ plan.startDate ? String(plan.startDate).slice(0, 10) : '' }} ~ {{ plan.endDate ? String(plan.endDate).slice(0, 10) : '' }}</span>
          </div>
          <table class="table">
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
      <div v-else class="empty">暂无数据</div>
    </div>
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
  appStatusText, appStatusBadge, itemStatusText, itemStatusBadge, relationTypeText,
  businessTypeText, customerScopeText, nodeLabel, roleText, actionText,
  productName, metricName, memberRoleText, termUnitText, commitmentUnitText,
  currencyText, decisionText, decisionBadge,
  roundStatusText, roundStatusBadge, execStatusText, execStatusBadge,
  evalResultText, planStatusText, planStatusBadge,
  guaranteeTypeText, measureTypeText, agreementTypeText, agreementStatusText, agreementStatusBadge,
  customerTypeText, customerClassText, certTypeText, inputModeText,
  entpScaleText, maritalStatusText, creditStatusText, snapshotStatusText, decisionSourceText,
  fiveLevelClassText, groupTypeText
} from '@/utils/dict'
import { fmtAmount, fmtSize, fmtDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const applicationId = route.params.id as string
const archive = ref<Record<string, any>>({})
const loading = ref(true)
const loadError = ref(false)
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

// ===== 申请内容留痕(§14.4):客户 / 授信 / 融资 / 附件 / 担保 / 贡献度 / 机构达成 =====
const customer = computed(() => (archive.value.customer && archive.value.customer.length) ? archive.value.customer[0] : {})
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || val(archive.value.application || {}, 'customer_no', 'customerNo') || '—')
const isCorpCustomer = computed(() => customer.value.custType === 'CORP')
const isIndivCustomer = computed(() => customer.value.custType === 'INDIV')
const isLoan = computed(() => val(archive.value.application || {}, 'business_type', 'businessType') !== 'DEPOSIT')
// 关联原申请(内部 source_application_id → 跳转原档案;§UI审查 ⑤)
const sourceApplicationId = computed(() => {
  const v = val(archive.value.application || {}, 'source_application_id', 'sourceApplicationId')
  return v !== '—' && v != null && v !== '' ? String(v) : ''
})
function goSourceArchive() {
  if (sourceApplicationId.value) router.push(`/history/archive/${sourceApplicationId.value}`)
}
// 决议区块标题状态(首条决议;§UI审查 ⑩ VOID 时显示「已作废」)
const firstResolutionStatus = computed(() => val((archive.value.resolutions || [])[0] || {}, 'status'))
const creditAgreements = computed(() => archive.value.creditAgreements || [])
const attachments = computed(() => archive.value.attachments || [])
const otherLoanSummary = computed(() => archive.value.otherLoanSummary || [])
const otherLoans = computed(() => archive.value.otherLoans || [])
const contribution = computed(() => archive.value.contribution || [])
const orgPerformance = computed(() => archive.value.orgPerformance || [])
// 机构达成概要行(与审批详情统一:后端至多 1 条,取首行,2026-09-04)
const orgPerfRow = computed(() => orgPerformance.value[0] || null)
const groupCredit = computed(() => archive.value.groupCredit || [])
const groupContributionText = computed(() => {
  const g = (archive.value.groupContribution || [])[0]
  if (!g || g.metricValue == null) return '暂无数据'
  return `${g.metricValue}${g.valueType === 'CONTRIBUTION_AMOUNT' ? ' 万元' : ''}`.trim()
})
// 授信分项明细(后端按 pricing_item_id 聚合)
function guaranteesOf(p: any): any[] {
  const map = archive.value.guaranteesByItem || {}
  return map[String(val(p, 'id'))] || []
}
// 授信分项行内展开担保明细(2026-09-04:原独立「担保明细」卡移除并入此行;信用/未录措施分项无内容不可展开)
const expandedItems = ref<string[]>([])
function isExpanded(p: any): boolean {
  return expandedItems.value.includes(String(val(p, 'id')))
}
function toggleExpand(p: any) {
  const id = String(val(p, 'id'))
  expandedItems.value = expandedItems.value.includes(id)
    ? expandedItems.value.filter((x) => x !== id)
    : [...expandedItems.value, id]
}
/** 该分项是否有真实担保措施行(排除信用等仅包无措施的占位行) */
function hasMeasureRows(p: any): boolean {
  return guaranteesOf(p).some((g: any) => g.measureType)
}
/** 该分项的担保措施明细(已过滤占位行,供展开表渲染) */
function measureRows(p: any): any[] {
  return guaranteesOf(p).filter((g: any) => g.measureType)
}
// 集团成员名称(档案成员行 snake_case/camel 兼容,缺失回退客户号)
function memberName(m: any): string {
  return val(m, 'member_name', 'memberName') || val(m, 'member_customer_no', 'memberCustomerNo') || '—'
}
// 定价分项成员标签(GROUP 场景):按成员客户号匹配成员名,无匹配回退客户号
function pricingMemberLabel(p: any): string {
  const no = val(p, 'member_customer_no', 'memberCustomerNo')
  if (!no) return val(p, 'pricing_customer_no', 'pricingCustomerNo') || '—'
  const m = (archive.value.members || []).find((x) => String(val(x, 'member_customer_no', 'memberCustomerNo')) === String(no))
  return m ? (val(m, 'member_name', 'memberName') || no) : no
}
// 定价分项授信协议编号:存量分项(有原执行利率=存量调息)取申请提交时授信协议(creditInfoJson.agreementNo),
// 补录缺失时兜底数仓/补录合并协议列表第一条;新增业务无协议显示「新增业务」(§2026-08-26 与审批口径一致)
function itemAgreementNo(p: any): string {
  if (val(p, 'original_rate', 'originalRate') === '—') return '新增业务'
  const app = archive.value.application || {}
  const ci = val(app, 'credit_info_json', 'creditInfoJson')
  if (ci !== '—') {
    try {
      const obj = typeof ci === 'string' ? JSON.parse(ci) : ci
      if (obj?.agreementNo) return obj.agreementNo
    } catch { /* 补录 JSON 解析失败走协议列表兜底 */ }
  }
  const first = (archive.value.creditAgreements || [])[0]
  return first?.agreementNo || '—'
}
// 定价分项担保方式合并(多担保方式去重逗号分隔;与审批详情页 guaranteesText 口径一致,§2026-08-26)
function itemGuaranteeText(p: any): string {
  const list = guaranteesOf(p)
  if (!list.length) return '—'
  const types = Array.from(new Set(list.map((g) => g.guaranteeType).filter(Boolean)))
  return types.map(guaranteeTypeText).join('、')
}
function extOf(g: any): any {
  const j = g?.extJson
  if (!j) return null
  if (typeof j === 'object') return j
  try { return JSON.parse(j) } catch { return null }
}
// 担保物信息列(2026-09-04 由「措施明细」更名):按措施类型取关键扩展字段转可读文本。
// 保证人是"人"不是"物",单独成组(名称/证件号/担保余额),避免误标产权证号、丢失余额;
// 抵押/质押/保证金/存单等物类展示担保物属性(坐落/产权证号/车牌号/存单号等)。
function extText(g: any): string {
  const ext = extOf(g)
  if (!ext) return '—'
  const labels: Record<string, string> = g?.measureType === 'GUARANTOR'
    ? { name: '保证人', certNo: '证件号', balance: '担保余额' }
    : {
        name: '名称', collateralType: '类型', specModel: '规格型号', quantity: '数量',
        plateNo: '车牌号', vin: '车架号', address: '坐落', area: '面积',
        certNo: '产权证号', owner: '权属人', pledgeType: '质押物类型',
        marginRatio: '比例', termMonths: '期限(月)', certificateNo: '存单号', maturityDate: '到期日'
      }
  const parts = Object.keys(labels)
    .filter((k) => ext[k] != null && ext[k] !== '')
    .map((k) => `${labels[k]}:${ext[k]}`)
  return parts.length ? parts.join('；') : '—'
}
function downloadAttachment(a: any) {
  download(`/ccr/applications/${applicationId}/attachments/${a.id}/download`)
}

/** 多键取值(后端档案 Map 混用 snake/camel;空值显示暂无口径) */
function val(row: any, ...keys: string[]): any {
  for (const k of keys) {
    if (row && row[k] !== null && row[k] !== undefined && row[k] !== '') return row[k]
  }
  return '—'
}
function fmtTime(t: any) {
  return fmtDateTime(t, false)
}
function fmtDate(t: any) {
  return t && t !== '—' ? String(t).slice(0, 10) : '—'
}
function rateText(r: any) {
  return r !== null && r !== undefined && r !== '' && r !== '—' ? `${r}%` : '—'
}
// 机构达成率(与审批详情同款 helper):completionRate 为 0-1 比例(到期终态 FINISHED_MET 占比,60%=0.6),
// ×100 转百分比;颜色 ≥1(100%)绿 / ≥0.8(80%)黄 / 其余红(2026-09-04 归档页与审批详情统一)
function rateCls(r: any): string {
  const v = Number(r)
  if (!Number.isFinite(v)) return ''
  if (v >= 1) return 'rate-ok'
  if (v >= 0.8) return 'rate-warn'
  return 'rate-bad'
}
function fmtPct(r: any): string {
  const v = Number(r)
  if (!Number.isFinite(v)) return '暂无数据'
  return `${Math.round(v * 10000) / 100}%`
}
function progressWidth(r: any): string {
  const v = Number(r)
  if (!Number.isFinite(v) || v <= 0) return '0%'
  return `${Math.min(v * 100, 100)}%`
}
function termText(p: any) {
  const v = val(p, 'term_value', 'termValue')
  if (v === '—') return '—'
  const unit = val(p, 'term_unit', 'termUnit')
  return `${v}${termUnitText(unit === '—' ? '' : unit)}`
}
function actionBadge(a: any) {
  const map: Record<string, string> = {
    APPROVE: 'badge badge--success', REJECT: 'badge badge--danger',
    ADJUST: 'badge badge--warning', RETURN: 'badge badge--warning'
  }
  return map[a] || 'badge badge--info'
}
// 表决计票汇总:按轮次从 voteResults 合并 通过/否决
function voteResultOf(roundId: any) {
  const r = (archive.value.voteResults || []).find((x: any) => String(val(x, 'roundId', 'round_id')) === String(roundId))
  return r ? `${val(r, 'approveCount', 'approve_count') ?? 0} / ${val(r, 'rejectCount', 'reject_count') ?? 0}` : '—'
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
  loadError.value = false
  try {
    archive.value = await getArchive(applicationId)
  } catch {
    archive.value = {}
    loadError.value = true
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
/* 描述列表统一用 design-system .desc-grid;仅保留整行项修饰 */
.desc-item--full { grid-column: 1 / -1; }
.hash-cell { font-size: 12px; color: var(--color-text-sub); word-break: break-all; }
.plan-block { border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); padding: 10px 12px; }
.plan-block__head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; font-size: 13px; flex-wrap: wrap; }
/* 长文本列防撑高(意见/调价理由/核验结果):max-width + 省略号 + title 悬停全量;§UI审查 ⑦ */
.text-ellipsis { max-width: 220px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 附件文件名超长省略;§UI审查 ⑧ */
.file-name { max-width: 260px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.archive-link { color: var(--color-primary); cursor: pointer; }
.archive-link:hover { text-decoration: underline; }
/* 授信分项行内展开担保明细(2026-09-04):担保方式列可点 + 展开行浅底内衬表 */
.expand-toggle { color: var(--color-primary); cursor: pointer; user-select: none; white-space: nowrap; }
.expand-toggle:hover { text-decoration: underline; }
.expand-toggle .chev { font-size: 10px; margin-left: 2px; }
.expand-row > td { padding: 0; background: var(--color-bg-page, #f7f9fc); }
.expand-panel { padding: 10px 16px 12px 20px; }
.expand-title { font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 8px; margin: 2px 0 8px; }
/* 机构达成概要卡(与审批详情统一,2026-09-04):名称/到期承诺达成率+进度条,金额/月份/数据日期已移除 */
.rate-ok { color: var(--color-success); font-weight: 600; }
.rate-bad { color: var(--color-danger); font-weight: 600; }
.rate-warn { color: var(--color-warning); font-weight: 600; }
.org-perf {
  display: flex; flex-direction: column; gap: 8px;
  padding: 12px 14px; border-radius: 8px;
  background: var(--color-bg, #f8f9fa); margin-bottom: 10px;
}
.org-perf__name { font-size: 15px; font-weight: 700; color: var(--color-text-main); }
.org-perf__rate { font-size: 18px; font-weight: 700; }
.org-perf__rate-label { font-size: 12px; color: var(--color-text-sub); }
.org-perf__main { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.org-perf__title { display: flex; align-items: baseline; gap: 10px; }
.org-perf__bar { height: 6px; border-radius: 3px; background: var(--color-border-light, #e5e7eb); overflow: hidden; }
.org-perf__bar-inner { height: 100%; border-radius: 3px; background: var(--color-primary); transition: width .3s ease; }
.org-perf__bar-inner.rate-ok { background: var(--color-success); }
.org-perf__bar-inner.rate-warn { background: var(--color-warning); }
.org-perf__bar-inner.rate-bad { background: var(--color-danger); }
</style>
