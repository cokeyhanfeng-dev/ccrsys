<template>
  <div>
    <div class="section-head">
      <div class="section-title">参数管理</div>
      <div class="section-tip">
        LPR / 权限矩阵 / 产品边界 / 利率规则集版本管理(草稿→送审→复核发布→停用,发布强制双人复核:发布人≠创建人)。
        /system/** 接口仅系统管理员可访问(复核发布放行配置复核人),其他角色操作将提示无权限。
      </div>
    </div>

    <div class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="btn"
        :class="activeTab === t.key ? 'btn--primary' : 'btn--ghost'"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ========== LPR 维护 ========== -->
    <div v-if="activeTab === 'lpr'" class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;align-items:center">
          <span>LPR 参数(计划财务部人工维护,PRD D12)</span>
          <select class="form-select" v-model="lprStatus" style="width:140px" @change="loadLpr">
            <option value="">全部状态</option>
            <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
          </select>
        </div>
        <button class="btn btn--primary" @click="openLprCreate">＋ 新增 LPR 草稿</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>版本号</th><th>一年期 LPR</th><th>五年期以上 LPR</th><th>生效时间</th><th>失效时间</th>
            <th>状态</th><th>创建人</th><th>发布人</th><th>发布时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in lprList" :key="l.id">
            <td>{{ l.versionCode }}</td>
            <td class="num">{{ l.lpr1y }}%</td>
            <td class="num">{{ l.lpr5y }}%</td>
            <td>{{ fmtTime(l.effectiveFrom) }}</td>
            <td>{{ fmtTime(l.effectiveTo) }}</td>
            <td><span :class="statusBadge(l.status)">{{ statusText(l.status) }}</span></td>
            <td>{{ l.createBy ?? '—' }}</td>
            <td>{{ l.publishBy ?? '—' }}</td>
            <td>{{ fmtTime(l.publishTime) }}</td>
            <td>
              <button v-if="l.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('lpr', l.id)">送审</button>
              <button v-if="l.status === 'REVIEW'" class="btn btn--text" @click="doPublish('lpr', l.id)">复核发布</button>
              <button v-if="l.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('lpr', l.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!lprList.length"><td colspan="10" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 权限矩阵 ========== -->
    <div v-if="activeTab === 'matrix'" class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;align-items:center">
          <span>权限矩阵(PRD §7.2 LPR±BP 路由阈值;生效行禁止原位修改,调整=新建行发布替换)</span>
          <select class="form-select" v-model="matrixStatus" style="width:140px" @change="loadMatrix">
            <option value="">仅生效</option>
            <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
          </select>
        </div>
        <button class="btn btn--primary" @click="openMatrixCreate">＋ 新增矩阵行</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>行编码</th><th>业务大类</th><th>存量/新增</th><th>客户类型</th><th>产品</th>
            <th>金额档</th><th>期限档</th><th>担保</th><th>终审岗位</th><th>边界</th><th>优先级</th>
            <th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in matrixList" :key="m.id">
            <td>{{ m.matrixNo }}</td>
            <td>{{ bizText(m.businessBigType) }}</td>
            <td>{{ m.newOrExisting === 'NEW' ? '新增' : '存量' }}</td>
            <td>{{ m.customerType || '通配' }}</td>
            <td>{{ m.productCode || '通配' }}</td>
            <td>{{ m.amountTier || '通配' }}</td>
            <td>{{ m.termTier || '通配' }}</td>
            <td>{{ m.guaranteeType || '通配' }}</td>
            <td>{{ nodeText(m.startNodeCode) }}</td>
            <td>{{ boundaryText(m) }}</td>
            <td class="num">{{ m.priority }}</td>
            <td><span :class="statusBadge(m.status)">{{ statusText(m.status) }}</span></td>
            <td>
              <button v-if="m.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('matrix', m.id)">送审</button>
              <button v-if="m.status === 'REVIEW'" class="btn btn--text" @click="doPublish('matrix', m.id)">复核发布</button>
              <button v-if="m.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('matrix', m.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!matrixList.length"><td colspan="13" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 产品边界(§12.13 ③) ========== -->
    <div v-if="activeTab === 'product'" class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;align-items:center">
          <span>产品业务硬边界(全行业务硬边界,任何节点调价/矩阵边界不得突破,§8.2/§8A.5)</span>
          <select class="form-select" v-model="productStatus" style="width:140px" @change="loadProductLimit">
            <option value="">全部状态</option>
            <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
          </select>
        </div>
        <button class="btn btn--primary" @click="openProductCreate">＋ 新增产品边界草稿</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>产品编码</th><th>产品名称</th><th>业务类型</th><th>硬边界利率</th><th>利率方向</th>
            <th>生效时间</th><th>失效时间</th><th>状态</th><th>发布人</th><th>发布时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in productList" :key="p.id">
            <td>{{ p.productCode }}</td>
            <td>{{ p.productName || '—' }}</td>
            <td>{{ p.businessType === 'LOAN' ? '贷款' : '存款' }}</td>
            <td class="num">{{ p.hardBoundaryRate }}%</td>
            <td>{{ p.rateDirection === 'HIGHER_BETTER' ? '越高越优惠(存款)' : '越低越优惠(贷款)' }}</td>
            <td>{{ fmtTime(p.effectiveFrom) }}</td>
            <td>{{ fmtTime(p.effectiveTo) }}</td>
            <td><span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
            <td>{{ p.publishBy ?? '—' }}</td>
            <td>{{ fmtTime(p.publishTime) }}</td>
            <td>
              <button v-if="p.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('product', p.id)">送审</button>
              <button v-if="p.status === 'REVIEW'" class="btn btn--text" @click="doPublish('product', p.id)">复核发布</button>
              <button v-if="p.status === 'REVIEW'" class="btn btn--text" @click="openReject(p.id)">驳回</button>
              <button v-if="p.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('product', p.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!productList.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 规则集 ========== -->
    <div v-if="activeTab === 'ruleset'" class="card">
      <div class="card__head">
        <span>利率规则集(发布前自动连续性校验:区间连续、无空档、无重叠)</span>
        <button class="btn btn--primary" @click="openSetCreate">＋ 新增规则集草稿</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>规则集编码</th><th>名称</th><th>状态</th><th>生效时间</th><th>失效时间</th>
            <th>发布人</th><th>发布时间</th><th>备注</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in ruleSets" :key="s.id">
            <td>{{ s.setCode }}</td>
            <td>{{ s.setName }}</td>
            <td><span :class="statusBadge(s.status)">{{ statusText(s.status) }}</span></td>
            <td>{{ fmtTime(s.effectiveFrom) }}</td>
            <td>{{ fmtTime(s.effectiveTo) }}</td>
            <td>{{ s.publishBy ?? '—' }}</td>
            <td>{{ fmtTime(s.publishTime) }}</td>
            <td>{{ s.remark || '—' }}</td>
            <td>
              <button v-if="s.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('ruleset', s.id)">送审</button>
              <button v-if="s.status === 'REVIEW'" class="btn btn--text" @click="doPublish('ruleset', s.id)">复核发布</button>
              <button v-if="s.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('ruleset', s.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!ruleSets.length"><td colspan="9" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 变更日志(§8A.2) ========== -->
    <div v-if="activeTab === 'changelog'" class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
          <span>配置变更日志(LPR/矩阵/规则集/产品边界全量留痕)</span>
          <select class="form-select" v-model="logQuery.configType" style="width:150px" @change="loadChangeLogs">
            <option value="">全部配置域</option>
            <option v-for="t in configTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
          <input class="form-input" v-model="logQuery.configId" type="number" placeholder="配置记录ID" style="width:130px" @keyup.enter="loadChangeLogs" />
          <button class="btn btn--secondary" @click="loadChangeLogs">查询</button>
        </div>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>操作时间</th><th>配置域</th><th>记录ID</th><th>版本号</th><th>动作</th>
            <th>操作人</th><th>意见</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in changeLogs" :key="c.id">
            <td>{{ fmtTime(c.operateTime || '') }}</td>
            <td>{{ configTypeText(c.configType) }}</td>
            <td class="num">{{ c.configId }}</td>
            <td class="num">{{ c.versionNo ?? '—' }}</td>
            <td><span :class="actionBadge(c.action)">{{ actionText(c.action) }}</span></td>
            <td>{{ c.operatorId ?? '—' }}</td>
            <td>{{ c.opinion || '—' }}</td>
            <td><button class="btn btn--text" @click="openLogDetail(c)">查看快照</button></td>
          </tr>
          <tr v-if="!changeLogs.length"><td colspan="8" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 路由试算 ========== -->
    <div v-if="activeTab === 'trial'" class="card">
      <div class="card__head">
        <span>矩阵路由试算(输入业务维度,输出终审岗位与完整链路)</span>
      </div>
      <div class="trial-form">
        <div class="form-field">
          <label class="form-field__label">业务大类 <span class="req">*</span></label>
          <select class="form-select" v-model="trial.businessBigType">
            <option value="LOAN_PUBLIC">对公贷款</option>
            <option value="LOAN_PERSONAL">个人贷款</option>
            <option value="DEPOSIT">存款</option>
            <option value="MARGIN">保证金</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label">存量/新增 <span class="req">*</span></label>
          <select class="form-select" v-model="trial.newOrExisting">
            <option value="NEW">新增授信</option>
            <option value="EXISTING">存量授信</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label">客户类型</label>
          <select class="form-select" v-model="trial.customerType">
            <option value="">通配</option>
            <option value="SOE">国企</option>
            <option value="NON_SOE">非国企</option>
            <option value="PERSONAL">个人</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label">担保主类型</label>
          <input class="form-input" v-model="trial.guaranteeType" placeholder="如 抵押/质押/信用" />
        </div>
        <div class="form-field">
          <label class="form-field__label">申请金额(万元) <span class="req">*</span></label>
          <input class="form-input" v-model="trial.amount" type="number" />
        </div>
        <div class="form-field">
          <label class="form-field__label">期限</label>
          <div style="display:flex;gap:4px">
            <input class="form-input" v-model="trial.termValue" type="number" style="width:90px" />
            <select class="form-select" v-model="trial.termUnit" style="width:90px">
              <option value="YEAR">年</option>
              <option value="MONTH">月</option>
              <option value="DAY">日</option>
            </select>
          </div>
        </div>
        <div class="form-field">
          <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
          <input class="form-input" v-model="trial.requestedRate" type="number" step="0.01" />
        </div>
        <div class="form-field" v-if="trial.newOrExisting === 'EXISTING'">
          <label class="form-field__label">原执行利率(%)</label>
          <input class="form-input" v-model="trial.originalRate" type="number" step="0.01" />
        </div>
        <div class="form-field" style="justify-content:flex-end">
          <button class="btn btn--primary" @click="runTrial">试算</button>
        </div>
      </div>

      <div v-if="trialResult" class="trial-result">
        <div class="result-row"><span class="dg-label">审批链首节点</span><b>{{ nodeText(trialResult.startNodeCode) }}</b><span class="badge badge--info">必经</span></div>
        <div class="result-row"><span class="dg-label">终审岗位</span><b>{{ nodeText(trialResult.finalNodeCode) }}</b></div>
        <div class="result-row">
          <span class="dg-label">审批链路</span>
          <span class="chain">
            <template v-for="(n, i) in trialResult.routeChain || []" :key="n">
              <span class="chain__node">{{ nodeText(n) }}</span>
              <span v-if="i < (trialResult.routeChain || []).length - 1" class="chain__arrow">→</span>
            </template>
          </span>
        </div>
        <div class="result-row"><span class="dg-label">利率方向</span>{{ trialResult.rateDirection === 'HIGHER_BETTER' ? '越高越优惠(存款)' : '越低越优惠(贷款)' }}</div>
        <div class="result-row"><span class="dg-label">命中规则</span>{{ trialResult.matchedRuleName || trialResult.matchedRuleCode || '—' }}</div>
        <div class="result-row"><span class="dg-label">采用 LPR 版本</span>{{ trialResult.lprVersionCode || '—' }}</div>
        <div class="result-row"><span class="dg-label">计算说明</span>{{ trialResult.message || '—' }}</div>
      </div>
      <div v-else class="section-tip" style="margin-top:12px">填写左侧维度后点击"试算",输出终审岗位与审批链路。</div>
    </div>

    <!-- 新增 LPR 草稿弹窗 -->
    <div class="modal" v-if="lprDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增 LPR 草稿</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">版本号 <span class="req">*</span></label>
            <input class="form-input" v-model="lprDialog.form.versionCode" placeholder="如 LPR-2026-08" />
          </div>
          <div class="form-field">
            <label class="form-field__label">一年期 LPR(%) <span class="req">*</span></label>
            <input class="form-input" v-model="lprDialog.form.lpr1y" type="number" step="0.05" min="0.5" max="8" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五年期以上 LPR(%) <span class="req">*</span></label>
            <input class="form-input" v-model="lprDialog.form.lpr5y" type="number" step="0.05" min="0.5" max="8" />
          </div>
          <div class="form-field">
            <label class="form-field__label">生效时间 <span class="req">*</span></label>
            <input class="form-input" v-model="lprDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="section-tip">
            校验规则:LPR 取值 0.5%–8% 且为 0.05 的整数倍(报价规则);生效时间不得早于发布日;同一生效日仅允许一版(草稿/待复核/生效均占用)。
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="lprDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveLpr">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 新增矩阵行弹窗 -->
    <div class="modal" v-if="matrixDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增权限矩阵行(草稿)</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">行编码 <span class="req">*</span></label>
            <input class="form-input" v-model="matrixDialog.form.matrixNo" placeholder="如 MX-LOAN-NEW-001" />
          </div>
          <div class="form-field">
            <label class="form-field__label">业务大类 <span class="req">*</span></label>
            <select class="form-select" v-model="matrixDialog.form.businessBigType">
              <option value="LOAN_PUBLIC">对公贷款</option>
              <option value="LOAN_PERSONAL">个人贷款</option>
              <option value="DEPOSIT">存款</option>
              <option value="MARGIN">保证金</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">存量/新增 <span class="req">*</span></label>
            <select class="form-select" v-model="matrixDialog.form.newOrExisting">
              <option value="NEW">新增授信</option>
              <option value="EXISTING">存量授信</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">终审岗位 <span class="req">*</span></label>
            <select class="form-select" v-model="matrixDialog.form.startNodeCode">
              <option value="BRANCH_MANAGER">支行行长</option>
              <option value="DEPT_GENERAL_MANAGER">部门总经理</option>
              <option value="VICE_PRESIDENT">分管行长</option>
              <option value="SIX_PEOPLE_GROUP">六人小组</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">客户类型</label>
            <select class="form-select" v-model="matrixDialog.form.customerType">
              <option value="">通配</option>
              <option value="SOE">国企</option>
              <option value="NON_SOE">非国企</option>
              <option value="PERSONAL">个人</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">金额档</label>
            <select class="form-select" v-model="matrixDialog.form.amountTier">
              <option value="">通配</option>
              <option value="LT_5000">5000万以下</option>
              <option value="GE_5000">5000万及以上</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">期限档</label>
            <select class="form-select" v-model="matrixDialog.form.termTier">
              <option value="">通配</option>
              <option value="1Y">1年</option>
              <option value="3Y">3年</option>
              <option value="5Y">5年</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">担保主类型</label>
            <input class="form-input" v-model="matrixDialog.form.guaranteeType" placeholder="空=通配" />
          </div>
          <div class="form-field">
            <label class="form-field__label">边界类型</label>
            <select class="form-select" v-model="matrixDialog.form.boundaryType">
              <option value="RATE">直接利率</option>
              <option value="SPREAD">存量降幅</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">绝对利率下限(%)</label>
            <input class="form-input" v-model="matrixDialog.form.boundaryMinRate" type="number" step="0.01" />
          </div>
          <div class="form-field">
            <label class="form-field__label">BP 边界</label>
            <div style="display:flex;gap:4px">
              <select class="form-select" v-model="matrixDialog.form.bpSign" style="width:70px">
                <option value="+">+</option>
                <option value="-">-</option>
              </select>
              <input class="form-input" v-model="matrixDialog.form.boundaryBp" type="number" />
              <select class="form-select" v-model="matrixDialog.form.lprTerm" style="width:90px">
                <option value="1Y">1Y LPR</option>
                <option value="5Y+">5Y+ LPR</option>
              </select>
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">优先级(低值优先)</label>
            <input class="form-input" v-model="matrixDialog.form.priority" type="number" />
          </div>
          <div class="form-field">
            <label class="form-field__label">生效时间 <span class="req">*</span></label>
            <input class="form-input" v-model="matrixDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="form-field">
            <label class="form-field__label">备注</label>
            <input class="form-input" v-model="matrixDialog.form.remark" />
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="matrixDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveMatrix">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 新增规则集弹窗 -->
    <div class="modal" v-if="setDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增规则集草稿</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">规则集编码 <span class="req">*</span></label>
            <input class="form-input" v-model="setDialog.form.setCode" placeholder="如 RS-2026-08" />
          </div>
          <div class="form-field">
            <label class="form-field__label">规则集名称 <span class="req">*</span></label>
            <input class="form-input" v-model="setDialog.form.setName" />
          </div>
          <div class="form-field">
            <label class="form-field__label">生效时间</label>
            <input class="form-input" v-model="setDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="form-field">
            <label class="form-field__label">备注</label>
            <input class="form-input" v-model="setDialog.form.remark" />
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="setDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveSet">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 新增产品边界草稿弹窗 -->
    <div class="modal" v-if="productDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增产品硬边界草稿</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">产品编码 <span class="req">*</span></label>
            <input class="form-input" v-model="productDialog.form.productCode" placeholder="如 LOAN-FLOW-001" />
          </div>
          <div class="form-field">
            <label class="form-field__label">产品名称</label>
            <input class="form-input" v-model="productDialog.form.productName" />
          </div>
          <div class="form-field">
            <label class="form-field__label">业务类型 <span class="req">*</span></label>
            <select class="form-select" v-model="productDialog.form.businessType">
              <option value="LOAN">贷款(全行不可低于硬边界)</option>
              <option value="DEPOSIT">存款(全行不可高于硬边界)</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">硬边界利率(%) <span class="req">*</span></label>
            <input class="form-input" v-model="productDialog.form.hardBoundaryRate" type="number" step="0.01" />
          </div>
          <div class="form-field">
            <label class="form-field__label">生效时间 <span class="req">*</span></label>
            <input class="form-input" v-model="productDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="section-tip">利率方向按业务类型自动确定:贷款=越低越优惠、存款=越高越优惠。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="productDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveProduct">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 产品边界复核驳回弹窗(意见必填) -->
    <div class="modal" v-if="rejectDialog.show">
      <div class="modal__card">
        <div class="modal__title">复核驳回(退回草稿)</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">驳回意见 <span class="req">*</span></label>
            <textarea class="form-input" v-model="rejectDialog.opinion" rows="4" placeholder="请填写驳回原因,将写入配置变更日志"></textarea>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="rejectDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="doReject">确认驳回</button>
        </div>
      </div>
    </div>

    <!-- 变更日志快照弹窗(新旧值 JSON) -->
    <div class="modal" v-if="logDetail.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">
          变更快照:{{ configTypeText(logDetail.row?.configType || '') }} #{{ logDetail.row?.configId }}
          ({{ actionText(logDetail.row?.action || '') }})
        </div>
        <div class="modal__body">
          <div class="json-compare">
            <div>
              <div class="json-compare__title">变更前</div>
              <pre class="json-view">{{ prettyJson(logDetail.row?.oldJson) }}</pre>
            </div>
            <div>
              <div class="json-compare__title">变更后</div>
              <pre class="json-view">{{ prettyJson(logDetail.row?.newJson) }}</pre>
            </div>
          </div>
          <div v-if="logDetail.row?.opinion" class="section-tip" style="margin-top:8px">意见:{{ logDetail.row.opinion }}</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="logDetail.show = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listLpr, createLpr, submitLpr, publishLpr, disableLpr,
  listMatrix, createMatrix, submitMatrix, publishMatrix, disableMatrix,
  listRuleSets, createRuleSet, submitRuleSet, publishRuleSet, disableRuleSet,
  listProductLimit, createProductLimit, submitProductLimit, publishProductLimit,
  disableProductLimit, rejectProductLimit,
  listChangeLogs,
  matrixRoute,
  type ConfigChangeLog
} from '@/api/system'

const tabs = [
  { key: 'lpr', label: 'LPR 维护' },
  { key: 'matrix', label: '权限矩阵' },
  { key: 'product', label: '产品边界' },
  { key: 'ruleset', label: '规则集' },
  { key: 'changelog', label: '变更日志' },
  { key: 'trial', label: '路由试算' }
]
const activeTab = ref('lpr')

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'REVIEW', label: '待复核' },
  { value: 'EFFECTIVE', label: '已生效' },
  { value: 'INVALID', label: '已停用' }
]

const lprList = ref<any[]>([])
const matrixList = ref<any[]>([])
const ruleSets = ref<any[]>([])
const productList = ref<any[]>([])
const changeLogs = ref<ConfigChangeLog[]>([])
const lprStatus = ref('')
const matrixStatus = ref('')
const productStatus = ref('')

function statusText(s: string) {
  return statusOptions.find((o) => o.value === s)?.label || s || '—'
}
function statusBadge(s: string) {
  const map: Record<string, string> = {
    DRAFT: 'badge badge--neutral',
    REVIEW: 'badge badge--warning',
    EFFECTIVE: 'badge badge--success',
    INVALID: 'badge badge--neutral'
  }
  return map[s] || 'badge badge--neutral'
}
function fmtTime(t: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function bizText(b: string) {
  const map: Record<string, string> = {
    LOAN_PUBLIC: '对公贷款', LOAN_PERSONAL: '个人贷款', DEPOSIT: '存款', MARGIN: '保证金'
  }
  return map[b] || b || '—'
}
function nodeText(code: string) {
  const map: Record<string, string> = {
    BRANCH_MANAGER: '支行行长',
    DEPT_GENERAL_MANAGER: '部门总经理',
    VICE_PRESIDENT: '分管行长',
    SIX_PEOPLE_GROUP: '六人小组',
    PRESIDENT: '总行行长'
  }
  return map[code] || code || '—'
}
function boundaryText(m: any) {
  if (m.boundaryMinRate != null && m.boundaryBp == null) return `≥ ${m.boundaryMinRate}%`
  if (m.boundaryBp != null) return `LPR ${m.bpSign || ''}${m.boundaryBp}BP`
  return '权限内'
}

// ---------- 变更日志展示辅助 ----------
const configTypeOptions = [
  { value: 'LPR', label: 'LPR' },
  { value: 'MATRIX', label: '权限矩阵' },
  { value: 'RULE_SET', label: '利率规则集' },
  { value: 'PRODUCT_LIMIT', label: '产品边界' }
]
function configTypeText(t: string) {
  return configTypeOptions.find((o) => o.value === t)?.label || t || '—'
}
const actionOptions = [
  { value: 'CREATE', label: '新增草稿' },
  { value: 'SUBMIT', label: '送审' },
  { value: 'PUBLISH', label: '复核发布' },
  { value: 'DISABLE', label: '停用' },
  { value: 'REJECT', label: '复核驳回' }
]
function actionText(a: string) {
  return actionOptions.find((o) => o.value === a)?.label || a || '—'
}
function actionBadge(a: string) {
  const map: Record<string, string> = {
    CREATE: 'badge badge--info',
    SUBMIT: 'badge badge--warning',
    PUBLISH: 'badge badge--success',
    DISABLE: 'badge badge--neutral',
    REJECT: 'badge badge--danger'
  }
  return map[a] || 'badge badge--neutral'
}
function prettyJson(json?: string) {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

async function loadLpr() {
  try {
    lprList.value = await listLpr(lprStatus.value || undefined)
  } catch {
    lprList.value = []
  }
}
async function loadMatrix() {
  try {
    matrixList.value = await listMatrix(matrixStatus.value || undefined)
  } catch {
    matrixList.value = []
  }
}
async function loadRuleSets() {
  try {
    ruleSets.value = await listRuleSets()
  } catch {
    ruleSets.value = []
  }
}
async function loadProductLimit() {
  try {
    productList.value = await listProductLimit(productStatus.value || undefined)
  } catch {
    productList.value = []
  }
}
const logQuery = reactive({ configType: '', configId: '' as number | '' })
async function loadChangeLogs() {
  try {
    changeLogs.value = await listChangeLogs(logQuery.configType || undefined, logQuery.configId || undefined)
  } catch {
    changeLogs.value = []
  }
}
const logDetail = reactive({ show: false, row: null as ConfigChangeLog | null })
function openLogDetail(row: ConfigChangeLog) {
  logDetail.row = row
  logDetail.show = true
}

// ---------- 生命周期操作(送审/复核发布/停用) ----------
type Kind = 'lpr' | 'matrix' | 'ruleset' | 'product'
const apiOf = (kind: Kind) => ({
  lpr: { submit: submitLpr, publish: publishLpr, disable: disableLpr, reload: loadLpr },
  matrix: { submit: submitMatrix, publish: publishMatrix, disable: disableMatrix, reload: loadMatrix },
  ruleset: { submit: submitRuleSet, publish: publishRuleSet, disable: disableRuleSet, reload: loadRuleSets },
  product: { submit: submitProductLimit, publish: publishProductLimit, disable: disableProductLimit, reload: loadProductLimit }
}[kind])

async function doSubmit(kind: Kind, id: number) {
  await apiOf(kind).submit(id)
  ElMessage.success('已送审,待复核发布')
  apiOf(kind).reload()
}
async function doPublish(kind: Kind, id: number) {
  await ElMessageBox.confirm(
    '发布强制双人复核:发布人不得与创建人为同一人;发布后同维度旧生效版本自动停用。确认复核发布?',
    '复核发布确认',
    { type: 'warning' }
  )
  await apiOf(kind).publish(id)
  ElMessage.success('已发布生效')
  apiOf(kind).reload()
}
async function doDisable(kind: Kind, id: number) {
  await ElMessageBox.confirm('确认停用该生效版本?', '停用确认', { type: 'warning' })
  await apiOf(kind).disable(id)
  ElMessage.success('已停用')
  apiOf(kind).reload()
}

// ---------- 产品边界复核驳回(意见必填,§8A.2) ----------
const rejectDialog = reactive({ show: false, id: 0, opinion: '' })
function openReject(id: number) {
  rejectDialog.id = id
  rejectDialog.opinion = ''
  rejectDialog.show = true
}
async function doReject() {
  if (!rejectDialog.opinion.trim()) {
    ElMessage.warning('驳回意见必填')
    return
  }
  await rejectProductLimit(rejectDialog.id, rejectDialog.opinion.trim())
  rejectDialog.show = false
  ElMessage.success('已驳回,退回草稿')
  loadProductLimit()
}

// ---------- 新增草稿 ----------
const lprDialog = reactive({ show: false, form: {} as any })
function openLprCreate() {
  lprDialog.form = { versionCode: '', lpr1y: null, lpr5y: null, effectiveFrom: '' }
  lprDialog.show = true
}

/** LPR 单值前端校验(与后端 §8A.3 口径一致):0.5%–8% 且为 0.05 的整数倍 */
function validLprValue(name: string, v: any): boolean {
  const n = Number(v)
  if (v === null || v === '' || Number.isNaN(n)) {
    ElMessage.warning(`${name}必填`)
    return false
  }
  if (n < 0.5 || n > 8) {
    ElMessage.warning(`${name}取值须为0.5%–8%(当前:${n}%)`)
    return false
  }
  if (Math.round(n * 100) % 5 !== 0) {
    ElMessage.warning(`${name}须为0.05的整数倍(LPR报价规则,当前:${n}%)`)
    return false
  }
  return true
}

