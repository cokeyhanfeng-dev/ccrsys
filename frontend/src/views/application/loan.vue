<template>
  <div class="wizard-page">
    <div class="section-head">
      <div class="section-title">贷款利率申请</div>
      <InfoTip content="分步录入客户、融资情况、利率申请、贡献承诺与材料附件;系统按权限矩阵自动识别审批路径,所有申请必经支行行长首节点(§7.1/§14.1)。" />
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
      <InfoTip content="草稿保存仅更新主单信息,分项/成员/承诺以创建时内容为准" />
    </div>

    <!-- 第一步:客户信息(个人/企业单户/集团三选) -->
    <div v-show="step === 0" class="form-card">
      <div class="form-card__title">
        客户信息
        <InfoTip content="客户基本信息由数仓统一提供(caps_corp/indv_cust_basic_info),带出后只读展示;集团客户输入集团号查询数仓集团快照。" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label class="form-field__label">客户主体 <span class="req">*</span></label>
          <select class="form-select" v-model="form.customerScope" @change="onCustomerScopeChange">
            <option value="CORPORATE">企业单户</option>
            <option value="INDIVIDUAL">个人</option>
            <option value="GROUP">集团客户</option>
          </select>
        </div>

        <!-- 单户:客户名称输入联想下拉选择(数仓模糊查询,取消独立查询按钮) -->
        <template v-if="form.customerScope !== 'GROUP'">
          <div class="form-field" style="grid-column: span 2">
            <label class="form-field__label">客户名称 <span class="req">*</span></label>
            <el-autocomplete
              v-model="form.customerName"
              :fetch-suggestions="queryCustomerSuggestions"
              :trigger-on-focus="false"
              clearable
              placeholder="输入客户名称自动联想,下拉选择客户"
              style="width:100%"
              @select="selectCustomer"
            />
          </div>
          <div class="form-field">
            <label class="form-field__label">客户号</label>
            <input class="form-input" v-model="form.customerNo" placeholder="数仓带出,可修改;新增客户可手工填写" />
          </div>
          <div class="form-field">
            <label class="form-field__label">客户性质</label>
            <input class="form-input" :value="customerNatureText" readonly placeholder="选客户后自动判定" />
          </div>
        </template>
      </div>

      <!-- 集团客户区块(§docs/19 集团补录集成申请页):集团号查询 → 新增集团就地补录/存量带出 + 申请额度 + 有效成员列表(数仓带出/手工补录) -->
      <template v-if="form.customerScope === 'GROUP'">
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2">
            <label class="form-field__label">集团客户 <span class="req">*</span></label>
            <el-autocomplete
              v-model="form.groupNo"
              :fetch-suggestions="queryGroupSuggestions"
              :trigger-on-focus="false"
              clearable
              placeholder="输入集团编号或集团名称自动联想;数仓未收录的集团可直接输入编号后回车,就地补录"
              style="width:100%"
              @select="selectGroup"
              @keyup.enter="queryGroup"
            />
          </div>
        </div>
        <!-- 新增集团(数仓未收录):就地补录集团基本信息,与对公客户信息要求一致(数据以数仓为准,数仓无即按新集团) -->
        <div v-if="isNewGroup" class="form-card group-supplement" style="margin-top:12px">
          <div class="form-card__title">
            新增集团补录 <span class="badge badge--warning">数仓未收录</span>
            <InfoTip content="数仓未收录该集团,按新集团对待:补录集团基本信息(与对公客户一致),授信以本次申请额度为准。" />
          </div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">集团编号 <span class="req">*</span></label>
              <input class="form-input" :value="form.groupNo" disabled />
            </div>
            <div class="form-field">
              <label class="form-field__label">集团名称 <span class="req">*</span></label>
              <input class="form-input" v-model="groupSupplement.groupName" placeholder="必填" />
            </div>
            <div class="form-field">
              <label class="form-field__label">集团属性 <span class="req">*</span></label>
              <select class="form-select" v-model="groupSupplement.stateOwnedFlag">
                <option value="">请选择</option>
                <option value="Y">国企集团</option>
                <option value="N">非国企集团</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">统一社会信用代码</label>
              <input class="form-input" v-model="groupSupplement.ucrCode" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">五级分类</label>
              <select class="form-select" v-model="groupSupplement.fiveLevelClass">
                <option value="">可空</option>
                <option v-for="f in fiveLevelOptions" :key="f.code" :value="f.code">{{ f.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">内部信用等级</label>
              <input class="form-input" v-model="groupSupplement.creditLevel" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">所属行业</label>
              <input class="form-input" v-model="groupSupplement.industry" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">注册资本(万元)</label>
              <input class="form-input form-input--amount" v-model="groupSupplement.registeredCapital" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">开户机构</label>
              <el-select class="open-org-select" v-model="groupSupplement.openOrg" filterable allow-create default-first-option clearable placeholder="可空">
                <el-option v-for="d in openOrgOptions" :key="d.id" :label="d.deptName" :value="d.deptName" />
              </el-select>
            </div>
            <div class="form-field">
              <label class="form-field__label">开户日期</label>
              <input class="form-input" v-model="groupSupplement.openDate" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">基本户账户</label>
              <input class="form-input" v-model="groupSupplement.basicAccount" placeholder="可空" />
            </div>
          </div>
        </div>
        <div v-if="groupInfo" class="group-summary" style="margin-top:12px">
          <div class="group-summary__item"><span>集团名称</span><b>{{ groupInfo.groupName || '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>集团属性</span><b>{{ groupNatureText(groupInfo.stateOwnedFlag) }}</b></div>
          <div class="group-summary__item"><span>集团状态</span><b>{{ groupStatusText(groupInfo.groupStatus) }}</b></div>
          <div class="group-summary__item"><span>授信总额(万元)</span><b>{{ groupCredit?.approvedTotalAmount ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>已分配额度(万元)</span><b>{{ groupAllocatedTotal ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>可用额度(万元)</span><b>{{ groupCredit?.availableAmount ?? '暂无数据' }}</b></div>
          <div class="group-summary__item"><span>授信到期日</span><b>{{ groupCredit?.creditEnd || '暂无数据' }}</b></div>
        </div>
        <!-- 申请额度(本次申请新增授信,必填;所有集团申请统一,数仓批复授信仅作展示参考) -->
        <div v-if="groupQueried" class="form-field" style="margin-top:12px">
          <label class="form-field__label">本次申请额度(万元) <span class="req">*</span></label>
          <input class="form-input form-input--amount" v-model="groupApplyAmount" placeholder="集团本次申请新增授信额度,必填" />
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
                <td>
                  {{ customerNoText(m.memberCustomerNo) }}
                  <span v-if="m.source === 'MANUAL'" class="badge badge--warning" style="margin-left:4px">手工</span>
                </td>
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
                    <option v-for="c in currencies" :key="c" :value="c">{{ currencyText(c) }}</option>
                  </select>
                </td>
              </tr>
            </tbody>
          </table>
          <button class="btn btn--secondary" style="margin-top:8px" @click="showSupplementMember = !showSupplementMember">
            {{ showSupplementMember ? '收起成员补录' : '＋ 添加手工成员(数仓未收录)' }}
          </button>
        </div>
        <div v-else-if="groupQueried" class="empty">
          该集团暂无有效成员数据
          <button class="btn btn--text" @click="showSupplementMember = true">＋ 添加手工成员(数仓未收录)</button>
        </div>

        <!-- 手工成员补录(数仓未收录成员,补录信息与对公客户申请要求一致:企业要素 + 成员要素,不含授信) -->
        <div v-if="showSupplementMember" class="form-card" style="margin-top:12px">
          <div class="form-card__title">
            手工成员补录 <span class="badge badge--warning">数仓未收录</span>
            <InfoTip content="补录成员信息与对公客户申请要求一致(客户号/名称/统一社会信用代码/五级分类/信用等级/行业/注册资本/开户机构/开户日期/基本账户);成员授信不做补录,授信集团级在申请额度补录。" />
          </div>
          <div v-for="(m, i) in supplementMembers" :key="i" class="form-grid" style="margin-bottom:10px;border:1px solid var(--color-border-light);border-radius:var(--radius);padding:10px 14px">
            <div class="form-field">
              <label class="form-field__label">成员客户号</label>
              <input class="form-input" v-model="m.memberCustomerNo" placeholder="可空(非我行客户可留空)" />
            </div>
            <div class="form-field">
              <label class="form-field__label">成员名称 <span class="req">*</span></label>
              <input class="form-input" v-model="m.memberName" placeholder="必填" />
            </div>
            <div class="form-field">
              <label class="form-field__label">统一社会信用代码</label>
              <input class="form-input" v-model="m.ucrCode" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">五级分类</label>
              <select class="form-select" v-model="m.fiveLevelClass">
                <option value="">可空</option>
                <option v-for="f in fiveLevelOptions" :key="f.code" :value="f.code">{{ f.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">信用等级</label>
              <input class="form-input" v-model="m.creditLevel" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">行业</label>
              <input class="form-input" v-model="m.industry" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">注册资本(万元)</label>
              <input class="form-input form-input--amount" v-model="m.registeredCapital" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">开户机构</label>
              <el-select class="open-org-select" v-model="m.openOrg" filterable allow-create default-first-option clearable placeholder="可空">
                <el-option v-for="d in openOrgOptions" :key="d.id" :label="d.deptName" :value="d.deptName" />
              </el-select>
            </div>
            <div class="form-field">
              <label class="form-field__label">开户日期</label>
              <input class="form-input" v-model="m.openDate" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">基本账户</label>
              <input class="form-input" v-model="m.basicAccount" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">成员角色</label>
              <select class="form-select" v-model="m.memberRole">
                <option value="CORE">核心</option>
                <option value="GENERAL">一般</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">控制关系</label>
              <input class="form-input" v-model="m.controlRelation" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">关系起止(空=在团)</label>
              <div class="credit-overview__range">
                <input class="form-input" type="date" v-model="m.relationStart" />
                <span class="credit-overview__range-sep">至</span>
                <input class="form-input" type="date" v-model="m.relationEnd" />
              </div>
            </div>
            <div class="form-field" style="display:flex;align-items:flex-end;gap:8px">
              <button class="btn btn--primary" @click="confirmSupplementMember(i)">加入成员列表</button>
              <button class="btn btn--text" @click="supplementMembers.splice(i, 1)">删除</button>
            </div>
          </div>
          <button class="btn btn--secondary" @click="addSupplementMember">＋ 添加成员行</button>
        </div>
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
            <input class="form-input" v-model="form.ucrCode" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五级分类</label>
            <select class="form-select" v-model="form.fiveLevelClass">
              <option value="">请选择</option>
              <option v-for="f in fiveLevelOptions" :key="f.code" :value="f.code">{{ f.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">内部信用等级</label>
            <input class="form-input" v-model="form.creditLevel" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">所属行业</label>
            <input class="form-input" v-model="form.industry" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">注册资本(万元)</label>
            <input class="form-input form-input--amount" v-model="form.registeredCapital" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">基本户账户</label>
            <input class="form-input" v-model="form.basicAccount" placeholder="请输入基本户账号,可空" />
          </div>
        </template>
        <template v-else>
          <div class="form-field">
            <label class="form-field__label">证件类型</label>
            <input class="form-input" :value="certTypeText(form.idType)" placeholder="数仓带出" readonly />
          </div>
          <div class="form-field">
            <label class="form-field__label">证件号码</label>
            <input class="form-input" v-model="form.idNo" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">职业</label>
            <input class="form-input" v-model="form.occupation" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">年收入(万元)</label>
            <input class="form-input form-input--amount" v-model="form.annualIncome" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">婚姻状况</label>
            <select class="form-select" v-model="form.maritalStatus">
              <option value="" disabled>选择婚姻状况</option>
              <option value="MARRIED">已婚</option>
              <option value="SINGLE">未婚</option>
              <option value="DIVORCED">离异</option>
              <option value="WIDOWED">丧偶</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">联系电话</label>
            <input class="form-input" v-model="form.phone" placeholder="数仓带出,可修改" />
          </div>
        </template>
        <div class="form-field">
          <label class="form-field__label">开户机构</label>
          <el-select class="open-org-select" v-model="form.openOrg" filterable allow-create default-first-option clearable placeholder="数仓带出,可修改">
            <el-option v-for="d in openOrgOptions" :key="d.id" :label="d.deptName" :value="d.deptName" />
          </el-select>
        </div>
        <div class="form-field">
          <label class="form-field__label">开户日期</label>
          <input class="form-input" v-model="form.openDate" placeholder="数仓带出,可修改" />
        </div>
        <div class="form-field">
          <label class="form-field__label">申请机构</label>
          <input class="form-input" :value="applyOrgText" disabled />
        </div>
      </div>

      <!-- 关联人员(§12.4④,随申请备注结构附带提交;证件号失焦全行判重+录入即绑定,§6.2/§10.3.21) -->
      <RelatedPersonsEditor v-model="relations" :customer-no="form.customerNo" :group-no="isGroup ? form.groupNo : ''" :application-id="draft.id || undefined" style="margin-top:16px" />

      <div class="wizard-actions">
        <span></span>
        <button class="btn btn--primary" @click="goNext(1)">下一步:融资情况</button>
      </div>
    </div>

    <!-- 第二步:业务/合同(融资情况:本行融资+他行融资) -->
    <div v-show="step === 1" class="form-card">
      <div class="form-card__title">融资情况</div>

      <template v-if="form.customerScope === 'GROUP'">
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
      <InfoTip content="概要来自数仓,可编辑;提交时与下方融资明细自动核对(授信机构数/总额/已用额/笔数)" />
      <table class="table">
        <thead>
          <tr>
            <th>授信机构数</th><th>他行授信总额(万元)</th><th>已用额度合计(万元)</th>
            <th>未结清笔数</th><th>逾期账户数</th><th>逾期余额(万元)</th>
            <th>不良贷款余额(万元)</th><th>关注类余额(万元)</th><th>对外担保余额(万元)</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><input class="form-input form-input--amount" v-model="otherSummary.lenderCount" type="number" min="0" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.creditAmountTotal" type="number" min="0" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.usedAmountTotal" type="number" min="0" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.loanAccountCount" type="number" min="0" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.overdueAccountCount" type="number" min="0" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.overdueBalance" type="number" min="0" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.nplBalance" type="number" min="0" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.specialMentionBalance" type="number" min="0" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.externalGuaranteeBalance" type="number" min="0" step="0.0001" placeholder="—" /></td>
          </tr>
        </tbody>
      </table>

      <div class="sub-title" style="margin-top:20px">他行融资明细</div>
      <table class="table" v-if="otherLoans.length">
        <thead>
          <tr><th>融资机构</th><th>授信额(万元)</th><th>已用额(万元)</th><th>余额(万元)</th><th>年化利率</th><th>来源</th></tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in otherLoans" :key="i">
            <td><input class="form-input" v-model="d.lenderName" /></td>
            <td><input class="form-input form-input--amount" v-model="d.creditAmount" type="number" min="0" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.usedAmount" type="number" min="0" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.balanceAmount" type="number" min="0" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.annualRate" type="number" min="0" step="0.000001" /></td>
            <td><span class="badge badge--neutral">{{ inputModeText(d.inputMode) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂无他行融资明细(可人工补录或 Excel 导入)</div>
      <div style="display:flex;gap:8px;margin-top:12px;align-items:center">
        <button class="btn btn--secondary" @click="addOtherLoan">＋ 添加他行融资</button>
        <button class="btn btn--secondary" @click="triggerImport"><el-icon><Upload /></el-icon>Excel 导入</button>
        <a class="btn btn--text" href="/templates/other-loans-template.xlsx" download="他行融资明细导入模板.xlsx"><el-icon><Download /></el-icon>模板下载</a>
        <InfoTip content="导入列顺序:融资机构 | 授信额(万元) | 已用额(万元) | 余额(万元) | 年化利率%" />
      </div>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onImportFile" />

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 0">上一步</button>
        <button class="btn btn--primary" @click="goNext(2)">下一步:利率申请</button>
      </div>
    </div>

    <!-- 第三步:利率申请(按成员×合同切分,逐担保方式;执行利率集中在分项录入) -->
    <div v-show="step === 2" class="form-card">
      <div class="form-card__title">
        利率申请
        <span class="badge badge--info">逐担保方式独立路由/表决</span>
        <span class="badge badge--warning">贷款利率越低越优惠</span>
        <InfoTip>
          <template v-if="form.businessType === 'EXISTING'">存量调息:按数仓授信担保拆分明细勾选拆分项,填申请利率提交(与新增一致,只关注拆分项,不再按合同/授信协议拆分)。</template>
          <template v-else>新增授信:尚无授信担保拆分项,按担保方式手工切分授信额度。</template>
          集团场景按“成员 × 担保项”生成分项;申请利率不限,任何利率均可提交审批(产品硬边界仅作展示,不再限制)。
        </InfoTip>
      </div>

      <!-- 申请要素(业务类型):决定存量调息/新增授信,客户与合同带出后自动填充、可手工切换;
           贷款品种/金额档为自动带出的重复展示,已移除(分项产品体现客户类型、分项金额可见) -->
      <div class="form-grid" style="margin-bottom:14px">
        <div class="form-field">
          <label class="form-field__label">业务类型 <span class="req">*</span></label>
          <select class="form-select" v-model="form.businessType" @change="onBusinessTypeChange">
            <option value="EXISTING">存量调息(现有贷款合同)</option>
            <option value="NEW">新增授信(拟签合同)</option>
          </select>
        </div>
      </div>

      <!-- 授信信息:存量=数仓授信协议只读(协议编号/授信金额/授信起止日期/授信状态)+授信总金额+拆分细项合计;新增=总授信额度手工录入+拆分细项合计 -->
      <div class="credit-overview">
        <template v-if="form.businessType === 'EXISTING'">
          <div v-if="creditAgreements.length" class="credit-overview__agreement">
            <table class="table">
              <thead><tr><th>授信协议编号</th><th>授信金额(万元)</th><th>授信日期</th><th>授信状态</th></tr></thead>
              <tbody>
                <tr v-for="a in creditAgreements" :key="a.agreementNo">
                  <td>{{ a.agreementNo || '—' }}</td>
                  <td>{{ a.creditAmount ?? '—' }}</td>
                  <td>{{ [a.startDate, a.endDate].filter(Boolean).join(' 至 ') || '—' }}</td>
                  <td><span :class="agreementStatusBadge(a.agreementStatus)">{{ agreementStatusText(a.agreementStatus) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="empty" style="margin-bottom:10px">暂无授信协议数据(数仓未推送)</div>
          <div class="credit-overview__item credit-overview__item--static">
            <span>授信总金额(万元)</span><b>{{ creditTotalText }}</b>
          </div>
          <div class="credit-overview__item credit-overview__item--static">
            <span>拆分细项合计(万元)</span><b>{{ guaranteesTotalText }}</b>
          </div>
        </template>
        <template v-else>
          <div class="credit-overview__item">
            <span>总授信额度(万元)</span>
            <input class="form-input form-input--amount" v-model="form.totalCredit" placeholder="手工录入" />
          </div>
          <div class="credit-overview__item credit-overview__item--static">
            <span>拆分细项合计(万元)</span><b>{{ guaranteesTotalText }}</b>
          </div>
        </template>
        <template v-if="form.customerScope === 'GROUP'">
          <div class="credit-overview__item credit-overview__item--static"><span>集团批复总额度(万元)</span><b>{{ groupCredit?.approvedTotalAmount ?? '暂无数据' }}</b></div>
          <div class="credit-overview__item credit-overview__item--static"><span>集团可用额度(万元)</span><b>{{ groupCredit?.availableAmount ?? '暂无数据' }}</b></div>
        </template>
      </div>

      <div v-if="overGroupAvailable" class="credit-overview-warning">
        分项金额合计已超过集团可用额度,请调整分项金额;是否超授以服务端提交校验为准。
      </div>

      <!-- 存量无拆分项提示:数仓未推送时按担保方式手工添加 -->
      <div v-if="form.businessType === 'EXISTING' && !creditSplits.length" class="empty" style="margin-bottom:12px">该客户暂无有效授信担保拆分明细(数仓未推送),请在下方按担保方式手工添加分项。</div>

      <!-- 担保分项卡片:同一 form.guarantees 行承载担保方式/措施明细 + 产品/期限/金额/利率 -->

      <div v-for="(g, idx) in form.guarantees" :key="idx" class="mortgage-item guarantee-item">
        <div class="mortgage-item__head">
          <span class="guarantee-item__title">
            分项{{ cnOrdinal(idx + 1) }}（{{ guaranteeTypeText(g.guaranteeType) }}）
            <span v-if="g.sourceSplitNo" class="badge badge--info">拆分项 {{ g.sourceSplitNo }}</span>
            <span v-if="g.guaranteeType === 'MORTGAGE'" class="badge badge--neutral">抵押物 {{ g.mortgages.length }} 项</span>
            <span v-else-if="g.guaranteeType === 'GUARANTEE'" class="badge badge--neutral">保证人 {{ g.guarantors.length }} 人</span>
            <span v-else-if="g.guaranteeType === 'PLEDGE'" class="badge badge--neutral">质押物 {{ g.pledges.length }} 项</span>
            <span v-else-if="isMarginType(g.guaranteeType)" class="badge badge--neutral">保证金 {{ g.margins.length }} 笔</span>
            <span v-else-if="g.guaranteeType === 'CERTIFICATE_DEPOSIT'" class="badge badge--neutral">存单 {{ g.cds.length }} 张</span>
            <span v-else class="badge badge--neutral">无需措施</span>
          </span>
          <button class="btn btn--text" @click="removeGuarantee(idx)" v-if="form.guarantees.length > 1">删除</button>
        </div>
        <!-- 第一行:新增/存量统一按担保方式切分;存量拆分项由上方勾选带出(担保方式/金额/原利率/措施预填,可再编辑) -->
        <div class="mortgage-item__grid">
          <div class="form-field" v-if="form.customerScope === 'GROUP'">
            <label class="form-field__label">涉及成员 <span class="req">*</span></label>
            <select class="form-select" v-model="g.memberCustomerNo">
              <option value="" disabled>选择成员</option>
              <option v-for="m in selectedMembers" :key="m.memberCustomerNo" :value="m.memberCustomerNo">
                {{ m.memberName || m.memberCustomerNo }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">担保方式 <span class="req">*</span></label>
            <select class="form-select" v-model="g.guaranteeType">
              <option v-for="t in guaranteeTypes" :key="t.code" :value="t.code">{{ t.name }}</option>
            </select>
          </div>
        </div>
        <!-- 第二行:产品/期限/金额/原利率(存量带出只读)/申请利率/币种 -->
        <div class="mortgage-item__grid" style="margin-top:10px">
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
              <input class="form-input form-input--amount" v-model="g.termValue" placeholder="数值" style="flex:1" />
              <select class="form-select" v-model="g.termUnit" style="width:76px">
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
            <input class="form-input form-input--amount" v-model="g.requestedRate" type="number" min="0" max="100" step="0.000001" placeholder="如 3.40" />
          </div>
          <div class="form-field">
            <label class="form-field__label">币种</label>
            <select class="form-select" v-model="g.currency">
              <option v-for="c in currencies" :key="c" :value="c">{{ currencyText(c) }}</option>
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
                  <div class="mortgage-item__grid">
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
        <div v-if="isMarginType(g.guaranteeType)" class="guarantee-detail-block">
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
        <InfoTip content="下拉选择承诺指标,参照数仓当前贡献度录入基线与拟达成目标;承诺随申请提交(commitments),审批通过后生成正式承诺计划跟踪。" />
      </div>

      <div class="sub-title">当前贡献度参考 <span class="badge badge--info">数仓取数</span></div>
      <ContributionPanel :contribution="contributionCurrent" :show-commitments="false" />

      <div class="sub-title">拟达成承诺</div>
      <div v-if="commitments.length" class="commitment-list">
        <div v-for="(c, i) in commitments" :key="i" class="commitment-card">
          <div class="commitment-card__head">
            <span class="commitment-card__no">承诺 {{ i + 1 }}</span>
            <button class="btn btn--text" @click="commitments.splice(i, 1)">删除</button>
          </div>
          <div class="form-grid commitment-card__grid">
            <div class="form-field">
              <label class="form-field__label">承诺指标 <span class="req">*</span></label>
              <select class="form-select" v-model="c.metricCode" @change="onMetricChange(c)">
                <option v-for="m in metricDict" :key="m.code" :value="m.code">{{ m.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">当前贡献度</label>
              <div class="section-tip commitment-static">{{ c.metricCode === 'OTHER' ? '—' : (currentOf(c.metricCode) ?? '—') }}</div>
            </div>
            <div class="form-field">
              <label class="form-field__label">目标类型 <span class="req">*</span></label>
              <span v-if="c.metricCode === 'OTHER'" class="badge badge--neutral commitment-static">手工描述</span>
              <select v-else class="form-select" v-model="c.targetType">
                <option value="TARGET_BALANCE">目标余额</option>
                <option value="INCREMENT">承诺新增</option>
                <option value="CUMULATIVE">期间累计</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">基线值</label>
              <input v-if="c.metricCode !== 'OTHER'" class="form-input form-input--amount" v-model="c.baselineValue" placeholder="可空" />
              <div v-else class="section-tip commitment-static">—</div>
            </div>
            <div class="form-field">
              <label class="form-field__label">拟达成目标 <span class="req">*</span></label>
              <template v-if="c.metricCode === 'OTHER'">
                <input class="form-input" v-model="c.commitmentDesc" placeholder="目标描述(金额或文本,§6.4)" />
                <div class="section-tip" style="color:var(--color-warning);margin-top:4px">手工描述跟踪,无数值达成率,不参与机构达成率</div>
              </template>
              <input v-else class="form-input form-input--amount" v-model="c.targetValue" />
            </div>
            <div class="form-field">
              <label class="form-field__label">截止日期 <span class="req">*</span></label>
              <input type="date" class="form-input" v-model="c.endDate" placeholder="承诺截止" />
            </div>
            <div class="form-field">
              <label class="form-field__label">单位</label>
              <template v-if="c.metricCode === 'OTHER'"><div class="section-tip commitment-static">—</div></template>
              <select v-else class="form-select" v-model="c.unit">
                <option value="WAN_YUAN">万元</option>
                <option value="COUNT">户/笔</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">适用范围</label>
              <select class="form-select" v-model="c.metricScope">
                <option value="PUBLIC">对公</option>
                <option value="PRIVATE_SELF">本人对私</option>
                <option value="RELATED">关联人</option>
                <option value="GROUP">集团</option>
                <option value="GROUP_MEMBER">集团成员</option>
              </select>
            </div>
            <div class="form-field" v-if="form.customerScope === 'GROUP'">
              <label class="form-field__label">成员</label>
              <select class="form-select" v-model="c.memberCustomerNo">
                <option value="">集团整体</option>
                <option v-for="m in selectedMembers" :key="m.memberCustomerNo" :value="m.memberCustomerNo">
                  {{ m.memberName || m.memberCustomerNo }}
                </option>
              </select>
            </div>
          </div>
        </div>
      </div>
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

      <div class="sub-title">
        其他申请附件
        <InfoTip content="附件随草稿保存/提交上传至申请单,审批各环节可查看;已上传附件在审批详情展示。" />
      </div>
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
      <div class="form-card__title">
        提交预览
        <InfoTip content="提交前先生成/保存草稿,再逐分项预览审批路由;正式提交需通过数据批次差异与质量预校验确认(§7.1 步骤9-11)。" />
      </div>
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
          <button class="btn btn--secondary" :disabled="saving || submitted" @click="onSaveDraft">存草稿</button>
          <button class="btn btn--secondary" :disabled="saving || submitted" @click="onRoutePreview">路由预览</button>
          <button class="btn btn--primary" :disabled="saving || submitting || submitted" @click="onSubmit">{{ submitted ? '已提交' : '提交申请' }}</button>
        </div>
      </div>
    </div>

    <!-- 提交前校验确认弹窗 -->
    <SubmitCheckDialog v-model="checkDialogVisible" :check="checkResult" :submitting="submitting" @confirm="onConfirmSubmit" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { listEnabledProducts } from '@/api/system'
import {
  searchCustomers as apiSearchCustomers,
  getCustomerDetail,
  getCustomerBusinessView,
  getGroup,
  getGroupMembers,
  suggestGroups,
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
  getOpenOrgs,
  type ApplicationPayload,
  type GuaranteeMeasureInput,
  type RoutePreview,
  type SubmitCheck
} from '@/api/application'
import SubmitCheckDialog from './SubmitCheckDialog.vue'
import {
  GUARANTEE_TYPES, guaranteeTypeText, nodeLabel, rateDirectionText,
  productName, inputModeText, LOAN_PRODUCTS, agreementTypeText, agreementStatusText, agreementStatusBadge,
  AGREEMENT_TYPES, certTypeText, groupStatusText, groupNatureText, currencyText, maritalStatusCode,
  FIVE_LEVEL_OPTIONS, normalizeFiveLevelClass, customerNoText, isManualCustomerNo
} from '@/utils/dict'
import { useMetricDict } from '@/store/metricDict'
import RelatedPersonsEditor, { serializeRelations, parseRelations, validateRelations, occupiedRelations, type RelatedPersonRow } from './RelatedPersonsEditor.vue'
import ContributionPanel from '@/components/ContributionPanel.vue'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

// ---------- 步骤条(§14.1) ----------
const steps = ['客户信息', '融资情况', '利率申请', '贡献承诺', '材料附件', '提交预览']
const step = ref(0)

// ---------- 字典 ----------
const guaranteeTypes = GUARANTEE_TYPES
const agreementTypes = AGREEMENT_TYPES
const currencies = ['CNY', 'USD', 'EUR', 'HKD', 'JPY']
// 五级分类下拉(数仓码值定稿:010 正常/020 关注/030 次级/040 可疑/050 损失;补录与对公可修改表单统一)
const fiveLevelOptions = FIVE_LEVEL_OPTIONS
// 贷款产品(与规则/硬边界配置中的 product_code 对齐)
// P2-4:以产品目录 ccr_product 为权威来源,目录为空时回退内置字典(避免新建环境缺目录不可用)
const loanProducts = ref<Array<{ code: string; name: string }>>(
  LOAN_PRODUCTS.map((p) => ({ code: p.code, name: p.name }))
)
async function loadLoanProducts() {
  try {
    const rows = await listEnabledProducts('LOAN')
    if (rows?.length) {
      loanProducts.value = rows.map((r) => ({ code: r.productCode, name: r.productName }))
    }
  } catch {
    // 失败保持字典回退
  }
}
// 开户机构下拉匹配(§用户要求):数据源 ccr_sys_dept 启用机构(客户经理可访问接口 /ccr/customers/open-orgs)
const openOrgOptions = ref<Array<{ id: number; deptName: string }>>([])
async function loadOpenOrgOptions() {
  try {
    const rows = await getOpenOrgs()
    openOrgOptions.value = (rows || []).map((r) => ({ id: r.id, deptName: r.deptName }))
  } catch {
    // 失败保持空,不影响手工输入
  }
}
// 贡献度指标字典(§9;ccr_metric_definition 权威来源,store 拉取,失败回退静态;当前值由数仓带出,不做静态假定)
const metricDict = computed(() => useMetricDict().list)

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
  /** 数仓授信担保拆分项编号(存量调息勾选来源,需求②;新增为空) */
  sourceSplitNo: string
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
/** 授信协议补录/修正要素(存量=数仓带出可修正;新增=手工补录,协议号可空;审批详情优先展示补录值) */
interface CreditInfo {
  agreementNo: string
  agreementType: string
  currency: string
  agreementStatus: string
  creditAmount: string
  usedAmount: string
  availableAmount: string
  startDate: string
  endDate: string
}
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
  /** 承诺完成截止日期(在什么时间点内完成,审批端拟达成贡献度同步展示) */
  endDate: string
}

/** 授信协议补录/修正初始值(新增授信协议号可空) */
function initialCreditInfo(): CreditInfo {
  return {
    agreementNo: '', agreementType: '', currency: 'CNY', agreementStatus: '',
    creditAmount: '', usedAmount: '', availableAmount: '', startDate: '', endDate: ''
  }
}

function newGuarantee(scope?: string): GuaranteeRow {
  return {
    memberCustomerNo: '', sourceSplitNo: '', guaranteeType: 'MORTGAGE',
    productCode: defaultProductByScope(scope ?? form.customerScope), termValue: '', termUnit: 'MONTH', amount: '', currency: 'CNY',
    originalRate: '', requestedRate: '', mortgages: [], guarantors: [], pledges: [], margins: [], cds: []
  }
}

/** 贷款产品按客户类型默认:个人客户→个人经营性贷款(LOAN_P),对公/集团(成员为企业)→对公贷款(LOAN_A);scope 入参避免 form 初始化前 TDZ 崩溃 */
function defaultProductByScope(scope: string): string {
  return scope === 'INDIVIDUAL' ? 'LOAN_P' : 'LOAN_A'
}

const form = reactive({
  customerScope: 'CORPORATE', // CORPORATE/INDIVIDUAL/GROUP(提交时映射 CORPORATE_SINGLE)
  customerName: '',
  customerNo: '',
  loanType: 'CORP_LOAN',
  businessType: 'NEW', // EXISTING 存量调息 / NEW 新增授信
  totalCredit: '', // 总授信额度(存量=拆分合计自动同步;新增=手工录入)
  creditAgreementNo: '', // 授信协议编号(存量)
  creditInfo: initialCreditInfo(), // 授信协议补录/修正要素(存量带出可改;新增手工补录,协议号可空)
  amountTier: 'LT_5000',
  customerNature: '', // 客户性质由数仓 customerClass 自动判定(存量/新增),不允许手选
  customerType: 'NON_SOE',
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '', industry: '', registeredCapital: '', basicAccount: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', annualIncome: '', maritalStatus: '', phone: '',
  // 通用
  openOrg: '', openDate: '',
  // 集团
  groupNo: '',
  applicationRemark: '',
  guarantees: [newGuarantee('CORPORATE')] as GuaranteeRow[]
})

const applyOrgText = computed(() => userStore.userInfo?.orgName || (userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据'))

// 客户性质只读展示(§用户要求):由数仓 customerClass 自动判定,不允许手选;未选客户显示占位
const customerNatureText = computed(() => {
  if (form.customerNature === 'EXISTING') return '存量客户'
  if (form.customerNature === 'NEW') return '新增客户'
  return '—'
})

// 数仓带出数据
const ownFinancing = ref<any[]>([])
const otherSummary = ref<any>({})
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
// 集团补录(§docs/19 集团补录集成申请页):新增集团就地补录 + 本次申请额度 + 手工补录成员
/** 本次申请额度(万元,集团本次申请新增授信,必填;数仓批复授信仅展示参考,勾稽基准读本字段) */
const groupApplyAmount = ref('')
/** 数仓未收录该集团(按新增集团对待,就地补录集团基本信息,与对公客户一致) */
const isNewGroup = ref(false)
/** 新增集团补录表单(集团对公全套:与对公客户基本信息一致 + 集团特有要素) */
const groupSupplement = reactive({
  groupName: '',
  ucrCode: '',
  fiveLevelClass: '',
  creditLevel: '',
  industry: '',
  registeredCapital: '',
  openOrg: '',
  openDate: '',
  basicAccount: '',
  groupType: 'INDUSTRY_GROUP',
  currency: 'CNY',
  /** 国企集团属性Y/N(集团本身属性,非旗下企业;§用户要求 2026-08-25,新增集团补录/存量集团回显) */
  stateOwnedFlag: '',
})
/** 成员补录区展开开关 */
const showSupplementMember = ref(false)
/** 手工补录成员行(对公客户申请要素全套 + 成员要素;不含授信) */
const supplementMembers = ref<Array<Record<string, any>>>([])

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
// 提交成功后置灰,防重复提交(§用户要求)
const submitted = ref(false)
const routeResult = ref<RoutePreview | null>(null)
const checkResult = ref<SubmitCheck | null>(null)
const checkDialogVisible = ref(false)
/** 用户是否手动选定过业务类型(存量调息/新增授信);true 时带出客户不再自动切存量(§用户要求) */
const userPickedBusinessType = ref(false)

// ---------- 客户查询带出(数仓) ----------
/** 客户名称联想下拉(el-autocomplete fetch-suggestions;输入即查,取消独立查询按钮) */
async function queryCustomerSuggestions(queryString: string, cb: (list: any[]) => void) {
  if (!queryString || !queryString.trim()) return cb([])
  try {
    const rows = await apiSearchCustomers(queryString.trim())
    cb((rows || []).map(r => ({
      value: `${r.customerName} · ${r.customerNo} · ${r.custType === 'CORP' ? '对公' : '个人'}`,
      data: r,
    })))
  } catch {
    cb([])
  }
}

async function selectCustomer(item: any) {
  const c = item?.data ?? item
  form.customerNo = c.customerNo
  form.customerName = c.customerName
  form.customerScope = c.custType === 'INDV' ? 'INDIVIDUAL' : 'CORPORATE'
  // 换客户重新自动判断业务类型(名下有合同默认存量;客户经理手动选定后保持其选择)
  userPickedBusinessType.value = false
  await loadCustomerDetail()
}

async function loadCustomerDetail() {
  if (!form.customerNo) return
  try {
    const detail = await getCustomerDetail(form.customerNo)
    const basic = detail.basic || {}
    form.ucrCode = basic.certNo || ''
    form.fiveLevelClass = normalizeFiveLevelClass(basic.fiveLevelClass || '')
    form.creditLevel = basic.creditLevel || ''
    form.industry = basic.industry || ''
    form.registeredCapital = basic.registeredCapital || ''
    form.openOrg = basic.openOrgName || ''
    form.openDate = basic.openDate || ''
    form.basicAccount = basic.basicAccount || form.basicAccount
    form.idType = basic.certType || ''
    form.idNo = basic.certNo || ''
    form.occupation = basic.occupation || ''
    form.annualIncome = basic.annualIncome || ''
    form.maritalStatus = maritalStatusCode(basic.maritalStatus)
    form.phone = basic.phone || ''
    form.customerNature = basic.customerClass === 'EXISTING' ? 'EXISTING' : 'NEW'
    // 企业性质带出(数仓 entp_charic 仅 SOE 判国企,其余非国企,与后端 resolveCustomerType 同口径)
    form.customerType = basic.entpCharic === 'SOE' ? 'SOE' : 'NON_SOE'
    ownFinancing.value = detail.financing || []
    // 授信协议(数仓);存量模式:授信项下全部贷款合同自动列为分项(可删除不需要调息的)
    try {
      const view = await getCustomerBusinessView(form.customerNo)
      creditAgreements.value = view.creditAgreements || []
      creditContracts.value = view.contracts || []
      relatedGuarantees.value = view.guarantees || { mortgages: [], guarantors: [] }
      creditSplits.value = view.creditSplits || []
      // 申请要素自动带出(§用户要求):贷款品种按客户类型、金额档按合同金额合计、业务类型按是否名下有合同
      form.loanType = form.customerScope === 'INDIVIDUAL' ? 'PERSONAL_LOAN' : 'CORP_LOAN'
      const contractRows = view.contracts || []
      const totalContractAmt = contractRows.reduce((s, c: any) => s + (Number(c.contractAmount) || 0), 0)
      if (totalContractAmt > 0) form.amountTier = totalContractAmt >= 5000 ? 'GE_5000' : 'LT_5000'
      // 需求②:存量利率申请按数仓授信担保拆分明细勾选(不再按授信协议/贷款合同);客户有拆分项默认进入存量并全选
      if (creditSplits.value.length && !userPickedBusinessType.value) {
        if (form.businessType !== 'EXISTING') form.businessType = 'EXISTING'
        form.guarantees = []
        selectAllSplits()

      }
    } catch { /* 忽略 */ }
    contributionCurrent.value = detail.contribution || []
    otherSummary.value = { ...(detail.creditSummary?.[0] || {}) }
    otherLoans.value = (detail.creditDetail || []).map((d: any) => ({ ...d, inputMode: 'DW' }))
    ElMessage.success(`已带出客户 ${form.customerName || form.customerNo} 信息`)
  } catch {
    // 数仓无该客户记录(新增客户手工填写)按新户判定;其余错误由拦截器提示
    form.customerNature = 'NEW'
  }
}

// ---------- 集团查询(真实数仓) ----------
/** 集团联想(el-autocomplete fetch-suggestions;输入即查,取消独立查询按钮,§13.1) */
async function queryGroupSuggestions(queryString: string, cb: (list: any[]) => void) {
  if (!queryString || !queryString.trim()) return cb([])
  try {
    const rows = await suggestGroups(queryString.trim())
    cb((rows || []).map(r => ({
      value: `${r.groupNo} · ${r.groupName}`,
      data: r,
    })))
  } catch {
    cb([])
  }
}

/** 集团下拉选中:回填集团号并加载集团信息 */
async function selectGroup(item: any) {
  const g = item?.data ?? item
  form.groupNo = g.groupNo
  await queryGroup()
}

async function queryGroup() {
  if (!form.groupNo || !form.groupNo.trim()) {
    ElMessage.warning('请输入集团客户编号')
    return
  }
  const no = form.groupNo.trim()
  // 数仓未收录(404)→ 按新增集团就地补录(§docs/19 §4.1);命中(数仓/手工落表)→ 存量带出
  try {
    const g = await getGroup(no)
    isNewGroup.value = false
    groupInfo.value = g.group || null
    groupCredit.value = g.groupCredit || null
    groupAllocatedTotal.value = g.allocatedTotal ?? null
  } catch {
    isNewGroup.value = true
    groupInfo.value = null
    groupCredit.value = null
    groupAllocatedTotal.value = null
  }
  groupQueried.value = true
  // 成员:数仓有效成员 + 已落表手工成员(§4.4);未收录则置空(新增集团可手工补录成员)
  try {
    const members = await getGroupMembers(no)
    groupMembers.value = (members || []).map((m: any) => ({ ...m, source: 'DW' }))
  } catch {
    groupMembers.value = []
  }
  // 已勾选但已不在有效成员列表中的成员剔除(就地补录的 MANUAL 行保留在列表内)
  selectedMembers.value = selectedMembers.value.filter((s) =>
    groupMembers.value.some((m) => m.memberCustomerNo === s.memberCustomerNo)
  )
  if (isNewGroup.value) {
    ElMessage.info('数仓未收录该集团,请补录集团基本信息并录入本次申请额度')
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

// ---------- 手工成员补录(§docs/19 §4.4 成员补录=对公客户申请要素全套+成员要素,不含授信) ----------

/** 追加一条空成员补录行 */
function addSupplementMember() {
  supplementMembers.value.push({
    memberCustomerNo: '', memberName: '', ucrCode: '', fiveLevelClass: '', creditLevel: '',
    industry: '', registeredCapital: '', openOrg: '', openDate: '', basicAccount: '',
    memberRole: 'GENERAL', controlRelation: '', relationStart: '', relationEnd: '',
  })
}

/** 补录成员确认:校验必填 → 去重 → 加入可勾选成员列表(标 MANUAL,随申请落 ccr_group_member) */
function confirmSupplementMember(i: number) {
  const m = supplementMembers.value[i]
  if (!m) return
  if (!m.memberName || !m.memberName.trim()) {
    ElMessage.warning('成员名称必填')
    return
  }
  const name = m.memberName.trim()
  let no = m.memberCustomerNo.trim()
  const manualBlank = !no // 非我行客户无客户号:客户号可空
  const ucr = (m.ucrCode || '').trim()
  if (!no && ucr) {
    // 新增客户(有证件号无客户号):生成占位号 NEW+完整证件号(2026-08-20 #017),
    // 提交时按证件号反查数仓回填真实客户号;展示层识别 NEW 前缀显示"新增客户(待回填)"
    no = 'NEW' + ucr
    m.memberCustomerNo = no
  } else if (!no) {
    // 非我行客户(无客户号亦无证件号):生成内部合成号(MANUAL-前缀),展示层识别后显示"非我行客户"
    // 非 HTTPS(http://IP)下 crypto.randomUUID 不可用,降级为时间戳+随机数兜底(2026-08-21 生产事故同源修复)
    no = 'MANUAL-' + (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`)
    m.memberCustomerNo = no
  }
  // 去重:有客户号按客户号;无客户号(合成号唯一不可比)按 名称+统一社会信用代码
  const dup = groupMembers.value.some((gm) => {
    if (!manualBlank) {
      return gm.memberCustomerNo === no
    }
    return (gm.memberName || '').trim() === name && (gm.ucrCode || '') === (m.ucrCode || '').trim()
  })
  if (dup) {
    ElMessage.warning(`成员[${name}]已在成员列表中,请勿重复添加`)
    return
  }
  groupMembers.value.push({
    memberCustomerNo: no,
    memberName: name,
    memberRole: m.memberRole || 'GENERAL',
    creditLimit: null,
    source: 'MANUAL',
    ucrCode: m.ucrCode, fiveLevelClass: m.fiveLevelClass, creditLevel: m.creditLevel,
    industry: m.industry, registeredCapital: m.registeredCapital, openOrg: m.openOrg,
    openDate: m.openDate, basicAccount: m.basicAccount,
    controlRelation: m.controlRelation, relationStart: m.relationStart, relationEnd: m.relationEnd,
  })
  supplementMembers.value.splice(i, 1)
  ElMessage.success(`成员[${name}]已加入成员列表,请勾选并录入本次申请金额`)
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
const creditAgreements = ref<any[]>([])
const creditContracts = ref<any[]>([])
const relatedGuarantees = ref<{ mortgages: any[]; guarantors: any[] }>({ mortgages: [], guarantors: [] })
/** 数仓授信担保拆分明细(T21,存量利率申请勾选来源;每项含措施 T22,需求②) */
const creditSplits = ref<any[]>([])
const guaranteesTotalText = computed(() => (Math.round(guaranteesTotalAmount.value * 100) / 100).toString())
/** 存量授信总金额(数仓授信协议 credit_amount 合计,万元;无协议显示 —) */
const creditTotalText = computed(() => {
  const total = creditAgreements.value.reduce((s, a) => s + (Number(a.creditAmount) || 0), 0)
  return total > 0 ? String(total) : '—'
})
/** 分项中文序号:1→一,2→二(≤10) */
const CN_ORDINAL = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']
function cnOrdinal(n: number): string {
  return CN_ORDINAL[n - 1] ?? String(n)
}


const overGroupAvailable = computed(() => {
  if (form.customerScope !== 'GROUP') return false
  const avail = groupCredit.value?.availableAmount
  if (avail === null || avail === undefined || avail === '') return false
  const n = Number(avail)
  return Number.isFinite(n) && guaranteesTotalAmount.value > n
})

/** 需求②(2026-08-24):存量按担保项拆分,不再按协议/合同自动生成分项(原 autoItemsFromContracts 已移除) */
function onBusinessTypeChange() {
  if (form.businessType === 'EXISTING') {
    form.totalCredit = ''
    form.creditAgreementNo = ''
    form.creditInfo = initialCreditInfo()
    // 需求②:存量按数仓授信担保拆分明细勾选(不再按授信协议/合同),重置分项并默认全选
    form.guarantees = []
    if (form.customerNo) {
      getCustomerBusinessView(form.customerNo)
        .then((view: any) => {
          creditSplits.value = view.creditSplits || []
          creditAgreements.value = view.creditAgreements || []
          selectAllSplits()
        })
        .catch(() => {})
    }
    ElMessage.info('存量调息:已自动带出数仓授信担保拆分项,填申请利率提交')
  } else {
    // 新增授信:无数仓拆分项,重置为一条空白分项由客户经理按担保方式手工补充
    form.creditAgreementNo = ''
    form.totalCredit = ''
    form.creditInfo = initialCreditInfo()
    form.guarantees = [newGuarantee()]
  }
  userPickedBusinessType.value = true
}

/** 保证金类担保(银票/信用证/存单外另行)——保证金质押同样录入金额/比例/期限 */
function isMarginType(t: string) {
  return t === 'BILL_MARGIN' || t === 'CREDIT_MARGIN' || t === 'MARGIN_PLEDGE'
}

/** 数仓抵押物类型 → 表单类型 */
const DW_MORTGAGE_TYPE: Record<string, string> = { FACTORY: '厂房', HOUSE: '住宅', LAND: '土地', EQUIPMENT: '设备', VEHICLE: '车辆' }
function parseExtJson(j: any): any {
  if (!j) return {}
  if (typeof j === 'object') return j
  try { return JSON.parse(j) } catch { return {} }
}
/** 合同关联的抵押物/保证人从数仓带出到分项(可再编辑) */
function populateMeasuresFromDw(g: GuaranteeRow, contractNo: string) {
  // 切换参考合同重新填充,先清空上次带出的措施避免重复
  g.mortgages = []
  g.guarantors = []
  for (const m of relatedGuarantees.value.mortgages.filter((x: any) => x.contractNo === contractNo)) {
    const ext = parseExtJson(m.extJson)
    g.mortgages.push({
      type: DW_MORTGAGE_TYPE[m.mortgageType] || '住宅',
      name: m.mortgageName || '', addr: m.mortgageAddr || '',
      value: m.assessValue != null ? String(m.assessValue) : '',
      owner: m.ownerName || '', ratio: m.mortgageRatio != null ? String(m.mortgageRatio) : '',
      area: ext.area || '', certNo: ext.certNo || m.registerNo || '',
      landUseType: ext.landUseType || '出让', landUseExpiry: ext.landUseExpiry || '',
      specModel: ext.specModel || '', quantity: ext.quantity || '', purchaseDate: ext.purchaseDate || '',
      plateNo: ext.plateNo || '', vin: ext.vin || '', regDate: ext.regDate || ''
    })
  }
  for (const t of relatedGuarantees.value.guarantors.filter((x: any) => x.contractNo === contractNo)) {
    g.guarantors.push({
      name: t.guarantorName || '', certNo: t.guarantorCertNo || '',
      amount: t.guaranteeAmount != null ? String(t.guaranteeAmount) : '',
      balance: t.guaranteeBalance != null ? String(t.guaranteeBalance) : ''
    })
  }
}

// ---------- 需求② 存量:数仓授信担保拆分项自动渲染 ----------

/** 拆分项是否已在分项列表中(sourceSplitNo 命中,供 selectAllSplits 去重) */
function isSplitSelected(splitNo: string): boolean {
  return form.guarantees.some((g) => g.sourceSplitNo === splitNo)
}

/** 数仓拆分项 → 分项行(担保方式/金额/原利率带出,担保措施按 T22 预填,可再编辑) */
function splitToGuarantee(sp: any): GuaranteeRow {
  const g = newGuarantee(form.customerScope)
  g.sourceSplitNo = sp.splitNo
  g.guaranteeType = sp.guaranteeType || 'MORTGAGE'
  g.amount = sp.splitAmount != null ? String(sp.splitAmount) : ''
  g.currency = sp.currency || 'CNY'
  g.originalRate = sp.originalRate != null ? String(sp.originalRate) : ''
  for (const m of sp.measures || []) {
    if (m.measureType === 'MORTGAGE') {
      const ext = parseExtJson(m.extJson)
      g.mortgages.push({
        type: DW_MORTGAGE_TYPE[m.mortgageType] || '住宅',
        name: m.mortgageName || '', addr: m.mortgageAddr || '',
        value: m.assessValue != null ? String(m.assessValue) : '',
        owner: m.ownerName || '', ratio: m.mortgageRatio != null ? String(m.mortgageRatio) : '',
        area: ext.area || '', certNo: ext.certNo || m.registerNo || '',
        landUseType: ext.landUseType || '出让', landUseExpiry: ext.landUseExpiry || '',
        specModel: ext.specModel || '', quantity: ext.quantity || '', purchaseDate: ext.purchaseDate || '',
        plateNo: ext.plateNo || '', vin: ext.vin || '', regDate: ext.regDate || ''
      })
    } else if (m.measureType === 'GUARANTEE' || m.measureType === 'GUARANTOR') {
      g.guarantors.push({
        name: m.guarantorName || '', certNo: m.guarantorCertNo || '',
        amount: m.guaranteeAmount != null ? String(m.guaranteeAmount) : '',
        balance: m.guaranteeBalance != null ? String(m.guaranteeBalance) : ''
      })
    } else if (m.measureType === 'PLEDGE') {
      g.pledges.push({
        type: '存单', name: m.measureName || '',
        value: m.guaranteeAmount != null ? String(m.guaranteeAmount) : '',
        owner: m.ownerName || ''
      })
    } else if (isMarginType(m.measureType)) {
      g.margins.push({
        amount: m.guaranteeAmount != null ? String(m.guaranteeAmount) : '',
        ratio: m.extJson?.marginRatio != null ? String(m.extJson.marginRatio) : '',
        term: m.extJson?.termMonths != null ? String(m.extJson.termMonths) : ''
      })
    } else if (m.measureType === 'CERTIFICATE_DEPOSIT') {
      g.cds.push({
        cdNo: m.collateralNo || m.measureName || '',
        amount: m.guaranteeAmount != null ? String(m.guaranteeAmount) : '',
        maturityDate: m.extJson?.maturityDate || ''
      })
    }
  }
  return g
}

/** 存量自动渲染:将数仓全部拆分项生成为分项卡片(进入存量调息时调用) */
function selectAllSplits() {
  for (const sp of creditSplits.value) {
    if (isSplitSelected(sp.splitNo)) continue
    const g = splitToGuarantee(sp)
    const blankIdx = form.guarantees.findIndex((x) => !x.sourceSplitNo && !x.requestedRate && !x.amount)
    if (blankIdx >= 0) form.guarantees[blankIdx] = g
    else form.guarantees.push(g)
  }
  syncTotalCredit()
}

/** 存量:总授信额度=拆分合计(随分项同步,供提交/展示) */
function syncTotalCredit() {
  if (form.businessType === 'EXISTING') form.totalCredit = guaranteesTotalText
}

/** 合同担保类型(own_financing 按合同号匹配) */
function finGuaranteeType(contractNo: string): string {
  const fin = ownFinancing.value.find((f: any) => f.contractNo === contractNo)
  return fin?.guaranteeType || ''
}

function addGuarantee() {
  // 需求②:存量与新增一致,按担保项拆分,直接追加空白担保行(可参考合同带出预填)
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
    groupCredit.value = null
    groupAllocatedTotal.value = null
    groupMembers.value = []
    groupQueried.value = false
    // 集团补录状态一并重置(§docs/19 §4.1)
    groupApplyAmount.value = ''
    isNewGroup.value = false
    groupSupplement.groupName = ''
    groupSupplement.ucrCode = ''
    groupSupplement.fiveLevelClass = ''
    groupSupplement.creditLevel = ''
    groupSupplement.industry = ''
    groupSupplement.registeredCapital = ''
    groupSupplement.openOrg = ''
    groupSupplement.openDate = ''
    groupSupplement.basicAccount = ''
    showSupplementMember.value = false
    supplementMembers.value = []
  }
  // 贷款产品默认值跟随客户类型:仅补未选择产品的分项(已选产品不覆盖)
  const defaultProduct = defaultProductByScope(form.customerScope)
  for (const g of form.guarantees) {
    if (!g.productCode) g.productCode = defaultProduct
  }
}

// ---------- 贡献承诺 ----------
function currentOf(code: string) {
  const m = contributionCurrent.value.find((x) => x.metricCode === code)
  return m?.metricValue ?? '暂无数据'
}
/** 选择承诺指标时,自动把当前贡献度值带出到基线值(可手工改) */
function onMetricChange(c: CommitmentRow) {
  if (c.metricCode === 'OTHER') return
  const v = currentOf(c.metricCode)
  if (v !== '暂无数据' && v != null && v !== '') {
    c.baselineValue = String(v)
  }
}
function addCommitment() {
  commitments.value.push({
    metricCode: 'PUBLIC_DEPOSIT_AVG', targetType: 'TARGET_BALANCE',
    baselineValue: '', targetValue: '', commitmentDesc: '', unit: 'WAN_YUAN',
    metricScope: form.customerScope === 'GROUP' ? 'GROUP' : 'PUBLIC', memberCustomerNo: '', endDate: ''
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
// 单户场景客户身份:已查客户或有证件号(新增客户无客户号,允许先录证件号,提交时后端反查数仓/占位,2026-08-20 #017)
function hasCustomerIdentity(): boolean {
  if (!isBlank(form.customerNo)) return true
  return form.customerScope === 'INDIVIDUAL' ? !isBlank(form.idNo) : !isBlank(form.ucrCode)
}

function validateStep(s: number): string | null {
  if (s === 0) {
    if (form.customerScope === 'GROUP') {
      if (isBlank(form.groupNo) || !groupQueried.value) return '请录入集团编号并查询加载集团信息'
      // 新增集团(数仓未收录):就地补录集团名称/集团属性必填(§docs/19 §4.3;国企属性 §2026-08-25)
      if (isNewGroup.value && isBlank(groupSupplement.groupName)) return '请补录集团名称(数仓未收录,新增集团必填)'
      if (isNewGroup.value && isBlank(groupSupplement.stateOwnedFlag)) return '请选择集团属性(国企集团/非国企集团)'
      // 本次申请额度必填(集团本次申请新增授信,§4.5;存量集团补申请额度同理)
      if (isBlank(groupApplyAmount.value) || Number(groupApplyAmount.value) <= 0) return '请录入本次申请额度(集团新增授信,必填)'
      if (!selectedMembers.value.length) return '请至少勾选一名集团成员'
      const bad = selectedMembers.value.find((m) => isBlank(m.requestAmount) || Number(m.requestAmount) <= 0)
      if (bad) return `成员 ${bad.memberCustomerNo} 未录入本次申请金额`
      // 勾稽:成员申请金额合计 ≤ 本次申请额度(§4.5,后端提交时同口径复核)
      const sum = selectedMembers.value.reduce((acc, m) => acc + (Number(m.requestAmount) || 0), 0)
      if (sum > Number(groupApplyAmount.value)) return `成员申请金额合计 ${sum} 超过本次申请额度 ${groupApplyAmount.value}`
    } else if (!hasCustomerIdentity()) {
      return '请查询并选择客户,或录入证件号(新增客户可先录证件号)'
    }
  }
  if (s === 1) {
    // 他行融资概要 vs 明细对应校验(口径与后端 submit checkCreditSummaryConsistency 一致;概要任一要素非空即校验,§2026-08-25)
    const summary = buildCreditSummary()
    if (summary) {
      const rows = otherLoans.value.filter((d) => !isBlank(d.lenderName))
      if (!rows.length) return '他行融资概要已填写,请同步补录融资明细(概要/明细需对应一致)'
      const creditTotal = rows.reduce((acc, d) => acc + (Number(d.creditAmount) || 0), 0)
      const usedTotal = rows.reduce((acc, d) => acc + (Number(d.usedAmount) || 0), 0)
      const distinctLenders = new Set(rows.map((d) => String(d.lenderName).trim())).size
      if (summary.creditAmountTotal !== undefined && Math.abs(creditTotal - Number(summary.creditAmountTotal)) > 1) {
        return `他行融资授信总额(概要 ${summary.creditAmountTotal} 万元)与明细合计(${creditTotal} 万元)不一致`
      }
      if (summary.usedAmountTotal !== undefined && Math.abs(usedTotal - Number(summary.usedAmountTotal)) > 1) {
        return `他行融资已用额合计(概要 ${summary.usedAmountTotal} 万元)与明细合计(${usedTotal} 万元)不一致`
      }
      if (summary.lenderCount !== undefined && Number(summary.lenderCount) !== distinctLenders) {
        return `他行融资授信机构数(概要 ${summary.lenderCount})与明细机构数(${distinctLenders})不一致`
      }
      if (summary.loanAccountCount !== undefined && Number(summary.loanAccountCount) !== rows.length) {
        return `他行融资未结清笔数(概要 ${summary.loanAccountCount})与明细笔数(${rows.length})不一致`
      }
    }
  }
  if (s === 2) {
    for (let i = 0; i < form.guarantees.length; i++) {
      const g = form.guarantees[i]
      if (form.customerScope === 'GROUP' && isBlank(g.memberCustomerNo)) return `第 ${i + 1} 条担保分项未选择集团成员`
      if (isBlank(g.guaranteeType)) return `第 ${i + 1} 条担保分项未选择担保方式`
      if (g.guaranteeType !== 'CREDIT') {
        const n = g.mortgages.length + g.guarantors.length + g.pledges.length + g.margins.length + g.cds.length
        if (n === 0) return `第 ${i + 1} 条担保分项为「${guaranteeTypeText(g.guaranteeType)}」,请至少登记一条担保措施明细`
      }
      if (isBlank(g.productCode)) return `第 ${i + 1} 条分项未选择产品`
      if (isBlank(g.termValue)) return `第 ${i + 1} 条分项未录入期限`
      if (isBlank(g.amount) || Number(g.amount) <= 0) return `第 ${i + 1} 条分项未录入金额`
      if (isBlank(g.requestedRate)) return `第 ${i + 1} 条分项未录入申请利率`
      // 申请利率范围兜底:落库列 DECIMAL(9,6) 整数上限 999,超范围报 MySQL out of range 晦涩错误;合理利率 0~100%(§bug 2026-08-25)
      const rate = Number(g.requestedRate)
      if (!(rate > 0 && rate <= 100)) return `第 ${i + 1} 条分项申请利率须在 0~100 之间(当前 ${g.requestedRate})`
    }
  }
  if (s === 3) {
    for (let i = 0; i < commitments.value.length; i++) {
      const c = commitments.value[i]
      if (isBlank(c.metricCode)) return `第 ${i + 1} 条承诺未选择指标`
      if (c.metricCode === 'OTHER' ? isBlank(c.commitmentDesc) : isBlank(c.targetValue)) return `第 ${i + 1} 条承诺未录入目标`
      if (isBlank(c.endDate)) return `第 ${i + 1} 条承诺未录入截止日期`
    }
  }
  return null
}
async function goNext(target: number) {
  const err = validateStep(step.value)
  if (err) {
    ElMessage.warning(err)
    return
  }
  // 自动暂存:点"下一步"先把上一步信息存为草稿,未完成可稍后从历史申请继续编辑(§用户要求 2026-08-25)
  await autoSaveDraft()
  step.value = target
}
async function goStep(i: number) {
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
  await autoSaveDraft()
  step.value = i
}

function validateForDraft(): string | null {
  const isGroup = form.customerScope === 'GROUP'
  if (isGroup) {
    if (isBlank(form.groupNo)) return '请填写集团客户编号'
    if (!selectedMembers.value.length) return '集团场景请至少勾选一名涉及成员'
    const noAmount = selectedMembers.value.find((m) => isBlank(m.requestAmount))
    if (noAmount) return `成员 ${noAmount.memberName} 未录入本次申请金额`
  } else if (!hasCustomerIdentity()) {
    return '请先查询并选择客户,或录入证件号(新增客户可先录证件号)'
  }
  if (!form.guarantees.length) return '请至少录入一条担保分项'
  for (let i = 0; i < form.guarantees.length; i++) {
    const g = form.guarantees[i]
    if (isGroup && isBlank(g.memberCustomerNo)) return `第 ${i + 1} 条担保分项未选择涉及成员`
    if (isBlank(g.productCode)) return `第 ${i + 1} 条分项未选择产品(利率申请步骤)`
    if (isBlank(g.termValue)) return `第 ${i + 1} 条分项未录入期限(利率申请步骤)`
    if (isBlank(g.amount)) return `第 ${i + 1} 条分项未录入授信金额(利率申请步骤)`
    if (isBlank(g.requestedRate)) return `第 ${i + 1} 条分项未录入申请利率(利率申请步骤)`
    // 申请利率范围兜底(同上,草稿/提交共用)
    const rate = Number(g.requestedRate)
    if (!(rate > 0 && rate <= 100)) return `第 ${i + 1} 条分项申请利率须在 0~100 之间(当前 ${g.requestedRate})`
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
      // 需求②:新增/存量统一纯拆分项——不建合同关系、不回填合同号;存量带数仓拆分项编号溯源
      sourceSplitNo: isBlank(g.sourceSplitNo) ? undefined : g.sourceSplitNo,
      guaranteeType: g.guaranteeType,
      measures: buildMeasures(g)
    })),
    // 关联人(结构化随单提交,审批详情按录入内容展示)
    relatedPersons: relations.value
      .filter((r) => !isBlank(r.name))
      .map((r) => ({
        personName: r.name,
        certType: r.certType || undefined,
        certNo: r.certNo || undefined,
        relationType: r.relationType,
        relatedCustomerNo: isBlank(r.customerNo) ? undefined : r.customerNo
      })),
    // 他行融资明细(人工补录/Excel 导入/数仓带出均可编辑,随单持久化,审批详情展示;空行过滤,§2026-08-25 存量行放开编辑)
    otherLoans: otherLoans.value
      .filter((d) => !isBlank(d.lenderName))
      .map((d) => ({
        lenderName: d.lenderName,
        creditAmount: isBlank(d.creditAmount) ? undefined : d.creditAmount,
        usedAmount: isBlank(d.usedAmount) ? undefined : d.usedAmount,
        balanceAmount: isBlank(d.balanceAmount) ? undefined : d.balanceAmount,
        annualRate: isBlank(d.annualRate) ? undefined : d.annualRate,
        inputMode: d.inputMode || 'MANUAL'
      })),
    // 他行融资概要(数仓带出可编辑,随单持久化;任一要素非空才提交,后端与明细对应校验,§2026-08-25)
    creditSummary: buildCreditSummary() ? [buildCreditSummary() as any] : undefined,
    commitments: commitments.value.map((c) => ({
      metricCode: c.metricCode,
      targetType: c.targetType,
      baselineValue: isBlank(c.baselineValue) ? undefined : c.baselineValue,
      // "其它"承诺无数值目标,以 commitmentDesc 手工描述为准(§6.4,后端持久化 commitment_desc)
      targetValue: c.metricCode === 'OTHER' ? undefined : c.targetValue,
      commitmentDesc: c.metricCode === 'OTHER' ? c.commitmentDesc : undefined,
      unit: c.unit || 'WAN_YUAN',
      metricScope: c.metricScope || 'PUBLIC',
      memberCustomerNo: isBlank(c.memberCustomerNo) ? undefined : c.memberCustomerNo,
      endDate: isBlank(c.endDate) ? undefined : c.endDate
    })),
    applicantUserId: userStore.userInfo?.userId,
    applicantOrgId: userStore.userInfo?.orgId,
    orgId: userStore.userInfo?.orgId,
    // 关联人员随备注结构附带(后端申请单无独立接收字段,§12.4④)
    applicationRemark: ((form.applicationRemark || '') + serializeRelations(relations.value)).trim() || undefined,
    // 客户信息人工修正快照(数仓带出后人工调整,新增客户后台拉不出时手工填写;审批详情优先展示)
    customerInfoJson: isGroup ? null : JSON.stringify({
      customerNo: form.customerNo,
      customerName: form.customerName,
      custType: form.customerScope === 'INDIVIDUAL' ? 'INDV' : 'CORP',
      ucrCode: form.ucrCode,
      fiveLevelClass: form.fiveLevelClass,
      creditLevel: form.creditLevel,
      industry: form.industry,
      registeredCapital: form.registeredCapital,
      idType: form.idType,
      idNo: form.idNo,
      occupation: form.occupation,
      annualIncome: form.annualIncome,
      maritalStatus: form.maritalStatus,
      phone: form.phone,
      openOrg: form.openOrg,
      openDate: form.openDate,
      basicAccount: form.basicAccount
    }),
    // 授信协议补录/修正快照(存量=协议带出可修正;新增=手工补录,协议号可空;审批详情优先展示补录值)
    creditInfoJson: serializeCreditInfo() ? JSON.stringify(serializeCreditInfo()) : undefined,
    // 集团补录/申请额度快照(集团对公全套 + 本次申请额度 + 手工补录成员;提交时落表,§docs/19 §4.5;审批详情优先展示)
    groupInfoJson: serializeGroupInfo() ? JSON.stringify(serializeGroupInfo()) : undefined
  }
}

/** 他行融资概要序列化(数仓带出可编辑快照;任一要素非空才随单提交,提交时后端与明细对应校验;§2026-08-25) */
function buildCreditSummary(): Record<string, unknown> | undefined {
  const s = otherSummary.value || {}
  const num = (v: unknown) => (isBlank(v) ? undefined : Number(v))
  const out: Record<string, unknown> = {}
  if (num(s.lenderCount) !== undefined) out.lenderCount = num(s.lenderCount)
  if (num(s.creditAmountTotal) !== undefined) out.creditAmountTotal = num(s.creditAmountTotal)
  if (num(s.usedAmountTotal) !== undefined) out.usedAmountTotal = num(s.usedAmountTotal)
  if (num(s.loanAccountCount) !== undefined) out.loanAccountCount = num(s.loanAccountCount)
  if (num(s.overdueAccountCount) !== undefined) out.overdueAccountCount = num(s.overdueAccountCount)
  if (num(s.overdueBalance) !== undefined) out.overdueBalance = num(s.overdueBalance)
  if (num(s.nplBalance) !== undefined) out.nplBalance = num(s.nplBalance)
  if (num(s.specialMentionBalance) !== undefined) out.specialMentionBalance = num(s.specialMentionBalance)
  if (num(s.externalGuaranteeBalance) !== undefined) out.externalGuaranteeBalance = num(s.externalGuaranteeBalance)
  return Object.keys(out).length ? out : undefined
}

/** 授信协议补录/修正快照序列化(任一要素非空才随单提交;全空返回 undefined 不落库) */
function serializeCreditInfo(): Record<string, unknown> | undefined {
  const c = form.creditInfo
  // 授信业务类型显式随单提交(NEW=新增授信/EXISTING=存量调息),供后端按授信口径判定存量/新增路由,不以分项原利率推断
  const out: Record<string, unknown> = { businessType: form.businessType }
  if (c.agreementNo) out.agreementNo = c.agreementNo
  if (c.agreementType) out.agreementType = c.agreementType
  if (c.currency) out.currency = c.currency
  if (c.agreementStatus) out.agreementStatus = c.agreementStatus
  if (c.creditAmount) out.creditAmount = c.creditAmount
  if (c.usedAmount) out.usedAmount = c.usedAmount
  if (c.availableAmount) out.availableAmount = c.availableAmount
  if (c.startDate) out.startDate = c.startDate
  if (c.endDate) out.endDate = c.endDate
  return Object.keys(out).length ? out : undefined
}

/** 集团补录/申请额度快照序列化(§docs/19 §4.5 申请额度必填存 group_info_json 多条并存;手工补录成员随单落表;无补录内容不随单提交) */
function serializeGroupInfo(): Record<string, unknown> | undefined {
  if (form.customerScope !== 'GROUP') return undefined
  const out: Record<string, unknown> = { groupNo: form.groupNo.trim() }
  // 新增集团就地补录的集团基本信息(与对公客户申请要求一致;数仓已收录则忽略,数据以数仓为准)
  if (isNewGroup.value) {
    if (groupSupplement.groupName) out.groupName = groupSupplement.groupName
    out.groupType = groupSupplement.groupType || 'INDUSTRY_GROUP'
    out.groupStatus = 'NORMAL'
    // 国企属性(集团本身,§2026-08-25;新增集团必填)
    if (groupSupplement.stateOwnedFlag) out.stateOwnedFlag = groupSupplement.stateOwnedFlag
    out.currency = groupSupplement.currency || 'CNY'
    if (groupSupplement.ucrCode) out.ucrCode = groupSupplement.ucrCode
    if (groupSupplement.fiveLevelClass) out.fiveLevelClass = groupSupplement.fiveLevelClass
    if (groupSupplement.creditLevel) out.creditLevel = groupSupplement.creditLevel
    if (groupSupplement.industry) out.industry = groupSupplement.industry
    if (groupSupplement.registeredCapital) out.registeredCapital = groupSupplement.registeredCapital
    if (groupSupplement.openOrg) out.openOrg = groupSupplement.openOrg
    if (groupSupplement.openDate) out.openDate = groupSupplement.openDate
    if (groupSupplement.basicAccount) out.basicAccount = groupSupplement.basicAccount
  }
  // 本次申请额度(集团本次申请新增授信,必填;每次申请一条,随申请上下文保存不覆盖历史)
  if (groupApplyAmount.value) out.applyAmount = groupApplyAmount.value
  // 手工补录成员(对公客户申请要素 + 成员要素,不含授信;数仓已有成员不在此列)
  const manualMembers = groupMembers.value.filter((m) => m.source === 'MANUAL')
  if (manualMembers.length) {
    out.supplementMembers = manualMembers.map((m) => ({
      memberCustomerNo: m.memberCustomerNo,
      memberName: m.memberName,
      memberRole: m.memberRole || 'GENERAL',
      controlRelation: m.controlRelation || undefined,
      relationStart: m.relationStart || undefined,
      relationEnd: m.relationEnd || undefined,
      ucrCode: m.ucrCode || undefined,
      fiveLevelClass: m.fiveLevelClass || undefined,
      creditLevel: m.creditLevel || undefined,
      industry: m.industry || undefined,
      registeredCapital: m.registeredCapital || undefined,
      openOrg: m.openOrg || undefined,
      openDate: m.openDate || undefined,
      basicAccount: m.basicAccount || undefined
    }))
  }
  const hasContent = !!(out.groupName || out.applyAmount || manualMembers.length)
  return hasContent ? out : undefined
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

/** 自动暂存(点"下一步"/跳步触发):宽松保存当前表单为草稿,不做严格必填校验;
 *  后端 createDraft/saveDraft 仅校验业务类型/客户范围,允许保存不完整草稿(§12.1 DRAFT)。
 *  已填完的步骤内容落库,未完成申请可从历史申请"继续编辑"接着填(§用户要求 2026-08-25)。
 *  失败静默不打断下一步(用户仍可手动点"存草稿"或提交时严格校验)。 */
async function autoSaveDraft() {
  if (saving.value || submitted.value) return
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
  } catch {
    // 自动暂存失败不阻塞切步;提交/路由预览走 ensureDraft 严格校验并已做错误提示
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
  // P1-3:关联人全行判重阻断(§6.2 已绑定其他客户/集团的证件号无法再次绑定,前后端双重拦截)
  const occ = occupiedRelations(relations.value)
  if (occ.length) {
    ElMessage.error(`关联人员「${occ.map((o) => o.name).join('、')}」${occ[0].by},无法重复绑定,请核对后移除或修改`)
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
    submitted.value = true
    // 提交成功直接跳回工作台首页(§用户要求);不再停留申请页,底部操作按钮同步置灰
    ElMessage.success(`申请 ${result.applicationNo} 已提交,当前节点:${nodeLabel(result.items?.[0]?.currentNodeCode)}`)
    router.push('/overview')
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ---------- 关联重提(?reapply={applicationId}:生成新草稿并加载内容) ----------
onMounted(async () => {
  loadLoanProducts()
  loadOpenOrgOptions()
  useMetricDict().load()
  const src = route.query.reapply
  if (src) {
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
    return
  }
  // ?edit={draftId}:从历史页"继续编辑"加载草稿,继续调整后提交(草稿重新发起流程)
  const editId = route.query.edit
  if (editId) {
    draft.id = String(editId)
    try {
      await loadDraftIntoForm(editId)
    } catch {
      // 拦截器已提示
    }
  }
})

async function loadDraftIntoForm(id: number | string) {
  const d = await getApplicationDetail(id)
  const app = d.application
  // 重新加载草稿(编辑/重提)视为可再次提交,解除已提交置灰
  submitted.value = false
  // 同步数据版本号:保存草稿(PUT)必须携带 versionNo(乐观锁),缺失会导致"保存草稿必须携带数据版本号"报错
  draft.versionNo = app.versionNo != null ? app.versionNo : (draft.versionNo ?? 1)
  form.customerScope = app.customerScope === 'GROUP' ? 'GROUP' : app.customerScope === 'INDIVIDUAL' ? 'INDIVIDUAL' : 'CORPORATE'
  form.customerNo = app.customerNo || ''
  form.groupNo = app.groupNo || ''
  // 客户信息人工快照回填(继续编辑/重提):数仓重查可能缺客户名称等字段,以提交时快照为准(§客户信息快照语义)
  const custInfo = parseExtJson(app.customerInfoJson)
  if (custInfo?.customerName) form.customerName = custInfo.customerName
  form.ucrCode = custInfo?.ucrCode || ''
  form.fiveLevelClass = normalizeFiveLevelClass(custInfo?.fiveLevelClass || '')
  form.creditLevel = custInfo?.creditLevel || ''
  form.industry = custInfo?.industry || ''
  form.registeredCapital = custInfo?.registeredCapital || ''
  form.idType = custInfo?.idType || ''
  form.idNo = custInfo?.idNo || ''
  form.occupation = custInfo?.occupation || ''
  form.annualIncome = custInfo?.annualIncome || ''
  form.maritalStatus = maritalStatusCode(custInfo?.maritalStatus)
  form.phone = custInfo?.phone || ''
  form.openOrg = custInfo?.openOrg || ''
  form.openDate = custInfo?.openDate || ''
  form.basicAccount = custInfo?.basicAccount || ''
  // 备注中的【关联人员】块还原到关联人员录入表,避免重复附带
  const [rels, cleanedRemark] = parseRelations(app.applicationRemark || '')
  relations.value = rels
  form.applicationRemark = cleanedRemark

  if (app.customerScope === 'GROUP' && app.groupNo) {
    await queryGroup()
    // 集团补录/申请额度快照回填(§docs/19 §4.5):本次申请额度 + 手工补录成员(数仓带出优先,补录成员仅并入可勾选列表)
    const gInfo = parseExtJson(app.groupInfoJson)
    if (gInfo?.applyAmount != null) groupApplyAmount.value = String(gInfo.applyAmount)
    // 新增集团:集团基本信息从快照回填(数仓仍未收录时,避免客户经理二次录入)
    if (isNewGroup.value && gInfo?.groupName) {
      groupSupplement.groupName = gInfo.groupName
      groupSupplement.stateOwnedFlag = gInfo.stateOwnedFlag || ''
      groupSupplement.ucrCode = gInfo.ucrCode || ''
      groupSupplement.fiveLevelClass = normalizeFiveLevelClass(gInfo.fiveLevelClass || '')
      groupSupplement.creditLevel = gInfo.creditLevel || ''
      groupSupplement.industry = gInfo.industry || ''
      groupSupplement.registeredCapital = gInfo.registeredCapital != null ? String(gInfo.registeredCapital) : ''
      groupSupplement.openOrg = gInfo.openOrg || ''
      groupSupplement.openDate = gInfo.openDate || ''
      groupSupplement.basicAccount = gInfo.basicAccount || ''
    }
    // 手工补录成员并入可勾选列表(标 MANUAL;数仓已带出同客户号则跳过)
    for (const sm of gInfo?.supplementMembers || []) {
      if (sm?.memberCustomerNo && !groupMembers.value.some((gm) => gm.memberCustomerNo === sm.memberCustomerNo)) {
        groupMembers.value.push({ ...sm, source: 'MANUAL', creditLimit: null })
      }
    }
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

  // 分项 → 利率申请;已批准沿用原决议的占位分项不在本页编辑
  const editable = (d.pricingItems || []).filter((p) => p.inheritFlag !== 'Y')
  inheritCount.value = (d.pricingItems || []).length - editable.length
  let hasPlanned = false
  form.guarantees = editable.map((p) => {
    const rel = (d.contractRelations || []).find((r) => r.pricingItemId === p.id)
    const pkg = (d.guaranteePackages || []).find((gp) => gp.guaranteePackage?.pricingItemId === p.id)
    if (rel?.plannedContractFlag === 'Y') hasPlanned = true
    const g = newGuarantee()
    g.memberCustomerNo = p.memberCustomerNo || ''
    g.sourceSplitNo = p.sourceSplitNo || ''
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
    commitmentDesc: c.commitmentDesc || '',
    unit: c.unit || 'WAN_YUAN',
    metricScope: c.metricScope || 'PUBLIC',
    memberCustomerNo: c.memberCustomerNo || '',
    endDate: c.endDate ? String(c.endDate).slice(0, 10) : ''
  }))
}
</script>

<style scoped>
/* 开户机构下拉(el-select)与 .form-input 对齐(40px 高度/边框/圆角一致) */
.open-org-select.el-select { width: 100%; }
.open-org-select.el-select :deep(.el-select__wrapper) {
  min-height: 40px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  box-shadow: 0 0 0 1px #dcdfe6 inset;
}
.open-org-select.el-select :deep(.el-select__placeholder) { color: #c0c4cc; }
.section-head { margin-bottom: 20px; }
.section-title { font-size: var(--fs-h1); font-weight: 700; }
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
.table { border-radius: var(--radius); overflow-x: auto; }
.table--nested { margin-top: 8px; }
.guarantee-item { margin-bottom: 14px; }
.guarantee-item__title { font-size: 14px; font-weight: 600; display: inline-flex; align-items: center; gap: 8px; }
.guarantee-detail-block { margin-top: 12px; border-top: 1px dashed var(--color-border); padding-top: 12px; }

/* 总授信额度概览条(利率申请步骤) */
.credit-overview {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px 16px;
  background: var(--color-primary-light); border-radius: var(--radius-sm);
  padding: 12px 16px; margin-bottom: 14px;
}
.credit-overview__item {
  font-size: 13px; display: flex; flex-direction: column; gap: 4px; min-width: 0;
}
.credit-overview__item span { color: var(--color-text-sub); font-size: 12px; white-space: nowrap; }
.credit-overview__item b { font-variant-numeric: tabular-nums; }
.credit-overview__item--static b { font-weight: 600; min-height: 40px; display: flex; align-items: center; }
.credit-overview__item .form-input, .credit-overview__item .form-select { width: 100%; }
.credit-overview__item--date { flex-direction: column; }
.credit-overview__range { display: flex; align-items: center; gap: 6px; min-width: 0; }
.credit-overview__range .form-input { min-width: 0; }
.credit-overview__range-sep { color: var(--color-text-sub); flex-shrink: 0; }
/* 新增授信时协议下拉禁用:灰底+不可点,提示资料需手工录入(§用户要求) */
.credit-overview select:disabled { background: var(--color-bg, #f3f4f6); color: var(--color-text-sub); cursor: not-allowed; }
.credit-overview-warning {
  background: #fef2f2; color: var(--color-danger);
  border: 1px solid var(--color-danger); border-radius: var(--radius-sm);
  padding: 8px 12px; font-size: 13px; margin-bottom: 14px;
}
/* 存量授信协议只读表(数仓带出,占满 credit-overview 整行) */
.credit-overview__agreement { grid-column: 1 / -1; }
.credit-overview__agreement .table { margin-bottom: 0; }
.customer-cands { margin-top: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow-x: auto; background: var(--color-surface); }
.customer-cand { padding: 8px 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid var(--color-border); }
.customer-cand:last-child { border-bottom: none; }
.customer-cand:hover { background: var(--color-primary-light); }
.detail-title { font-size: 13px; font-weight: 600; color: var(--color-text-sub); display: flex; align-items: center; justify-content: space-between; }
.req { color: var(--color-danger); }
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }

/* 拟达成承诺卡片(替代横向表格:承诺字段多,表格列宽超容器被横向滚动截断,卡片 3 列网格完整展示) */
.commitment-list { display: flex; flex-direction: column; gap: 12px; }
.commitment-card {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  padding: 12px 16px; background: var(--color-surface);
}
.commitment-card__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.commitment-card__no { font-size: 13px; font-weight: 600; color: var(--color-text-main); }
.commitment-card__grid { margin-bottom: 0; }
.commitment-card__grid .form-field { margin-bottom: 0; }
.commitment-static { min-height: 40px; display: flex; align-items: center; }

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
  border-left: 3px solid var(--color-primary); border-radius: var(--radius-sm);
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

/* 利率申请分项卡片网格(3 列等宽,minmax 防内容溢出) */
.mortgage-item__grid { grid-template-columns: repeat(3, minmax(0, 1fr)) !important; gap: 12px 20px !important; }
.mortgage-item__grid .form-input, .mortgage-item__grid .form-select { width: 100%; }
@media (max-width: 1100px) { .mortgage-item__grid { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; } }
.credit-overview__item--full { grid-column: 1 / -1; }
/* 向导内容铺满主区(2026-08-21:移除 1360px 限宽,宽屏下页面整体占满不留右侧留白) */

/* 协议项下合同与关联担保 */
.agreement-detail { background: #f8fafc; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 12px 16px; margin-bottom: 14px; }
.agreement-detail .sub-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.agreement-detail__gua { display: flex; flex-wrap: wrap; gap: 8px; }
.gua-chip { display: inline-flex; align-items: center; gap: 6px; background: #fff; border: 1px solid var(--color-border); border-radius: 999px; padding: 4px 12px; font-size: 13px; }

/* 中间断点:中等宽度下 3 列网格降为 2 列(页面自适应增强) */
@media (max-width: 1100px) {
  .form-grid, .credit-overview, .group-summary { grid-template-columns: repeat(2, 1fr); }
}
</style>
