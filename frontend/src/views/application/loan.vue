<template>
  <div>
    <div class="section-head">
      <div class="eyebrow">RATE APPLICATION · 贷款利率申请</div>
      <div class="section-title">贷款利率申请</div>
      <div class="section-tip">分步录入客户、融资情况、担保组合与利率定价、贡献承诺与材料附件;系统按权限矩阵自动识别审批路径,所有申请必经支行行长首节点(§7.1/§14.1)。</div>
    </div>

    <!-- 规则来源提示(§12.4⑦) -->
    <div class="rule-notice">
      规则来源:本页审批路径、权限矩阵与利率边界依据《关于调整经营性贷款利率审批流程的议案》配置;规则调整经规则版本发布生效后自动适用于新申请。
    </div>

    <!-- 步骤条(§14.1) -->
    <div class="stepper wizard-stepper">
      <div
        v-for="(s, i) in steps" :key="s"
        class="stepper__step"
        :class="{ 'stepper__step--active': i === step, 'stepper__step--done': i < step }"
        @click="goStep(i)"
      >
        <span class="stepper__dot">{{ i + 1 }}</span>
        <div>{{ s }}</div>
      </div>
    </div>

    <!-- 关联重提提示 -->
    <div v-if="draft.id" class="form-card draft-banner">
      <span class="badge badge--info">草稿</span>
      申请号 {{ draft.applicationNo }}(版本 v{{ draft.versionNo }})
      <template v-if="inheritCount > 0">· {{ inheritCount }} 条已批准分项沿用原决议,不在本页重复编辑</template>
      <span class="section-tip">· 草稿保存仅更新主单信息,分项/成员/承诺以创建时内容为准</span>
    </div>

    <!-- 第一步:客户信息(个人/企业单户/集团三选) -->
    <div v-show="step === 0" class="form-card">
      <div class="form-card__title">客户信息</div>
      <div class="section-tip" style="margin-bottom:12px">客户基本信息由数仓统一提供(caps_corp/indv_cust_basic_info),带出后只读展示;集团客户输入集团号查询数仓集团快照。</div>
      <div class="form-grid">
        <div class="form-field">
          <label class="form-field__label">客户主体 <span class="req">*</span></label>
          <select class="form-select" v-model="form.customerScope" @change="onCustomerScopeChange">
            <option value="CORPORATE">企业单户</option>
            <option value="INDIVIDUAL">个人</option>
            <option value="GROUP">集团客户</option>
          </select>
        </div>

        <!-- 单户:客户姓名模糊查询带出 -->
        <template v-if="form.customerScope !== 'GROUP'">
          <div class="form-field" style="grid-column: span 2">
            <label class="form-field__label">客户名称 <span class="req">*</span></label>
            <div style="display:flex;gap:8px">
              <input class="form-input" v-model="form.customerName" placeholder="输入客户名称模糊查询" @keyup.enter="searchCustomers" />
              <button class="btn btn--secondary" @click="searchCustomers">查询</button>
            </div>
            <div class="customer-cands" v-if="customerCands.length">
              <div v-for="c in customerCands" :key="c.customerNo" class="customer-cand" @click="selectCustomer(c)">
                {{ c.customerName }} · {{ c.customerNo }} · {{ c.custType === 'CORP' ? '对公' : '个人' }}
              </div>
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">客户号</label>
            <input class="form-input" v-model="form.customerNo" disabled placeholder="查询带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">客户性质</label>
            <select class="form-select" v-model="form.customerNature">
              <option value="EXISTING">存量客户(有历史贷款余额)</option>
              <option value="NEW">新增客户(无历史贷款余额)</option>
            </select>
          </div>
        </template>
      </div>

      <!-- 集团客户区块:集团号查询 → 集团授信总额(只读) + 有效成员列表 -->
      <template v-if="form.customerScope === 'GROUP'">
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2">
            <label class="form-field__label">集团客户编号 <span class="req">*</span></label>
            <div style="display:flex;gap:8px">
              <input class="form-input" v-model="form.groupNo" placeholder="输入集团客户编号,如 GROUP001" @keyup.enter="queryGroup" />
              <button class="btn btn--secondary" @click="queryGroup">查询</button>
            </div>
          </div>
        </div>
        <div v-if="groupInfo" class="group-summary">
          <div class="group-summary__item"><span>集团名称</span><b>{{ groupInfo.groupName || '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>集团状态</span><b>{{ groupInfo.groupStatus === 'NORMAL' ? '正常' : (groupInfo.groupStatus || '暂无数据') }}</b></div>
          <div class="group-summary__item"><span>授信总额(万元)</span><b>{{ groupCredit?.approvedTotalAmount ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>已分配额度(万元)</span><b>{{ groupAllocatedTotal ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>可用额度(万元)</span><b>{{ groupCredit?.availableAmount ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>授信到期日</span><b>{{ groupCredit?.creditEnd || '暂无数据' }}</b></div>
        </div>
        <div v-if="groupMembers.length" class="form-field" style="margin-top:12px">
          <label class="form-field__label">涉及成员(勾选并逐成员录入本次申请金额) <span class="req">*</span></label>
          <table class="table">
            <thead>
              <tr>
                <th>选择</th><th>成员客户号</th><th>成员名称</th><th>角色</th>
                <th>分配额度(万元)</th><th>可用额度(万元)</th>
                <th>本次申请金额(万元) <span class="req">*</span></th><th>币种</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in groupMembers" :key="m.memberCustomerNo">
                <td><input type="checkbox" :checked="isMemberChecked(m.memberCustomerNo)" @change="toggleMember(m)" /></td>
                <td>{{ m.memberCustomerNo }}</td>
                <td>{{ m.memberName || '暂无数据' }}</td>
                <td><span class="badge badge--neutral">{{ m.memberRole === 'CORE' ? '核心' : '一般' }}</span></td>
                <td class="num">{{ m.creditLimit?.allocatedAmount ?? '暂无数据' }}</td>
                <td class="num">{{ m.creditLimit?.availableAmount ?? '暂无数据' }}</td>
                <td>
                  <input
                    class="form-input form-input--amount"
                    :disabled="!isMemberChecked(m.memberCustomerNo)"
                    :value="memberInput(m.memberCustomerNo)?.requestAmount"
                    @input="setMemberField(m.memberCustomerNo, 'requestAmount', ($event.target as HTMLInputElement).value)"
                  />
                </td>
                <td>
                  <select
                    class="form-select"
                    :disabled="!isMemberChecked(m.memberCustomerNo)"
                    :value="memberInput(m.memberCustomerNo)?.currency || 'CNY'"
                    @change="setMemberField(m.memberCustomerNo, 'currency', ($event.target as HTMLSelectElement).value)"
                  >
                    <option v-for="c in currencies" :key="c" :value="c">{{ c }}</option>
                  </select>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="groupQueried" class="empty">该集团暂无有效成员数据</div>
      </template>

      <!-- 单户基本信息(数仓带出,只读) -->
      <div class="form-grid" v-if="form.customerScope !== 'GROUP'" style="margin-top:4px">
        <template v-if="form.customerScope !== 'INDIVIDUAL'">
          <div class="form-field">
            <label class="form-field__label">企业性质</label>
            <select class="form-select" v-model="form.customerType">
              <option value="NON_SOE">非国企</option>
              <option value="SOE">国企</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">统一社会信用代码</label>
            <input class="form-input" v-model="form.ucrCode" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五级分类</label>
            <input class="form-input" v-model="form.fiveLevelClass" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">内部信用等级</label>
            <input class="form-input" v-model="form.creditLevel" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">所属行业</label>
            <input class="form-input" v-model="form.industry" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">注册资本(万元)</label>
            <input class="form-input form-input--amount" v-model="form.registeredCapital" disabled placeholder="数仓带出" />
          </div>
        </template>
        <template v-else>
          <div class="form-field">
            <label class="form-field__label">证件类型</label>
            <input class="form-input" v-model="form.idType" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">证件号码</label>
            <input class="form-input" v-model="form.idNo" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">职业</label>
            <input class="form-input" v-model="form.occupation" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">年收入(万元)</label>
            <input class="form-input form-input--amount" v-model="form.annualIncome" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">婚姻状况</label>
            <input class="form-input" v-model="form.maritalStatus" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">联系电话</label>
            <input class="form-input" v-model="form.phone" disabled placeholder="数仓带出" />
          </div>
        </template>
        <div class="form-field">
          <label class="form-field__label">开户机构</label>
          <input class="form-input" v-model="form.openOrg" disabled placeholder="数仓带出" />
        </div>
        <div class="form-field">
          <label class="form-field__label">开户日期</label>
          <input class="form-input" v-model="form.openDate" disabled placeholder="数仓带出" />
        </div>
        <div class="form-field">
          <label class="form-field__label">申请机构</label>
          <input class="form-input" :value="applyOrgText" disabled />
        </div>
      </div>

      <!-- 关联人员(§12.4④,随申请备注结构附带提交) -->
      <RelatedPersonsEditor v-model="relations" style="margin-top:16px" />

      <div class="wizard-actions">
        <span></span>
        <button class="btn btn--primary" @click="goNext(1)">下一步:融资情况</button>
      </div>
    </div>

    <!-- 第二步:业务/合同(融资情况:本行融资+他行融资) -->
    <div v-show="step === 1" class="form-card">
      <div class="form-card__title">融资情况</div>
      <div class="form-grid">
        <div class="form-field">
          <label class="form-field__label">贷款品种 <span class="req">*</span></label>
          <select class="form-select" v-model="form.loanType">
            <option value="CORP_LOAN">对公贷款</option>
            <option value="PERSONAL_LOAN">个人经营性贷款</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label">业务类型 <span class="req">*</span></label>
          <select class="form-select" v-model="form.businessType">
            <option value="EXISTING">存量调息(选择现有贷款合同)</option>
            <option value="NEW">新增授信(拟签合同)</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label">金额档 <span class="req">*</span></label>
          <select class="form-select" v-model="form.amountTier">
            <option value="GE_5000">5000万以上(含)贷款</option>
            <option value="LT_5000">5000万以下贷款</option>
          </select>
          <div v-if="form.amountTier === 'GE_5000'" class="section-tip" style="color:var(--color-warning);margin-top:6px">
            5000万以上贷款必经六人小组表决 + 总行行长决策(§8A.5②),请确认材料齐全
          </div>
        </div>
      </div>

      <!-- 本行融资(数仓带出,合同选择来源) -->
      <template v-if="form.customerScope !== 'GROUP'">
        <div class="sub-title">本行融资 <span class="badge badge--info">数仓取数</span></div>
        <table class="table" v-if="ownFinancing.length">
          <thead>
            <tr><th>合同号</th><th>贷款余额(万元)</th><th>执行利率</th><th>担保类型</th></tr>
          </thead>
          <tbody>
            <tr v-for="f in ownFinancing" :key="f.contractNo">
              <td>{{ f.contractNo }}</td>
              <td class="num">{{ f.loanBalance ?? '暂无数据' }}</td>
              <td class="num">{{ f.contractRate != null ? f.contractRate + '%' : '暂无数据' }}</td>
              <td>{{ guaranteeTypeText(f.guaranteeType, '暂无数据') }}</td>
            </tr>
          </tbody>
        </table>
        <div class="empty" v-else>暂无本行融资数据(请先选择客户)</div>
      </template>
      <template v-else>
        <div class="sub-title">成员合同 <span class="badge badge--info">数仓取数</span></div>
        <table class="table" v-if="groupContractRows.length">
          <thead>
            <tr><th>成员</th><th>合同号</th><th>合同余额(万元)</th><th>执行利率</th><th>到期日</th><th>合同下借据数</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in groupContractRows" :key="i">
              <td>{{ r.memberName }}</td>
              <td>{{ r.contractNo }}</td>
              <td class="num">{{ r.contractBalance ?? '暂无数据' }}</td>
              <td class="num">{{ r.executionRate != null ? r.executionRate + '%' : '暂无数据' }}</td>
              <td>{{ r.maturityDate || '暂无数据' }}</td>
              <td class="num">{{ r.noteCount }}</td>
            </tr>
          </tbody>
        </table>
        <div class="empty" v-else>勾选涉及成员后自动带出成员名下贷款合同</div>
      </template>

      <!-- 他行融资概要/明细(数仓+人工补录,Excel 导入在材料附件步骤) -->
      <div class="sub-title" style="margin-top:20px">他行融资概要</div>
      <table class="table" v-if="otherSummary">
        <thead>
          <tr>
            <th>授信机构数</th><th>他行授信总额(万元)</th><th>已用额度合计(万元)</th>
            <th>逾期账户数</th><th>不良贷款余额(万元)</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td class="num">{{ otherSummary.lenderCount ?? '暂无数据' }}</td>
            <td class="num">{{ otherSummary.creditAmountTotal ?? '暂无数据' }}</td>
            <td class="num">{{ otherSummary.usedAmountTotal ?? '暂无数据' }}</td>
            <td class="num">{{ otherSummary.overdueAccountCount ?? '暂无数据' }}</td>
            <td class="num">{{ otherSummary.nplBalance ?? '暂无数据' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂无他行融资概要数据</div>

      <div class="sub-title" style="margin-top:20px">他行融资明细</div>
      <table class="table" v-if="otherLoans.length">
        <thead>
          <tr><th>融资机构</th><th>授信额(万元)</th><th>已用额(万元)</th><th>余额(万元)</th><th>年化利率</th><th>来源</th></tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in otherLoans" :key="i">
            <td><input class="form-input" v-model="d.lenderName" :disabled="d.inputMode !== 'MANUAL'" /></td>
            <td><input class="form-input form-input--amount" v-model="d.creditAmount" :disabled="d.inputMode !== 'MANUAL'" /></td>
            <td><input class="form-input form-input--amount" v-model="d.usedAmount" :disabled="d.inputMode !== 'MANUAL'" /></td>
            <td><input class="form-input form-input--amount" v-model="d.balanceAmount" :disabled="d.inputMode !== 'MANUAL'" /></td>
            <td><input class="form-input form-input--amount" v-model="d.annualRate" :disabled="d.inputMode !== 'MANUAL'" /></td>
            <td><span class="badge badge--neutral">{{ inputModeText(d.inputMode) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂无他行融资明细(可人工补录或 Excel 导入)</div>
      <div style="display:flex;gap:8px;margin-top:12px;align-items:center">
        <button class="btn btn--secondary" @click="addOtherLoan">＋ 添加他行融资</button>
        <button class="btn btn--secondary" @click="triggerImport">📄 Excel 导入</button>
        <a class="btn btn--text" href="/templates/other-loans-template.xlsx" download="他行融资明细导入模板.xlsx">⬇ 模板下载</a>
        <span class="section-tip">列顺序:融资机构 | 授信额(万元) | 已用额(万元) | 余额(万元) | 年化利率%</span>
      </div>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onImportFile" />

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 0">上一步</button>
        <button class="btn btn--primary" @click="goNext(2)">下一步:担保组合与利率定价</button>
      </div>
    </div>

    <!-- 第三步:担保组合与利率定价(按成员×合同切分,逐担保方式;执行利率集中在分项录入) -->
    <div v-show="step === 2" class="form-card">
      <div class="form-card__title">
        担保组合与利率定价
        <span class="badge badge--info">逐担保方式独立路由/表决</span>
        <span class="badge badge--warning">贷款利率越低越优惠</span>
      </div>
      <div class="section-tip" style="margin-bottom:12px">
        <template v-if="form.businessType === 'EXISTING'">存量授信:每个贷款合同对应一个担保方式,按合同切分授信额度(原利率取合同执行利率)。</template>
        <template v-else>新增授信:尚无贷款合同,按担保方式切分授信额度,审批通过后回填正式合同(拟签合同)。</template>
        集团场景按“成员 × 合同”生成分项;申请利率不得低于产品硬边界,突破将被提交校验阻断。
      </div>

      <!-- 总授信额度:存量按所选合同金额自动带出;新增由客户经理手工录入,拆分细项合计与总额核对 -->
      <div class="credit-overview">
        <div class="credit-overview__item" v-if="form.businessType === 'EXISTING'">
          <span>总授信额度(万元,按合同带出)</span><b>{{ guaranteesTotalText }}</b>
        </div>
        <div class="credit-overview__item" v-else>
          <span>总授信额度(万元)</span>
          <input class="form-input form-input--amount" style="width:160px" v-model="form.totalCredit" placeholder="手工录入" />
        </div>
        <div class="credit-overview__item" v-if="form.businessType !== 'EXISTING'">
          <span>拆分细项合计(万元)</span><b :style="totalMismatch ? 'color:var(--color-danger)' : ''">{{ guaranteesTotalText }}</b>
        </div>
        <div class="credit-overview__item" v-if="form.businessType !== 'EXISTING' && totalMismatch">
          <span class="section-tip" style="color:var(--color-danger)">拆分合计与总授信额度不一致,请核对</span>
        </div>
        <template v-if="form.customerScope === 'GROUP'">
          <div class="credit-overview__item"><span>集团批复总额度(万元)</span><b>{{ groupCredit?.approvedTotalAmount ?? '暂无数据' }}</b></div>
          <div class="credit-overview__item"><span>集团可用额度(万元)</span><b>{{ groupCredit?.availableAmount ?? '暂无数据' }}</b></div>
        </template>
      </div>
      <div v-if="overGroupAvailable" class="credit-overview-warning">
        分项金额合计已超过集团可用额度,请调整分项金额;是否超授以服务端提交校验为准。
      </div>

      <!-- 担保分项卡片:同一 form.guarantees 行承载担保方式/合同/措施明细 + 产品/期限/金额/利率 -->
      <div v-for="(g, idx) in form.guarantees" :key="idx" class="mortgage-item guarantee-item">
        <div class="mortgage-item__head">
          <span class="guarantee-item__title">
            分项 {{ idx + 1 }}
            <span v-if="g.guaranteeType === 'MORTGAGE'" class="badge badge--neutral">抵押物 {{ g.mortgages.length }} 项</span>
            <span v-else-if="g.guaranteeType === 'GUARANTEE'" class="badge badge--neutral">保证人 {{ g.guarantors.length }} 人</span>
            <span v-else-if="g.guaranteeType === 'PLEDGE'" class="badge badge--neutral">质押物 {{ g.pledges.length }} 项</span>
            <span v-else-if="g.guaranteeType === 'BILL_MARGIN' || g.guaranteeType === 'CREDIT_MARGIN'" class="badge badge--neutral">保证金 {{ g.margins.length }} 笔</span>
            <span v-else-if="g.guaranteeType === 'CERTIFICATE_DEPOSIT'" class="badge badge--neutral">存单 {{ g.cds.length }} 张</span>
            <span v-else class="badge badge--neutral">无需措施</span>
          </span>
          <button class="btn btn--text" @click="removeGuarantee(idx)" v-if="form.guarantees.length > 1">删除</button>
        </div>
        <!-- 第一行:担保方式/成员(集团)/贷款合同 -->
        <div class="form-grid mortgage-item__grid">
          <div class="form-field">
            <label class="form-field__label">担保方式 <span class="req">*</span></label>
            <select class="form-select" v-model="g.guaranteeType">
              <option v-for="t in guaranteeTypes" :key="t.code" :value="t.code">{{ t.name }}</option>
            </select>
          </div>
          <div class="form-field" v-if="form.customerScope === 'GROUP'">
            <label class="form-field__label">涉及成员 <span class="req">*</span></label>
            <select class="form-select" v-model="g.memberCustomerNo" @change="g.contractBusinessKey = ''">
              <option value="" disabled>选择成员</option>
              <option v-for="m in selectedMembers" :key="m.memberCustomerNo" :value="m.memberCustomerNo">
                {{ m.memberName || m.memberCustomerNo }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">贷款合同 <span class="req">*</span></label>
            <template v-if="form.businessType === 'EXISTING'">
              <select class="form-select" v-model="g.contractBusinessKey" @change="onContractSelect(g)">
                <option value="" disabled>选择合同</option>
                <option v-for="c in contractOptions(g)" :key="c.contractNo" :value="c.contractNo">
                  {{ c.contractNo }}(余额 {{ c.contractBalance ?? c.loanBalance ?? '-' }} 万)
                </option>
              </select>
            </template>
            <template v-else>
              <input class="form-input" v-model="g.contractBusinessKey" placeholder="拟签合同标识(留空自动生成)" />
              <span class="badge badge--neutral" style="margin-top:4px">拟签合同</span>
            </template>
          </div>
        </div>
        <!-- 第二行:产品/期限/金额/原利率(存量带出只读)/申请利率/币种 -->
        <div class="form-grid mortgage-item__grid" style="margin-top:10px">
          <div class="form-field">
            <label class="form-field__label">产品 <span class="req">*</span></label>
            <select class="form-select" v-model="g.productCode">
              <option value="" disabled>选择产品</option>
              <option v-for="p in loanProducts" :key="p.code" :value="p.code">{{ p.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">期限 <span class="req">*</span></label>
            <div style="display:flex;gap:4px">
              <input class="form-input form-input--amount" v-model="g.termValue" placeholder="数值" style="width:80px" />
              <select class="form-select" v-model="g.termUnit" style="width:80px">
                <option value="DAY">天</option><option value="MONTH">月</option><option value="YEAR">年</option>
              </select>
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">授信金额(万元) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="g.amount" />
          </div>
          <div class="form-field" v-if="form.businessType === 'EXISTING'">
            <label class="form-field__label">原利率(%)</label>
            <input class="form-input form-input--amount" v-model="g.originalRate" disabled placeholder="合同带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="g.requestedRate" placeholder="如 3.40" />
          </div>
          <div class="form-field">
            <label class="form-field__label">币种</label>
            <select class="form-select" v-model="g.currency">
              <option v-for="c in currencies" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
        </div>
        <!-- 抵押物明细 -->
        <div v-if="g.guaranteeType === 'MORTGAGE'" class="guarantee-detail-block">
          <div class="detail-title">抵押物(关联本分项) <button class="btn btn--text" @click="addGuaranteeMortgage(g)">＋ 添加抵押物</button></div>
                <div v-for="(m, mi) in g.mortgages" :key="mi" class="mortgage-item">
                  <div class="mortgage-item__head">
                    <select class="form-select" style="width:140px" v-model="m.type"><option>住宅</option><option>厂房</option><option>土地</option><option>设备</option><option>车辆</option></select>
                    <button class="btn btn--text" @click="g.mortgages.splice(mi, 1)">删除</button>
                  </div>
                  <div class="form-grid mortgage-item__grid">
                    <!-- 不动产(住宅/厂房/土地)共有:名称+坐落+面积 -->
                    <div class="form-field"><label class="form-field__label">{{ m.type === '土地' ? '地块名称' : m.type === '设备' ? '设备名称' : m.type === '车辆' ? '品牌型号' : '名称' }}</label><input class="form-input" v-model="m.name" /></div>
                    <template v-if="m.type === '住宅' || m.type === '厂房'">
                      <div class="form-field"><label class="form-field__label">坐落位置</label><input class="form-input" v-model="m.addr" /></div>
                      <div class="form-field"><label class="form-field__label">建筑面积(㎡)</label><input class="form-input form-input--amount" v-model="m.area" /></div>
                      <div class="form-field"><label class="form-field__label">产权证号</label><input class="form-input" v-model="m.certNo" /></div>
                    </template>
                    <template v-else-if="m.type === '土地'">
                      <div class="form-field"><label class="form-field__label">坐落位置</label><input class="form-input" v-model="m.addr" /></div>
                      <div class="form-field"><label class="form-field__label">土地面积(㎡)</label><input class="form-input form-input--amount" v-model="m.area" /></div>
                      <div class="form-field"><label class="form-field__label">使用权类型</label><select class="form-select" v-model="m.landUseType"><option>出让</option><option>划拨</option></select></div>
                      <div class="form-field"><label class="form-field__label">使用权到期日</label><input class="form-input" type="date" v-model="m.landUseExpiry" /></div>
                    </template>
                    <template v-else-if="m.type === '设备'">
                      <div class="form-field"><label class="form-field__label">规格型号</label><input class="form-input" v-model="m.specModel" /></div>
                      <div class="form-field"><label class="form-field__label">数量(台/套)</label><input class="form-input form-input--amount" v-model="m.quantity" /></div>
                      <div class="form-field"><label class="form-field__label">购置日期</label><input class="form-input" type="date" v-model="m.purchaseDate" /></div>
                    </template>
                    <template v-else-if="m.type === '车辆'">
                      <div class="form-field"><label class="form-field__label">车牌号</label><input class="form-input" v-model="m.plateNo" /></div>
                      <div class="form-field"><label class="form-field__label">车架号(VIN)</label><input class="form-input" v-model="m.vin" /></div>
                      <div class="form-field"><label class="form-field__label">登记日期</label><input class="form-input" type="date" v-model="m.regDate" /></div>
                    </template>
                    <div class="form-field"><label class="form-field__label">评估价值(万元)</label><input class="form-input form-input--amount" v-model="m.value" /></div>
                    <div class="form-field"><label class="form-field__label">权属人</label><input class="form-input" v-model="m.owner" /></div>
                    <div class="form-field"><label class="form-field__label">抵押率(%)</label><input class="form-input form-input--amount" v-model="m.ratio" /></div>
                  </div>
                </div>
        </div>
        <!-- 质押物明细 -->
        <div v-if="g.guaranteeType === 'PLEDGE'" class="guarantee-detail-block">
          <div class="detail-title">质押物(关联本分项) <button class="btn btn--text" @click="addPledge(g)">＋ 添加质押物</button></div>
                <table class="table table--nested" v-if="g.pledges.length">
                  <thead><tr><th>质押物类型</th><th>名称</th><th>估值(万元)</th><th>权属人</th><th></th></tr></thead>
                  <tbody>
                    <tr v-for="(m, mi) in g.pledges" :key="mi">
                      <td><select class="form-select" v-model="m.type"><option>存单</option><option>股权</option><option>应收账款</option><option>存货</option><option>仓单</option></select></td>
                      <td><input class="form-input" v-model="m.name" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.value" /></td>
                      <td><input class="form-input" v-model="m.owner" /></td>
                      <td><button class="btn btn--text" @click="g.pledges.splice(mi, 1)">删除</button></td>
                    </tr>
                  </tbody>
                </table>
        </div>
        <!-- 保证金明细(银票/信用证) -->
        <div v-if="g.guaranteeType === 'BILL_MARGIN' || g.guaranteeType === 'CREDIT_MARGIN'" class="guarantee-detail-block">
          <div class="detail-title">保证金(关联本分项) <button class="btn btn--text" @click="addMargin(g)">＋ 添加保证金</button></div>
                <table class="table table--nested" v-if="g.margins.length">
                  <thead><tr><th>保证金金额(万元)</th><th>保证金比例(%)</th><th>期限(月)</th><th></th></tr></thead>
                  <tbody>
                    <tr v-for="(m, mi) in g.margins" :key="mi">
                      <td><input class="form-input form-input--amount" v-model="m.amount" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.ratio" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.term" /></td>
                      <td><button class="btn btn--text" @click="g.margins.splice(mi, 1)">删除</button></td>
                    </tr>
                  </tbody>
                </table>
        </div>
        <!-- 存单质押明细 -->
        <div v-if="g.guaranteeType === 'CERTIFICATE_DEPOSIT'" class="guarantee-detail-block">
          <div class="detail-title">存单质押(关联本分项) <button class="btn btn--text" @click="addCd(g)">＋ 添加存单</button></div>
                <table class="table table--nested" v-if="g.cds.length">
                  <thead><tr><th>存单号</th><th>金额(万元)</th><th>到期日</th><th></th></tr></thead>
                  <tbody>
                    <tr v-for="(m, mi) in g.cds" :key="mi">
                      <td><input class="form-input" v-model="m.cdNo" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.amount" /></td>
                      <td><input class="form-input" type="date" v-model="m.maturityDate" /></td>
                      <td><button class="btn btn--text" @click="g.cds.splice(mi, 1)">删除</button></td>
                    </tr>
                  </tbody>
                </table>
        </div>
        <!-- 保证人明细 -->
        <div v-if="g.guaranteeType === 'GUARANTEE'" class="guarantee-detail-block">
          <div class="detail-title">保证人(关联本分项) <button class="btn btn--text" @click="addGuaranteeGuarantor(g)">＋ 添加保证人</button></div>
                <table class="table table--nested" v-if="g.guarantors.length">
                  <thead><tr><th>保证人名称</th><th>证件号码</th><th>担保金额(万元)</th><th>担保余额(万元)</th><th></th></tr></thead>
                  <tbody>
                    <tr v-for="(gt, gi) in g.guarantors" :key="gi">
                      <td><input class="form-input" v-model="gt.name" /></td>
                      <td><input class="form-input" v-model="gt.certNo" /></td>
                      <td><input class="form-input form-input--amount" v-model="gt.amount" /></td>
                      <td><input class="form-input form-input--amount" v-model="gt.balance" /></td>
                      <td><button class="btn btn--text" @click="g.guarantors.splice(gi, 1)">删除</button></td>
                    </tr>
                  </tbody>
                </table>
        </div>
      </div>
      <button class="btn btn--secondary" style="margin-top:12px" @click="addGuarantee">＋ 添加担保分项</button>

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 1">上一步</button>
        <button class="btn btn--primary" @click="goNext(3)">下一步:贡献承诺</button>
      </div>
    </div>

    <!-- 第四步:贡献承诺(拟达成贡献度,随申请提交 commitments) -->
    <div v-show="step === 3" class="form-card">
      <div class="form-card__title">
        贡献承诺
        <span class="badge badge--warning">拟达成贡献度 · 承诺基线</span>
      </div>
      <div class="section-tip" style="margin-bottom:12px">
        下拉选择承诺指标,参照数仓当前贡献度录入基线与拟达成目标;承诺随申请提交(commitments),审批通过后生成正式承诺计划跟踪。
      </div>

      <div class="sub-title">当前贡献度参考 <span class="badge badge--info">数仓取数</span></div>
      <ContributionPanel :contribution="contributionCurrent" :show-commitments="false" />

      <div class="sub-title">拟达成承诺</div>
      <table class="table" v-if="commitments.length">
        <thead>
          <tr>
            <th>承诺指标 <span class="req">*</span></th>
            <th>当前贡献度</th>
            <th>目标类型 <span class="req">*</span></th>
            <th>基线值</th><th>拟达成目标 <span class="req">*</span></th>
            <th>单位</th><th>适用范围</th>
            <th v-if="form.customerScope === 'GROUP'">成员</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in commitments" :key="i">
            <td>
              <select class="form-select" v-model="c.metricCode">
                <option v-for="m in metricDict" :key="m.code" :value="m.code">{{ m.name }}</option>
              </select>
            </td>
            <td class="num">{{ c.metricCode === 'OTHER' ? '—' : (currentOf(c.metricCode) ?? '—') }}</td>
            <td>
              <span v-if="c.metricCode === 'OTHER'" class="badge badge--neutral">手工描述</span>
              <select v-else class="form-select" v-model="c.targetType">
                <option value="TARGET_BALANCE">目标余额</option>
                <option value="INCREMENT">承诺新增</option>
                <option value="CUMULATIVE">期间累计</option>
              </select>
            </td>
            <td>
              <input v-if="c.metricCode !== 'OTHER'" class="form-input form-input--amount" v-model="c.baselineValue" placeholder="可空" />
              <span v-else class="section-tip">—</span>
            </td>
            <td>
              <template v-if="c.metricCode === 'OTHER'">
                <input class="form-input" v-model="c.commitmentDesc" placeholder="目标描述(金额或文本,§6.4)" />
                <div class="section-tip" style="color:var(--color-warning);margin-top:4px">手工描述跟踪,无数值达成率,不参与机构达成率</div>
              </template>
              <input v-else class="form-input form-input--amount" v-model="c.targetValue" />
            </td>
            <td>
              <template v-if="c.metricCode === 'OTHER'"><span class="section-tip">—</span></template>
              <select v-else class="form-select" v-model="c.unit">
                <option value="WAN_YUAN">万元</option>
                <option value="COUNT">户/笔</option>
              </select>
            </td>
            <td>
              <select class="form-select" v-model="c.metricScope">
                <option value="PUBLIC">对公</option>
                <option value="PRIVATE_SELF">本人对私</option>
                <option value="RELATED">关联人</option>
                <option value="GROUP">集团</option>
                <option value="GROUP_MEMBER">集团成员</option>
              </select>
            </td>
            <td v-if="form.customerScope === 'GROUP'">
              <select class="form-select" v-model="c.memberCustomerNo">
                <option value="">集团整体</option>
                <option v-for="m in selectedMembers" :key="m.memberCustomerNo" :value="m.memberCustomerNo">
                  {{ m.memberName || m.memberCustomerNo }}
                </option>
              </select>
            </td>
            <td><button class="btn btn--text" @click="commitments.splice(i, 1)">删除</button></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂未录入承诺,可点击下方按钮添加</div>
      <button class="btn btn--secondary" style="margin-top:8px" @click="addCommitment">＋ 添加承诺指标</button>

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 2">上一步</button>
        <button class="btn btn--primary" @click="goNext(4)">下一步:材料附件</button>
      </div>
    </div>

    <!-- 第五步:材料附件(他行融资 Excel 导入 + 其他附件前端暂存) -->
    <div v-show="step === 4" class="form-card">
      <div class="form-card__title">材料附件</div>

      <div class="sub-title">其他申请附件</div>
      <div class="section-tip" style="margin-bottom:8px">后端暂无通用附件上传接口,附件在前端暂存(base64),不随申请单上传、不阻断提交流程;上传接口就绪后自动接入。</div>
      <button class="btn btn--secondary" @click="attachmentInput?.click()">＋ 添加附件</button>
      <input ref="attachmentInput" type="file" multiple style="display:none" @change="onAttachmentFiles" />
      <table class="table" v-if="attachments.length" style="margin-top:12px">
        <thead><tr><th>文件名</th><th>大小</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in attachments" :key="i">
            <td>{{ a.name }}</td>
            <td class="num">{{ (a.size / 1024).toFixed(1) }} KB</td>
            <td><span class="badge" :class="a.uploaded ? 'badge--success' : 'badge--warning'">{{ a.uploaded ? '已上传' : '待上传' }}</span></td>
            <td><button class="btn btn--text" @click="attachments.splice(i, 1)">移除</button></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂无附件</div>

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 3">上一步</button>
        <button class="btn btn--primary" @click="goNext(5)">下一步:提交预览</button>
      </div>
    </div>

    <!-- 第六步:提交预览(路由预览 + 提交校验确认 + 正式提交) -->
    <div v-show="step === 5" class="form-card">
      <div class="form-card__title">提交预览</div>
      <div class="section-tip" style="margin-bottom:12px">提交前先生成/保存草稿,再逐分项预览审批路由;正式提交需通过数据批次差异与质量预校验确认(§7.1 步骤9-11)。</div>
      <div class="form-field">
        <label class="form-field__label">申请备注(客户经理手工描述,展示在审批界面)</label>
        <textarea class="form-input" v-model="form.applicationRemark" rows="3" placeholder="可描述申请背景、特殊情况等" style="width:100%;resize:vertical"></textarea>
      </div>

      <!-- 路由预览结果(真实接口逐分项) -->
      <template v-if="routeResult">
        <div class="sub-title">
          审批路由预览
          <span class="badge badge--info">LPR 版本:{{ routeResult.lprVersionCode || '暂无数据' }}</span>
          <span v-if="routeResult.groupCreditTotal != null" class="badge badge--neutral">集团授信总额 {{ routeResult.groupCreditTotal }} 万</span>
        </div>
        <table class="table" v-if="routeResult.items?.length">
          <thead>
            <tr><th>分项编号</th><th v-if="form.customerScope === 'GROUP'">成员</th><th>产品</th><th>申请利率</th><th>比较方向</th><th>路由链路</th><th>终审岗位</th><th>硬边界</th></tr>
          </thead>
          <tbody>
            <tr v-for="it in routeResult.items" :key="it.pricingItemId">
              <td>{{ it.pricingItemNo }}</td>
              <td v-if="form.customerScope === 'GROUP'">{{ memberNameOf(it.memberCustomerNo || '') }}</td>
              <td>{{ productName(it.productCode) }}</td>
              <td class="num">{{ it.requestedRate != null ? it.requestedRate + '%' : '—' }}</td>
              <td>{{ rateDirectionText(it.rateDirection) }}</td>
              <td>
                <template v-if="it.errorCode">
                  <span class="badge badge--danger">路由失败:{{ it.errorMessage || it.errorCode }}</span>
                </template>
                <template v-else-if="it.routeChain?.length">
                  <span v-for="(n, ni) in it.routeChain" :key="ni">
                    <span class="route-node">{{ nodeLabel(n) }}</span><span v-if="ni < it.routeChain.length - 1"> → </span>
                  </span>
                </template>
                <span v-else>暂无数据</span>
              </td>
              <td>{{ nodeLabel(it.finalNodeCode) }}</td>
              <td>
                <span v-if="it.hardBoundaryPass === true" class="badge badge--success">通过({{ it.hardBoundaryRate }}%)</span>
                <span v-else-if="it.hardBoundaryPass === false" class="badge badge--danger">突破({{ it.hardBoundaryRate }}%)</span>
                <span v-else class="badge badge--neutral">暂无数据</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="empty" v-else>暂无定价分项,无法预览路由</div>
      </template>

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 4">上一步</button>
        <div style="display:flex;gap:12px">
          <button class="btn btn--secondary" :disabled="saving" @click="onSaveDraft">存草稿</button>
          <button class="btn btn--secondary" :disabled="saving" @click="onRoutePreview">路由预览</button>
          <button class="btn btn--primary" :disabled="saving" @click="onSubmit">提交申请</button>
        </div>
      </div>
    </div>

    <!-- 提交前校验确认弹窗 -->
    <SubmitCheckDialog v-model="checkDialogVisible" :check="checkResult" :submitting="submitting" @confirm="onConfirmSubmit" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  searchCustomers as apiSearchCustomers,
  getCustomerDetail,
  getGroup,
  getGroupMembers,
  getMemberCreditView,
  createApplication,
  saveApplication,
  getApplicationDetail,
  routePreview,
  uploadAttachment,
  listAttachments,
  submitCheck,
  submitApplication,
  reapplyApplication,
  importOtherLoans,
  type ApplicationPayload,
  type GuaranteeMeasureInput,
  type RoutePreview,
  type SubmitCheck
} from '@/api/application'
import SubmitCheckDialog from './SubmitCheckDialog.vue'
import {
  GUARANTEE_TYPES, guaranteeTypeText, nodeLabel, rateDirectionText,
  productName, inputModeText, LOAN_PRODUCTS, METRIC_CODES
} from '@/utils/dict'
import RelatedPersonsEditor, { serializeRelations, parseRelations, validateRelations, type RelatedPersonRow } from './RelatedPersonsEditor.vue'
import ContributionPanel from '@/components/ContributionPanel.vue'

const userStore = useUserStore()
const route = useRoute()

// ---------- 步骤条(§14.1) ----------
const steps = ['客户信息', '融资情况', '担保组合与利率定价', '贡献承诺', '材料附件', '提交预览']
const step = ref(0)

// ---------- 字典 ----------
const guaranteeTypes = GUARANTEE_TYPES
const currencies = ['CNY', 'USD', 'EUR', 'HKD', 'JPY']
// 贷款产品(与规则/硬边界配置中的 product_code 对齐)
const loanProducts = LOAN_PRODUCTS
// 贡献度指标字典(§9;当前值由数仓带出,不做静态假定)
const metricDict = METRIC_CODES

// ---------- 表单状态 ----------
interface MortgageRow {
  type: string; name: string; addr: string; value: string; owner: string; ratio: string
  area: string; certNo: string; landUseType: string; landUseExpiry: string
  specModel: string; quantity: string; purchaseDate: string
  plateNo: string; vin: string; regDate: string
}
interface GuarantorRow { name: string; certNo: string; amount: string; balance: string }
interface GuaranteeRow {
  memberCustomerNo: string
  contractBusinessKey: string
  guaranteeType: string
  productCode: string
  termValue: string
  termUnit: string
  amount: string
  currency: string
  originalRate: string
  requestedRate: string
  mortgages: MortgageRow[]
  guarantors: GuarantorRow[]
  pledges: PledgeRow[]
  margins: MarginRow[]
  cds: CdRow[]
}
interface PledgeRow { type: string; name: string; value: string; owner: string }
interface MarginRow { amount: string; ratio: string; term: string }
interface CdRow { cdNo: string; amount: string; maturityDate: string }
interface CommitmentRow {
  metricCode: string
  targetType: string
  baselineValue: string
  targetValue: string
  /** 承诺类型"其它"手工目标描述(金额或文本,§6.4;后端 application_commitment 未接收字段,登记依赖) */
  commitmentDesc: string
  unit: string
  metricScope: string
  memberCustomerNo: string
}

function newGuarantee(): GuaranteeRow {
  return {
    memberCustomerNo: '', contractBusinessKey: '', guaranteeType: 'MORTGAGE',
    productCode: '', termValue: '', termUnit: 'MONTH', amount: '', currency: 'CNY',
    originalRate: '', requestedRate: '', mortgages: [], guarantors: [], pledges: [], margins: [], cds: []
  }
}

const form = reactive({
  customerScope: 'CORPORATE', // CORPORATE/INDIVIDUAL/GROUP(提交时映射 CORPORATE_SINGLE)
  customerName: '',
  customerNo: '',
  loanType: 'CORP_LOAN',
  businessType: 'NEW', // EXISTING 存量调息 / NEW 新增授信
  totalCredit: '', // 新增授信总授信额度(手工录入;拆分细项合计核对)
  amountTier: 'LT_5000',
  customerNature: 'EXISTING',
  customerType: 'NON_SOE',
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '', industry: '', registeredCapital: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', annualIncome: '', maritalStatus: '', phone: '',
  // 通用
  openOrg: '', openDate: '',
  // 集团
  groupNo: '',
  applicationRemark: '',
  guarantees: [newGuarantee()] as GuaranteeRow[]
})

const applyOrgText = computed(() => userStore.userInfo?.orgName || (userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据'))

// 数仓带出数据
const ownFinancing = ref<any[]>([])
const otherSummary = ref<any | null>(null)
const otherLoans = ref<any[]>([])
const contributionCurrent = ref<any[]>([])

// 集团数据
const groupInfo = ref<any | null>(null)
const groupCredit = ref<any | null>(null)
const groupAllocatedTotal = ref<any>(null)
const groupMembers = ref<any[]>([])
const groupQueried = ref(false)
const selectedMembers = ref<Array<{ memberCustomerNo: string; memberName: string; requestAmount: string; currency: string; memberRole: string }>>([])
const memberContracts = ref<Record<string, any[]>>({})

// 承诺与附件
const commitments = ref<CommitmentRow[]>([])
const attachments = ref<Array<{ name: string; size: number; dataBase64: string; file?: File; uploaded?: boolean }>>([])
// 关联人员(§12.4④,后端无独立接收字段,序列化后随申请备注附带)
const relations = ref<RelatedPersonRow[]>([])

// 草稿与提交闭环状态
const draft = reactive<{ id: number | null; versionNo: number | null; applicationNo: string }>({ id: null, versionNo: null, applicationNo: '' })
const inheritCount = ref(0)
const saving = ref(false)
const submitting = ref(false)
const routeResult = ref<RoutePreview | null>(null)
const checkResult = ref<SubmitCheck | null>(null)
const checkDialogVisible = ref(false)

// ---------- 客户查询带出(数仓) ----------
const customerCands = ref<any[]>([])
async function searchCustomers() {
  if (!form.customerName || !form.customerName.trim()) return
  try {
    customerCands.value = await apiSearchCustomers(form.customerName.trim())
    if (!customerCands.value.length) ElMessage.info('未查询到匹配客户')
  } catch {
    customerCands.value = []
  }
}

async function selectCustomer(c: any) {
  form.customerNo = c.customerNo
  form.customerName = c.customerName
  form.customerScope = c.custType === 'INDV' ? 'INDIVIDUAL' : 'CORPORATE'
  customerCands.value = []
  await loadCustomerDetail()
}

async function loadCustomerDetail() {
  if (!form.customerNo) return
  try {
    const detail = await getCustomerDetail(form.customerNo)
    const basic = detail.basic || {}
    form.ucrCode = basic.certNo || ''
    form.fiveLevelClass = basic.fiveLevelClass || ''
    form.creditLevel = basic.creditLevel || ''
    form.industry = basic.industry || ''
    form.registeredCapital = basic.registeredCapital || ''
    form.openOrg = basic.openOrgName || ''
    form.openDate = basic.openDate || ''
    form.idType = basic.certType || ''
    form.idNo = basic.certNo || ''
    form.occupation = basic.occupation || ''
    form.annualIncome = basic.annualIncome || ''
    form.maritalStatus = basic.maritalStatus || ''
    form.phone = basic.phone || ''
    form.customerNature = basic.customerClass === 'NEW' ? 'NEW' : 'EXISTING'
    ownFinancing.value = detail.financing || []
    contributionCurrent.value = detail.contribution || []
    otherSummary.value = detail.creditSummary?.[0] || null
    otherLoans.value = (detail.creditDetail || []).map((d: any) => ({ ...d, inputMode: 'DW' }))
    ElMessage.success(`已带出客户 ${form.customerName || form.customerNo} 信息`)
  } catch {
    // 带出失败由拦截器提示,不影响已选客户
  }
}

// ---------- 集团查询(真实数仓) ----------
async function queryGroup() {
  if (!form.groupNo || !form.groupNo.trim()) {
    ElMessage.warning('请输入集团客户编号')
    return
  }
  const no = form.groupNo.trim()
  try {
    const [g, members] = await Promise.all([getGroup(no), getGroupMembers(no)])
    groupInfo.value = g.group || null
    groupCredit.value = g.groupCredit || null
    groupAllocatedTotal.value = g.allocatedTotal ?? null
    groupMembers.value = members || []
    groupQueried.value = true
    // 已勾选但已不在有效成员列表中的成员剔除
    selectedMembers.value = selectedMembers.value.filter((s) =>
      groupMembers.value.some((m) => m.memberCustomerNo === s.memberCustomerNo)
    )
  } catch {
    groupInfo.value = null
    groupCredit.value = null
    groupMembers.value = []
    groupQueried.value = false
  }
}

function isMemberChecked(no: string) {
  return selectedMembers.value.some((m) => m.memberCustomerNo === no)
}
function memberInput(no: string) {
  return selectedMembers.value.find((m) => m.memberCustomerNo === no)
}
function setMemberField(no: string, field: 'requestAmount' | 'currency', value: string) {
  const m = memberInput(no)
  if (m) m[field] = value
}
async function toggleMember(m: any) {
  const idx = selectedMembers.value.findIndex((s) => s.memberCustomerNo === m.memberCustomerNo)
  if (idx >= 0) {
    selectedMembers.value.splice(idx, 1)
    return
  }
  selectedMembers.value.push({
    memberCustomerNo: m.memberCustomerNo,
    memberName: m.memberName || m.memberCustomerNo,
    requestAmount: '',
    currency: 'CNY',
    memberRole: m.memberRole || ''
  })
  // 带出成员名下贷款合同(供存量调息合同选择)
  if (!memberContracts.value[m.memberCustomerNo]) {
    try {
      const view = await getMemberCreditView(m.memberCustomerNo)
      memberContracts.value[m.memberCustomerNo] = view.contracts || []
    } catch {
      memberContracts.value[m.memberCustomerNo] = []
    }
  }
}
function memberNameOf(no: string) {
  if (!no) return '—'
  const m = selectedMembers.value.find((s) => s.memberCustomerNo === no)
    || groupMembers.value.find((s) => s.memberCustomerNo === no)
  return m ? (m.memberName || m.memberCustomerNo) : no
}

// 集团场景:全部已选成员的合同汇总(业务/合同步骤展示)
const groupContractRows = computed(() => {
  const rows: any[] = []
  for (const m of selectedMembers.value) {
    for (const c of memberContracts.value[m.memberCustomerNo] || []) {
      rows.push({
        memberName: m.memberName,
        contractNo: c.contractNo,
        contractBalance: c.contractBalance,
        executionRate: c.executionRate,
        maturityDate: c.maturityDate,
        noteCount: (c.notes || []).length
      })
    }
  }
  return rows
})

// ---------- 担保组合 ----------
// 总授信额度概览:分项金额自动合计(万元);集团场景对照可用额度,超限仅前端预警,阻断靠后端
const guaranteesTotalAmount = computed(() =>
  form.guarantees.reduce((acc, g) => {
    const n = Number(g.amount)
    return acc + (Number.isFinite(n) && n > 0 ? n : 0)
  }, 0)
)
const guaranteesTotalText = computed(() => (Math.round(guaranteesTotalAmount.value * 100) / 100).toString())
// 新增场景:拆分细项合计与手工录入的总授信额度不一致提示(容差 0.01)
const totalMismatch = computed(() => {
  const total = Number(form.totalCredit)
  if (!Number.isFinite(total) || total <= 0 || guaranteesTotalAmount.value <= 0) return false
  return Math.abs(guaranteesTotalAmount.value - total) > 0.01
})
const overGroupAvailable = computed(() => {
  if (form.customerScope !== 'GROUP') return false
  const avail = groupCredit.value?.availableAmount
  if (avail === null || avail === undefined || avail === '') return false
  const n = Number(avail)
  return Number.isFinite(n) && guaranteesTotalAmount.value > n
})

function contractOptions(g: GuaranteeRow) {
  if (form.customerScope === 'GROUP') {
    return memberContracts.value[g.memberCustomerNo] || []
  }
  return ownFinancing.value
}
function onContractSelect(g: GuaranteeRow) {
  const c = contractOptions(g).find((x: any) => x.contractNo === g.contractBusinessKey)
  if (c) {
    g.originalRate = c.executionRate ?? c.contractRate ?? ''
    if (c.currency) g.currency = c.currency
    // 存量:分项金额按合同金额自动带出(合同金额缺省用合同余额)
    const amt = c.contractAmount ?? c.loanBalance
    if (amt != null) g.amount = String(amt)
  }
}
function addGuarantee() {
  form.guarantees.push(newGuarantee())
}
function removeGuarantee(idx: number) {
  form.guarantees.splice(idx, 1)
}
function addGuaranteeMortgage(g: GuaranteeRow) {
  g.mortgages.push({ type: '住宅', name: '', addr: '', value: '', owner: '', ratio: '', area: '', certNo: '', landUseType: '出让', landUseExpiry: '', specModel: '', quantity: '', purchaseDate: '', plateNo: '', vin: '', regDate: '' })
}
function addGuaranteeGuarantor(g: GuaranteeRow) {
  g.guarantors.push({ name: '', certNo: '', amount: '', balance: '' })
}
function addPledge(g: GuaranteeRow) {
  g.pledges.push({ type: '存单', name: '', value: '', owner: '' })
}
function addMargin(g: GuaranteeRow) {
  g.margins.push({ amount: '', ratio: '', term: '' })
}
function addCd(g: GuaranteeRow) {
  g.cds.push({ cdNo: '', amount: '', maturityDate: '' })
}
function onCustomerScopeChange() {
  if (form.customerScope !== 'GROUP') {
    selectedMembers.value = []
    groupInfo.value = null
    groupMembers.value = []
    groupQueried.value = false
  }
}

// ---------- 贡献承诺 ----------
function currentOf(code: string) {
  const m = contributionCurrent.value.find((x) => x.metricCode === code)
  return m?.metricValue ?? '暂无数据'
}
function addCommitment() {
  commitments.value.push({
    metricCode: 'PUBLIC_DEPOSIT_AVG', targetType: 'TARGET_BALANCE',
    baselineValue: '', targetValue: '', commitmentDesc: '', unit: 'WAN_YUAN',
    metricScope: form.customerScope === 'GROUP' ? 'GROUP' : 'PUBLIC', memberCustomerNo: ''
  })
}

// ---------- 他行融资 ----------
function addOtherLoan() {
  otherLoans.value.push({ lenderName: '', creditAmount: '', usedAmount: '', balanceAmount: '', annualRate: '', inputMode: 'MANUAL' })
}

// Excel 导入(材料附件步骤,结果回显融资情况步骤)
const fileInput = ref<HTMLInputElement | null>(null)
function triggerImport() {
  fileInput.value?.click()
}
async function onImportFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const rows = await importOtherLoans(file)
    if (rows?.length) {
      otherLoans.value.push(...rows)
      ElMessage.success(`Excel 导入成功,解析 ${rows.length} 条,已并入他行融资明细`)
    } else {
      ElMessage.warning('Excel 未解析到有效数据')
    }
  } catch {
    // 拦截器已提示
  } finally {
    input.value = ''
  }
}

// 其他附件:草稿存在时立即上传,否则暂存文件对象待提交前上传(§7.1 步骤6)
const attachmentInput = ref<HTMLInputElement | null>(null)
async function onAttachmentFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  for (const f of files) {
    attachments.value.push({ name: f.name, size: f.size, dataBase64: '', file: f, uploaded: false } as any)
  }
  input.value = ''
  if (draft.id) {
    await uploadPendingAttachments()
  }
}
/** 提交前把暂存附件逐个上传(幂等:只传未上传过的) */
async function uploadPendingAttachments() {
  if (!draft.id) return
  for (const a of attachments.value as any[]) {
    if (a.uploaded || !a.file) continue
    try {
      await uploadAttachment(draft.id, a.file)
      a.uploaded = true
    } catch (err: any) {
      ElMessage.error(`附件「${a.name}」上传失败:${err?.message || '网络异常'}`)
    }
  }
}

// ---------- 提交闭环:创建/保存草稿 → submit-check → 确认 → submit ----------
function isBlank(v: any) {
  return v === undefined || v === null || String(v).trim() === ''
}

// ---------- 分步校验(点击下一步/跳步时提示当前必填项) ----------
function validateStep(s: number): string | null {
  if (s === 0) {
    if (form.customerScope === 'GROUP') {
      if (isBlank(form.groupNo) || !groupInfo.value) return '请录入集团编号并查询加载集团信息'
      if (!selectedMembers.value.length) return '请至少勾选一名集团成员'
      const bad = selectedMembers.value.find((m) => isBlank(m.requestAmount) || Number(m.requestAmount) <= 0)
      if (bad) return `成员 ${bad.memberCustomerNo} 未录入本次申请金额`
    } else if (isBlank(form.customerNo)) {
      return '请查询并选择客户'
    }
  }
  if (s === 2) {
    for (let i = 0; i < form.guarantees.length; i++) {
      const g = form.guarantees[i]
      if (form.customerScope === 'GROUP' && isBlank(g.memberCustomerNo)) return `第 ${i + 1} 条担保分项未选择集团成员`
      if (form.businessType === 'EXISTING' && isBlank(g.contractBusinessKey)) return `第 ${i + 1} 条担保分项未选择贷款合同`
      if (isBlank(g.guaranteeType)) return `第 ${i + 1} 条担保分项未选择担保方式`
      if (g.guaranteeType !== 'CREDIT') {
        const n = g.mortgages.length + g.guarantors.length + g.pledges.length + g.margins.length + g.cds.length
        if (n === 0) return `第 ${i + 1} 条担保分项为「${guaranteeTypeText(g.guaranteeType)}」,请至少登记一条担保措施明细`
      }
      if (isBlank(g.productCode)) return `第 ${i + 1} 条分项未选择产品`
      if (isBlank(g.termValue)) return `第 ${i + 1} 条分项未录入期限`
      if (isBlank(g.amount) || Number(g.amount) <= 0) return `第 ${i + 1} 条分项未录入金额`
      if (isBlank(g.requestedRate)) return `第 ${i + 1} 条分项未录入申请利率`
    }
  }
  if (s === 3) {
    for (let i = 0; i < commitments.value.length; i++) {
      const c = commitments.value[i]
      if (isBlank(c.metricCode)) return `第 ${i + 1} 条承诺未选择指标`
      if (c.metricCode === 'OTHER' ? isBlank(c.commitmentDesc) : isBlank(c.targetValue)) return `第 ${i + 1} 条承诺未录入目标`
    }
  }
  return null
}
function goNext(target: number) {
  const err = validateStep(step.value)
  if (err) {
    ElMessage.warning(err)
    return
  }
  step.value = target
}
function goStep(i: number) {
  if (i <= step.value) {
    step.value = i
    return
  }
  for (let s = step.value; s < i; s++) {
    const err = validateStep(s)
    if (err) {
      ElMessage.warning(err)
      return
    }
  }
  step.value = i
}

function validateForDraft(): string | null {
  const isGroup = form.customerScope === 'GROUP'
  if (isGroup) {
    if (isBlank(form.groupNo)) return '请填写集团客户编号'
    if (!selectedMembers.value.length) return '集团场景请至少勾选一名涉及成员'
    const noAmount = selectedMembers.value.find((m) => isBlank(m.requestAmount))
    if (noAmount) return `成员 ${noAmount.memberName} 未录入本次申请金额`
  } else if (isBlank(form.customerNo)) {
    return '请先查询并选择客户'
  }
  if (!form.guarantees.length) return '请至少录入一条担保分项'
  for (let i = 0; i < form.guarantees.length; i++) {
    const g = form.guarantees[i]
    if (isGroup && isBlank(g.memberCustomerNo)) return `第 ${i + 1} 条担保分项未选择涉及成员`
    if (form.businessType === 'EXISTING' && isBlank(g.contractBusinessKey)) return `第 ${i + 1} 条担保分项未选择贷款合同`
    if (isBlank(g.productCode)) return `第 ${i + 1} 条分项未选择产品(担保组合与利率定价步骤)`
    if (isBlank(g.termValue)) return `第 ${i + 1} 条分项未录入期限(担保组合与利率定价步骤)`
    if (isBlank(g.amount)) return `第 ${i + 1} 条分项未录入授信金额(担保组合与利率定价步骤)`
    if (isBlank(g.requestedRate)) return `第 ${i + 1} 条分项未录入申请利率(担保组合与利率定价步骤)`
  }
  for (let i = 0; i < commitments.value.length; i++) {
    const c = commitments.value[i]
    if (c.metricCode === 'OTHER') {
      if (isBlank(c.commitmentDesc)) return `第 ${i + 1} 条承诺(其它)未录入目标描述`
    } else if (isBlank(c.targetValue)) {
      return `第 ${i + 1} 条承诺未录入拟达成目标`
    }
  }
  return null
}

function buildMeasures(g: GuaranteeRow): GuaranteeMeasureInput[] {
  const list: GuaranteeMeasureInput[] = []
  for (const m of g.mortgages) {
    if (isBlank(m.name) && isBlank(m.value)) continue
    list.push({
      measureType: 'MORTGAGE',
      guaranteeAmount: m.value || undefined,
      currency: 'CNY',
      extJson: { collateralType: m.type, name: m.name, address: m.addr, owner: m.owner, mortgageRatio: m.ratio, area: m.area, certNo: m.certNo, landUseType: m.landUseType, landUseExpiry: m.landUseExpiry, specModel: m.specModel, quantity: m.quantity, purchaseDate: m.purchaseDate, plateNo: m.plateNo, vin: m.vin, regDate: m.regDate }
    })
  }
  for (const t of g.guarantors) {
    if (isBlank(t.name)) continue
    list.push({
      measureType: 'GUARANTOR',
      guaranteeAmount: t.amount || undefined,
      currency: 'CNY',
      extJson: { name: t.name, certNo: t.certNo, balance: t.balance }
    })
  }
  for (const m of g.pledges) {
    if (isBlank(m.name) && isBlank(m.value)) continue
    list.push({
      measureType: 'PLEDGE',
      guaranteeAmount: m.value || undefined,
      currency: 'CNY',
      extJson: { pledgeType: m.type, name: m.name, owner: m.owner }
    })
  }
  for (const m of g.margins) {
    if (isBlank(m.amount)) continue
    list.push({
      measureType: g.guaranteeType,
      guaranteeAmount: m.amount || undefined,
      currency: 'CNY',
      extJson: { marginRatio: m.ratio, termMonths: m.term }
    })
  }
  for (const m of g.cds) {
    if (isBlank(m.cdNo) && isBlank(m.amount)) continue
    list.push({
      measureType: 'CERTIFICATE_DEPOSIT',
      guaranteeAmount: m.amount || undefined,
      currency: 'CNY',
      extJson: { certificateNo: m.cdNo, maturityDate: m.maturityDate }
    })
  }
  return list
}

function buildPayload(): ApplicationPayload {
  const isGroup = form.customerScope === 'GROUP'
  const scopeMap: Record<string, ApplicationPayload['customerScope']> = {
    CORPORATE: 'CORPORATE_SINGLE', INDIVIDUAL: 'INDIVIDUAL', GROUP: 'GROUP'
  }
  return {
    businessType: 'LOAN',
    customerScope: scopeMap[form.customerScope] || 'CORPORATE_SINGLE',
    customerNo: isGroup ? null : form.customerNo,
    groupNo: isGroup ? form.groupNo.trim() : null,
    members: isGroup
      ? selectedMembers.value.map((m) => ({
          memberCustomerNo: m.memberCustomerNo,
          requestAmount: m.requestAmount,
          currency: m.currency || 'CNY',
          memberRole: m.memberRole || undefined
        }))
      : null,
    guarantees: form.guarantees.map((g) => ({
      requestedRate: g.requestedRate,
      productCode: g.productCode,
      termValue: g.termValue,
      termUnit: g.termUnit,
      amount: g.amount,
      currency: g.currency || 'CNY',
      originalRate: isBlank(g.originalRate) ? undefined : g.originalRate,
      memberCustomerNo: isGroup ? g.memberCustomerNo : undefined,
      contractBusinessKey: isBlank(g.contractBusinessKey) ? undefined : g.contractBusinessKey,
      plannedContractFlag: form.businessType === 'NEW' ? 'Y' : 'N',
      guaranteeType: g.guaranteeType,
      measures: buildMeasures(g)
    })),
    // 关联人(结构化随单提交,审批详情按录入内容展示)
    relatedPersons: relations.value
      .filter((r) => !isBlank(r.name))
      .map((r) => ({
        personName: r.name,
        certNo: r.certNo || undefined,
        relationType: r.relationType,
        relatedCustomerNo: isBlank(r.customerNo) ? undefined : r.customerNo
      })),
    // 人工补录/Excel 导入他行融资(随单持久化,审批详情展示;空行过滤,数仓带出的不回传)
    otherLoans: otherLoans.value
      .filter((d) => d.inputMode !== 'DW' && !isBlank(d.lenderName))
      .map((d) => ({
        lenderName: d.lenderName,
        creditAmount: isBlank(d.creditAmount) ? undefined : d.creditAmount,
        usedAmount: isBlank(d.usedAmount) ? undefined : d.usedAmount,
        balanceAmount: isBlank(d.balanceAmount) ? undefined : d.balanceAmount,
        annualRate: isBlank(d.annualRate) ? undefined : d.annualRate,
        inputMode: d.inputMode || 'MANUAL'
      })),
    commitments: commitments.value.map((c) => ({
      metricCode: c.metricCode,
      targetType: c.targetType,
      baselineValue: isBlank(c.baselineValue) ? undefined : c.baselineValue,
      // "其它"承诺无数值目标,以 commitmentDesc 手工描述为准(后端未接收字段,登记依赖)
      targetValue: c.metricCode === 'OTHER' ? undefined : c.targetValue,
      unit: c.unit || 'WAN_YUAN',
      metricScope: c.metricScope || 'PUBLIC',
      memberCustomerNo: isBlank(c.memberCustomerNo) ? undefined : c.memberCustomerNo
    })),
    applicantUserId: userStore.userInfo?.userId,
    applicantOrgId: userStore.userInfo?.orgId,
    orgId: userStore.userInfo?.orgId,
    // 关联人员随备注结构附带(后端申请单无独立接收字段,§12.4④)
    applicationRemark: ((form.applicationRemark || '') + serializeRelations(relations.value)).trim() || undefined
  }
}

/** 创建或保存草稿;保存(PUT)仅更新主单字段,需携带 versionNo */
async function ensureDraft(): Promise<boolean> {
  const err = validateForDraft()
  if (err) {
    ElMessage.error(err)
    return false
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (draft.id) {
      const saved = await saveApplication(draft.id, { ...payload, versionNo: draft.versionNo ?? undefined })
      draft.versionNo = saved.versionNo
    } else {
      const created = await createApplication(payload)
      draft.id = created.id
      draft.versionNo = created.versionNo ?? 1
      draft.applicationNo = created.applicationNo
    }
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

async function onSaveDraft() {
  if (await ensureDraft()) {
    ElMessage.success(`草稿已保存,申请号 ${draft.applicationNo}(版本 v${draft.versionNo})`)
  }
}

async function onRoutePreview() {
  if (!(await ensureDraft()) || !draft.id) return
  await uploadPendingAttachments()
  try {
    routeResult.value = await routePreview(draft.id)
  } catch {
    routeResult.value = null
  }
}

async function onSubmit() {
  const missingRel = validateRelations(relations.value)
  if (missingRel.length) {
    ElMessage.error(`关联人员「${missingRel.join('、')}」未填写证件号,请补全后再提交`)
    return
  }
  if (!(await ensureDraft()) || !draft.id) return
  await uploadPendingAttachments()
  try {
    checkResult.value = await submitCheck(draft.id)
    checkDialogVisible.value = true
  } catch {
    checkResult.value = null
  }
}

async function onConfirmSubmit() {
  if (!draft.id) return
  submitting.value = true
  try {
    const result = await submitApplication(draft.id)
    checkDialogVisible.value = false
    const firstNode = nodeLabel(result.items?.[0]?.currentNodeCode)
    const finalNode = nodeLabel(result.items?.[0]?.routeCode)
    ElMessageBox.alert(
      `申请号:${result.applicationNo}\n当前节点:${firstNode}\n终审岗位:${finalNode}\n提交时间:${result.submitTime || '—'}`,
      result.submitted === false ? '申请已提交(幂等返回)' : '提交成功',
      { confirmButtonText: '知道了' }
    )
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ---------- 关联重提(?reapply={applicationId}:生成新草稿并加载内容) ----------
onMounted(async () => {
  const src = route.query.reapply
  if (!src) return
  try {
    const newDraft = await reapplyApplication(String(src))
    draft.id = newDraft.id
    draft.versionNo = newDraft.versionNo ?? 1
    draft.applicationNo = newDraft.applicationNo
    await loadDraftIntoForm(newDraft.id)
    ElMessage.success(`已基于原申请 #${src} 生成新草稿 ${newDraft.applicationNo},请调整后提交`)
  } catch {
    // 拦截器已提示
  }
})

async function loadDraftIntoForm(id: number) {
  const d = await getApplicationDetail(id)
  const app = d.application
  form.customerScope = app.customerScope === 'GROUP' ? 'GROUP' : app.customerScope === 'INDIVIDUAL' ? 'INDIVIDUAL' : 'CORPORATE'
  form.customerNo = app.customerNo || ''
  form.groupNo = app.groupNo || ''
  // 备注中的【关联人员】块还原到关联人员录入表,避免重复附带
  const [rels, cleanedRemark] = parseRelations(app.applicationRemark || '')
  relations.value = rels
  form.applicationRemark = cleanedRemark

  if (app.customerScope === 'GROUP' && app.groupNo) {
    await queryGroup()
    selectedMembers.value = (d.members || []).map((m) => ({
      memberCustomerNo: m.memberCustomerNo,
      memberName: groupMembers.value.find((g) => g.memberCustomerNo === m.memberCustomerNo)?.memberName || m.memberCustomerNo,
      requestAmount: m.requestAmount != null ? String(m.requestAmount) : '',
      currency: m.currency || 'CNY',
      memberRole: m.memberRole || ''
    }))
    for (const m of selectedMembers.value) {
      try {
        const view = await getMemberCreditView(m.memberCustomerNo)
        memberContracts.value[m.memberCustomerNo] = view.contracts || []
      } catch {
        memberContracts.value[m.memberCustomerNo] = []
      }
    }
  } else if (app.customerNo) {
    await loadCustomerDetail()
  }

  // 分项 → 担保组合与利率定价;已批准沿用原决议的占位分项不在本页编辑
  const editable = (d.pricingItems || []).filter((p) => p.inheritFlag !== 'Y')
  inheritCount.value = (d.pricingItems || []).length - editable.length
  let hasPlanned = false
  form.guarantees = editable.map((p) => {
    const rel = (d.contractRelations || []).find((r) => r.pricingItemId === p.id)
    const pkg = (d.guaranteePackages || []).find((gp) => gp.guaranteePackage?.pricingItemId === p.id)
    if (rel?.plannedContractFlag === 'Y') hasPlanned = true
    const g = newGuarantee()
    g.memberCustomerNo = p.memberCustomerNo || ''
    g.contractBusinessKey = rel?.contractBusinessKey || rel?.loanContractNo || ''
    g.guaranteeType = pkg?.guaranteePackage?.mainGuaranteeType || 'MORTGAGE'
    g.productCode = p.productCode || ''
    g.termValue = p.termValue != null ? String(p.termValue) : ''
    g.termUnit = p.termUnit || 'MONTH'
    g.amount = p.pricingAmount != null ? String(p.pricingAmount) : ''
    g.currency = p.currency || 'CNY'
    g.originalRate = p.originalRate != null ? String(p.originalRate) : ''
    g.requestedRate = p.requestedRate != null ? String(p.requestedRate) : ''
    for (const ms of pkg?.measures || []) {
      if (ms.measureType === 'MORTGAGE') {
        g.mortgages.push({
          type: ms.extJson?.collateralType || '住宅',
          name: ms.extJson?.name || '',
          addr: ms.extJson?.address || '',
          value: ms.guaranteeAmount != null ? String(ms.guaranteeAmount) : '',
          owner: ms.extJson?.owner || '',
          ratio: ms.extJson?.mortgageRatio || '',
          area: ms.extJson?.area || '', certNo: ms.extJson?.certNo || '',
          landUseType: ms.extJson?.landUseType || '出让', landUseExpiry: ms.extJson?.landUseExpiry || '',
          specModel: ms.extJson?.specModel || '', quantity: ms.extJson?.quantity || '',
          purchaseDate: ms.extJson?.purchaseDate || '', plateNo: ms.extJson?.plateNo || '',
          vin: ms.extJson?.vin || '', regDate: ms.extJson?.regDate || ''
        })
      } else if (ms.measureType === 'GUARANTOR') {
        g.guarantors.push({
          name: ms.extJson?.name || '',
          certNo: ms.extJson?.certNo || '',
          amount: ms.guaranteeAmount != null ? String(ms.guaranteeAmount) : '',
          balance: ms.extJson?.balance || ''
        })
      } else if (ms.measureType === 'PLEDGE') {
        g.pledges.push({
          type: ms.extJson?.pledgeType || '存单',
          name: ms.extJson?.name || '',
          value: ms.guaranteeAmount != null ? String(ms.guaranteeAmount) : '',
          owner: ms.extJson?.owner || ''
        })
      } else if (ms.measureType === 'BILL_MARGIN' || ms.measureType === 'CREDIT_MARGIN') {
        g.margins.push({
          amount: ms.guaranteeAmount != null ? String(ms.guaranteeAmount) : '',
          ratio: ms.extJson?.marginRatio || '',
          term: ms.extJson?.termMonths || ''
        })
      } else if (ms.measureType === 'CERTIFICATE_DEPOSIT') {
        g.cds.push({
          cdNo: ms.extJson?.certificateNo || '',
          amount: ms.guaranteeAmount != null ? String(ms.guaranteeAmount) : '',
          maturityDate: ms.extJson?.maturityDate || ''
        })
      }
    }
    return g
  })
  if (!form.guarantees.length) form.guarantees = [newGuarantee()]
  // 已上传附件回显(材料附件步骤)
  try {
    const atts = await listAttachments(id)
    for (const a of atts || []) {
      if (!attachments.value.some((x) => x.name === a.fileName)) {
        attachments.value.push({ name: a.fileName, size: a.fileSize, dataBase64: '', uploaded: true } as any)
      }
    }
  } catch { /* 忽略 */ }
  form.businessType = hasPlanned ? 'NEW' : 'EXISTING'

  commitments.value = (d.commitments || []).map((c) => ({
    metricCode: c.metricCode,
    targetType: c.targetType,
    baselineValue: c.baselineValue != null ? String(c.baselineValue) : '',
    targetValue: c.targetValue != null ? String(c.targetValue) : '',
    unit: c.unit || 'WAN_YUAN',
    metricScope: c.metricScope || 'PUBLIC',
    memberCustomerNo: c.memberCustomerNo || ''
  }))
}
</script>

<style scoped>
.section-head { margin-bottom: 20px; }
.eyebrow { font-size: 12px; color: var(--color-text-light); letter-spacing: 1px; margin-bottom: 4px; }
.section-title { font-size: var(--fs-h1); font-weight: 700; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.form-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-4);
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}
.form-card__title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.table--nested { margin-top: 8px; }
.guarantee-item { margin-bottom: 14px; }
.guarantee-item__title { font-size: 14px; font-weight: 600; display: inline-flex; align-items: center; gap: 8px; }
.guarantee-detail-block { margin-top: 12px; border-top: 1px dashed var(--color-border); padding-top: 12px; }

/* 总授信额度概览条(担保组合与利率定价步骤) */
.credit-overview {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px;
  background: var(--color-primary-light); border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-4); margin-bottom: 14px;
}
.credit-overview__item { font-size: 13px; display: flex; flex-direction: column; gap: 2px; }
.credit-overview__item span { color: var(--color-text-sub); font-size: 12px; }
.credit-overview__item b { font-variant-numeric: tabular-nums; }
.credit-overview-warning {
  background: #fef2f2; color: var(--color-danger);
  border: 1px solid var(--color-danger); border-radius: var(--radius-sm);
  padding: 8px 12px; font-size: 13px; margin-bottom: 14px;
}
.customer-cands { margin-top: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow: hidden; background: var(--color-surface); }
.customer-cand { padding: 8px 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid var(--color-border); }
.customer-cand:last-child { border-bottom: none; }
.customer-cand:hover { background: var(--color-primary-light); }
.detail-title { font-size: 13px; font-weight: 600; color: var(--color-text-sub); display: flex; align-items: center; justify-content: space-between; }
.req { color: var(--color-danger); }
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }

/* 向导步骤条(沿用 design-system .stepper) */
.wizard-stepper {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-3) var(--space-4);
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}
.wizard-stepper .stepper__step { cursor: pointer; }
.wizard-actions {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--color-border);
}

/* 草稿横幅 */
.draft-banner { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--color-text-sub); }

/* 规则来源提示(§12.4⑦) */
.rule-notice {
  background: var(--color-primary-light); color: var(--color-primary);
  border: 1px solid var(--color-primary); border-radius: var(--radius);
  padding: 10px 14px; font-size: 13px; margin-bottom: 16px;
}

/* 集团概要 */
.group-summary {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px;
  background: var(--color-primary-light); border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-4); margin-bottom: 4px;
}
.group-summary__item { font-size: 13px; display: flex; flex-direction: column; gap: 2px; }
.group-summary__item span { color: var(--color-text-sub); font-size: 12px; }
.group-summary__item b { font-variant-numeric: tabular-nums; }

/* 路由链路节点 */
.route-node {
  display: inline-block; padding: 1px 8px; border-radius: 999px;
  background: var(--color-primary-light); color: var(--color-primary);
  font-size: 12px; font-weight: 500;
}
</style>