async function saveLpr() {
  const f = lprDialog.form
  if (!f.versionCode) {
    ElMessage.warning('版本号必填')
    return
  }
  if (!validLprValue('一年期LPR', f.lpr1y) || !validLprValue('五年期以上LPR', f.lpr5y)) return
  if (!f.effectiveFrom) {
    ElMessage.warning('生效时间必填')
    return
  }
  const effectiveDate = String(f.effectiveFrom).slice(0, 10)
  const today = new Date().toISOString().slice(0, 10)
  if (effectiveDate < today) {
    ElMessage.warning(`生效时间${effectiveDate}不得早于发布日${today}`)
    return
  }
  // 同一生效日仅一版提示(草稿/待复核/生效均占用;后端保存时强校验)
  try {
    const occupied = (await listLpr()).filter((l) =>
      ['DRAFT', 'REVIEW', 'EFFECTIVE'].includes(l.status)
      && l.effectiveFrom && String(l.effectiveFrom).slice(0, 10) === effectiveDate
    )
    if (occupied.length) {
      ElMessage.warning(`同一生效日仅允许一版:${effectiveDate}已被版本${occupied[0].versionCode}占用`)
      return
    }
  } catch {
    // 预检失败不阻断,交由后端强校验
  }
  await createLpr({ ...f })
  lprDialog.show = false
  ElMessage.success('草稿已保存')
  loadLpr()
}

