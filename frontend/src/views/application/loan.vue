<template>
  <div class="wizard-page">
    <div class="section-head">
      <div class="section-title">贷款利率申请</div>
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

        <!-- 集团:查询与集团信息字段化紧凑展示(§2026-08-25 仿对公/个人字段行;授信总额已删(§2026-08-26),全部字段 span1 等宽 4 列不补字段,联想面板跟随输入框不拉宽) -->
        <template v-if="form.customerScope === 'GROUP'">
          <div class="form-field">
            <label class="form-field__label">集团客户名称 <span class="req">*</span></label>
            <el-autocomplete
              v-model="form.groupName"
              :fetch-suggestions="queryGroupSuggestions"
              :trigger-on-focus="false"
              clearable
              placeholder="输入集团名称联想选择;未收录回车补录"
              style="width:100%"
              @select="selectGroup"
              @keyup.enter="queryGroup"
            />
          </div>
          <div class="form-field">
            <label class="form-field__label">集团客户编号</label>
            <input class="form-input" :value="form.groupNo" readonly placeholder="查询后带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">集团属性</label>
            <select class="form-select" v-model="form.stateOwnedFlag">
              <option value="">请选择</option>
              <option value="Y">国企</option>
              <option value="N">非国企</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">统一社会信用代码</label>
            <input class="form-input" v-model="form.ucrCode" placeholder="查询后带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五级分类</label>
            <select class="form-select" v-model="form.fiveLevelClass">
              <option value="">请选择</option>
              <option v-for="f in fiveLevelOptions" :key="f.code" :value="f.code">{{ f.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">信用评级</label>
            <input class="form-input" :value="groupInfo?.creditLevel || ''" readonly placeholder="查询后带出" />
          </div>
        </template>

        <!-- 单户:客户名称输入联想下拉选择(数仓模糊查询,取消独立查询按钮) -->
        <template v-else>
          <div class="form-field">
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

          <!-- 单户基本信息(数仓带出,可改;并入同一 form-grid:个人/企业全部字段 span1 等宽 4 列;开户机构/开户日期/基本户账户申请页不展示,由审批页数仓同步展示(§2026-08-26)) -->
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
          </template>
          <template v-else>
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
            <div class="form-field">
              <label class="form-field__label">五级分类</label>
              <select class="form-select" v-model="form.fiveLevelClass">
                <option value="">请选择</option>
                <option v-for="f in fiveLevelOptions" :key="f.code" :value="f.code">{{ f.name }}</option>
              </select>
            </div>
          </template>
          <div class="form-field">
            <label class="form-field__label">申请机构</label>
            <input class="form-input" :value="applyOrgText" disabled />
          </div>
        </template>
      </div>

      <!-- 集团客户区块(§docs/19 集团补录集成申请页):集团号查询 → 新增集团就地补录/存量带出 + 有效成员列表(数仓带出/手工补录);集团查询已上移与客户主体并排一行(§2026-08-25) -->
      <template v-if="form.customerScope === 'GROUP'">
        <!-- 新增集团(数仓未收录):就地补录集团基本信息,与对公客户信息要求一致(数据以数仓为准,数仓无即按新集团) -->
        <div v-if="isNewGroup" class="form-card group-supplement" style="margin-top:12px">
          <div class="form-card__title">
            新增集团补录 <span class="badge badge--warning">数仓未收录</span>
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
              <input class="form-input" type="date" v-model="groupSupplement.openDate" placeholder="可空" />
            </div>
            <div class="form-field">
              <label class="form-field__label">基本户账户</label>
              <input class="form-input" v-model="groupSupplement.basicAccount" placeholder="可空" />
            </div>
          </div>
        </div>
        <!-- 集团成员:第一步勾选本次申请涉及成员(勾选集合供第三步分项「涉及成员」下拉选择);标题文字不展示(§2026-08-25),表格拉满整宽 -->
        <div v-if="groupMembers.length" class="member-block" style="margin-top:12px">
          <div class="member-head member-head--bare">
            <span class="badge badge--neutral">{{ selectedMembers.length }}/{{ groupMembers.length }} 已选</span>
            <button class="btn btn--text" @click="showSupplementMember = !showSupplementMember">
              {{ showSupplementMember ? '收起' : '添加成员' }}
            </button>
          </div>
          <table class="table member-table">
            <thead>
              <tr>
                <th style="width:40px"></th><th>成员客户号</th><th>成员名称</th><th>证件号码</th><th>行业类型</th><th>五级分类</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in groupMembers" :key="m.memberCustomerNo" :class="{ 'member-row--checked': isMemberChecked(m.memberCustomerNo) }">
                <td>
                  <input type="checkbox" class="member-check" :checked="isMemberChecked(m.memberCustomerNo)" @change="toggleMember(m.memberCustomerNo)" />
                </td>
                <td>
                  {{ customerNoText(m.memberCustomerNo) }}
                  <span v-if="m.source === 'MANUAL'" class="badge badge--warning" style="margin-left:4px">手工</span>
                </td>
                <td>{{ m.memberName || '暂无数据' }}</td>
                <td>{{ m.idNo || m.ucrCode || '—' }}</td>
                <td>{{ m.industry || '—' }}</td>
                <td>{{ fiveLevelClassText(normalizeFiveLevelClass(m.fiveLevelClass)) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="groupQueried" class="empty">
          该集团暂无有效成员数据
          <button class="btn btn--text" @click="showSupplementMember = true">添加成员</button>
        </div>

        <!-- 手工成员补录(数仓未收录成员,补录信息与对公客户申请要求一致:企业要素 + 成员要素,不含授信) -->
        <div v-if="showSupplementMember" class="form-card" style="margin-top:12px">
          <div class="form-card__title">
            手工成员补录 <span class="badge badge--warning">数仓未收录</span>
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
              <input class="form-input" type="date" v-model="m.openDate" placeholder="可空" />
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
            <td><input class="form-input form-input--amount" v-model="otherSummary.lenderCount" type="number" min="0" step="1" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.creditAmountTotal" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.usedAmountTotal" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.loanAccountCount" type="number" min="0" step="1" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.overdueAccountCount" type="number" min="0" step="1" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.overdueBalance" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.nplBalance" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.specialMentionBalance" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
            <td><input class="form-input form-input--amount" v-model="otherSummary.externalGuaranteeBalance" type="number" min="0" max="999999999.99" step="0.0001" placeholder="—" /></td>
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
            <td><input class="form-input form-input--amount" v-model="d.creditAmount" type="number" min="0" max="999999999.99" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.usedAmount" type="number" min="0" max="999999999.99" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.balanceAmount" type="number" min="0" max="999999999.99" step="0.0001" /></td>
            <td><input class="form-input form-input--amount" v-model="d.annualRate" type="number" min="0" max="100" step="0.000001" /></td>
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
      </div>

      <!-- 授信概览:业务类型 + 授信额度/拆分合计(存量/新增/GROUP 项按列对齐);GROUP 追加集团批复/可用 -->
      <div class="credit-overview">
        <div class="credit-overview__item">
          <span>业务类型 <span class="req">*</span></span>
          <select class="form-select" v-model="form.businessType" @change="onBusinessTypeChange">
            <option value="EXISTING">存量调息(现有贷款合同)</option>
            <option value="NEW">新增授信(拟签合同)</option>
          </select>
        </div>
        <template v-if="form.businessType === 'EXISTING'">
          <!-- 存量调息授信概览只保留授信总金额(选中协议后=协议额度,只读带出),去掉拆分细项合计(§2026-08-25 精简展示;超限仍走协议区 warning 条) -->
          <div class="credit-overview__item credit-overview__item--static">
            <span>授信总金额(万元)</span><b>{{ creditTotalText }}</b>
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
      </div>

      <!-- 存量授信协议(需求六:每份协议独立申请不可合并;折叠面板默认收起减少空白,点标题展开选/录;数仓有协议→下拉选+详情展示编号/总额/日期,无(含集团,集团查询不带协议)→手工补录三字段;§2026-08-26 集团协议块可见) -->
      <div v-if="form.businessType === 'EXISTING'" class="agreement-block">
        <div class="agreement-pick agreement-pick--head" @click="agreementExpanded = !agreementExpanded">
          <span class="agreement-pick__label">授信协议 <span class="req">*</span></span>
          <span class="agreement-pick__state">{{ agreementPickState }}</span>
          <span class="agreement-pick__arrow">{{ agreementExpanded ? '▾' : '▸' }}</span>
        </div>
        <div v-if="agreementExpanded" class="agreement-pick__body">
          <template v-if="creditAgreements.length">
            <div class="agreement-pick">
              <select class="form-select" :value="selectedAgreementNo" @change="onAgreementChange">
                <option value="" disabled>请选择本次申请对应的授信协议</option>
                <option v-for="a in creditAgreements" :key="a.agreementNo" :value="a.agreementNo">
                  {{ a.agreementNo }}（授信 {{ a.creditAmount ?? '—' }} 万）
                </option>
              </select>
              <span class="agreement-pick__hint">每份协议独立申请,不可合并;授信总金额按所选协议额度带出,分项合计原则上不超过协议额度</span>
            </div>
            <div v-if="selectedAgreement" class="agreement-detail">
              <div class="agreement-detail__item">
                <span class="agreement-detail__label">授信协议编号</span><b>{{ selectedAgreement.agreementNo || '—' }}</b>
              </div>
              <div class="agreement-detail__item agreement-detail__item--amount">
                <span class="agreement-detail__label">授信协议总额</span><b>{{ selectedAgreement.creditAmount ?? '—' }} 万</b>
              </div>
              <div class="agreement-detail__item">
                <span class="agreement-detail__label">授信协议日期</span><b>{{ selectedAgreement.startDate || '—' }}</b>
              </div>
              <div class="agreement-detail__item">
                <span class="agreement-detail__label">授信状态</span>
                <span class="badge" :class="agreementStatusBadge(selectedAgreement.agreementStatus)">{{ agreementStatusText(selectedAgreement.agreementStatus) }}</span>
              </div>
              <div class="agreement-detail__item">
                <span class="agreement-detail__label">到期日期</span><b>{{ selectedAgreement.endDate || '—' }}</b>
              </div>
            </div>
            <div v-else class="empty agreement-detail-empty">请选择本次申请对应的授信协议</div>
          </template>
          <template v-else>
            <div class="agreement-pick__hint">数仓暂无该客户授信协议,请按现有授信协议手工录入编号/总额/日期</div>
            <div class="agreement-manual">
              <div class="form-field">
                <label class="form-field__label">授信协议编号</label>
                <input class="form-input" v-model="form.creditInfo.agreementNo" placeholder="如 XH20240001" />
              </div>
              <div class="form-field">
                <label class="form-field__label">授信协议总额(万元)</label>
                <input class="form-input form-input--amount" v-model="form.creditInfo.creditAmount" placeholder="0" @change="syncTotalCredit" />
              </div>
              <div class="form-field">
                <label class="form-field__label">授信协议日期</label>
                <input class="form-input" type="date" v-model="form.creditInfo.startDate" />
              </div>
            </div>
          </template>
        </div>
      </div>

      <div v-if="overGroupAvailable" class="credit-overview-warning">
        分项金额合计已超过集团可用额度,请调整分项金额;是否超授以服务端提交校验为准。
      </div>
      <div v-if="overAgreementCredit" class="credit-overview-warning">
        分项金额合计已超过所选授信协议额度({{ selectedAgreement?.agreementNo || '—' }})，请按协议额度向下拆分；是否超授以服务端提交校验为准。
      </div>

      <!-- 需求:存量不再自动拉入拆分项,分项由业务人员按担保方式手工录入 -->
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
        <!-- 基础字段 3 列×2 行(上面三个下面三个):担保方式/产品/期限 + 授信金额/申请利率/币种;GROUP 涉及成员与存量原利率随条件追加 -->
        <div class="mortgage-item__grid loan-basic-grid">
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
          <div class="form-field">
            <label class="form-field__label">产品 <span class="req">*</span></label>
            <!-- 产品按客户类型固定:对公/集团→对公贷款(LOAN_A),个人→个人经营性贷款(LOAN_P);不再分项选择(§2026-08-26 用户要求) -->
            <input class="form-input" :value="productName(defaultProductByScope(form.customerScope))" readonly />
          </div>
          <div class="form-field">
            <label class="form-field__label">期限 <span class="req">*</span></label>
            <!-- 贷款期限固定一年期/三年期/五年期下拉(取消自由输入月/天,§用户要求) -->
            <select class="form-select" :value="termChoiceOf(g)" @change="onTermChange(g, $event)">
              <option value="" disabled>选择期限</option>
              <option v-for="opt in loanTermOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">授信金额(万元) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="g.amount" type="number" min="0" max="999999999.99" step="0.0001" />
          </div>
          <div class="form-field" v-if="form.businessType === 'EXISTING'">
            <label class="form-field__label">原利率(%)</label>
            <input class="form-input form-input--amount" v-model="g.originalRate" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="g.requestedRate" type="number" min="0" max="100" step="0.000001" placeholder="如 3.40" />
          </div>
          <div class="form-field">
            <label class="form-field__label">测算利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="g.calculatedRate" type="number" min="0" max="100" step="0.000001" placeholder="如 3.60" />
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
                <div v-for="(m, mi) in g.mortgages" :key="mi" class="mortgage-sub">
                  <div class="mortgage-item__head">
                    <select class="form-select" style="width:140px" v-model="m.type"><option>住宅</option><option>厂房</option><option>土地</option><option>设备</option><option>车辆</option></select>
                    <button class="btn btn--text" @click="g.mortgages.splice(mi, 1)">删除</button>
                  </div>
                  <div class="mortgage-item__grid">
                    <!-- 不动产(住宅/厂房/土地)共有:名称+坐落+面积 -->
                    <div class="form-field"><label class="form-field__label">{{ m.type === '土地' ? '地块名称' : m.type === '设备' ? '设备名称' : m.type === '车辆' ? '品牌型号' : '名称' }}</label><input class="form-input" v-model="m.name" /></div>
                    <template v-if="m.type === '住宅' || m.type === '厂房'">
                      <div class="form-field"><label class="form-field__label">坐落位置</label><input class="form-input" v-model="m.addr" /></div>
                      <div class="form-field"><label class="form-field__label">建筑面积(㎡)</label><input class="form-input form-input--amount" v-model="m.area" type="number" min="0" step="0.0001" /></div>
                      <div class="form-field"><label class="form-field__label">产权证号</label><input class="form-input" v-model="m.certNo" /></div>
                    </template>
                    <template v-else-if="m.type === '土地'">
                      <div class="form-field"><label class="form-field__label">坐落位置</label><input class="form-input" v-model="m.addr" /></div>
                      <div class="form-field"><label class="form-field__label">土地面积(㎡)</label><input class="form-input form-input--amount" v-model="m.area" type="number" min="0" step="0.0001" /></div>
                      <div class="form-field"><label class="form-field__label">使用权类型</label><select class="form-select" v-model="m.landUseType"><option>出让</option><option>划拨</option></select></div>
                      <div class="form-field"><label class="form-field__label">使用权到期日</label><input class="form-input" type="date" v-model="m.landUseExpiry" /></div>
                    </template>
                    <template v-else-if="m.type === '设备'">
                      <div class="form-field"><label class="form-field__label">规格型号</label><input class="form-input" v-model="m.specModel" /></div>
                      <div class="form-field"><label class="form-field__label">数量(台/套)</label><input class="form-input form-input--amount" v-model="m.quantity" type="number" min="0" step="1" /></div>
                      <div class="form-field"><label class="form-field__label">购置日期</label><input class="form-input" type="date" v-model="m.purchaseDate" /></div>
                    </template>
                    <template v-else-if="m.type === '车辆'">
                      <div class="form-field"><label class="form-field__label">车牌号</label><input class="form-input" v-model="m.plateNo" /></div>
                      <div class="form-field"><label class="form-field__label">车架号(VIN)</label><input class="form-input" v-model="m.vin" /></div>
                      <div class="form-field"><label class="form-field__label">登记日期</label><input class="form-input" type="date" v-model="m.regDate" /></div>
                    </template>
                    <div class="form-field"><label class="form-field__label">评估价值(万元)</label><input class="form-input form-input--amount" v-model="m.value" type="number" min="0" step="0.0001" /></div>
                    <div class="form-field"><label class="form-field__label">权属人</label><input class="form-input" v-model="m.owner" /></div>
                    <div class="form-field"><label class="form-field__label">抵押率(%)</label><input class="form-input form-input--amount" v-model="m.ratio" type="number" min="0" max="100" step="0.000001" /></div>
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
                      <td><input class="form-input form-input--amount" v-model="m.value" type="number" min="0" step="0.0001" /></td>
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
                      <td><input class="form-input form-input--amount" v-model="m.amount" type="number" min="0" step="0.0001" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.ratio" type="number" min="0" max="100" step="0.000001" /></td>
                      <td><input class="form-input form-input--amount" v-model="m.term" type="number" min="1" step="1" /></td>
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
                      <td><input class="form-input form-input--amount" v-model="m.amount" type="number" min="0" step="0.0001" /></td>
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
                      <td><input class="form-input form-input--amount" v-model="gt.amount" type="number" min="0" step="0.0001" /></td>
                      <td><input class="form-input form-input--amount" v-model="gt.balance" type="number" min="0" step="0.0001" /></td>
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

      <!-- 当前贡献度仅审批页对照展示,申请页不展示给客户经理;选指标时基线值自动带出当前贡献度(§2026-08-26) -->
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
              <label class="form-field__label">基线值</label>
              <input v-if="c.metricCode !== 'OTHER'" class="form-input form-input--amount" v-model="c.baselineValue" type="number" min="0" step="0.0001" placeholder="默认带出当前贡献度，可修改" />
              <div v-else class="section-tip commitment-static">—</div>
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
              <label class="form-field__label">拟达成目标 <span class="req">*</span></label>
              <template v-if="c.metricCode === 'OTHER'">
                <input class="form-input" v-model="c.commitmentDesc" placeholder="目标描述(金额或文本)" />
              </template>
              <input v-else class="form-input form-input--amount" v-model="c.targetValue" type="number" min="0" step="0.0001" />
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

    <!-- 第六步:提交预览(申请概要 + 下一步审批人 + 提交确认;§2026-08-26 精简:路由技术明细不再展示) -->
    <div v-show="step === 5" class="form-card">
      <div class="form-card__title">
        提交预览
      </div>
      <div class="form-field form-field--stack">
        <label class="form-field__label">申请备注(客户经理手工描述,展示在审批界面)</label>
        <textarea class="form-input" v-model="form.applicationRemark" rows="3" placeholder="可描述申请背景、特殊情况等" style="width:100%;resize:vertical"></textarea>
      </div>

      <!-- 当前申请概要(进入本步骤自动加载路由,展示下一步审批人) -->
      <div class="submit-summary">
        <div class="submit-summary__head">申请概要</div>
        <div class="submit-summary__grid">
          <div class="submit-summary__item"><span>客户主体</span><b>{{ scopeLabel }}</b></div>
          <div class="submit-summary__item"><span>客户名称</span><b>{{ form.customerScope === 'GROUP' ? form.groupName : (form.customerName || '—') }}</b></div>
          <div class="submit-summary__item"><span>客户号</span><b>{{ form.customerScope === 'GROUP' ? form.groupNo : (form.customerNo || '—') }}</b></div>
          <div class="submit-summary__item"><span>申请号</span><b>{{ draft.applicationNo || '—' }}</b></div>
          <div class="submit-summary__item"><span>业务类型</span><b>{{ form.businessType === 'EXISTING' ? '存量调息' : '新增授信' }}</b></div>
          <div class="submit-summary__item"><span>授信总额(万元)</span><b>{{ guaranteesTotalText }}</b></div>
          <div class="submit-summary__item"><span>分项笔数</span><b>{{ form.guarantees.length }} 笔</b></div>
          <div class="submit-summary__item"><span>下一步审批</span><b class="submit-summary__approver">{{ nextApproverText }}</b></div>
        </div>
      </div>

      <!-- 分项明细(提交内容核心:担保方式/产品/期限/金额/申请利率) -->
      <template v-if="form.guarantees.length">
        <div class="sub-title" style="margin-top:14px">分项明细</div>
        <table class="table">
          <thead>
            <tr>
              <th>分项</th>
              <th v-if="form.customerScope === 'GROUP'">成员</th>
              <th>担保方式</th><th>产品</th><th>期限</th>
              <th>授信金额(万元)</th><th>申请利率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(g, i) in form.guarantees" :key="i">
              <td>{{ cnOrdinal(i + 1) }}</td>
              <td v-if="form.customerScope === 'GROUP'">{{ memberNameOf(g.memberCustomerNo) }}</td>
              <td>{{ guaranteeTypeText(g.guaranteeType) }}</td>
              <td>{{ productName(g.productCode) }}</td>
              <td>{{ termTextOf(g) }}</td>
              <td class="num">{{ g.amount }}</td>
              <td class="num">{{ g.requestedRate }}%</td>
            </tr>
          </tbody>
        </table>
      </template>

      <div class="wizard-actions">
        <button class="btn btn--secondary" @click="step = 4">上一步</button>
        <div style="display:flex;gap:12px">
          <button class="btn btn--secondary" :disabled="saving || submitted" @click="onSaveDraft">存草稿</button>
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
  GUARANTEE_TYPES, guaranteeTypeText, nodeLabel,
  productName, inputModeText, LOAN_PRODUCTS, agreementTypeText, agreementStatusText, agreementStatusBadge,
  AGREEMENT_TYPES, currencyText, maritalStatusCode,
  FIVE_LEVEL_OPTIONS, normalizeFiveLevelClass, fiveLevelClassText, customerNoText, isManualCustomerNo
} from '@/utils/dict'
import { useMetricDict } from '@/store/metricDict'
import RelatedPersonsEditor, { serializeRelations, parseRelations, validateRelations, occupiedRelations, type RelatedPersonRow } from './RelatedPersonsEditor.vue'

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
// 贷款期限固定三档(一年期/三年期/五年期),取消自由输入月/天;存量带出非标准期限保留原值(下拉不显示,可重选覆盖)
const loanTermOptions = [
  { value: '1Y', label: '一年期', years: 1 },
  { value: '3Y', label: '三年期', years: 3 },
  { value: '5Y', label: '五年期', years: 5 },
] as const
function termChoiceOf(g: { termValue?: string | number; termUnit?: string }): string {
  const v = String(g.termValue ?? '').trim()
  const u = g.termUnit || ''
  if (u === 'YEAR' && (v === '1' || v === '3' || v === '5')) return v + 'Y'
  // 12/36/60 个月等价 1/3/5 年(存量带出月份场景自动归一)
  if (u === 'MONTH' && (v === '12' || v === '36' || v === '60')) return String(Number(v) / 12) + 'Y'
  return ''
}
function onTermChange(g: { termValue?: string; termUnit?: string }, e: Event) {
  const value = (e.target as HTMLSelectElement).value
  const opt = loanTermOptions.find((o) => o.value === value)
  if (!opt) return
  g.termValue = String(opt.years)
  g.termUnit = 'YEAR'
}
/** 分项期限展示(三档固定名 + 存量非标保留原值;提交预览用) */
function termTextOf(g: { termValue?: string | number; termUnit?: string }): string {
  const c = termChoiceOf(g)
  const opt = loanTermOptions.find((o) => o.value === c)
  if (opt) return opt.label
  const v = String(g.termValue ?? '').trim()
  if (!v) return '—'
  return `${v} ${g.termUnit === 'MONTH' ? '个月' : '年'}`
}
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
  calculatedRate: string
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
    originalRate: '', requestedRate: '', calculatedRate: '', mortgages: [], guarantors: [], pledges: [], margins: [], cds: []
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
  totalCredit: '', // 总授信额度(存量=所选授信协议额度自动带出;新增=手工录入)
  creditAgreementNo: '', // 授信协议编号(存量=选中协议)
  creditInfo: initialCreditInfo(), // 授信协议补录/修正要素(存量带出可改;新增手工补录,协议号可空)
  amountTier: 'LT_5000',
  customerType: 'NON_SOE',
  stateOwnedFlag: '', // 集团属性(国企 Y/非国企 N;数仓带出可下拉修改)
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '', industry: '', registeredCapital: '', basicAccount: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', annualIncome: '', maritalStatus: '', phone: '',
  // 通用
  openOrg: '', openDate: '',
  // 集团
  groupNo: '',
  groupName: '', // 集团名称(联想选择显示;新增集团手输)
  applicationRemark: '',
  guarantees: [newGuarantee('CORPORATE')] as GuaranteeRow[]
})

const applyOrgText = computed(() => userStore.userInfo?.orgName || (userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据'))

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
// 集团本次申请涉及的成员 = 第一步勾选集合(§2026-08-25 恢复勾选:涉及成员表头加勾选列,勾选结果供第三步分项「涉及成员」下拉选择)
const selectedMembers = ref<Array<{ memberCustomerNo: string; memberName: string; requestAmount: string; currency: string; memberRole: string }>>([])
/** 成员是否已勾选 */
const isMemberChecked = (no: string) => selectedMembers.value.some((s) => s.memberCustomerNo === no)
/** 勾选/取消勾选成员;取消勾选时同步清空贷款分项中该成员的引用(避免下拉悬空值) */
function toggleMember(no: string) {
  const idx = selectedMembers.value.findIndex((s) => s.memberCustomerNo === no)
  if (idx >= 0) {
    selectedMembers.value.splice(idx, 1)
    for (const g of form.guarantees) if (g.memberCustomerNo === no) g.memberCustomerNo = ''
  } else {
    const m = groupMembers.value.find((x) => x.memberCustomerNo === no)
    selectedMembers.value.push({
      memberCustomerNo: no,
      memberName: m?.memberName || no,
      requestAmount: '',
      currency: m?.currency || 'CNY',
      memberRole: m?.memberRole || ''
    })
  }
}
// 集团补录(§docs/19 集团补录集成申请页):新增集团就地补录 + 手工补录成员
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
/** 客户主体展示名(表单 customerScope: CORPORATE/INDIVIDUAL/GROUP;提交预览用) */
const scopeLabel = computed(() =>
  form.customerScope === 'GROUP' ? '集团客户' : form.customerScope === 'INDIVIDUAL' ? '个人' : '企业单户'
)
/** 下一步审批人:提交后第一步审批岗位(路由链路首节点,多分项统一;未预览/失败显示 —) */
const nextApproverText = computed(() => {
  for (const it of routeResult.value?.items || []) {
    if (it.routeChain?.length) return nodeLabel(it.routeChain[0])
  }
  return '—'
})

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
    // 企业性质带出(数仓 entp_charic 仅 SOE 判国企,其余非国企,与后端 resolveCustomerType 同口径)
    form.customerType = basic.entpCharic === 'SOE' ? 'SOE' : 'NON_SOE'
    ownFinancing.value = detail.financing || []
    // 授信协议(数仓);存量模式:授信项下全部贷款合同自动列为分项(可删除不需要调息的)
    try {
      const view = await getCustomerBusinessView(form.customerNo)
      creditAgreements.value = view.creditAgreements || []
      // 需求六:无协议时清空选择态,防止残留上一客户协议号绕过校验
      if (!creditAgreements.value.length) selectedAgreementNo.value = ''
      creditContracts.value = view.contracts || []
      relatedGuarantees.value = view.guarantees || { mortgages: [], guarantors: [] }
      creditSplits.value = view.creditSplits || []
      // 申请要素自动带出(§用户要求):贷款品种按客户类型、金额档按合同金额合计、业务类型按是否名下有合同
      form.loanType = form.customerScope === 'INDIVIDUAL' ? 'PERSONAL_LOAN' : 'CORP_LOAN'
      const contractRows = view.contracts || []
      const totalContractAmt = contractRows.reduce((s, c: any) => s + (Number(c.contractAmount) || 0), 0)
      if (totalContractAmt > 0) form.amountTier = totalContractAmt >= 5000 ? 'GE_5000' : 'LT_5000'
      // 需求:存量不再自动拉入数仓拆分项,仅默认进入存量模式;分项与利率由业务人员手工录入
      if (creditSplits.value.length && !userPickedBusinessType.value) {
        if (form.businessType !== 'EXISTING') form.businessType = 'EXISTING'
        form.guarantees = [newGuarantee()]
        selectedAgreementNo.value = ''
        autoSelectAgreement()
        syncTotalCredit()
      }
    } catch { /* 忽略 */ }
    contributionCurrent.value = detail.contribution || []
    otherSummary.value = { ...(detail.creditSummary?.[0] || {}) }
    otherLoans.value = (detail.creditDetail || []).map((d: any) => ({ ...d, inputMode: 'DW' }))
    ElMessage.success(`已带出客户 ${form.customerName || form.customerNo} 信息`)
  } catch {
    // 数仓无该客户记录(新增客户手工填写)按新户判定;其余错误由拦截器提示
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
  form.groupName = g.groupName || ''
  await queryGroup()
}

async function queryGroup() {
  // autocomplete 绑定名称;手动输入编号回车时同步到 groupNo(联想选中已由 selectGroup 回填)
  if (!form.groupNo?.trim()) form.groupNo = (form.groupName || '').trim()
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
    // 集团名称带出(autocomplete 显示,§2026-08-25 名称框/编号框对齐对公)
    if (g.group?.groupName) form.groupName = g.group.groupName
    // 统一社会信用代码(数仓有则带出,无则留空手填;集团区块原「集团状态」展示已替换,§2026-08-26)
    if (g.group?.ucrCode) form.ucrCode = g.group.ucrCode
    // 五级分类自动带出(数仓码值,可下拉修改,§2026-08-25)
    form.fiveLevelClass = normalizeFiveLevelClass(g.group?.fiveLevelClass || '')
    // 集团属性自动带出(国企/非国企,可下拉修改)
    form.stateOwnedFlag = g.group?.stateOwnedFlag || ''
  } catch {
    isNewGroup.value = true
    groupInfo.value = null
    groupCredit.value = null
    groupAllocatedTotal.value = null
    form.fiveLevelClass = ''
    form.stateOwnedFlag = ''
    form.ucrCode = ''
  }
  groupQueried.value = true
  // 成员:数仓有效成员 + 已落表手工成员(§4.4);未收录则置空(新增集团可手工补录成员)
  try {
    const members = await getGroupMembers(no)
    groupMembers.value = (members || []).map((m: any) => ({ ...m, source: 'DW' }))
    selectedMembers.value = []
  } catch {
    groupMembers.value = []
    selectedMembers.value = []
  }
  if (isNewGroup.value) {
    ElMessage.info('数仓未收录该集团,请补录集团基本信息并勾选成员录入本次申请金额')
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
/** 所选授信协议(需求六:每份协议独立发起申请,不可合并,总授信=所选协议额度) */
const selectedAgreementNo = ref('')
const selectedAgreement = computed(() =>
  creditAgreements.value.find((a) => a.agreementNo === selectedAgreementNo.value) || null
)
/** 存量授信协议折叠面板:默认收起(减少整页空白),点标题展开选/录(§2026-08-26) */
const agreementExpanded = ref(false)
/** 折叠标题条状态:已选协议号 / 已手工录入编号 / 未选择 */
const agreementPickState = computed(() => {
  if (selectedAgreementNo.value) return `已选 ${selectedAgreementNo.value}`
  if (form.creditInfo.agreementNo) return `已录 ${form.creditInfo.agreementNo}`
  return '未选择'
})
/** 存量授信总金额 = 所选授信协议/手工补录 credit_amount(万元;未选显示 —) */
const creditTotalText = computed(() => {
  const src = selectedAgreement.value?.creditAmount ?? form.creditInfo.creditAmount
  const n = Number(src)
  return n > 0 ? String(n) : '—'
})
/** 下拉选择授信协议(需求六:选到哪份就展示哪份的内容) */
function onAgreementChange(e: any) {
  const no = e?.target?.value
  const a = creditAgreements.value.find((x) => x.agreementNo === no)
  if (a) selectCreditAgreement(a)
}
/** 选中授信协议:带出协议内容并同步总授信(审批按所选协议总授信额度定档) */
function selectCreditAgreement(a: any) {
  selectedAgreementNo.value = a.agreementNo
  form.creditAgreementNo = a.agreementNo || ''
  form.creditInfo = {
    agreementNo: a.agreementNo || '', agreementType: a.agreementType || '', currency: a.currency || 'CNY',
    agreementStatus: a.agreementStatus || '', creditAmount: a.creditAmount != null ? String(a.creditAmount) : '',
    usedAmount: a.usedAmount != null ? String(a.usedAmount) : '',
    availableAmount: a.availableAmount != null ? String(a.availableAmount) : '',
    startDate: a.startDate || '', endDate: a.endDate || ''
  }
  syncTotalCredit()
}
/** 自动默认选中第一条有效协议(仅存量为选中态;未显式选择时减少操作) */
function autoSelectAgreement() {
  if (!selectedAgreementNo.value && creditAgreements.value.length) {
    const first = creditAgreements.value.find((x) => x.agreementStatus === 'EFFECTIVE') || creditAgreements.value[0]
    selectCreditAgreement(first)
  }
}
/** 分项合计超过所选协议额度 → 软提示(集团无协议维度不适用;不拦截提交,以服务端提交校验为准) */
const overAgreementCredit = computed(() => {
  if (form.businessType !== 'EXISTING' || form.customerScope === 'GROUP') return false
  const limit = Number(selectedAgreement.value?.creditAmount)
  return Number.isFinite(limit) && guaranteesTotalAmount.value > limit
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
    selectedAgreementNo.value = ''
    // 需求:存量不再自动拉入拆分项,保留一条空白分项由业务人员手工录入
    form.guarantees = [newGuarantee()]
    if (form.customerNo) {
      getCustomerBusinessView(form.customerNo)
        .then((view: any) => {
          creditSplits.value = view.creditSplits || []
          creditAgreements.value = view.creditAgreements || []
          autoSelectAgreement()
          syncTotalCredit()
        })
        .catch(() => {})
    }
    ElMessage.info('存量调息:请选择本次申请对应的授信协议(默认已选第一条有效协议),授信总金额按所选协议额度带出,分项与利率请手工录入')
  } else {
    // 新增授信:无数仓拆分项,重置为一条空白分项由客户经理按担保方式手工补充
    form.creditAgreementNo = ''
    form.totalCredit = ''
    form.creditInfo = initialCreditInfo()
    selectedAgreementNo.value = ''
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

/** 存量:总授信额度=所选授信协议/手工补录 credit_amount(需求六:每份协议独立,不合并;新增=手工录入;§2026-08-26 数仓无协议走手工补录) */
function syncTotalCredit() {
  if (form.businessType !== 'EXISTING') return
  const src = selectedAgreement.value ? selectedAgreement.value.creditAmount : form.creditInfo.creditAmount
  form.totalCredit = src != null && src !== '' ? String(src) : ''
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
    form.groupNo = ''
    form.groupName = ''
    groupInfo.value = null
    groupCredit.value = null
    groupAllocatedTotal.value = null
    groupMembers.value = []
    selectedMembers.value = []
    groupQueried.value = false
    // 集团补录状态一并重置(§docs/19 §4.1)
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
      // 有有效成员时必须先选择本次申请涉及的成员(§2026-08-25 恢复勾选)
      if (groupMembers.value.length && !selectedMembers.value.length) return '请至少选择一名涉及成员'
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
    // 概要数字格式校验(计数非负整数,金额范围兜底;type=number+min/max/step 拦输入,此处拦非法值,§2026-08-25)
    const os = otherSummary.value
    const osCounts: Array<[number, string]> = [
      [Number(os.lenderCount), '授信机构数'],
      [Number(os.loanAccountCount), '未结清笔数'],
      [Number(os.overdueAccountCount), '逾期笔数'],
    ]
    for (const [v, label] of osCounts) {
      if (!Number.isNaN(v) && (!Number.isInteger(v) || v < 0)) return `他行融资概要「${label}」须为非负整数(当前 ${v})`
    }
    const osAmts: Array<[number, string]> = [
      [Number(os.creditAmountTotal), '授信总额'],
      [Number(os.usedAmountTotal), '已用额'],
      [Number(os.overdueBalance), '逾期余额'],
      [Number(os.nplBalance), '不良余额'],
      [Number(os.specialMentionBalance), '关注类余额'],
      [Number(os.externalGuaranteeBalance), '对外担保余额'],
    ]
    for (const [v, label] of osAmts) {
      if (!Number.isNaN(v) && !(v >= 0 && v <= 999999999.99)) return `他行融资概要「${label}」须在 0~999999999.99 万元之间(当前 ${v})`
    }
    // 明细数字格式校验(金额范围/年化利率 0~100)
    for (const dl of otherLoans.value) {
      const dlAmts: Array<[number, string]> = [
        [Number(dl.creditAmount), '授信金额'],
        [Number(dl.usedAmount), '已用额'],
        [Number(dl.balanceAmount), '余额'],
      ]
      for (const [v, label] of dlAmts) {
        if (!Number.isNaN(v) && !(v >= 0 && v <= 999999999.99)) return `他行融资明细「${label}」须在 0~999999999.99 万元之间(当前 ${v})`
      }
      const ar = Number(dl.annualRate)
      if (!Number.isNaN(ar) && !(ar >= 0 && ar <= 100)) return `他行融资明细「年化利率」须在 0~100 之间(当前 ${dl.annualRate})`
    }
  }
  if (s === 2) {
    for (let i = 0; i < form.guarantees.length; i++) {
      const g = form.guarantees[i]
      if (form.customerScope === 'GROUP' && isBlank(g.memberCustomerNo)) return `第 ${i + 1} 条担保分项未选择集团成员`
      if (isBlank(g.guaranteeType)) return `第 ${i + 1} 条担保分项未选择担保方式`
      // 需求:担保/抵质押物不再强制录入——无论信用还是抵押/质押等,均可不登记担保措施直接提交
      if (isBlank(g.productCode)) return `第 ${i + 1} 条分项未选择产品`
      if (isBlank(g.termValue)) return `第 ${i + 1} 条分项未录入期限`
      if (isBlank(g.amount) || Number(g.amount) <= 0) return `第 ${i + 1} 条分项未录入金额`
      if (isBlank(g.requestedRate)) return `第 ${i + 1} 条分项未录入申请利率`
      // 申请利率/测算利率范围兜底:落库列 DECIMAL(9,6) 整数上限 999,超范围报 MySQL out of range 晦涩错误;合理利率 0~100%(§bug 2026-08-25)
      const rate = Number(g.requestedRate)
      if (!(rate > 0 && rate <= 100)) return `第 ${i + 1} 条分项申请利率须在 0~100 之间(当前 ${g.requestedRate})`
      if (isBlank(g.calculatedRate)) return `第 ${i + 1} 条分项未录入测算利率`
      const cRate = Number(g.calculatedRate)
      if (!(cRate > 0 && cRate <= 100)) return `第 ${i + 1} 条分项测算利率须在 0~100 之间(当前 ${g.calculatedRate})`
      // 期限须为正整数(落库 INT,小数/负数报错),授信金额范围兜底(同上 out of range,§2026-08-25)
      const tv = Number(g.termValue)
      if (!Number.isInteger(tv) || tv < 1) return `第 ${i + 1} 条分项期限须为正整数(当前 ${g.termValue})`
      const amt = Number(g.amount)
      if (!(amt > 0 && amt <= 999999999.99)) return `第 ${i + 1} 条分项授信金额须在 0~999999999.99 万元之间(当前 ${g.amount})`
      // 担保措施金额非负(抵押/质押/存单/保证金/保证人金额与余额,负值后端报错,前端先拦)
      for (const mm of g.mortgages) {
        if (Number(mm.value) < 0) return `第 ${i + 1} 条分项抵押物评估价值不能为负(当前 ${mm.value})`
      }
      for (const mm of g.pledges) {
        if (Number(mm.value) < 0) return `第 ${i + 1} 条分项质押物估值不能为负(当前 ${mm.value})`
      }
      for (const gt of g.guarantors) {
        if (Number(gt.amount) < 0) return `第 ${i + 1} 条分项保证人担保金额不能为负(当前 ${gt.amount})`
        if (Number(gt.balance) < 0) return `第 ${i + 1} 条分项保证人账户余额不能为负(当前 ${gt.balance})`
      }
      for (const mm of g.margins) {
        if (Number(mm.amount) < 0) return `第 ${i + 1} 条分项保证金金额不能为负(当前 ${mm.amount})`
        if (Number(mm.ratio) < 0) return `第 ${i + 1} 条分项保证金比例不能为负(当前 ${mm.ratio})`
      }
      for (const cdd of g.cds) {
        if (Number(cdd.amount) < 0) return `第 ${i + 1} 条分项存单金额不能为负(当前 ${cdd.amount})`
      }
    }
  }
  if (s === 3) {
    for (let i = 0; i < commitments.value.length; i++) {
      const c = commitments.value[i]
      if (isBlank(c.metricCode)) return `第 ${i + 1} 条承诺未选择指标`
      if (c.metricCode === 'OTHER' ? isBlank(c.commitmentDesc) : isBlank(c.targetValue)) return `第 ${i + 1} 条承诺未录入目标`
      if (isBlank(c.endDate)) return `第 ${i + 1} 条承诺未录入截止日期`
      // 承诺数值非负(基准值可空,目标值必填;负数/越界在此拦截,§2026-08-25)
      if (c.metricCode !== 'OTHER') {
        const bv = Number(c.baselineValue)
        if (c.baselineValue !== undefined && c.baselineValue !== '' && (Number.isNaN(bv) || bv < 0 || bv > 999999999.99)) {
          return `第 ${i + 1} 条承诺基准值须在 0~999999999.99 之间(当前 ${c.baselineValue})`
        }
        const tgt = Number(c.targetValue)
        if (Number.isNaN(tgt) || tgt < 0 || tgt > 999999999.99) return `第 ${i + 1} 条承诺目标值须在 0~999999999.99 之间(当前 ${c.targetValue})`
      }
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
  // 提交预览:进入即自动加载路由,展示下一步审批人(§2026-08-26 精简提交页,不再手动点路由预览)
  if (target === 5) onRoutePreview()
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
  // 提交预览:进入即自动加载路由(同上,§2026-08-26)
  if (i === 5) onRoutePreview()
}

function validateForDraft(): string | null {
  const isGroup = form.customerScope === 'GROUP'
  if (isGroup) {
    if (isBlank(form.groupNo)) return '请填写集团客户编号'
    if (groupMembers.value.length && !selectedMembers.value.length) return '请至少选择一名涉及成员'
  } else if (!hasCustomerIdentity()) {
    return '请先查询并选择客户,或录入证件号(新增客户可先录证件号)'
  }
  // 存量须选/录授信协议(数仓有协议→下拉选择;无协议含集团→手工补录编号;§2026-08-26 集团协议块已可见)
  if (form.businessType === 'EXISTING' && !selectedAgreementNo.value && !form.creditInfo.agreementNo) {
    return '存量调息请选择或录入本次申请对应的授信协议(授信协议编号必填)'
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
    // 期限正整数 + 授信金额范围(草稿暂存共用,§2026-08-25)
    const tv = Number(g.termValue)
    if (!Number.isInteger(tv) || tv < 1) return `第 ${i + 1} 条分项期限须为正整数(当前 ${g.termValue})`
    const amt = Number(g.amount)
    if (!(amt > 0 && amt <= 999999999.99)) return `第 ${i + 1} 条分项授信金额须在 0~999999999.99 万元之间(当前 ${g.amount})`
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
  // 成员本次申请金额 = 该成员名下贷款分项授信金额合计(金额在利率申请分项里录,§2026-08-25)
  const memberAmountOf = (no: string) =>
    form.guarantees.filter((g) => g.memberCustomerNo === no).reduce((s, g) => s + (Number(g.amount) || 0), 0)
  return {
    businessType: 'LOAN',
    customerScope: scopeMap[form.customerScope] || 'CORPORATE_SINGLE',
    customerNo: isGroup ? null : form.customerNo,
    groupNo: isGroup ? form.groupNo.trim() : null,
    members: isGroup
      ? selectedMembers.value.map((m) => ({
          memberCustomerNo: m.memberCustomerNo,
          // 成员本次申请金额 = 该成员名下贷款分项授信金额合计(§2026-08-25)
          requestAmount: memberAmountOf(m.memberCustomerNo),
          currency: m.currency || 'CNY',
          memberRole: m.memberRole || undefined
        }))
      : null,
    guarantees: form.guarantees.map((g) => ({
      requestedRate: g.requestedRate,
      calculatedRate: g.calculatedRate,
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
    // 集团补录/申请额度快照(集团对公全套 + 成员申请金额合计 + 手工补录成员;提交时落表,§docs/19 §4.5;审批详情优先展示)
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
  // 总授信额度(存量=所选授信协议/手工补录金额带出,新增=手工录入),审批链路按此定档匹配
  if (form.totalCredit) out.totalCredit = form.totalCredit
  else if (out.businessType === 'EXISTING' && form.creditInfo.creditAmount) out.totalCredit = form.creditInfo.creditAmount
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
  // 五级分类(存量集团数仓带出可下拉修改,§2026-08-25;新增集团以补录卡为准)
  if (!isNewGroup.value && form.fiveLevelClass) out.fiveLevelClass = form.fiveLevelClass
  // 集团属性(存量集团数仓带出可下拉修改,§2026-08-25)
  if (!isNewGroup.value && form.stateOwnedFlag) out.stateOwnedFlag = form.stateOwnedFlag
  // 本次申请额度(原独立录入字段已取消展示,§2026-08-25):集团流程按集团授信额度定档走,优先取集团批复授信额度;数仓未收录的新集团无批复额度,回退成员贷款分项金额合计
  const guaranteeSum = form.guarantees.reduce((s, g) => s + (Number(g.amount) || 0), 0)
  const groupCreditTotal = Number(groupCredit.value?.approvedTotalAmount)
  const applyAmount = Number.isFinite(groupCreditTotal) && groupCreditTotal > 0 ? groupCreditTotal : guaranteeSum
  if (applyAmount > 0) out.applyAmount = applyAmount
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
    // 集团补录/申请额度快照回填(§docs/19 §4.5):手工补录成员(数仓带出优先,补录成员仅并入可勾选列表);本次申请额度=成员申请金额合计自动得出
    const gInfo = parseExtJson(app.groupInfoJson)
    // 新增集团:集团基本信息从快照回填(数仓仍未收录时,避免客户经理二次录入)
    if (isNewGroup.value && gInfo?.groupName) {
      groupSupplement.groupName = gInfo.groupName
      form.groupName = gInfo.groupName
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
    // 存量集团:五级分类以提交快照为准(数仓可能已变化,保持客户经理已确认的修改,§2026-08-25)
    if (!isNewGroup.value && gInfo?.fiveLevelClass) {
      form.fiveLevelClass = normalizeFiveLevelClass(gInfo.fiveLevelClass)
    }
    if (!isNewGroup.value && gInfo?.stateOwnedFlag) {
      form.stateOwnedFlag = gInfo.stateOwnedFlag
    }
    // 手工补录成员并入可勾选列表(标 MANUAL;数仓已带出同客户号则跳过)
    for (const sm of gInfo?.supplementMembers || []) {
      if (sm?.memberCustomerNo && !groupMembers.value.some((gm) => gm.memberCustomerNo === sm.memberCustomerNo)) {
        groupMembers.value.push({ ...sm, source: 'MANUAL', creditLimit: null })
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
  // 集团勾选集合回填(从分项涉及成员去重,§2026-08-25 恢复勾选)
  if (form.customerScope === 'GROUP') {
    const nos: string[] = []
    for (const g of form.guarantees) if (g.memberCustomerNo && !nos.includes(g.memberCustomerNo)) nos.push(g.memberCustomerNo)
    selectedMembers.value = nos.map((no) => {
      const m = groupMembers.value.find((x) => x.memberCustomerNo === no)
      return { memberCustomerNo: no, memberName: m?.memberName || no, requestAmount: '', currency: m?.currency || 'CNY', memberRole: m?.memberRole || '' }
    })
  }
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
/* 开户机构下拉(el-select)与 .form-input 对齐(36px 高度/边框/圆角一致,跟随全局紧凑值) */
.open-org-select.el-select { width: 100%; }
.open-org-select.el-select :deep(.el-select__wrapper) {
  min-height: 36px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  box-shadow: 0 0 0 1px #dcdfe6 inset;
}
.open-org-select.el-select :deep(.el-select__placeholder) { color: #c0c4cc; }
.section-head { margin-bottom: 10px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
/* 表单字段横向布局:label 定宽右对齐 + 输入框同行,输入框左缘整齐对齐 */
.form-field {
  display: grid;
  grid-template-columns: 108px 1fr;
  align-items: center;
  column-gap: 6px;
}
.form-field__label { margin-bottom: 0; font-size: 13px; text-align: right; padding-right: 2px; }
.form-field > .form-input,
.form-field > .form-select,
.form-field > .el-select,
.form-field > .el-autocomplete,
.form-field > div:not(.section-tip):not(.limit-hint) {
  width: 100%;
  min-width: 0;
  grid-column: 2;
}
/* 反查提示/标准上限说明置于输入框下方,与输入框左缘对齐 */
.form-field > .section-tip,
.form-field > .limit-hint { grid-column: 2; }
/* 涉及成员整块(标题行+成员表格)跨满两列,避免被 2 列 form-field 网格挤入 108px 窄列导致显示不全(§2026-08-25) */
.form-field > .member-head,
.form-field > table.table { grid-column: 1 / -1; }
/* 提交预览申请备注:保持原竖排风格(label 左对齐在上方,文本框全宽) */
.form-field--stack { display: block; }
.form-field--stack .form-field__label { display: block; margin-bottom: 4px; font-size: 13px; text-align: left; padding-right: 0; }
/* 文本框内字体优化:字号与页面正文一致(14px)+字体族统一+数字等宽对齐+占位提示可读(原生控件与 Element 控件一致) */
.form-field .form-input,
.form-field .form-select,
.form-field :deep(.el-input__inner),
.form-field :deep(.el-select__selected-item),
.form-field :deep(.el-select__placeholder),
.form-field :deep(.el-textarea__inner) {
  font-size: 14px;
  font-family: inherit;
  font-variant-numeric: tabular-nums;
}
.form-field .form-input::placeholder,
.form-field .form-select::placeholder,
.form-field :deep(.el-input__inner::placeholder),
.form-field :deep(.el-select__placeholder) {
  color: #9ca3af;
}
/* 表单卡与全局 .card 观感一致(去边框+浅投影,紧凑内边距) */
.form-card {
  background: var(--color-surface);
  border: none;
  border-radius: var(--radius);
  padding: 12px 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
}
.form-card__title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
/* 4 列网格:文本框随列收窄;label 同行后列宽紧凑,跨列字段降至 span 2 */
.form-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px 10px; }
.table { border-radius: var(--radius); overflow-x: auto; }
.table--nested { margin-top: 8px; }
.guarantee-item { margin-bottom: 10px; }
.guarantee-item__title { font-size: 14px; font-weight: 600; display: inline-flex; align-items: center; gap: 8px; }
.guarantee-detail-block { margin-top: 12px; border-top: 1px dashed var(--color-border); padding-top: 12px; }

/* 授信概览条(利率申请步骤):固定 3 列,存量/新增/GROUP 项按列对齐,协议表占整行 */
.credit-overview {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 12px;
  background: var(--color-primary-light); border-radius: var(--radius-sm);
  padding: 8px 12px; margin-bottom: 10px;
}
.credit-overview__item span.req { color: var(--color-danger); }
.credit-overview__item {
  font-size: 13px; display: flex; flex-direction: column; gap: 4px; min-width: 0;
}
.credit-overview__item span { color: var(--color-text-sub); font-size: 12px; white-space: nowrap; }
.credit-overview__item b { font-variant-numeric: tabular-nums; }
.credit-overview__item--static b { font-weight: 600; min-height: 36px; display: flex; align-items: center; }
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
/* 存量授信协议区块(独立卡片,替代原塞在 credit-overview 内的整行区;每份协议独立申请,选中即展示该协议内容) */
.agreement-block {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 12px 14px;
  margin-bottom: 10px;
}
.agreement-pick { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.agreement-pick__label { font-size: 13px; font-weight: 600; }
.agreement-pick .form-select { max-width: 380px; }
.agreement-pick__hint { font-size: 12px; color: var(--color-text-sub); }
.agreement-detail {
  display: flex; flex-wrap: wrap; align-items: stretch; gap: 10px 28px;
  background: #f8fafc; border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); padding: 10px 14px; margin-top: 10px;
}
.agreement-detail__item { display: flex; flex-direction: column; gap: 3px; min-width: 150px; }
.agreement-detail__item--grow { flex: 1 1 auto; min-width: 200px; }
.agreement-detail__label { font-size: 12px; color: var(--color-text-sub); }
.agreement-detail__item b { font-size: 14px; font-weight: 600; color: var(--color-text-main); font-variant-numeric: tabular-nums; }
.agreement-detail__item--amount b { font-size: 18px; color: var(--color-primary); }
.agreement-detail-empty { margin-top: 10px; }
/* 存量授信协议折叠面板:标题条可点(默认收起减少空白),展开区承载下拉选/手工补录(§2026-08-26) */
.agreement-pick--head { cursor: pointer; user-select: none; }
.agreement-pick__state { font-size: 12px; color: var(--color-text-sub); font-variant-numeric: tabular-nums; }
.agreement-pick__arrow { margin-left: auto; color: var(--color-text-light); font-size: 12px; }
.agreement-pick__body { margin-top: 10px; }
.agreement-manual { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 10px; margin-top: 10px; }
.agreement-manual .form-field { margin-bottom: 0; }
/* 拆分细项合计超过所选协议额度 → 数字标红(与 warning 条呼应) */
.over-limit { color: var(--color-danger); }
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
  border: none; border-radius: var(--radius-sm);
  padding: 10px 12px; background: var(--color-surface);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
}
.commitment-card__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.commitment-card__no { font-size: 13px; font-weight: 600; color: var(--color-text-main); }
.commitment-card__grid { margin-bottom: 0; }
.commitment-card__grid .form-field { margin-bottom: 0; }
.commitment-static { min-height: 36px; display: flex; align-items: center; }

/* 向导步骤条(沿用 design-system .stepper) */
.wizard-stepper {
  background: var(--color-surface);
  border: none;
  border-radius: var(--radius);
  padding: var(--space-3) var(--space-4);
  margin-bottom: 10px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
}
.wizard-stepper .stepper__step { cursor: pointer; }
.wizard-actions {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--color-border);
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
/* 涉及成员标题行:label + 小号「添加成员」按钮同行(§2026-08-25,不单独占行) */
.member-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
/* 集团成员整块跨满整行(外层 form-grid 4 列,grid-column 跨全部列铺满页面,§2026-08-25 用户要求表格拉满) */
.member-block { width: 100%; grid-column: 1 / -1; }
/* 集团成员表格整宽拉满(§2026-08-25 用户要求列表尽量拉满;须 display:table 覆盖 .table 的 display:block,否则列不伸展、多余空间全留右侧) */
.member-table { display: table; width: 100%; }
/* 成员勾选复选框 */
.member-check { width: 16px; height: 16px; accent-color: var(--color-primary); cursor: pointer; }
/* 裸标题行(不展示「涉及成员」文字):已选计数 + 添加成员按钮右对齐 */
.member-head--bare { justify-content: flex-end; }
/* 涉及成员行内切换按钮:紧凑小号 */
.member-btn { padding: 3px 10px; font-size: 12px; border-radius: var(--radius-sm); }
/* 已涉及成员行高亮 */
.member-row--checked td { background: var(--color-primary-light); }
.group-summary {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px;
  background: var(--color-primary-light); border-radius: var(--radius-sm);
  padding: 8px 12px; margin-bottom: 4px;
}
.group-summary__item { font-size: 13px; display: flex; flex-direction: column; gap: 2px; }
.group-summary__item span { color: var(--color-text-sub); font-size: 12px; }
.group-summary__item b { font-variant-numeric: tabular-nums; }

/* 提交预览申请概要(§2026-08-26) */
.submit-summary {
  border: 1px solid var(--color-border); border-radius: var(--radius); padding: 12px 14px;
}
.submit-summary__head { font-size: 14px; font-weight: 600; margin-bottom: 10px; }
.submit-summary__grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 18px; }
.submit-summary__item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.submit-summary__item span { font-size: 12px; color: var(--color-text-sub); }
.submit-summary__item b {
  font-size: 13px; font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.submit-summary__approver { color: var(--color-primary); }

/* 利率申请分项卡片网格(4 列等宽对齐全局,minmax 防内容溢出;文本框随列收窄) */
.mortgage-item__grid { grid-template-columns: repeat(4, minmax(0, 1fr)) !important; gap: 8px 10px !important; }
.mortgage-item__grid .form-input, .mortgage-item__grid .form-select { width: 100%; }
@media (max-width: 1100px) { .mortgage-item__grid { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; } }
/* 贷款分项基础字段 3 列×2 行(用户要求上面三个下面三个;GROUP 涉及成员/存量原利率多出字段 flow 第三行) */
.loan-basic-grid { grid-template-columns: repeat(3, minmax(0, 1fr)) !important; }
@media (max-width: 1100px) { .loan-basic-grid { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; } }
/* 抵押物子项:不再嵌套全局 .mortgage-item 浅灰卡,避免双层卡视觉乱 */
.mortgage-sub { margin-bottom: 10px; }
.mortgage-sub .mortgage-item__head { margin-bottom: 8px; }
/* 明细表格(质押/保证金/存单/保证人)内输入框与表单字段字号统一 13px */
.table .form-input { font-size: 13px; }
/* 授信概览条窄屏降 2 列 */
@media (max-width: 1100px) { .credit-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.credit-overview__item--full { grid-column: 1 / -1; }
/* 向导内容铺满主区(2026-08-21:移除 1360px 限宽,宽屏下页面整体占满不留右侧留白) */

/* 中间断点:中等宽度下 3 列网格降为 2 列(页面自适应增强) */
@media (max-width: 1100px) {
  .form-grid, .credit-overview, .group-summary { grid-template-columns: repeat(2, 1fr); }
}
</style>
