<template>
  <div v-if="loaded">
    <div class="section-head">
      <div class="section-title">审批详情</div>
      <div class="section-tip">基础信息只读,普通审批人仅可编辑审批利率与审批意见。</div>
    </div>

    <!-- 0. 数据来源与快照信息(§12.16-7) -->
    <div class="card">
      <div class="card__head">
        <span>数据来源</span>
        <span class="badge" :class="source === 'SNAPSHOT' ? 'badge--success' : 'badge--warning'">
          {{ source === 'SNAPSHOT' ? '冻结快照' : '实时取数' }}
        </span>
      </div>
      <div class="detail-grid" v-if="source === 'SNAPSHOT'">
        <div><span class="dg-label">数据日期</span>{{ snapshotInfo.dataDt || '—' }}</div>
        <div><span class="dg-label">冻结时间</span>{{ snapshotInfo.freezeTime || '—' }}</div>
        <div><span class="dg-label">快照批次号</span>{{ snapshotInfo.bundleNo || '—' }}</div>
      </div>
      <div class="section-tip" v-else>未找到提交时冻结快照,客户/融资/贡献度为数仓实时查询结果,可能与提交时点存在差异。</div>
    </div>


    <!-- 3. 醒目利率决策区 -->
    <div class="card card--decision">
      <div class="card__head">
        <span>利率决策区</span>
        <span class="badge" :class="actionable ? 'badge--processing' : 'badge--neutral'">{{ actionable ? '待我审批' : statusText }}</span>
      </div>
      <div class="decision-row">
        <div class="decision-item"><span class="dg-label">客户</span><b>{{ customerName }}</b></div>
        <div class="decision-item"><span class="dg-label">申请利率</span><b class="rate">{{ fmtRate(pi.requested_rate) }}</b></div>
        <div class="decision-item">
          <span class="dg-label">当前审批利率</span>
          <b class="rate rate--approval">{{ fmtRate(pi.current_approval_rate ?? pi.requested_rate) }}</b>
        </div>
        <div class="decision-item">
          <span class="dg-label">申请金额</span>
          <b>{{ pi.pricing_amount ?? '—' }} 万元</b>
          <div class="stat-card__sub">{{ isLoan ? '贷款利率越低越优惠' : '存款利率越高越优惠' }}</div>
        </div>
      </div>
    </div>

    <!-- 4. 申请内容 -->
    <div class="card">
      <div class="card__head">
        <span>申请内容</span>
        <span v-if="pi.inherit_flag === 'Y' || pi.inheritFlag === 'Y'" class="badge badge--info">沿用原决议</span>
      </div>
      <div class="detail-grid">
        <div><span class="dg-label">申请号</span>{{ application.applicationNo || '—' }}</div>
        <div><span class="dg-label">业务类型</span>{{ businessTypeText }}</div>
        <div><span class="dg-label">客户号</span>{{ application.customerNo || pi.pricing_customer_no || '—' }}</div>
        <div><span class="dg-label">定价分项</span>{{ pi.pricing_item_no || '—' }}</div>
        <div><span class="dg-label">分项状态</span>{{ statusText }}</div>
        <div><span class="dg-label">产品编码</span>{{ productName(pi.product_code) }}</div>
        <div><span class="dg-label">原执行利率</span>{{ pi.original_rate != null ? fmtRate(pi.original_rate) : '新增业务' }}</div>
        <div><span class="dg-label">期限</span>{{ pi.term_value ? `${pi.term_value}${termUnitText(pi.term_unit)}` : '—' }}</div>
        <div><span class="dg-label">当前节点</span>{{ pi.current_node_code ? nodeLabel(pi.current_node_code) : '—' }}</div>
      </div>
      <div class="remark-text" style="margin-top:12px" v-if="application.applicationRemark">{{ application.applicationRemark }}</div>
    </div>

    <!-- 5. 客户基本信息 -->
    <div class="card">
      <div class="card__head"><span>客户基本信息</span><span class="badge badge--info">数仓</span></div>
      <div class="detail-grid" v-if="hasCustomer">
        <div><span class="dg-label">客户名称</span>{{ customerName }}</div>
        <div v-if="customer.entpCharic"><span class="dg-label">企业性质</span>{{ customerTypeText(customer.entpCharic) }}</div>
        <div v-if="customer.industry"><span class="dg-label">所属行业</span>{{ customer.industry }}</div>
        <div v-if="customer.creditLevel"><span class="dg-label">内部信用等级</span>{{ customer.creditLevel }}</div>
        <div v-if="customer.fiveLevelClass"><span class="dg-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
        <div v-if="customer.openOrgName"><span class="dg-label">开户机构</span>{{ customer.openOrgName }}</div>
        <div v-if="customer.customerClass"><span class="dg-label">客户分类</span>{{ customer.customerClass }}</div>
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
        <div><span class="dg-label">集团贡献度</span>暂无数据</div>
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
                <td class="num">{{ c.targetValue ?? '—' }}</td>
                <td>{{ c.unit || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty" style="padding:8px">该成员暂无承诺指标</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 7. 授信/账户与本行融资 -->
    <div class="card">
      <div class="card__head"><span>授信/账户与本行融资</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="financing.length">
        <thead><tr><th>合同号</th><th>贷款余额(万元)</th><th>原利率</th><th>担保类型</th></tr></thead>
        <tbody>
          <tr v-for="f in financing" :key="f.contractNo">
            <td>{{ f.contractNo }}</td><td class="num">{{ f.loanBalance ?? '—' }}</td>
            <td class="num">{{ fmtRate(f.contractRate) }}</td><td>{{ guaranteeTypeText(f.guaranteeType) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 6b. 申请材料附件 -->
    <div class="card" v-if="attachments.length">
      <div class="card__head"><span>申请材料附件</span></div>
      <table class="table">
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
    </div>

    <!-- 7b. 他行融资(申请人工补录/Excel 导入 + 数仓征信) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>他行融资</span></div>
      <div class="detail-grid" v-if="otherLoanSummary.length">
        <div><span class="dg-label">他行机构数</span>{{ otherLoanSummary[0].lenderCount ?? '—' }}</div>
        <div><span class="dg-label">授信总额</span>{{ otherLoanSummary[0].creditAmountTotal ?? '—' }} 万元</div>
        <div><span class="dg-label">已用总额</span>{{ otherLoanSummary[0].usedAmountTotal ?? '—' }} 万元</div>
        <div><span class="dg-label">不良余额</span>{{ otherLoanSummary[0].nplBalance ?? '—' }} 万元</div>
        <div><span class="dg-label">逾期账户</span>{{ otherLoanSummary[0].overdueAccountCount ?? '—' }}</div>
      </div>
      <table class="table" v-if="otherLoans.length" style="margin-top:8px">
        <thead><tr><th>融资机构</th><th>授信额(万元)</th><th>已用额(万元)</th><th>余额(万元)</th><th>年化利率(%)</th><th>来源</th></tr></thead>
        <tbody>
          <tr v-for="(d, i) in otherLoans" :key="i">
            <td>{{ d.lenderName }}</td>
            <td class="num">{{ d.creditAmount ?? '—' }}</td>
            <td class="num">{{ d.usedAmount ?? '—' }}</td>
            <td class="num">{{ d.balanceAmount ?? '—' }}</td>
            <td class="num">{{ d.annualRate ?? '—' }}</td>
            <td><span class="badge badge--neutral">{{ inputModeText(d.inputMode) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无他行融资记录</div>
    </div>

    <!-- 7c. 关联人情况(数仓客户关系 + 申请录入) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>关联人情况</span></div>
      <table class="table" v-if="relations.length">
        <thead><tr><th>关联人</th><th>关系类型</th><th>关联强度</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in relations" :key="i">
            <td>{{ r.relatedCustomerNo }}</td>
            <td>{{ relationTypeText(r.relationType) }}</td>
            <td>{{ r.relationStrength === 'STRONG' ? '强' : r.relationStrength === 'WEAK' ? '弱' : (r.relationStrength || '—') }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无关联人记录</div>
    </div>

    <!-- 8. 贷款合同/存款账户与担保 -->
    <div class="card">
      <div class="card__head"><span>{{ isLoan ? '贷款合同与担保' : '存款账户' }}</span></div>
      <div class="detail-grid" v-if="isLoan">
        <div><span class="dg-label">定价载体</span>{{ carrierTypeText(pi.pricing_carrier_type) }}</div>
        <div><span class="dg-label">载体来源</span>{{ pi.credit_tranche_ref || '—' }}</div>
        <div><span class="dg-label">合同下借据</span>暂无数据</div>
      </div>
      <template v-else>
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
      <table class="table" style="margin-top:12px" v-if="guarantees.length">
        <thead><tr><th>担保方式</th><th>措施编号</th><th>措施类型</th><th>担保金额(万元)</th></tr></thead>
        <tbody>
          <template v-for="(g, i) in guarantees" :key="i">
            <tr>
              <td>{{ guaranteeTypeText(g.guaranteeType) }}</td><td>{{ g.measureNo || '—' }}</td>
              <td>{{ measureTypeText(g.measureType) }}</td><td class="num">{{ g.guaranteeAmount ?? '—' }}</td>
            </tr>
            <!-- 担保措施明细行(抵押物坐落/面积/估值、保证人等,取快照 extJson;无则暂无数据) -->
            <tr class="measure-detail" v-if="extOf(g)">
              <td colspan="4">
                <template v-if="g.measureType === 'MORTGAGE'">
                  <span class="dg-label">类型</span>{{ extOf(g).collateralType || '暂无数据' }}
                  <span class="dg-label">名称</span>{{ extOf(g).name || '暂无数据' }}
                  <template v-if="extOf(g).collateralType === '土地'">
                    <span class="dg-label">坐落</span>{{ extOf(g).address || '暂无数据' }}
                    <span class="dg-label">面积</span>{{ extOf(g).area ? extOf(g).area + '㎡' : '暂无数据' }}
                    <span class="dg-label">使用权</span>{{ extOf(g).landUseType || '暂无数据' }}{{ extOf(g).landUseExpiry ? '至' + extOf(g).landUseExpiry : '' }}
                  </template>
                  <template v-else-if="extOf(g).collateralType === '设备'">
                    <span class="dg-label">规格型号</span>{{ extOf(g).specModel || '暂无数据' }}
                    <span class="dg-label">数量</span>{{ extOf(g).quantity || '暂无数据' }}
                    <span class="dg-label">购置日期</span>{{ extOf(g).purchaseDate || '暂无数据' }}
                  </template>
                  <template v-else-if="extOf(g).collateralType === '车辆'">
                    <span class="dg-label">车牌号</span>{{ extOf(g).plateNo || '暂无数据' }}
                    <span class="dg-label">车架号</span>{{ extOf(g).vin || '暂无数据' }}
                    <span class="dg-label">登记日期</span>{{ extOf(g).regDate || '暂无数据' }}
                  </template>
                  <template v-else>
                    <span class="dg-label">坐落</span>{{ extOf(g).address || '暂无数据' }}
                    <span class="dg-label">面积</span>{{ extOf(g).area ? extOf(g).area + '㎡' : '暂无数据' }}
                    <span class="dg-label">产权证号</span>{{ extOf(g).certNo || '暂无数据' }}
                  </template>
                  <span class="dg-label">估值(万元)</span>{{ g.guaranteeAmount ?? '暂无数据' }}
                  <span class="dg-label">权属人</span>{{ extOf(g).owner || '暂无数据' }}
                  <span class="dg-label">抵押率</span>{{ extOf(g).mortgageRatio ? extOf(g).mortgageRatio + '%' : '暂无数据' }}
                </template>
                <template v-else-if="g.measureType === 'GUARANTOR'">
                  <span class="dg-label">保证人名称</span>{{ extOf(g).name || '暂无数据' }}
                  <span class="dg-label">证件号码</span>{{ extOf(g).certNo || '暂无数据' }}
                  <span class="dg-label">担保余额(万元)</span>{{ extOf(g).balance ?? '暂无数据' }}
                </template>
                <template v-else-if="g.measureType === 'PLEDGE'">
                  <span class="dg-label">质押物类型</span>{{ extOf(g).pledgeType || '暂无数据' }}
                  <span class="dg-label">名称</span>{{ extOf(g).name || '暂无数据' }}
                  <span class="dg-label">估值(万元)</span>{{ g.guaranteeAmount ?? '暂无数据' }}
                  <span class="dg-label">权属人</span>{{ extOf(g).owner || '暂无数据' }}
                </template>
                <template v-else-if="g.measureType === 'BILL_MARGIN' || g.measureType === 'CREDIT_MARGIN'">
                  <span class="dg-label">保证金(万元)</span>{{ g.guaranteeAmount ?? '暂无数据' }}
                  <span class="dg-label">比例</span>{{ extOf(g).marginRatio ? extOf(g).marginRatio + '%' : '暂无数据' }}
                  <span class="dg-label">期限(月)</span>{{ extOf(g).termMonths || '暂无数据' }}
                </template>
                <template v-else-if="g.measureType === 'CERTIFICATE_DEPOSIT'">
                  <span class="dg-label">存单号</span>{{ extOf(g).certificateNo || '暂无数据' }}
                  <span class="dg-label">金额(万元)</span>{{ g.guaranteeAmount ?? '暂无数据' }}
                  <span class="dg-label">到期日</span>{{ extOf(g).maturityDate || '暂无数据' }}
                </template>
                <span v-else class="dg-label">暂无措施明细数据</span>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div v-else class="empty" style="padding:8px">无担保明细</div>
    </div>

    <!-- 9. 当前与拟达成贡献度(双概念并排;存款场景不涉贡献度,仅贷款展示) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
      <ContributionPanel :contribution="contribution" :commitments="commitments" />
    </div>

    <!-- 10. 历史履约(tracking:该客户承诺最新评估) -->
    <div class="card">
      <div class="card__head"><span>历史履约</span><span class="badge badge--info">承诺跟踪</span></div>
      <template v-if="tracking.length">
        <div v-if="unmetTracking.length" class="warn-bar">
          {{ unmetTracking.length }} 项承诺指标未达成({{ unmetTracking.map((t) => metricName(t.metricCode)).join('、') }}),请关注履约风险。
        </div>
        <table class="table">
          <thead><tr><th>计划号</th><th>指标</th><th>目标值</th><th>实际值</th><th>完成率</th><th>评估结论</th><th>数据日期</th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in tracking" :key="i">
              <td>{{ t.planNo || '—' }}</td>
              <td>{{ metricName(t.metricCode) }}</td>
              <td class="num">{{ t.targetValue ?? '—' }}</td>
              <td class="num">{{ t.actualValue ?? '暂无数据' }}</td>
              <td class="num">
                <span v-if="t.achievementRatio != null" :class="Number(t.achievementRatio) >= 100 ? 'rate-ok' : 'rate-bad'">
                  {{ t.achievementRatio }}%
                </span>
                <span v-else>暂无数据</span>
              </td>
              <td>
                <span class="badge" :class="t.resultStatus === 'ACHIEVED' ? 'badge--success' : t.resultStatus === 'AT_RISK' ? 'badge--danger' : 'badge--warning'">
                  {{ evalResultText(t.resultStatus) }}
                </span>
              </td>
              <td>{{ t.dataDt || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <div v-else class="empty">暂无数据</div>
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

    <!-- 12. 流程轨迹 -->
    <div class="card">
      <div class="card__head"><span>流程轨迹</span></div>
      <el-timeline v-if="flowTrace.length">
        <el-timeline-item v-for="(t, i) in flowTrace" :key="i" :timestamp="t.operationTime || ''" placement="top">
          <div>
            <span class="badge" :class="t.actionType === 'REJECT' ? 'badge--rejected' : 'badge--approved'">
              {{ actionText(t.actionType) }}
            </span>
            <span class="dg-label" style="margin-left:8px">{{ nodeLabel(t.nodeCode) }}</span>
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
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 12. 审批操作(仅当前节点待办可操作) -->
    <div class="card" v-if="actionable">
      <div class="card__head"><span>审批操作</span><span class="badge badge--processing">当前节点:{{ nodeLabel(pi.current_node_code) }}</span></div>
      <div class="op-form">
        <div class="op-form__row">
          <label class="op-form__label">审批利率(%)</label>
          <el-input-number v-model="opRate" :min="0" :max="36" :precision="4" :step="0.01" controls-position="right" />
          <div class="stat-card__sub">
            {{ isLoan
              ? '贷款:审批利率不低于本节点下限方可权限内终审,低于下限将保留利率自动上送下一节点;调价不得突破本节点权限边界。'
              : '存款:审批利率不高于本节点上限方可权限内终审,超出将保留利率上送小组表决;调价不得突破本节点权限边界。' }}
          </div>
        </div>
        <div class="op-form__row">
          <label class="op-form__label">审批意见</label>
          <el-input v-model="opComment" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请输入审批意见(否决时建议说明原因)" />
        </div>
        <div style="display:flex;gap:12px">
          <button class="btn btn--primary" :disabled="submitting" @click="doApprove">{{ rateAdjusted ? '调价并通过' : '通过' }}</button>
          <button class="btn btn--danger" :disabled="submitting" @click="doReject">否决</button>
          <button class="btn btn--secondary" @click="goBack">返回待办列表</button>
        </div>
      </div>
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
  customerTypeText, memberRoleText
} from '@/utils/dict'
// eslint-disable-next-line no-duplicate-imports
import { inputModeText, relationTypeText } from '@/utils/dict'

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
const source = ref('')
const snapshotInfo = ref<any>({})
const tracking = ref<any[]>([])
const orgPerformance = ref<any[]>([])
const depositAccounts = ref<any[]>([])
const otherLoanSummary = ref<any[]>([])
const otherLoans = ref<any[]>([])
const relations = ref<any[]>([])
const attachments = ref<any[]>([])
const resolutions = ref<any[]>([])
const resolutionExecutions = ref<any[]>([])
const voteRounds = ref<any[]>([])
const voteResults = ref<any[]>([])
const presidentDecisions = ref<any[]>([])

const opRate = ref<number | undefined>(undefined)
const opComment = ref('')

const ROLE_NODE: Record<string, string> = {
  branch_manager: 'BRANCH_MANAGER', dept_gm: 'DEPT_GENERAL_MANAGER', vice_president: 'VICE_PRESIDENT'
}

const isLoan = computed(() => application.value.businessType !== 'DEPOSIT')
const businessTypeText = computed(() => application.value.businessType === 'DEPOSIT' ? '存款' : '贷款')
const isGroup = computed(() => !!application.value.groupNo)
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || pi.value.pricing_customer_no || '—')
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

const rateAdjusted = computed(() => {
  const base = pi.value.current_approval_rate ?? pi.value.requested_rate
  return opRate.value != null && base != null && Number(opRate.value) !== Number(base)
})


const groupTotalAmount = computed(() =>
  groupMembers.value.reduce((sum, m) => sum + (Number(m.requestAmount) || 0), 0))

function extOf(g: any): any {
  const j = g?.extJson
  if (!j) return null
  if (typeof j === 'object') return j
  try { return JSON.parse(j) } catch { return null }
}

function fmtRate(v: any) {
  return v == null || v === '' ? '—' : `${v}%`
}

function memberCommitments(memberNo: string) {
  return commitments.value.filter((c) => c.memberCustomerNo === memberNo)
}

// 历史履约:未达成指标(用于警示条)
const unmetTracking = computed(() =>
  tracking.value.filter((t) => t.resultStatus && t.resultStatus !== 'ACHIEVED'))

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
    source.value = data.source || ''
    snapshotInfo.value = data.snapshotInfo || {}
    tracking.value = data.tracking || []
    orgPerformance.value = data.orgPerformance || []
    depositAccounts.value = data.depositAccounts || []
    otherLoanSummary.value = data.otherLoanSummary || []
    otherLoans.value = [...(data.otherLoans || []), ...(data.appOtherLoans || [])]
    relations.value = data.relations || []
    attachments.value = data.attachments || []
    resolutions.value = data.resolutions || []
    resolutionExecutions.value = data.resolutionExecutions || []
    voteRounds.value = data.voteRounds || []
    voteResults.value = data.voteResults || []
    presidentDecisions.value = data.presidentDecisions || []
    const base = pi.value.current_approval_rate ?? pi.value.requested_rate
    opRate.value = base != null ? Number(base) : undefined
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

async function doApprove() {
  if (opRate.value == null) {
    ElMessage.warning('请填写审批利率')
    return
  }
  submitting.value = true
  try {
    await approveTask({
      pricingItemId: Number(pricingItemId.value),
      nodeCode: pi.value.current_node_code,
      adjustRate: rateAdjusted.value ? opRate.value : null,
      comment: opComment.value || undefined,
      versionNo: Number(pi.value.version_no)
    }, newIdempotencyKey())
    ElMessage.success(rateAdjusted.value ? '已调价并通过' : '已通过')
    goBack()
  } catch {
    load() // 版本冲突/已处理等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

function doReject() {
  ElMessageBox.confirm('确认否决该定价分项?否决后为终态。', '否决', { type: 'warning', confirmButtonText: '确认否决', cancelButtonText: '取消' })
    .then(async () => {
      submitting.value = true
      try {
        await rejectTask({
          pricingItemId: Number(pricingItemId.value),
          nodeCode: pi.value.current_node_code,
          comment: opComment.value || undefined,
          versionNo: Number(pi.value.version_no)
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

onMounted(load)
</script>

<style scoped>
.card--decision { border-color: var(--color-primary); }
.decision-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.decision-item { font-size: 14px; }
.decision-item .rate { font-size: 22px; color: var(--color-primary); }
.decision-item .rate--approval { color: var(--color-warning); }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
.table { border-radius: var(--radius-sm); overflow: hidden; }
.remark-text { font-size: 14px; background: var(--color-bg); border-radius: 6px; padding: 12px; line-height: 1.6; }
.op-form__row { margin-bottom: 12px; }
.op-form__label { display: block; font-size: 13px; color: var(--color-text-sub); margin-bottom: 6px; }
.stat-card__sub { margin-top: 4px; }
.warn-bar { background: var(--color-warning-light, #fef3c7); color: var(--color-warning); border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-bottom: 10px; }
.rate-ok { color: var(--color-success); font-weight: 600; }
.rate-bad { color: var(--color-danger); font-weight: 600; }
.measure-detail > td { background: #fafbfc; font-size: 13px; color: var(--color-text-sub); }
.measure-detail .dg-label { margin: 0 4px 0 12px; }
.measure-detail .dg-label:first-child { margin-left: 0; }
</style>