const matrixDialog = reactive({ show: false, form: {} as any })
function openMatrixCreate() {
  matrixDialog.form = {
    matrixNo: '', businessBigType: 'LOAN_PUBLIC', newOrExisting: 'NEW',
    startNodeCode: 'BRANCH_MANAGER', customerType: '', amountTier: '', termTier: '',
    guaranteeType: '', boundaryType: 'RATE', boundaryMinRate: null,
    bpSign: '+', boundaryBp: null, lprTerm: '1Y', priority: 100, effectiveFrom: '', remark: ''
  }
  matrixDialog.show = true
}
async function saveMatrix() {
  const f = matrixDialog.form
  const payload: any = { ...f }
  // 空字符串的可选维度按"通配"(null)上送
  for (const k of ['customerType', 'amountTier', 'termTier', 'guaranteeType']) {
    if (!payload[k]) payload[k] = null
  }
  if (payload.boundaryMinRate === '' || payload.boundaryMinRate == null) payload.boundaryMinRate = null
  if (payload.boundaryBp === '' || payload.boundaryBp == null) payload.boundaryBp = null
  await createMatrix(payload)
  matrixDialog.show = false
  ElMessage.success('草稿已保存')
  matrixStatus.value = 'DRAFT'
  loadMatrix()
}

const setDialog = reactive({ show: false, form: {} as any })
function openSetCreate() {
  setDialog.form = { setCode: '', setName: '', effectiveFrom: '', remark: '' }
  setDialog.show = true
}
async function saveSet() {
  const f = setDialog.form
  await createRuleSet({ setCode: f.setCode, setName: f.setName, effectiveFrom: f.effectiveFrom || null, remark: f.remark })
  setDialog.show = false
  ElMessage.success('草稿已保存')
  loadRuleSets()
}

