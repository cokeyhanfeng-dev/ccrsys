<template>
  <div v-if="loadError" class="empty">
    加载失败,请刷新
    <div style="margin-top:12px"><button class="btn btn--secondary" @click="load">重新加载</button></div>
  </div>
  <div v-else class="detail-wrap" :class="{ 'detail-wrap--action': actionable }" v-loading="loading">
  <div v-if="loaded">
    <div class="section-head">
      <div class="section-title">
        <button class="btn btn--ghost btn--back" @click="goBackList">‹ 返回列表</button>
        {{ isPresidentDecision ? '行长决策' : '审批详情' }}
      </div>
      <InfoTip :content="isPresidentDecision ? '六人小组表决已通过,请审阅完整申请内容与六人匿名审批意见后整单决策:同意利率或一票否决。' : isCommitteeVoting ? '基础信息只读,六人小组成员仅可对申请内待表决分项投同意/否决,不能调整利率。' : '基础信息只读,普通审批人仅可编辑审批利率与审批意见。'" />
    </div>

    <!-- 吸顶摘要条(docs/29 §2.3):申请号/客户/金额/业务类型/当前节点 + 五段锚点导航,任何位置一键跳到操作区 -->
    <div class="summary-bar">
      <div class="summary-bar__item">申请号<strong>{{ application.applicationNo || '—' }}</strong></div>
      <div class="summary-bar__item">客户<strong>{{ customerName }}</strong></div>
      <div class="summary-bar__item">申请金额<strong>{{ fmtAmount(applyAmountTotal) }} 万元</strong></div>
      <div class="summary-bar__item">业务类型<strong><span class="badge badge--info">{{ businessTypeText }}<template v-if="applyBizTypeText !== '—'"> · {{ applyBizTypeText }}</template></span></strong></div>
      <div class="summary-bar__item">当前节点<strong><span class="badge badge--processing">{{ nodeLabel(pi.current_node_code) }}</span></strong></div>
      <nav class="summary-bar__nav">
        <a class="anchor-link" @click="scrollToSection('s-apply')">申请内容</a>
        <a class="anchor-link" @click="scrollToSection('s-customer')">客户与集团</a>
        <a class="anchor-link" @click="scrollToSection('s-contrib')">承诺与履约</a>
        <a class="anchor-link" @click="scrollToSection('s-flow')">流程轨迹</a>
        <a class="anchor-link" style="font-weight:700" @click="scrollToSection('s-decide')">审批决定 ⬇</a>
      </nav>
    </div>

    <!-- 第一段:申请内容(数据来源 + 申请级字段 + 分项明细表) -->
    <div class="anchor-section" id="s-apply">
    <!-- 0. 数据来源与快照信息(§12.16-7) -->
    <div class="card">
      <div class="card__head">
        <span>数据来源</span>
        <span class="badge" :class="source === 'SNAPSHOT' ? 'badge--success' : source === 'MANUAL' ? 'badge--danger' : 'badge--warning'">
          {{ source === 'SNAPSHOT' ? '冻结快照' : source === 'MANUAL' ? '人工录入' : source === 'MANUAL_OVERRIDE' ? '人工修正' : '实时取数' }}
        </span>
      </div>
      <div class="desc-grid desc-grid--3" v-if="source === 'SNAPSHOT'">
        <div><div class="desc-item__label">数据日期</div><div class="desc-item__value">{{ snapshotInfo.dataDt || '—' }}</div></div>
        <div><div class="desc-item__label">冻结时间</div><div class="desc-item__value">{{ snapshotInfo.freezeTime || '—' }}</div></div>
        <div><div class="desc-item__label">快照批次号</div><div class="desc-item__value">{{ snapshotInfo.bundleNo || '—' }}</div></div>
      </div>
      <div class="source-note" v-if="source === 'MANUAL'">未找到数仓/快照客户数据,客户信息由客户经理手工录入,以人工填写为准。</div>
      <div class="source-note" v-else-if="source === 'MANUAL_OVERRIDE'">数仓/快照客户信息已由客户经理人工修正,以人工填写为准。</div>
      <div class="source-note" v-else-if="source !== 'SNAPSHOT'">未找到提交时冻结快照,客户/融资/贡献度为数仓实时查询结果,可能与提交时点存在差异。</div>
    </div>


    <!-- 4. 申请内容(多分项:申请级字段 + 分项明细表;原执行利率/申请利率/期限/节点按分项展示,不再只取当前分项) -->
    <div class="card">
      <div class="card__head">
        <span>申请内容</span>
        <span class="badge badge--info">共 {{ siblingItems.length }} 个分项</span>
        <span v-if="pi.inherit_flag === 'Y' || pi.inheritFlag === 'Y'" class="badge badge--info">沿用原决议</span>
      </div>
      <div class="desc-grid desc-grid--3">
        <div><div class="desc-item__label">申请号</div><div class="desc-item__value">{{ application.applicationNo || '—' }}</div></div>
        <div><div class="desc-item__label">业务类型</div><div class="desc-item__value">{{ businessTypeText }}</div></div>
        <div><div class="desc-item__label">申请类型</div><div class="desc-item__value">{{ applyBizTypeText }}</div></div>
        <div><div class="desc-item__label">客户号</div><div class="desc-item__value">{{ customerNoText(application.customerNo || pi.pricing_customer_no) }}
          <button v-if="isPlaceholderCustomerNo(application.customerNo || pi.pricing_customer_no) && canBackfill"
                  class="btn btn--primary" @click="openBackfillDlg">回填客户号</button>
        </div></div>
        <div><div class="desc-item__label">产品编码</div><div class="desc-item__value">{{ productName(pi.product_code) }}</div></div>
        <div v-if="applyTotalCredit != null"><div class="desc-item__label">授信总额(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(applyTotalCredit) }}</div></div>
      </div>
      <!-- 分项表(docs/29 §2.3):12 列收敛为 7 主列,产品/原执行/测算/期限/部门归属收进「明细▸」展开行 -->
      <table class="table" style="margin-top:12px">
        <thead><tr><th>定价分项</th><th v-if="isGroup">成员</th><th v-if="isLoan">担保方式</th><th>金额(万元)</th><th>申请利率</th><th>当前节点</th><th>状态</th><th></th></tr></thead>
        <tbody>
          <template v-for="it in siblingItems" :key="it.id">
            <tr>
              <td>{{ it.pricingItemNo || '—' }}</td>
              <td v-if="isGroup">{{ memberLabel(it.memberCustomerNo || it.member_customer_no) }}</td>
              <td v-if="isLoan">{{ guaranteesText(it.guarantees) }}</td>
              <td class="num">{{ fmtAmount(it.pricingAmount) }}</td>
              <td class="num">{{ fmtRate(it.requestedRate) }}</td>
              <td>{{ it.currentNodeCode ? nodeLabel(it.currentNodeCode) : '—' }}</td>
              <td>{{ itemStatusText(it.status) }}</td>
              <td><button class="btn btn--text" @click="toggleItemDetail(it.id)">{{ itemDetailOpen(it.id) ? '收起 ▲' : '明细 ▸' }}</button></td>
            </tr>
            <!-- 明细展开行:主列之外的字段以描述列表呈现 -->
            <tr v-if="itemDetailOpen(it.id)">
              <td :colspan="itemTableCols" style="padding: 4px 12px 10px; background: var(--color-fill, #f8f9fa)">
                <div class="desc-grid desc-grid--3">
                  <div><div class="desc-item__label">产品</div><div class="desc-item__value">{{ productName(it.productCode) }}</div></div>
                  <div><div class="desc-item__label">原执行利率</div><div class="desc-item__value">{{ it.originalRate != null ? fmtRate(it.originalRate) : '新增业务' }}</div></div>
                  <div><div class="desc-item__label">测算利率</div><div class="desc-item__value desc-item__value--num">{{ fmtRate(it.calculatedRate) }}</div></div>
                  <div><div class="desc-item__label">期限</div><div class="desc-item__value">{{ it.termValue != null ? `${it.termValue}${termUnitText(it.termUnit)}` : '—' }}</div></div>
                  <div><div class="desc-item__label">部门归属</div><div class="desc-item__value">{{ it.routeCode === 'SIX_PEOPLE_GROUP' ? '上会表决' : deptText(it.deptCode) }}</div></div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div class="remark-text" style="margin-top:12px" v-if="application.applicationRemark">{{ application.applicationRemark }}</div>
    </div>
    </div>

    <!-- 第二段:客户与集团(客户基本信息/集团成员/授信/附件/他行融资/关联人,低频只读资料默认折叠) -->
    <div class="anchor-section" id="s-customer">
    <!-- 5. 客户基本信息 -->
    <div class="card">
      <div class="card__head"><span>客户基本信息</span>
        <span class="badge" :class="source === 'MANUAL' || source === 'MANUAL_OVERRIDE' ? 'badge--danger' : 'badge--info'">
          {{ source === 'MANUAL' ? '人工录入' : source === 'MANUAL_OVERRIDE' ? '含人工修正' : '数仓' }}
        </span>
      </div>
      <div v-if="application.applicantOrgName" class="desc-grid desc-grid--3" style="margin-top:12px">
        <div><div class="desc-item__label">申请机构</div><div class="desc-item__value">{{ application.applicantOrgName }}</div></div>
      </div>
      <div class="desc-grid desc-grid--3" v-if="hasCustomer">
        <!-- 对公客户(§20 ①:名称/客户号/统一社会信用代码/企业性质/行业/信用等级/五级分类等) -->
        <template v-if="isCorpCustomer">
          <div><div class="desc-item__label">客户名称</div><div class="desc-item__value">{{ customerName }}</div></div>
          <div><div class="desc-item__label">{{ isGroup ? '集团编号' : '行内客户号' }}</div><div class="desc-item__value">{{ customer.customerNo || '—' }}</div></div>
          <div><div class="desc-item__label">客户类型</div><div class="desc-item__value">{{ isGroup ? '集团' : '对公' }}</div></div>
          <div v-if="isGroup && customer.groupType"><div class="desc-item__label">集团类型</div><div class="desc-item__value">{{ groupTypeText(customer.groupType) }}</div></div>
          <div v-if="isGroup && customer.currency"><div class="desc-item__label">币种</div><div class="desc-item__value">{{ currencyText(customer.currency) }}</div></div>
          <div v-if="isGroup && customer.applyAmount != null"><div class="desc-item__label">本次申请额度(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(customer.applyAmount) }}</div></div>
          <div v-if="customer.certNo"><div class="desc-item__label">统一社会信用代码</div><div class="desc-item__value">{{ customer.certNo }}</div></div>
          <div><div class="desc-item__label">企业性质</div><div class="desc-item__value">{{ customer.entpCharic === 'SOE' ? '国企' : '非国企' }}</div></div>
          <div v-if="customer.entpScale"><div class="desc-item__label">企业规模</div><div class="desc-item__value">{{ entpScaleText(customer.entpScale) }}</div></div>
          <div v-if="customer.industry"><div class="desc-item__label">所属行业</div><div class="desc-item__value">{{ customer.industry }}</div></div>
          <div v-if="customer.creditLevel"><div class="desc-item__label">内部信用等级</div><div class="desc-item__value">{{ customer.creditLevel }}</div></div>
          <div v-if="customer.fiveLevelClass"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(customer.fiveLevelClass) }}</div></div>
          <div v-if="customer.empeNum != null"><div class="desc-item__label">员工人数</div><div class="desc-item__value">{{ customer.empeNum }}</div></div>
          <div v-if="customer.totalAssets != null"><div class="desc-item__label">总资产(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(customer.totalAssets) }}</div></div>
          <div v-if="customer.registeredCapital != null"><div class="desc-item__label">注册资本(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(customer.registeredCapital) }}</div></div>
          <div v-if="customer.estbDate"><div class="desc-item__label">成立日期</div><div class="desc-item__value">{{ customer.estbDate }}</div></div>
          <div v-if="customer.restAddr"><div class="desc-item__label">注册地址</div><div class="desc-item__value">{{ customer.restAddr }}</div></div>
          <div v-if="customer.openOrgName"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ customer.openOrgName }}</div></div>
          <div v-if="customer.openDate"><div class="desc-item__label">开户日期</div><div class="desc-item__value">{{ customer.openDate }}</div></div>
          <div v-if="customer.basicAccount"><div class="desc-item__label">基本户账户</div><div class="desc-item__value">{{ customer.basicAccount }}</div></div>
          <div v-if="customer.customerClass"><div class="desc-item__label">客户分类</div><div class="desc-item__value">{{ customerClassText(customer.customerClass) }}</div></div>
        </template>
        <!-- 对私客户(§20 ①:姓名/证件类型/证件号码/职业/年收入/婚姻状况/居住地址等) -->
        <template v-else-if="isIndivCustomer">
          <div><div class="desc-item__label">姓名</div><div class="desc-item__value">{{ customerName }}</div></div>
          <div><div class="desc-item__label">客户号</div><div class="desc-item__value">{{ customer.customerNo || '—' }}</div></div>
          <div><div class="desc-item__label">客户类型</div><div class="desc-item__value">个人</div></div>
          <div v-if="customer.certType || customer.certNo"><div class="desc-item__label">证件类型</div><div class="desc-item__value">{{ certTypeText(customer.certType) }}</div></div>
          <div v-if="customer.certNo"><div class="desc-item__label">证件号码</div><div class="desc-item__value">{{ customer.certNo }}</div></div>
          <div v-if="customer.gender"><div class="desc-item__label">性别</div><div class="desc-item__value">{{ genderText(customer.gender) }}</div></div>
          <div v-if="customer.occupation"><div class="desc-item__label">职业</div><div class="desc-item__value">{{ customer.occupation }}</div></div>
          <div v-if="customer.annualIncome != null"><div class="desc-item__label">年收入(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(customer.annualIncome) }}</div></div>
          <div v-if="customer.maritalStatus"><div class="desc-item__label">婚姻状况</div><div class="desc-item__value">{{ maritalStatusText(customer.maritalStatus) }}</div></div>
          <div v-if="customer.address"><div class="desc-item__label">居住地址</div><div class="desc-item__value">{{ customer.address }}</div></div>
          <div v-if="customer.phone"><div class="desc-item__label">联系电话</div><div class="desc-item__value">{{ customer.phone }}</div></div>
          <div v-if="customer.fiveLevelClass"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(customer.fiveLevelClass) }}</div></div>
          <div v-if="customer.openOrgName"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ customer.openOrgName }}</div></div>
          <div v-if="customer.openDate"><div class="desc-item__label">开户日期</div><div class="desc-item__value">{{ customer.openDate }}</div></div>
          <div v-if="customer.customerClass"><div class="desc-item__label">客户分类</div><div class="desc-item__value">{{ customerClassText(customer.customerClass) }}</div></div>
        </template>
        <!-- 存量数据无 custType:按字段存在性兜底 -->
        <template v-else>
          <div><div class="desc-item__label">客户名称</div><div class="desc-item__value">{{ customerName }}</div></div>
          <div v-if="customer.certNo"><div class="desc-item__label">证件号码</div><div class="desc-item__value">{{ customer.certNo }}</div></div>
          <div v-if="customer.entpCharic"><div class="desc-item__label">企业性质</div><div class="desc-item__value">{{ customer.entpCharic === 'SOE' ? '国企' : '非国企' }}</div></div>
          <div v-if="customer.industry"><div class="desc-item__label">所属行业</div><div class="desc-item__value">{{ customer.industry }}</div></div>
          <div v-if="customer.creditLevel"><div class="desc-item__label">内部信用等级</div><div class="desc-item__value">{{ customer.creditLevel }}</div></div>
          <div v-if="customer.fiveLevelClass"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(customer.fiveLevelClass) }}</div></div>
          <div v-if="customer.openOrgName"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ customer.openOrgName }}</div></div>
          <div v-if="customer.customerClass"><div class="desc-item__label">客户分类</div><div class="desc-item__value">{{ customerClassText(customer.customerClass) }}</div></div>
        </template>
      </div>
      <div v-else class="empty-line">暂无数据</div>
    </div>

    <!-- 6. 集团信息(仅集团场景) -->
    <div class="card" v-if="isGroup">
      <div class="card__head"><span>集团信息</span><span class="badge badge--info">集团客户</span></div>
      <div class="desc-grid desc-grid--3">
        <div><div class="desc-item__label">集团号</div><div class="desc-item__value">{{ application.groupNo }}</div></div>
        <div><div class="desc-item__label">成员数</div><div class="desc-item__value">{{ groupMembers.length }} 户</div></div>
        <div><div class="desc-item__label">合计申请金额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(groupTotalAmount) }} 万元</div></div>
        <!-- P1-2:集团贡献度(数仓 GROUP 口径综合贡献总额) -->
        <div><div class="desc-item__label">集团贡献度</div><div class="desc-item__value">{{ groupContributionText }}</div></div>
      </div>
      <el-collapse v-if="groupMembers.length" style="margin-top:12px">
        <el-collapse-item v-for="(m, i) in groupMembers" :key="i" :title="memberTitle(m)" :name="i">
          <div class="desc-grid desc-grid--3">
            <div v-if="m.memberName"><div class="desc-item__label">成员名称</div><div class="desc-item__value">{{ m.memberName }}</div></div>
            <div><div class="desc-item__label">成员客户号</div><div class="desc-item__value">{{ customerNoText(m.memberCustomerNo) }}
              <button v-if="isPlaceholderCustomerNo(m.memberCustomerNo) && canBackfill"
                      class="btn btn--primary" @click="openMemberBackfillDlg(m.memberCustomerNo)">回填客户号</button>
            </div></div>
            <div><div class="desc-item__label">成员角色</div><div class="desc-item__value">{{ memberRoleText(m.memberRole) }}</div></div>
            <div><div class="desc-item__label">申请金额(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(m.requestAmount) }}</div></div>
            <div v-if="m.certNo"><div class="desc-item__label">统一社会信用代码</div><div class="desc-item__value">{{ m.certNo }}</div></div>
            <div v-if="m.fiveLevelClass"><div class="desc-item__label">五级分类</div><div class="desc-item__value">{{ fiveLevelClassText(m.fiveLevelClass) }}</div></div>
            <div v-if="m.creditLevel"><div class="desc-item__label">内部信用等级</div><div class="desc-item__value">{{ m.creditLevel }}</div></div>
            <div v-if="m.industry"><div class="desc-item__label">所属行业</div><div class="desc-item__value">{{ m.industry }}</div></div>
            <div v-if="m.registeredCapital != null"><div class="desc-item__label">注册资本(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(m.registeredCapital) }}</div></div>
            <div v-if="m.openOrgName"><div class="desc-item__label">开户机构</div><div class="desc-item__value">{{ m.openOrgName }}</div></div>
            <div v-if="m.openDate"><div class="desc-item__label">开户日期</div><div class="desc-item__value">{{ m.openDate }}</div></div>
            <div v-if="m.basicAccount"><div class="desc-item__label">基本户账户</div><div class="desc-item__value">{{ m.basicAccount }}</div></div>
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
          <div v-else class="empty-line" style="padding:8px">该成员暂无承诺指标</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 6c. 授信信息(授信协议:编号/类型/币种/状态/起止/额度/已用/可用;仅贷款场景,存款无授信) -->
    <!-- 低频只读资料(docs/29 §2.3):默认折叠为一行轻提示,点击展开查看 -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>授信信息</span><span class="badge badge--info">存量授信</span></div>
      <template v-if="creditAgreements.length">
        <div v-if="fold.credit" class="empty-line">授信协议共 {{ creditAgreements.length }} 笔,已折叠 —— <button class="btn btn--text" @click="fold.credit = false">展开 ▾</button></div>
        <div v-show="!fold.credit">
          <table class="table">
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
          <div class="empty-line"><button class="btn btn--text" @click="fold.credit = true">收起 ▲</button></div>
        </div>
      </template>
      <div v-else class="empty-line">暂无授信协议数据</div>
    </div>

    <!-- 6b. 申请材料附件(仅贷款场景;存款无申请材料附件概念) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>申请材料附件</span><span class="badge badge--info">{{ attachments.length }} 个附件</span></div>
      <template v-if="attachments.length">
        <div v-if="fold.attach" class="empty-line">附件共 {{ attachments.length }} 个,已折叠 —— <button class="btn btn--text" @click="fold.attach = false">展开 ▾</button></div>
        <div v-show="!fold.attach">
          <table class="table">
            <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="(a, i) in attachments" :key="i">
                <td>{{ a.fileName }}</td>
                <td class="num">{{ fmtSize(a.fileSize) }}</td>
                <td>{{ a.createTime ? String(a.createTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
                <td><button class="btn btn--text" @click="downloadAttachment(a)">下载</button></td>
              </tr>
            </tbody>
          </table>
          <div class="empty-line"><button class="btn btn--text" @click="fold.attach = true">收起 ▲</button></div>
        </div>
      </template>
      <div v-else class="empty-line">暂无附件(申请时未上传材料)</div>
    </div>

    <!-- 7b. 他行融资(申请人工补录/Excel 导入 + 数仓征信) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>他行融资</span></div>
      <template v-if="otherLoanSummary.length || otherLoans.length">
        <div v-if="fold.otherLoan" class="empty-line">他行融资汇总与明细共 {{ otherLoans.length }} 笔,已折叠 —— <button class="btn btn--text" @click="fold.otherLoan = false">展开 ▾</button></div>
        <div v-show="!fold.otherLoan">
          <div class="desc-grid desc-grid--3" v-if="otherLoanSummary.length">
            <div><div class="desc-item__label">他行机构数</div><div class="desc-item__value">{{ otherLoanSummary[0].lenderCount ?? '—' }}</div></div>
            <div><div class="desc-item__label">授信总额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].creditAmountTotal) }} 万元</div></div>
            <div><div class="desc-item__label">已用总额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].usedAmountTotal) }} 万元</div></div>
            <div><div class="desc-item__label">未结清笔数</div><div class="desc-item__value">{{ otherLoanSummary[0].loanAccountCount ?? '—' }}</div></div>
            <div><div class="desc-item__label">逾期账户</div><div class="desc-item__value">{{ otherLoanSummary[0].overdueAccountCount ?? '—' }}</div></div>
            <div><div class="desc-item__label">逾期余额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].overdueBalance) }} 万元</div></div>
            <div><div class="desc-item__label">不良余额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].nplBalance) }} 万元</div></div>
            <div><div class="desc-item__label">关注类余额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].specialMentionBalance) }} 万元</div></div>
            <div><div class="desc-item__label">对外担保余额</div><div class="desc-item__value desc-item__value--num">{{ fmtAmount(otherLoanSummary[0].externalGuaranteeBalance) }} 万元</div></div>
            <div><div class="desc-item__label">报告日期(征信)</div><div class="desc-item__value">{{ otherLoanSummary[0].reportDate ? String(otherLoanSummary[0].reportDate).slice(0, 10) : '—' }}</div></div>
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
          <div class="empty-line"><button class="btn btn--text" @click="fold.otherLoan = true">收起 ▲</button></div>
        </div>
      </template>
      <div v-else class="empty-line">暂无他行融资记录</div>
    </div>

    <!-- 7c. 关联人情况(数仓客户关系 + 申请录入) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>关联人情况</span></div>
      <template v-if="relatedPersons.length || relations.length">
        <div v-if="fold.related" class="empty-line">关联人共 {{ relatedPersons.length || relations.length }} 人,已折叠 —— <button class="btn btn--text" @click="fold.related = false">展开 ▾</button></div>
        <div v-show="!fold.related">
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
                <td v-if="r.custType === 'CORP'">{{ customerTypeText(r.entpCharic || '—') }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ r.industry || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ r.creditLevel || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ fiveLevelClassText(r.fiveLevelClass) }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'INDIV'">{{ r.occupation || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'INDIV'">{{ fmtAmount(r.annualIncome) }}</td>
                <td v-else>—</td>
                <td class="num">{{ r.creditAgreementCount ?? '—' }}</td>
                <td class="num">{{ r.loanBalanceTotal ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
          <table class="table" v-else>
            <thead>
              <tr><th>关联人</th><th>关系类型</th><th>关联强度</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in relations" :key="i">
                <td>{{ r.relatedCustomerNo }}</td>
                <td>{{ relationTypeText(r.relationType) }}</td>
                <td>{{ r.relationStrength === 'STRONG' ? '强' : r.relationStrength === 'WEAK' ? '弱' : (r.relationStrength || '—') }}</td>
                <td v-if="r.custType === 'CORP'">{{ customerTypeText(r.entpCharic || '—') }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ r.industry || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ r.creditLevel || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'CORP'">{{ fiveLevelClassText(r.fiveLevelClass) }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'INDIV'">{{ r.occupation || '—' }}</td>
                <td v-else>—</td>
                <td v-if="r.custType === 'INDIV'">{{ fmtAmount(r.annualIncome) }}</td>
                <td v-else>—</td>
                <td class="num">{{ r.creditAgreementCount ?? '—' }}</td>
                <td class="num">{{ r.loanBalanceTotal ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div class="empty-line"><button class="btn btn--text" @click="fold.related = true">收起 ▲</button></div>
        </div>
      </template>
      <div v-else class="empty-line">暂无关联人记录</div>
    </div>

    <!-- 8. 存款权限上限提示(存款账户列表已取消展示;本节点权限上限为审批决策依据,保留) -->
    <div class="card" v-if="!isLoan && pi.boundary_rate != null">
      <div class="card__head"><span>本节点权限</span></div>
      <div class="section-tip">
        本节点权限上限 {{ pi.boundary_rate }}%(超过上限将上送小组表决)
        <span v-if="pi.requested_rate != null"> · 申请利率较上限 {{ ((pi.requested_rate - pi.boundary_rate) * 100).toFixed(0) }} BP</span>
      </div>
    </div>
    </div>

    <!-- 第三段:承诺与履约(贡献度参考/历史履约/机构达成) -->
    <div class="anchor-section" id="s-contrib">
    <!-- 9. 当前与拟达成贡献度(双概念并排;存款场景不涉贡献度,仅贷款展示) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
      <ContributionPanel :contribution="contribution" :commitments="commitments" />
    </div>

    <!-- 10. 历史履约:该客户每一次申请的履约比例 + 总额(按申请聚合,Σ实际/Σ目标,口径同承诺跟踪页);仅贷款场景,存款无承诺概念 -->
    <div class="card" v-if="isLoan">
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
        <!-- 按申请列表(点击行展开该申请指标明细,明细紧跟当前行下方) -->
        <table class="table" style="margin-top:14px">
          <thead><tr><th>申请号</th><th>申请时间</th><th>承诺计划</th><th>履约比例</th><th>贡献总额</th><th>指标数</th><th class="num"></th></tr></thead>
          <tbody>
            <template v-for="(t, i) in tracking" :key="i">
              <tr class="app-row" tabindex="0" role="button" :aria-expanded="t.open"
                  @click="t.open = !t.open"
                  @keydown.enter.prevent="t.open = !t.open"
                  @keydown.space.prevent="t.open = !t.open">
                <td>{{ t.applicationNo || '—' }}</td>
                <td>{{ t.submitTime ? fmtDate(t.submitTime) : '—' }}</td>
                <td><span class="badge badge--info">{{ t.planNo }}</span></td>
                <td class="num">
                  <span v-if="t.ratio != null" :class="ratioClass(t.ratio)">{{ t.ratio }}%</span>
                  <span v-else class="muted">暂无评估</span>
                </td>
                <td class="num">{{ t.sumActual != null ? fmtAmount(t.sumActual) + ' 万' : '—' }}</td>
                <td class="num">{{ (t.metrics || []).length }}</td>
                <td class="num muted">{{ t.open ? '收起 ▲' : '展开 ▼' }}</td>
              </tr>
              <!-- 该申请指标明细:紧跟当前行下方展开 -->
              <tr v-if="t.open" class="detail-row">
                <td colspan="7" style="padding: 4px 8px 8px; background: var(--color-fill, #f8f9fa)">
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
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </template>
      <div v-else class="empty-line">该客户暂无历史承诺申请</div>
    </div>

    <!-- 11. 机构达成(仅贷款场景;存款无机构达成概念) -->
    <div class="card" v-if="isLoan">
      <div class="card__head"><span>机构达成</span><span class="badge badge--info">数仓</span></div>
      <template v-if="orgPerformance.length">
        <div v-if="fold.orgPerf" class="empty-line">机构达成共 {{ orgPerformance.length }} 条,已折叠 —— <button class="btn btn--text" @click="fold.orgPerf = false">展开 ▾</button></div>
        <div v-show="!fold.orgPerf">
          <table class="table">
            <thead><tr><th>机构</th><th>统计月份</th><th>达成金额</th><th>目标金额</th><th>达成率</th><th>数据日期</th></tr></thead>
            <tbody>
              <tr v-for="(o, i) in orgPerformance" :key="i">
                <td>{{ o.orgCode || '—' }}</td>
                <td>{{ o.statMonth || '—' }}</td>
                <td class="num">{{ fmtAmount(o.achievedAmount) }}</td>
                <td class="num">{{ fmtAmount(o.expectedAmount) }}</td>
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
          <div class="empty-line"><button class="btn btn--text" @click="fold.orgPerf = true">收起 ▲</button></div>
        </div>
      </template>
      <div v-else class="empty-line">暂无数据</div>
    </div>
    </div>

    <!-- 第四段:流程轨迹(决议/表决记录/路由链与留痕时间线) -->
    <div class="anchor-section" id="s-flow">

    <!-- 11b. 决议与执行核验(§12.7 ⑪:决议日期=issue_time,无有效期周期) -->
    <div class="card" v-if="resolutions.length">
      <div class="card__head"><span>决议</span><span class="badge badge--success">已签发</span></div>
      <table class="table">
        <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in resolutions" :key="i">
            <td>{{ r.resolutionNo || '—' }}</td>
            <td class="num">{{ fmtRate(r.finalRate) }}</td>
            <td>{{ decisionSourceText(r.decisionSource || '—') }}</td>
            <td>{{ fmtDate(r.issueTime) }}</td>
            <td><span class="badge" :class="resolutionStatusBadge(r.status)">{{ execStatusText(r.status) }}</span></td>
          </tr>
        </tbody>
      </table>
      <table class="table" style="margin-top:8px" v-if="isLoan && resolutionExecutions.length">
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

    <!-- 11c. 表决与行长决策(§12.7:小组表决计票汇总 + 行长决策;仅行长·审计·超管可见) -->
    <div class="card" v-if="canViewVote && (voteRounds.length || presidentDecisions.length)">
      <div class="card__head"><span>表决与行长决策</span></div>
      <table class="table" v-if="voteRounds.length">
        <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
        <tbody>
          <tr v-for="(v, i) in voteRounds" :key="i">
            <td>{{ v.roundName || v.roundNo || '—' }}</td>
            <td><span :class="roundStatusBadge(v.status)">{{ roundStatusText(v.status) }}</span></td>
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
            <td><span :class="decisionBadge(d.decision)">{{ decisionText(d.decision) }}</span></td>
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
        <div class="flow-status__item"><span class="dg-label">执行状态</span><b>{{ flowStatusText }}</b></div>
        <div class="flow-status__item"><span class="dg-label">到达时间</span><b>{{ fmtDate(flowStatus.nodeReachTime) }}</b></div>
      </div>
      <!-- 流程路由链:首节点→当前节点(高亮)→终审,已过=完成/当前=处理中/后续=待办(通用 .flow-node 胶囊) -->
      <div v-if="routeChain.length" class="flow-steps">
        <template v-for="(code, idx) in routeChain" :key="code">
          <span class="flow-node" :class="{
            'flow-node--done': idx < currentNodeIndex,
            'flow-node--current': idx === currentNodeIndex,
            'flow-node--todo': idx > currentNodeIndex
          }">
            {{ nodeLabel(code) }}
            <span v-if="idx < currentNodeIndex" class="flow-node__tag">已经审批完成</span>
            <span v-else-if="idx === currentNodeIndex && code === 'SIX_PEOPLE_GROUP' && voteRound" class="flow-node__tag">
              <template v-if="voteRound.roundStatus === 'PASSED' || voteRound.roundStatus === 'FAILED'">
                表决{{ voteRound.roundStatus === 'PASSED' ? '通过' : '未通过' }} · 赞成 {{ voteRound.approveCount }} / 反对 {{ voteRound.rejectCount }} (通过线 ≥{{ voteRound.requiredCount }})
              </template>
              <template v-else>
                {{ voteRound.submittedCount }}/{{ voteRound.voterCount }} 人已投 · 通过线 ≥{{ voteRound.requiredCount }}
              </template>
            </span>
            <span v-else-if="idx === currentNodeIndex" class="flow-node__tag">{{ nodeHandled(code) ? '已经审批完成' : '审批中' }}</span>
            <span v-else class="flow-node__tag">待办</span>
          </span>
          <span v-if="idx < routeChain.length - 1" class="flow-node__arrow">→</span>
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
      <div v-else-if="routeChain.length" class="empty-line">尚无审批动作,申请已流转至当前节点。</div>
      <div v-else class="empty-line">暂无数据</div>
    </div>
    </div>

    <!-- 第五段:审批决定(行长整单决策/按担保分项逐项审批 + 吸底操作条) -->
    <div class="anchor-section" id="s-decide">
    <!-- 11d. 行长决策(整单,§7.5):六人小组表决通过后待总行行长决策;与申请/审批页一致不拆分为项,
         行长统一「同意利率/一票否决」,并在本区查看六人小组匿名审批意见(§12.7) -->
    <div class="card" v-if="isPresidentDecision">
      <div class="card__head"><span>行长决策(整单)</span></div>
      <!-- 六人审批结果汇总(整单:取首待决策分项计票,分项明细在下方匿名意见折叠内);
           分层展示:表决结论(主) + 计票(次) + 申请金额/利率(整单决策需知) -->
      <div class="vote-summary" v-if="presidentDecisionItems.length">
        <div class="vote-summary__main">
          <span class="vote-summary__verdict" :class="presidentVoteResult === 'FAIL' ? 'is-fail' : 'is-pass'">
            {{ presidentVoteResult === 'FAIL' ? '六人小组表决未通过' : '六人小组表决通过' }}
          </span>
          <template v-if="presidentVote">
            <span class="badge badge--info">通过线 ≥{{ presidentVote.requiredCount }}</span>
            <span class="badge badge--neutral">已收 {{ presidentVote.submittedCount }} 票</span>
          </template>
        </div>
        <div class="vote-summary__stats" v-if="presidentVote">
          <span class="badge badge--success">{{ presidentVote.approveCount }} 票赞成</span>
          <span class="badge badge--danger">{{ presidentVote.rejectCount }} 票反对</span>
        </div>
        <div class="vote-summary__meta">
          <span class="dg-label">申请总额</span><b>{{ fmtAmount(presidentTotalAmount) }} 万元</b>
          <span class="dg-label">申请利率</span><b>{{ fmtRate(presidentDecisionItems[0]?.requestedRate) }}</b>
          <span class="dg-label">测算利率</span><b>{{ fmtRate(presidentDecisionItems[0]?.calculatedRate) }}</b>
          <span class="dg-label">待决策分项</span><b>{{ presidentDecisionItems.length }} 项</b>
        </div>
      </div>
      <div class="stat-card__sub" v-if="presidentDecisionItems.length > 1" style="margin:8px 0">
        本申请含多个分项,六人小组按分项分别计票;以下按分项展示表决结果与匿名意见,行长统一整单决策。
      </div>
      <!-- 六人小组匿名审批意见(匿名码每批随机分配,仅行长/审计可见;默认收起) -->
      <el-collapse v-if="presidentDecisionItems.length">
        <el-collapse-item v-for="it in presidentDecisionItems" :key="it.id" :name="`item-${it.id}`">
          <template #title>
            <span style="font-weight:600;margin-right:8px">{{ itemName(it) }}</span>
            <span v-if="voteResultOfItem(it)" class="badge badge--success">{{ voteText(voteResultOfItem(it)) }}</span>
          </template>
          <div class="stat-card__sub" style="margin-bottom:6px">
            金额 {{ fmtAmount(it.pricingAmount) }} 万 · 期限 {{ fmtTerm(it) }}
            · 申请利率 {{ fmtRate(it.requestedRate) }} · 测算利率 {{ fmtRate(it.calculatedRate) }} · 审批利率 {{ fmtRate(it.currentApprovalRate ?? it.requestedRate) }}
            · 六人表决 {{ voteText(voteResultOfItem(it)) }}
          </div>
          <table class="table">
            <thead><tr><th>委员(匿名)</th><th>表决</th><th>意见</th><th>提交时间</th></tr></thead>
            <tbody>
              <tr v-for="(o, i) in (presidentOpinions[it.id] || [])" :key="i">
                <td>{{ o.anonymNo || '—' }}</td>
                <td>
                  <span :class="voteChoiceBadge(o.voteChoice)">
                    {{ voteChoiceText(o.voteChoice) }}
                  </span>
                </td>
                <td>{{ o.voteComment || '—' }}</td>
                <td>{{ o.submitTime ? String(o.submitTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!(presidentOpinions[it.id] || []).length" class="empty-line" style="padding:8px">暂无委员匿名意见</div>
        </el-collapse-item>
      </el-collapse>
      <div class="op-form__row" style="margin-top:12px">
        <label class="op-form__label">行长决策意见</label>
        <el-input v-model="presidentOpinion" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="同意可填意见;一票否决必须填写意见" />
      </div>
      <div style="display:flex;gap:12px;flex-wrap:wrap">
        <button class="btn btn--primary" :disabled="submitting" @click="doPresidentDecision('APPROVE')">同意利率</button>
        <button class="btn btn--danger" :disabled="submitting" @click="doPresidentDecision('VETO')">一票否决</button>
      </div>
    </div>

    <!-- 12. 审批决定(整单交付改造 2026-08-29:整单一次审批,不逐分项点同意/否决;
         分项明细只读,贷款按利率最低分项定流程,整单统一利率/操作) -->
    <div class="card" v-if="actionable">
      <div class="card__head">
        <span>审批决定(整单)</span>
        <span class="badge badge--processing">当前节点:{{ nodeLabel(currentNodeCode) }} · 整单一次审批</span>
      </div>
      <div class="stat-card__sub" style="margin-bottom:10px">
        {{ isLoan
          ? `本申请共 ${siblingItems.length} 个分项,按利率最低分项定整单流程,一次审批处理整单(任一节点否决即整单否决)。`
          : `本申请共 ${siblingItems.length} 个分项,按原流程整单审批,一次处理整单。` }}
      </div>
      <!-- 分项明细只读(整单统一决策,分项不再独立审批) -->
      <div class="op-item__subhead">分项明细(只读)</div>
      <table class="table">
        <thead><tr>
          <th>分项编号</th><th>成员</th><th>金额(万元)</th><th>期限</th><th>产品</th><th>担保方式</th><th>申请利率</th><th>测算利率</th><th>审批利率</th><th>定链</th>
        </tr></thead>
        <tbody>
          <tr v-for="it in siblingItems" :key="it.id">
            <td>{{ it.pricingItemNo || it.id }}</td>
            <td>{{ memberLabel(it.memberCustomerNo || it.member_customer_no) }}</td>
            <td class="num">{{ fmtAmount(it.pricingAmount) }}</td>
            <td>{{ fmtTerm(it) }}</td>
            <td>{{ productName(it.productCode) }}</td>
            <td>{{ guaranteesText(it.guarantees) }}</td>
            <td class="num">{{ fmtRate(it.requestedRate) }}</td>
            <td class="num">{{ fmtRate(it.calculatedRate) }}</td>
            <td class="num">{{ fmtRate(it.currentApprovalRate ?? it.requestedRate) }}</td>
            <td><span v-if="isAnchorItem(it)" class="badge badge--info">定链分项</span><span v-else class="dg-label">—</span></td>
          </tr>
        </tbody>
      </table>
      <!-- 整单利率(贷款非小组/非秘书岗可调:整单统一审批利率,后端应用到全部在途分项;存款/审核岗只读) -->
      <div class="rate-compare" style="margin-top:12px">
        <span class="rate-compare__item">申请利率<strong>{{ fmtRate(anchorRate) }}</strong></span>
        <span class="rate-compare__arrow">→</span>
        <span class="rate-compare__item">审批后利率(整单)
          <el-input-number v-if="canAdjustWholeOrderRate" v-model="opRate" :min="0" :max="36" :precision="4" :step="0.01" controls-position="right" style="width:140px" />
          <template v-else><strong>{{ fmtRate(opRate) }}</strong></template>
        </span>
        <span class="rate-compare__item">测算利率<strong>{{ fmtRate(anchorCalculatedRate) }}</strong></span>
      </div>
      <div class="stat-card__sub" v-if="canAdjustWholeOrderRate" style="margin-top:8px">
        {{ isLoan ? '整单审批利率统一应用到全部在途分项;不得突破本节点权限边界与产品硬边界,低于下限保留利率随整单上送下一节点。' : '' }}
      </div>
      <div class="stat-card__sub" v-else-if="isCommitteeVoting" style="margin-top:8px">
        委员仅同意/否决,不能调整利率;6 人全部投完后统计,≥4 同意上送总行行长,&lt;4 同意直接否决整单。
      </div>
      <div class="stat-card__sub" v-else-if="isSecretaryNode" style="margin-top:8px">
        贷审会秘书岗为审核岗:仅同意/否决,不调整利率;否决即整单拦截,流程终止。
      </div>
      <!-- 整单操作(一次操作处理整单;小组为投整单票) -->
      <div class="op-item__actions" style="margin-top:12px">
        <template v-if="isCommitteeVoting">
          <button class="btn btn--primary" :disabled="submitting || wholeOrderActed" @click="doVote('APPROVE')">提交同意票(整单)</button>
          <button class="btn btn--danger" :disabled="submitting || wholeOrderActed" @click="doVote('REJECT')">提交否决票(整单)</button>
        </template>
        <template v-else>
          <button class="btn btn--primary" :disabled="submitting || wholeOrderActed" @click="doApprove">同意审批(整单)</button>
          <button class="btn btn--danger" :disabled="submitting || wholeOrderActed" @click="doReject">否决(整单)</button>
        </template>
      </div>
      <div class="op-item__passed-tip" v-if="wholeOrderActed" style="margin-top:8px">
        {{ isCommitteeVoting ? '本人已投整单票,提交后不可修改。' : '本申请当前节点已审批处理,无需重复操作。' }}
      </div>
    </div>
    <div class="card" v-else-if="flowStatus?.currentStatus === 'ROUTING'">
      <div class="empty-line">该申请当前节点为「{{ nodeLabel(currentNodeCode) }}」,不在本人审批范围,仅可查看。</div>
    </div>
    </div>

    <!-- 审批操作吸底(整单交付改造 2026-08-29):审批意见与整单通过/否决固定页底随手可及;一次操作处理整单 -->
    <div class="approve-bar" v-if="actionable">
      <div class="approve-bar__inner">
        <el-input v-model="opComment" type="textarea" :rows="1" maxlength="500" placeholder="请输入审批意见(否决时建议说明原因)" style="flex:1" />
        <template v-if="isCommitteeVoting">
          <span class="stat-card__sub" style="white-space:nowrap;align-self:center">
            已投 {{ voteRound?.submittedCount ?? 0 }}/{{ voteRound?.voterCount ?? 6 }} · 通过线 ≥{{ voteRound?.requiredCount ?? 4 }}
          </span>
          <button class="btn btn--primary" :disabled="submitting || wholeOrderActed" @click="doVote('APPROVE')">提交同意票(整单)</button>
          <button class="btn btn--danger" :disabled="submitting || wholeOrderActed" @click="doVote('REJECT')">提交否决票(整单)</button>
        </template>
        <template v-else>
          <button class="btn btn--primary" :disabled="submitting || wholeOrderActed" @click="doApprove">同意审批(整单)</button>
          <button class="btn btn--danger" :disabled="submitting || wholeOrderActed" @click="doReject">否决(整单)</button>
        </template>
        <button class="btn btn--secondary" @click="goBack">返回待办列表</button>
      </div>
    </div>

    <!-- 审批中客户号回填弹窗(2026-08-20 #017):新增客户占位号→真实号,支持直接给号或证件号反查 -->
    <el-dialog v-model="backfillVisible" title="回填客户号" width="520px">
      <div class="dlg-tip">该申请为客户经理登记的新增客户,提交时数仓尚未收录客户号。数仓生成客户号后请在此回填真实客户号,系统将同步申请/分项/快照与后续承诺数据;也可输入证件号自动反查数仓。</div>
      <div class="form-field">
        <label class="form-field__label">真实客户号</label>
        <input class="form-input" v-model="backfillForm.customerNo" placeholder="数仓生成的客户号(优先)" />
      </div>
      <div class="form-field" style="margin-top:10px">
        <label class="form-field__label">证件号(或)</label>
        <input class="form-input" v-model="backfillForm.certNo" placeholder="统一社会信用代码 / 身份证号,自动反查数仓客户号" />
      </div>
      <div class="dlg-actions" style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px">
        <button class="btn btn--secondary" @click="backfillVisible = false">取消</button>
        <button class="btn btn--primary" :disabled="backfilling" @click="doBackfill">确认回填</button>
      </div>
    </el-dialog>
  </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApprovalDetail, approveTask, rejectTask, backfillCustomerNo, newIdempotencyKey, type ApprovalResult } from '@/api/approval'
import { submitBallot, submitPresidentDecision } from '@/api/vote'
import { listRoundOpinions } from '@/api/approval2'
import { download } from '@/api/request'
import { useUserStore } from '@/store/user'
import ContributionPanel from '@/components/ContributionPanel.vue'
import {
  guaranteeTypeText, nodeLabel, itemStatusText, actionText, decisionText,
  execStatusText, roundStatusText, evalResultText, ruleLevelText, voteChoiceText,
  productName, metricName, termUnitText, carrierTypeText, measureTypeText,
  customerTypeText, memberRoleText, rateTypeText,
  customerClassText, certTypeText, currencyText,
  entpScaleText, genderText, maritalStatusText, decisionSourceText,
  fiveLevelClassText, groupTypeText, customerNoText, isPlaceholderCustomerNo,
  roundStatusBadge, decisionBadge, voteChoiceBadge
} from '@/utils/dict'
// eslint-disable-next-line no-duplicate-imports
import { inputModeText, relationTypeText, agreementTypeText, agreementStatusText, agreementStatusBadge } from '@/utils/dict'
import { fmtAmount, fmtSize } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loaded = ref(false)
const loading = ref(true)
const loadError = ref(false)
const submitting = ref(false)
// 整单交付改造(2026-08-29):详情入口为申请号,整单一次审批
const applicationId = computed(() => route.params.id as string)

const pi = ref<any>({})
const application = ref<any>({})
const customer = ref<any>({})
const contribution = ref<any[]>([])
const commitments = ref<any[]>([])
const guarantees = ref<any[]>([])
const groupMembers = ref<any[]>([])
const routeChain = ref<string[]>([])
const flowTrace = ref<any[]>([])
// 当前执行状态(流程轨迹增强):当前节点/状态/到达时间
const flowStatus = ref<any>(null)
// 当前节点是否已审批处理过(存在该节点的审批动作:通过/上送/否决/行长否决)
function nodeHandled(nodeCode: string): boolean {
  if (!nodeCode) return false
  return flowTrace.value.some(t => t.nodeCode === nodeCode
    && ['APPROVE', 'ESCALATE', 'REJECT', 'VETO'].includes(t.actionType))
}
// 执行状态文案:当前节点已审批结束 → 显示"已经审批完成",否则按分项状态显示
const flowStatusText = computed(() => {
  const node = flowStatus.value?.currentNodeCode
  if (nodeHandled(node)) return '已经审批完成'
  return itemStatusText(flowStatus.value?.currentStatus)
})
const source = ref('')
const snapshotInfo = ref<any>({})
const tracking = ref<any[]>([])
const orgPerformance = ref<any[]>([])
const otherLoanSummary = ref<any[]>([])
const otherLoans = ref<any[]>([])
const relations = ref<any[]>([])
const relatedPersons = ref<any[]>([])
const rawCreditAgreements = ref<any[]>([])
/** 授信信息只展示存量已有授信(数仓);本次申请补录/新增授信不进"本行授信情况"(§用户要求) */
const creditAgreements = computed(() =>
  (rawCreditAgreements.value || []).filter((a: any) => a.source !== 'APPLICATION'))
// 集团贡献度(数仓 GROUP 口径 TOTAL)
const groupContribution = ref<any>(null)
const attachments = ref<any[]>([])
const resolutions = ref<any[]>([])
const resolutionExecutions = ref<any[]>([])
const voteRounds = ref<any[]>([])
const voteResults = ref<any[]>([])
const presidentDecisions = ref<any[]>([])
// 小组节点当前轮次(匿名汇总 + 本人票;普通节点为 null)
const voteRound = ref<any>(null)
// 行长决策(整单,§7.5):六人表决通过后待总行行长决策的分项与其匿名意见(§12.7)
const presidentOpinion = ref('')
const presidentOpinions = ref<Record<string, any[]>>({})
const presidentDecisionItems = computed(() => siblingItems.value.filter((it: any) =>
  it.status === 'COMMITTEE_PASS' || it.status === 'PRESIDENT_DECISION'))
// 行长角色且存在待决策分项 → 展示行长决策卡片(行长「独立区域」查看六人匿名意见并整单决策)
const isPresidentDecision = computed(() =>
  (userStore.userInfo?.roles?.[0] || '') === 'president' && presidentDecisionItems.value.length > 0)
const presidentVote = computed(() => {
  const first = presidentDecisionItems.value[0]
  if (!first) return null
  return voteResults.value.find((r: any) => String(r.pricingItemId) === String(first.id)) || null
})
function voteResultOfItem(it: any) {
  return voteResults.value.find((r: any) => String(r.pricingItemId) === String(it.id)) || null
}
// 行长决策卡(11d):待决策分项金额合计(整单决策看申请总额,与申请/审批页金额口径一致)
const presidentTotalAmount = computed(() =>
  presidentDecisionItems.value.reduce((s: number, it: any) => s + (Number(it.pricingAmount) || 0), 0))
// 六人小组表决结论:计票结果 PASS/FAIL;无记录按通过展示(能到行长决策即已表决通过)
const presidentVoteResult = computed(() => {
  const r = presidentVote.value
  return r ? String(r.result || '') : ''
})

const opComment = ref('')

// 吸顶摘要条申请金额:同申请全部分项金额合计(万元,纯展示口径)
const applyAmountTotal = computed(() =>
  siblingItems.value.reduce((s: number, it: any) => s + (Number(it.pricingAmount) || 0), 0))

// 锚点导航:吸顶摘要条跳转对应区块(原生 href 锚点会污染前端路由,改用滚动定位)
function scrollToSection(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 低频只读资料卡默认折叠(docs/29 §2.3):授信/附件/他行融资/关联人/机构达成,v-show 切换
const fold = ref({ credit: true, attach: true, otherLoan: true, related: true, orgPerf: true })

// 分项表明细展开行(docs/29 §2.3):主列之外的字段收进展开行,按分项 id 记录展开状态
const expandedItemIds = ref<Set<string>>(new Set())
function itemDetailOpen(id: any): boolean {
  return expandedItemIds.value.has(String(id))
}
function toggleItemDetail(id: any) {
  const k = String(id)
  const next = new Set(expandedItemIds.value)
  if (next.has(k)) next.delete(k)
  else next.add(k)
  expandedItemIds.value = next
}
// 分项表展开行 colspan:6 个固定主列 + 集团成员列 + 贷款担保方式列
const itemTableCols = computed(() => 6 + (isGroup.value ? 1 : 0) + (isLoan.value ? 1 : 0))

// 整单交付改造(2026-08-29):分项列表只读明细,审批按整单
const siblingItems = ref<any[]>([])

const ROLE_NODE: Record<string, string> = {
  branch_manager: 'BRANCH_MANAGER', dept_gm: 'DEPT_GENERAL_MANAGER', vice_president: 'VICE_PRESIDENT',
  committee_member: 'SIX_PEOPLE_GROUP', secretary: 'SECRETARY'
}

// 整单当前节点:优先申请单(application.current_node_code,后端 detail 已整单化),回退锚定分项
const currentNodeCode = computed(() => flowStatus.value?.currentNodeCode || pi.value.current_node_code || '')

// 六人小组节点(委员内联审批):整单在小组节点且登录人具备委员身份(含兼岗,§D-7)
const isCommitteeVoting = computed(() =>
  currentNodeCode.value === 'SIX_PEOPLE_GROUP'
  && (userStore.userInfo?.roles || []).includes('committee_member'))

// 贷审会秘书岗节点(需求四):审核岗仅同意/否决,不调整利率,否决即整单拦截
const isSecretaryNode = computed(() => currentNodeCode.value === 'SECRETARY')

const isLoan = computed(() => application.value.businessType !== 'DEPOSIT')
const businessTypeText = computed(() => application.value.businessType === 'DEPOSIT' ? '存款' : '贷款')
const isGroup = computed(() => !!application.value.groupNo)
// 授信总额(万元)=申请页填的总授信额度(credit_info_json.totalCredit,非分项合计/集团批复口径;§2026-08-27 #387)
const applyTotalCredit = computed(() => {
  try {
    const raw = application.value.creditInfoJson
    const ci = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : null
    const v = ci?.totalCredit
    return v != null && v !== '' ? Number(v) : null
  } catch {
    return null
  }
})
// 申请类型(新增授信/存量调息)=申请页选填的 form.businessType(NEW/EXISTING,credit_info_json.businessType;2026-08-27 补充展示)
const applyBizTypeText = computed(() => {
  try {
    const raw = application.value.creditInfoJson
    const ci = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : null
    return ci?.businessType === 'EXISTING' ? '存量调息' : ci?.businessType === 'NEW' ? '新增授信' : '—'
  } catch {
    return '—'
  }
})
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || pi.value.pricing_customer_no || '—')
// 客户主体类型(对公 CORP/对私 INDIV),决定客户基本信息卡片的字段分组
const isCorpCustomer = computed(() => customer.value.custType === 'CORP')
const isIndivCustomer = computed(() => customer.value.custType === 'INDIV')
const statusText = computed(() => itemStatusText(pi.value.status))

// 当前节点在路由链中的位置(高亮)
const currentNodeIndex = computed(() => {
  const idx = routeChain.value.indexOf(currentNodeCode.value)
  return idx < 0 ? 0 : idx
})

// 仅当分项在审批中且当前节点与登录人角色节点一致时可操作
// 六人小组节点:委员可操作(VOTING 即小组轮次中),与普通节点 ROUTING 语义对齐
const actionable = computed(() => {
  const roles = userStore.userInfo?.roles || []
  const node = currentNodeCode.value
  if (!node) return false
  // §D-7 兼岗:roles 含 committee_member(含主角色非委员被配置进小组名单)即按委员处理
  if (node === 'SIX_PEOPLE_GROUP' && roles.includes('committee_member')) {
    // 委员在小组节点全程可见表决操作区:即使最后一人投完翻出 VOTING(PRESIDENT_DECISION/REJECTED),
    // 也保留卡片展示已投状态与进度,避免"最后一人投完后页面效果与其他委员不一致"(卡片整卡消失)
    return true
  }
  // §秘书岗(计划财务部总经理兼任):SECRETARY 节点按 roles 数组含 secretary 判定(与委员兼岗一致,不只看主角色)
  if (node === 'SECRETARY' && roles.includes('secretary')) {
    return flowStatus.value?.currentStatus === 'ROUTING'
  }
  const role = roles[0] || ''
  if (ROLE_NODE[role] !== node) return false
  return flowStatus.value?.currentStatus === 'ROUTING'
})

// 整单交付改造(2026-08-29):审批按整单,不再逐分项点同意/否决。
// 定链分项=在途分项中有效利率最低者(贷款,整单流程由它决定);存款取批内第一个分项
function pickAnchorItem(items: any[]): any {
  if (!items || !items.length) return null
  if (isLoan.value) {
    const routing = items.filter((it) => it.status === 'ROUTING')
    const pool = routing.length ? routing : items
    return pool.reduce((min, it) => {
      const r = Number(it.currentApprovalRate ?? it.requestedRate ?? Number.MAX_VALUE)
      const mr = Number(min.currentApprovalRate ?? min.requestedRate ?? Number.MAX_VALUE)
      return r < mr ? it : min
    })
  }
  return items[0]
}
const anchorItem = computed(() => pickAnchorItem(siblingItems.value))
// 定链分项申请/测算利率(整单区只读展示)
const anchorRate = computed(() => anchorItem.value?.requestedRate)
const anchorCalculatedRate = computed(() => anchorItem.value?.calculatedRate)
// 整单统一审批利率(后端 adjustRate 应用到全部在途分项)
const opRate = ref<number | null>(null)
// 整单利率相对定链分项基线是否变化(决定是否传 adjustRate)
function wholeOrderRateChanged(): boolean {
  const base = anchorItem.value?.currentApprovalRate ?? anchorItem.value?.requestedRate
  return opRate.value != null && base != null && Number(opRate.value) !== Number(base)
}
// 整单是否已处理:委员=本人已投整单票;普通节点=该节点已有审批动作(通过/上送/否决)
const wholeOrderActed = computed(() => {
  if (isCommitteeVoting.value) return !!voteRound.value?.myChoice
  return nodeHandled(currentNodeCode.value)
})
// 整单利率可调:贷款且非小组/秘书岗(审核岗只读利率),且当前节点可操作
const canAdjustWholeOrderRate = computed(() =>
  actionable.value && isLoan.value && !isCommitteeVoting.value && !isSecretaryNode.value)
// 定链分项标识(分项明细表「定链分项」徽标)
function isAnchorItem(it: any): boolean {
  return anchorItem.value != null && String(anchorItem.value.id) === String(it.id)
}

// 登录人当前角色节点
const currentRoleNode = computed(() => ROLE_NODE[userStore.userInfo?.roles?.[0] || ''])

// 表决统计可见性(§12.7/T4-02/T4-10):表决计票/轮次/行长决策仅行长·审计·超管可见,委员与审批人隐藏
const canViewVote = computed(() => ['admin', 'auditor', 'president'].includes(userStore.userInfo?.roles?.[0] || ''))

function canOperate(it: any): boolean {
  // 六人小组节点:委员对小组轮次内分项(VOTING 本人未投整单票)可操作;普通节点沿用 ROUTING 口径
  if (isCommitteeVoting.value) {
    return it.status === 'VOTING' && it.currentNodeCode === 'SIX_PEOPLE_GROUP' && !voteRound.value?.myChoice
  }
  // 秘书岗兼岗(§需求四,计划财务部总经理兼任,主角色 dept_gm + 附加 secretary):
  // SECRETARY 节点按 roles 含 secretary 判定(与 actionable 一致,不只看主角色),否则按钮恒禁用
  if (it.currentNodeCode === 'SECRETARY' && (userStore.userInfo?.roles || []).includes('secretary')) {
    // 秘书岗仅对命中秘书岗条件的分项有权操作(route_chain 含 SECRETARY);
    // 未命中分项仅过手,不显示审批动作(2026-08-28 用户拍板)
    return it.status === 'ROUTING' && String(it.routeChain || '').includes('SECRETARY')
  }
  return it.status === 'ROUTING' && !!it.currentNodeCode && currentRoleNode.value === it.currentNodeCode
}

// 审批中客户号回填权限(2026-08-20 #017):申请内存在占位客户号/占位成员
// (单户主客户号,或集团任一成员号),且当前节点可操作(审批人)或行长/审计/admin
const hasPlaceholderCustomer = computed(() =>
  isPlaceholderCustomerNo(application.value.customerNo || pi.value.pricing_customer_no)
  || groupMembers.value.some((m) => isPlaceholderCustomerNo(m.memberCustomerNo)))
const canBackfill = computed(() =>
  hasPlaceholderCustomer.value
  && (canOperate(anchorItem.value) || ['admin', 'auditor', 'president'].includes(userStore.userInfo?.roles?.[0] || '')))

const backfillVisible = ref(false)
const backfilling = ref(false)
const backfillForm = ref<{ customerNo: string; certNo: string }>({ customerNo: '', certNo: '' })
const backfillTargetId = ref<number | string>('') // 回填目标分项(集团按成员定位分项)
function openBackfillDlg() {
  // 整单回填以锚定分项为落点(后端按申请整单替换,任一分项即可)
  backfillTargetId.value = anchorItem.value?.id ?? ''
  backfillForm.value = { customerNo: '', certNo: '' }
  backfillVisible.value = true
}
/** 集团成员回填:定位该成员对应分项(后端按申请+占位号整单替换,任一分项即可) */
function openMemberBackfillDlg(memberNo: string) {
  const target = siblingItems.value.find((it) => (it.memberCustomerNo || it.member_customer_no) === memberNo)
  if (!target) {
    ElMessage.warning('未找到该成员的定价分项,无法回填')
    return
  }
  backfillTargetId.value = target.id
  backfillForm.value = { customerNo: '', certNo: '' }
  backfillVisible.value = true
}
async function doBackfill() {
  const { customerNo, certNo } = backfillForm.value
  if (!customerNo.trim() && !certNo.trim()) {
    ElMessage.warning('请输入真实客户号或证件号(二选一)')
    return
  }
  backfilling.value = true
  try {
    await backfillCustomerNo(backfillTargetId.value, {
      customerNo: customerNo.trim() || undefined,
      certNo: certNo.trim() || undefined
    })
    ElMessage.success('客户号回填成功')
    backfillVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '回填失败')
  } finally {
    backfilling.value = false
  }
}

// 整单交付改造(2026-08-29):已无逐分项审批,itemApproved/itemRejected/myBallotChoice/isVotableItem 移除,
// 整单处理状态统一由 wholeOrderActed 判定(委员=本人已投整单票,普通节点=该节点已有审批动作)

function itemName(it: any): string {
  // 载体类型:贷款合同按业务口径展示为授信方案(2026-08-27),存款账户不变
  const carrier = it.carrierType === 'LOAN_CONTRACT' ? '授信方案' : carrierTypeText(it.carrierType)
  const amount = it.pricingAmount != null ? `${fmtAmount(it.pricingAmount)} 万` : ''
  let name = `${it.pricingItemNo || '定价分项'}${carrier ? ' · ' + carrier : ''}${amount ? ' · ' + amount : ''}`
  // 集团场景:审批决定区标明是哪家成员申请的、申请利率是多少(成员级定价,非整个集团)
  const memberNo = it.memberCustomerNo || it.member_customer_no
  if (isGroup.value && memberNo) {
    name = `成员 ${memberLabel(memberNo)} · 申请利率 ${fmtRate(it.requestedRate)} · ${name}`
  }
  return name
}

// 集团成员标签:优先成员名称,无名称回退客户号(分项成员列/审批决定区共用;内部合成号显示"非我行客户")
function memberLabel(memberNo?: string): string {
  if (!memberNo) return '—'
  const m = groupMembers.value.find((x) => String(x.memberCustomerNo) === String(memberNo))
  if (m) return m.memberName ? `${m.memberName}(${customerNoText(m.memberCustomerNo)})` : customerNoText(memberNo)
  return customerNoText(memberNo)
}

// 整单交付改造(2026-08-29):整单统一 adjustRate(应用到全部在途分项),逐分项 rateChanged/collectRateAdjustments 移除,
// 由 wholeOrderRateChanged 替代(定链分项利率相对基线变化)


const groupTotalAmount = computed(() =>
  groupMembers.value.reduce((sum, m) => sum + (Number(m.requestAmount) || 0), 0))

// 集团成员折叠标题:有名称显示「名称(客户号)」,无数仓/手工名称回退客户号
function memberTitle(m: any): string {
  const who = m.memberName ? `${m.memberName}(${m.memberCustomerNo})` : m.memberCustomerNo
  return `成员 ${who}(${memberRoleText(m.memberRole, '成员')})`
}

// P1-2:集团贡献度(数仓 GROUP 口径综合贡献总额,万元)
const groupContributionText = computed(() => {
  const g = groupContribution.value
  if (!g || g.metricValue == null) return '暂无数据'
  return `${g.metricValue}${g.valueType === 'CONTRIBUTION_AMOUNT' ? ' 万元' : ''}`.trim()
})


// 部门归属文案(§D16a 矩阵透出:机构org_code——3202233912公司金融部/3202233943授信评审部/3202233991零售金融)
function deptText(code?: string) {
  const map: Record<string, string> = { '3202233912': '公司金融部', '3202233943': '授信评审部', '3202233991': '零售金融' }
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

// 流程轨迹动作徽标:否决/终审红,提交灰蓝,通过绿
function traceBadge(t?: string) {
  if (t === 'REJECT' || t === 'VETO') return 'badge--rejected'
  if (t === 'SUBMIT') return 'badge--info'
  return 'badge--approved'
}

function fmtRate(v: any) {
  return v == null || v === '' ? '—' : `${v}%`
}

function fmtTerm(it: any) {
  return it.termValue != null ? `${it.termValue}${termUnitText(it.termUnit)}` : '—'
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
  if (!Number.isFinite(r)) return 'var(--color-text-light)'
  if (r >= 100) return 'var(--color-success)'
  if (r >= 80) return 'var(--color-warning)'
  return 'var(--color-danger)'
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

// 计票文案(全中文):「赞成 X 票 / 反对 Y 票」,避免「5:1」冒号写法对非技术读者晦涩
function voteText(r: any): string {
  return r ? `赞成 ${r.approveCount ?? 0} 票 / 反对 ${r.rejectCount ?? 0} 票` : '—'
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const data = await getApprovalDetail(applicationId.value)
    pi.value = data.pricingItem || {}
    application.value = data.application?.[0] || {}
    customer.value = data.customer?.[0] || {}
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
    otherLoanSummary.value = data.otherLoanSummary || []
    otherLoans.value = [...(data.otherLoans || []), ...(data.appOtherLoans || [])]
    relations.value = data.relations || []
    relatedPersons.value = data.relatedPersons || []
    rawCreditAgreements.value = data.creditAgreements || []
    groupContribution.value = data.groupContribution || null
    attachments.value = data.attachments || []
    resolutions.value = data.resolutions || []
    resolutionExecutions.value = data.resolutionExecutions || []
    voteRounds.value = data.voteRounds || []
    voteResults.value = data.voteResults || []
    presidentDecisions.value = data.presidentDecisions || []
    // 小组节点当前表决轮次(匿名汇总 + 本人票),内联审批/链路进度数据源
    voteRound.value = data.voteRound || null
    // 分项审批决定区:同申请分项摘要(后端未返回时回退为当前分项单元素),每分项预填审批利率
    siblingItems.value = (data.siblingItems && data.siblingItems.length) ? data.siblingItems : [data.pricingItem || {}]
    // 整单统一审批利率:预填定链分项当前审批利率(审批人可调,后端应用到全部在途分项)
    const anchor = pickAnchorItem(siblingItems.value)
    const baseRate = anchor?.currentApprovalRate ?? anchor?.requestedRate
    opRate.value = baseRate != null ? Number(baseRate) : null
    // 行长/审计视角:加载六人小组匿名审批意见(§12.7,按轮次查询按分项归组)
    if (canViewVote.value) {
      await loadPresidentOpinions()
    }
    loaded.value = true
  } catch {
    ElMessage.error('审批详情加载失败')
    loadError.value = true
  } finally {
    loading.value = false
  }
}

// 行长视角:按计票轮次加载六人匿名意见,按分项归组;单轮失败不影响其余
async function loadPresidentOpinions() {
  const roundIds = [...new Set(voteResults.value.map((r: any) => r.roundId).filter((id: any) => id != null))]
  const map: Record<string, any[]> = {}
  for (const rid of roundIds) {
    try {
      const rounds = await listRoundOpinions(rid)
      for (const row of rounds || []) {
        map[String(row.pricingItemId)] = row.opinions || []
      }
    } catch { /* 忽略单个轮次意见加载失败 */ }
  }
  presidentOpinions.value = map
}

// 行长决策(整单,§7.5):同意利率 → 整单终审签发决议;一票否决 → 整单终态;必填意见
async function doPresidentDecision(decision: 'APPROVE' | 'VETO') {
  const appId = application.value.id ?? pi.value.application_id
  if (!appId) {
    ElMessage.error('无法获取申请编号,请刷新后重试')
    return
  }
  if (decision === 'VETO' && !presidentOpinion.value?.trim()) {
    ElMessage.warning('一票否决必须填写决策意见')
    return
  }
  const confirmText = decision === 'APPROVE'
    ? `确认同意利率 ${fmtRate(presidentDecisionItems.value[0]?.requestedRate)}?同意后整单签发决议,不可撤销。`
    : '确认一票否决该申请?否决后为终态,同申请全部分项一并否决。'
  try {
    await ElMessageBox.confirm(confirmText, decision === 'APPROVE' ? '同意利率' : '一票否决', {
      type: decision === 'APPROVE' ? 'info' : 'warning',
      confirmButtonText: decision === 'APPROVE' ? '确认同意' : '确认否决',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  submitting.value = true
  try {
    await submitPresidentDecision({
      applicationId: appId,
      decision,
      opinion: presidentOpinion.value?.trim() || undefined
    })
    ElMessage.success(decision === 'APPROVE' ? '已同意利率,申请终审通过' : '已一票否决')
    router.push('/president')
  } catch {
    load()
  } finally {
    submitting.value = false
  }
}

function downloadAttachment(a: any) {
  download(`/ccr/applications/${application.value.id}/attachments/${a.id}/download`)
}

function goBack() {
  router.push('/approval')
}

/** 返回列表(顶部「返回列表」按钮):行长返回行长工作台,其余角色返回审批列表 */
function goBackList() {
  if (isPresidentDecision.value) router.push('/president')
  else router.push('/approval')
}

// 审批提交成功提示:整单齐套终审→流程完结;推进→显示下一节点;未齐套→停留待整单齐套
function approveSuccessMsg(res?: ApprovalResult, nodeCode?: string): string {
  if (res?.terminal || !res?.nextNodeCode) {
    return '审批提交成功,本申请已终审通过'
  }
  if (res.nextNodeCode === nodeCode) {
    return '审批提交成功,已同意,待整单齐套后推进'
  }
  return `审批提交成功,已推进至「${nodeLabel(res.nextNodeCode)}」`
}

// 整单同意审批(2026-08-29):一次操作处理整单;调整利率统一应用到全部在途分项,不得越界
async function doApprove() {
  if (isLoan.value && opRate.value == null) {
    ElMessage.warning('请填写整单审批利率')
    return
  }
  const nodeCode = currentNodeCode.value
  if (!nodeCode) return
  submitting.value = true
  try {
    const res = await approveTask({
      applicationId: applicationId.value, // 雪花 id 传字符串,避免 JS 精度丢失
      nodeCode,
      adjustRate: wholeOrderRateChanged() ? opRate.value : null,
      comment: opComment.value || undefined,
      versionNo: application.value.versionNo
    }, newIdempotencyKey())
    ElMessage.success(approveSuccessMsg(res, nodeCode))
    goBack()
  } catch {
    load() // 版本冲突/已处理等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

// 整单交付改造(2026-08-29):一键通过 doApproveAll 移除,整单一次审批由 doApprove 承担

// 委员整单投票(2026-08-29):一批=一申请=整单票,投一次即整单票;一人一票投后不可改
async function doVote(choice: string) {
  const roundId = voteRound.value?.roundId
  if (!roundId) {
    ElMessage.warning('未找到当前表决轮次,请刷新后重试')
    return
  }
  if (choice === 'REJECT' && !opComment.value?.trim()) {
    try {
      await ElMessageBox.confirm('反对票未填写意见,建议补充以便后续环节参考,仍确认提交?', '反对意见缺失', {
        type: 'warning', confirmButtonText: '仍提交', cancelButtonText: '返回补充'
      })
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    await submitBallot(roundId, {
      applicationId: applicationId.value,
      choice,
      comment: opComment.value || undefined
    }, newIdempotencyKey())
    ElMessage.success(choice === 'APPROVE' ? '已提交同意票(整单)' : '已提交否决票(整单)')
    await load() // 刷新本人票与匿名汇总
  } catch {
    load() // 已投/轮次关闭等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

// 整单否决(2026-08-29):任一节点否决即整单否决,同申请全部分项一并退回;否决必填意见
async function doReject() {
  // P2-1:否决必填意见(否决后整单退回,客户经理凭意见了解否决原因)
  if (!opComment.value?.trim()) {
    ElMessage.warning('否决必须填写审批意见,以便客户经理了解否决原因')
    return
  }
  try {
    await ElMessageBox.confirm('确认否决该整单?任一节点否决即整单否决,同申请全部分项一并退回,流程直接结束。', '否决整单', {
      type: 'warning', confirmButtonText: '确认否决', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  const nodeCode = currentNodeCode.value
  if (!nodeCode) return
  submitting.value = true
  try {
    await rejectTask({
      applicationId: applicationId.value, // 雪花 id 传字符串,避免 JS 精度丢失
      nodeCode,
      comment: opComment.value || undefined,
      versionNo: application.value.versionNo
    }, newIdempotencyKey())
    ElMessage.warning('已否决,整单退回')
    goBack()
  } catch {
    load() // 版本冲突/已处理等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 数据来源提示(非冻结快照时的风险提示条) */
.source-note {
  margin-top: 12px; padding: 8px 12px;
  font-size: 13px; line-height: 1.6;
  color: #92400e; background: var(--color-warning-light);
  border: 1px solid #f5d58a; border-radius: var(--radius-sm);
}
/* 返回列表按钮(section-title 内嵌,ghost 小按钮) */
.btn--back {
  margin-right: 10px;
  padding: 6px 12px;
  font-size: 13px;
  border: 1px solid var(--color-border);
}
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
/* 表格自然宽:design-system .table 为 fit-content + overflow-x:auto,宽表(关联人 12 列/他行融资等)横向滚动,
   长文本列不再被 width:100% 压缩换行、行高失控(UI 审查 T9) */
.table { border-radius: var(--radius-sm); }
.detail-wrap { min-height: 200px; }
/* 吸底审批操作条为 fixed 定位:审批人可操作时给页尾留出条高,避免遮挡内容 */
.detail-wrap--action { padding-bottom: 96px; }
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
.op-item--rejected { background: var(--color-danger-light, #fef2f2); }
.op-item--passed { background: var(--color-bg); opacity: .85; }
.op-item--lock { background: var(--color-bg); opacity: .75; }
.op-item__head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 14px; }
.op-item__name { font-weight: 600; }
.op-item__subhead { margin-top: 12px; font-size: 13px; font-weight: 600; color: var(--color-text-sub); }
.op-item__empty-tip { margin-top: 8px; font-size: 12px; color: var(--color-text-sub); background: var(--color-bg); border-radius: 6px; padding: 8px 12px; }
.op-item__actions { display: flex; gap: 8px; margin-top: 8px; }
.op-item__passed-tip { margin-top: 8px; font-size: 12px; color: var(--color-text-sub); }
/* 流程路由链节点内标签与箭头(节点胶囊用 design-system .flow-node,此处仅补标签/待办/箭头) */
.flow-node__tag { margin-left: 6px; font-weight: 400; white-space: nowrap; }
.flow-node--todo { opacity: .6; }
.flow-node__arrow { color: var(--color-border); align-self: center; }
.warn-bar { background: var(--color-warning-light, #fef3c7); color: var(--color-warning); border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-bottom: 10px; }
/* 借据链接与弹窗提示(借据仅作参考) */
.link { color: var(--color-primary); cursor: pointer; text-decoration: underline; }
.link:hover { opacity: .8; }
.text-sub { color: var(--color-text-sub); }
.dlg-tip { margin-bottom: 10px; font-size: 13px; color: var(--color-text-sub); }
.rate-ok { color: var(--color-success); font-weight: 600; }
.rate-bad { color: var(--color-danger); font-weight: 600; }
.rate-warn { color: var(--color-warning); font-weight: 600; }
.muted { color: var(--color-text-secondary, #909399); }

/* 历史履约:按申请聚合总览 */
.overall { margin-bottom: 2px; }
.overall__sum { display: flex; flex-wrap: wrap; gap: 28px; }
.overall__sum-item { display: inline-flex; align-items: baseline; gap: 6px; font-size: 13px; color: var(--color-text-secondary, #606266); }
.overall__sum-item .metric-val__num { font-size: 18px; font-weight: 700; color: var(--color-text-primary, #303133); }
.overall__sum-item .unit { font-size: 12px; }

/* 历史履约:按申请行(可点击展开)+ 明细表 */
.app-row { cursor: pointer; }
.app-row:focus-visible { outline: 2px solid var(--color-primary); outline-offset: -2px; }
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
/* 行长决策卡(11d):六人表决结论分层(结论主/计票次/申请金额) */
.vote-summary { display: flex; flex-direction: column; gap: 8px; }
.vote-summary__main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.vote-summary__verdict { font-size: 15px; font-weight: 700; }
.vote-summary__verdict.is-pass { color: var(--color-success); }
.vote-summary__verdict.is-fail { color: var(--color-danger); }
.vote-summary__stats { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.vote-summary__meta { display: flex; align-items: baseline; gap: 18px; flex-wrap: wrap; padding-top: 2px; }
.vote-summary__meta .dg-label { margin-right: 4px; font-size: 12px; }
.vote-summary__meta b { font-weight: 600; color: var(--color-text-main); }
</style>