// ---------- 产品边界新增草稿 ----------
const productDialog = reactive({ show: false, form: {} as any })
function openProductCreate() {
  productDialog.form = { productCode: '', productName: '', businessType: 'LOAN', hardBoundaryRate: null, effectiveFrom: '' }
  productDialog.show = true
}
async function saveProduct() {
  const f = productDialog.form
  if (!f.productCode || !f.businessType || f.hardBoundaryRate === null || f.hardBoundaryRate === '' || !f.effectiveFrom) {
    ElMessage.warning('产品编码/业务类型/硬边界利率/生效时间必填')
    return
  }
  if (Number(f.hardBoundaryRate) <= 0) {
    ElMessage.warning('硬边界利率必须大于0')
    return
  }
  await createProductLimit({ ...f, hardBoundaryRate: Number(f.hardBoundaryRate) })
  productDialog.show = false
  ElMessage.success('草稿已保存')
  productStatus.value = 'DRAFT'
  loadProductLimit()
}

// ---------- 路由试算 ----------
const trial = reactive({
  businessBigType: 'LOAN_PUBLIC',
  newOrExisting: 'NEW',
  customerType: '',
  guaranteeType: '',
  amount: '' as any,
  termValue: '' as any,
  termUnit: 'YEAR',
  requestedRate: '' as any,
  originalRate: '' as any
})
const trialResult = ref<any>(null)

async function runTrial() {
  if (!trial.amount || !trial.requestedRate) {
    ElMessage.warning('申请金额与申请利率必填')
    return
  }
  const payload: any = {
    businessBigType: trial.businessBigType,
    newOrExisting: trial.newOrExisting,
    customerType: trial.customerType || null,
    guaranteeType: trial.guaranteeType || null,
    amount: Number(trial.amount),
    requestedRate: Number(trial.requestedRate)
  }
  if (trial.termValue) {
    payload.termValue = Number(trial.termValue)
    payload.termUnit = trial.termUnit
  }
  if (trial.newOrExisting === 'EXISTING' && trial.originalRate) {
    payload.originalRate = Number(trial.originalRate)
  }
  try {
    trialResult.value = await matrixRoute(payload)
  } catch {
    trialResult.value = null
  }
}

onMounted(() => {
  loadLpr()
  loadMatrix()
  loadRuleSets()
  loadProductLimit()
  loadChangeLogs()
})
</script>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.card__head { gap: 8px; flex-wrap: wrap; }
.table { border-radius: var(--radius-sm); overflow: hidden; }
.req { color: var(--color-danger); }
.trial-form { display: flex; flex-wrap: wrap; gap: 12px 20px; align-items: flex-end; }
.trial-form .form-field { min-width: 160px; }
.trial-result { margin-top: 16px; border-top: 1px dashed var(--color-border); padding-top: 12px; }
.result-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 14px; }
.dg-label { color: var(--color-text-sub); min-width: 96px; }
.chain { display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.chain__node { background: var(--color-primary-light, #eff6ff); color: var(--color-primary); border-radius: 4px; padding: 2px 8px; font-size: 13px; }
.chain__arrow { color: var(--color-text-light); }
.modal__card--wide { width: 860px; max-width: 94vw; }
.json-compare { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.json-compare__title { font-weight: 600; margin-bottom: 6px; }
.json-view { background: var(--color-bg, #f8fafc); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 10px; font-size: 12px; max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
