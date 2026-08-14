<template>
  <div>
    <div class="section-head">
      <div class="section-title">参数管理</div>
      <InfoTip>
        LPR / 权限矩阵 / 产品边界 / 利率规则集版本管理(草稿→送审→复核发布→停用,发布强制双人复核:发布人≠创建人)。
        /system/** 接口仅系统管理员可访问(复核发布放行配置复核人),其他角色操作将提示无权限。
      </InfoTip>
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
        <button v-if="canMaintain" class="btn btn--primary" @click="openLprCreate">＋ 新增 LPR 草稿</button>
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
              <button v-if="canMaintain && l.status === 'DRAFT'" class="btn btn--text" @click="openLprConfig(l)">维护明细</button>
              <button v-if="canMaintain && l.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('lpr', l.id)">送审</button>
              <button v-if="canReview && l.status === 'REVIEW'" class="btn btn--text" @click="doPublish('lpr', l.id)">复核发布</button>
              <button v-if="canMaintain && l.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('lpr', l.id)">停用</button>
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
        <button v-if="canMaintain" class="btn btn--primary" @click="openMatrixCreate">＋ 新增矩阵行</button>
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
            <td>{{ customerTypeText(m.customerType, '通配') }}</td>
            <td>{{ productName(m.productCode, '通配') }}</td>
            <td>{{ amountTierText(m.amountTier, '通配') }}</td>
            <td>{{ termTierText(m.termTier, '通配') }}</td>
            <td>{{ guaranteeTypeText(m.guaranteeType, '通配') }}</td>
            <td>{{ nodeLabel(m.startNodeCode) }}</td>
            <td>{{ boundaryText(m) }}</td>
            <td class="num">{{ m.priority }}</td>
            <td><span :class="statusBadge(m.status)">{{ statusText(m.status) }}</span></td>
            <td>
              <button v-if="canMaintain && m.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('matrix', m.id)">送审</button>
              <button v-if="canReview && m.status === 'REVIEW'" class="btn btn--text" @click="doPublish('matrix', m.id)">复核发布</button>
              <button v-if="canMaintain && m.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('matrix', m.id)">停用</button>
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
        <button v-if="canMaintain" class="btn btn--primary" @click="openLimitCreate">＋ 新增产品边界草稿</button>
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
            <td>{{ businessTypeText(p.businessType) }}</td>
            <td class="num">{{ p.hardBoundaryRate }}%</td>
            <td>{{ rateDirectionText(p.rateDirection) }}</td>
            <td>{{ fmtTime(p.effectiveFrom) }}</td>
            <td>{{ fmtTime(p.effectiveTo) }}</td>
            <td><span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
            <td>{{ p.publishBy ?? '—' }}</td>
            <td>{{ fmtTime(p.publishTime) }}</td>
            <td>
              <button v-if="canMaintain && p.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('product', p.id)">送审</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" @click="doPublish('product', p.id)">复核发布</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" @click="openReject(p.id)">驳回</button>
              <button v-if="canMaintain && p.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('product', p.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!productList.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 产品配置中心(§8A.5:产品目录 + 产品审批链路) ========== -->
    <div v-if="activeTab === 'productCenter'" class="card">
      <div class="tabs" style="margin-bottom:16px">
        <button class="btn" :class="pcTab === 'catalog' ? 'btn--primary' : 'btn--ghost'" @click="pcTab = 'catalog'">产品目录</button>
        <button class="btn" :class="pcTab === 'route' ? 'btn--primary' : 'btn--ghost'" @click="pcTab = 'route'">产品审批链路</button>
      </div>

      <!-- 产品目录(§8A.5①:申请页产品下拉/LPR明细/权限矩阵/产品边界的权威来源) -->
      <div v-if="pcTab === 'catalog'">
        <div class="card__head">
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <span>产品目录(产品编码一经启用禁改;停用后新申请不可选,在途审批不受影响 D11)</span>
            <select class="form-select" v-model="productQuery.businessBigType" style="width:130px" @change="loadProductCatalog">
              <option value="">全部业务</option>
              <option value="LOAN">贷款</option>
              <option value="DEPOSIT">存款</option>
            </select>
            <select class="form-select" v-model="productQuery.status" style="width:120px" @change="loadProductCatalog">
              <option value="">全部状态</option>
              <option value="ENABLED">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
          <button v-if="canMaintain" class="btn btn--primary" @click="openProductCreate">＋ 新增产品</button>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th>产品编码</th><th>产品名称</th><th>业务大类</th><th>产品线</th><th>客户类型</th><th>币种</th>
              <th>默认利率区间(%)</th><th>默认期限(月)</th><th>生效日</th><th>状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in productCatalog" :key="p.id">
              <td>{{ p.productCode }}</td>
              <td>{{ p.productName }}</td>
              <td>{{ p.businessBigType === 'LOAN' ? '贷款' : '存款' }}</td>
              <td>{{ p.productCategory || '—' }}</td>
              <td>{{ p.customerType ? productCustomerTypeText(p.customerType) : '—' }}</td>
              <td>{{ p.currency || 'CNY' }}</td>
              <td class="num">{{ p.defaultMinRate != null || p.defaultMaxRate != null ? `${p.defaultMinRate ?? '—'} ~ ${p.defaultMaxRate ?? '—'}` : '—' }}</td>
              <td class="num">{{ p.defaultMinTermMonths != null || p.defaultMaxTermMonths != null ? `${p.defaultMinTermMonths ?? '—'} ~ ${p.defaultMaxTermMonths ?? '—'}` : '—' }}</td>
              <td>{{ fmtTime(p.effectiveDate) }}</td>
              <td><span :class="productStatusBadge(p.status)">{{ productStatusText(p.status) }}</span></td>
              <td>
                <button v-if="canMaintain" class="btn btn--text" @click="openProductEdit(p)">编辑</button>
                <button v-if="canMaintain && p.status === 'ENABLED'" class="btn btn--text" @click="doProductStatus(p, 'DISABLED')">停用</button>
                <button v-if="canMaintain && p.status !== 'ENABLED'" class="btn btn--text" @click="doProductStatus(p, 'ENABLED')">启用</button>
                <button v-if="canMaintain && p.status === 'DISABLED'" class="btn btn--text" @click="doProductDelete(p)">删除</button>
              </td>
            </tr>
            <tr v-if="!productCatalog.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 产品审批链路(§8A.5②:路由引擎读取替代硬编码;支行行长节点恒必经,B13) -->
      <div v-if="pcTab === 'route'">
        <div class="card__head">
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <span>产品审批链路(草稿→送审→复核发布→停用;同产品同生效日仅一版发布生效)</span>
            <select class="form-select" v-model="routeQuery.productCode" style="width:200px" @change="loadProductRoutes">
              <option value="">全部产品</option>
              <option v-for="p in productCatalog" :key="p.productCode" :value="p.productCode">{{ p.productCode }} · {{ p.productName }}</option>
            </select>
            <select class="form-select" v-model="routeQuery.status" style="width:120px" @change="loadProductRoutes">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="PENDING_REVIEW">待复核</option>
              <option value="PUBLISHED">已生效</option>
              <option value="OBSOLETE">已停用</option>
            </select>
          </div>
          <button v-if="canMaintain" class="btn btn--primary" @click="openRouteCreate">＋ 新增链路</button>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th>产品</th><th>业务大类</th><th>路由模式</th><th>起始节点</th><th>强制上会</th><th>行长决策</th>
              <th>上会条件</th><th>优先级</th><th>生效日</th><th>状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in productRoutes" :key="r.id">
              <td>
                <div>{{ r.productCode }}</div>
                <div class="section-tip">{{ productName(r.productCode) }}</div>
              </td>
              <td>{{ r.businessBigType === 'LOAN' ? '贷款' : '存款' }}</td>
              <td><span class="badge" :class="r.routeMode === 'DIRECT_VOTE' ? 'badge--warning' : 'badge--info'">{{ r.routeMode === 'DIRECT_VOTE' ? '直接上会' : '链式逐级' }}</span></td>
              <td>{{ r.startNodeCode ? nodeLabel(r.startNodeCode) : '—' }}</td>
              <td>{{ r.mandatoryVote === 'Y' ? '是' : '否' }}</td>
              <td>{{ r.presidentDecision === 'Y' ? '是' : '否' }}</td>
              <td>{{ voteConditionText(r.voteCondition) }}</td>
              <td class="num">{{ r.priority ?? '—' }}</td>
              <td>{{ fmtTime(r.effectiveDate) }}</td>
              <td><span :class="routeStatusBadge(r.status)">{{ routeStatusText(r.status) }}</span></td>
              <td>
                <button class="btn btn--text" @click="openRouteSimulate(r)">模拟路由</button>
                <button v-if="canMaintain && r.status === 'DRAFT'" class="btn btn--text" @click="doRouteSubmit(r)">送审</button>
                <button v-if="canReview && r.status === 'PENDING_REVIEW'" class="btn btn--text" @click="doRoutePublish(r)">复核发布</button>
                <button v-if="canReview && r.status === 'PENDING_REVIEW'" class="btn btn--text" @click="openRouteReject(r)">驳回</button>
                <button v-if="canMaintain && r.status === 'PUBLISHED'" class="btn btn--text" @click="doRouteDisable(r)">停用</button>
                <button v-if="canMaintain && r.status !== 'PUBLISHED'" class="btn btn--text" @click="doRouteDelete(r)">删除</button>
              </td>
            </tr>
            <tr v-if="!productRoutes.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ========== 规则集 ========== -->
    <div v-if="activeTab === 'ruleset'" class="card">
      <div class="card__head">
        <span>利率规则集(发布前自动连续性校验:区间连续、无空档、无重叠)</span>
        <button v-if="canMaintain" class="btn btn--primary" @click="openSetCreate">＋ 新增规则集草稿</button>
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
              <button v-if="canMaintain && s.status === 'DRAFT'" class="btn btn--text" @click="doSubmit('ruleset', s.id)">送审</button>
              <button v-if="canReview && s.status === 'REVIEW'" class="btn btn--text" @click="doPublish('ruleset', s.id)">复核发布</button>
              <button v-if="canMaintain && s.status === 'EFFECTIVE'" class="btn btn--text" @click="doDisable('ruleset', s.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!ruleSets.length"><td colspan="9" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 跟踪策略(§11.5/§11.7) ========== -->
    <div v-if="activeTab === 'policy'" class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
          <span>跟踪策略(版本化:草稿→送审→复核发布→停用;匹配优先级 指标+业务+机构 &gt; 指标+业务 &gt; 指标默认 &gt; 全行默认*)</span>
          <select class="form-select" v-model="policyMetric" style="width:170px" @change="loadPolicies">
            <option value="">全部指标</option>
            <option value="*">全行默认</option>
            <option v-for="m in METRIC_CODES" :key="m.code" :value="m.code">{{ m.name }}</option>
          </select>
        </div>
        <button v-if="canMaintain" class="btn btn--primary" @click="openPolicyCreate">＋ 新增策略</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>策略编号</th><th>策略名称</th><th>指标</th><th>业务类型</th><th>机构编码</th>
            <th>优先级</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in policyList" :key="p.id">
            <td>{{ p.policyNo || '—' }}</td>
            <td>{{ p.policyName }}</td>
            <td>{{ metricName(p.metricCode) }}</td>
            <td>{{ p.businessType ? businessTypeText(p.businessType) : '不限' }}</td>
            <td>{{ p.orgCode || '通用' }}</td>
            <td class="num">{{ p.priority ?? '—' }}</td>
            <td><span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
            <td>
              <button class="btn btn--text" @click="openVersionMgr(p)">版本管理</button>
              <button v-if="canMaintain && p.status === 'DRAFT'" class="btn btn--text" @click="doPolicyStatus(p, 'REVIEW')">送审</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" @click="doPolicyStatus(p, 'EFFECTIVE')">复核发布</button>
              <button v-if="canMaintain && p.status === 'EFFECTIVE'" class="btn btn--text" @click="doPolicyStatus(p, 'INVALID')">停用</button>
            </td>
          </tr>
          <tr v-if="!policyList.length"><td colspan="8" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>

      <!-- 策略试算(§11.7):选历史承诺计划,输出命中策略与预警判定 -->
      <div style="margin-top:20px;border-top:1px dashed var(--color-border);padding-top:16px">
        <div class="sub-title">策略试算</div>
        <div style="display:flex;gap:8px;align-items:center;margin-top:8px">
          <select class="form-select" v-model="simulatePlanId" style="width:260px">
            <option value="">选择历史承诺计划</option>
            <option v-for="pl in planOptions" :key="pl.id" :value="pl.id">{{ pl.planNo }} · {{ pl.customerNo || '—' }}</option>
          </select>
          <button class="btn btn--primary" :disabled="!simulatePlanId" @click="runPolicySimulate">试算</button>
          <span class="section-tip">输入计划编号,按计划冻结的指标逐项匹配当前生效策略并判定预警等级</span>
        </div>
        <div v-if="simulateResult" class="trial-result">
          <div class="result-row"><span class="dg-label">计划</span>{{ simulateResult.planNo }} · 冻结策略版本 {{ simulateResult.frozenPolicyVersionId || '—' }}</div>
          <table class="table" style="margin-top:8px" v-if="simulateResult.metrics?.length">
            <thead>
              <tr>
                <th>指标</th><th>命中策略</th><th>达成率</th><th>时间进度</th><th>阈值线(达成/风险)</th>
                <th>临近到期(天)</th><th>容忍(天)</th><th>判定</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(m, i) in simulateResult.metrics" :key="i">
                <td>{{ metricName(m.metricCode) }}</td>
                <td>{{ m.matchedPolicyNo ? `${m.matchedPolicyNo}(${m.matchedVersionCode})` : '默认阈值' }}</td>
                <td class="num">{{ m.achievementRatio != null ? m.achievementRatio + '%' : '—' }}</td>
                <td class="num">{{ m.progressRatio != null ? m.progressRatio + '%' : '—' }}</td>
                <td>{{ m.achieveLine ?? '—' }} / {{ m.atRiskLine ?? '—' }}</td>
                <td class="num">{{ m.nearExpiryDays ?? '—' }}</td>
                <td class="num">{{ m.toleranceDays ?? '—' }}</td>
                <td><span :class="policyRiskBadge(m.judgeResult)">{{ policyRiskText(m.judgeResult) }}</span></td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty" style="margin-top:8px">该计划无承诺指标数据</div>
        </div>
      </div>
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
          <select class="form-select" v-model="trial.guaranteeType">
            <option value="">通配</option>
            <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
          </select>
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
        <div class="result-row"><span class="dg-label">审批链首节点</span><b>{{ nodeLabel(trialResult.startNodeCode) }}</b><span class="badge badge--info">必经</span></div>
        <div class="result-row"><span class="dg-label">终审岗位</span><b>{{ nodeLabel(trialResult.finalNodeCode) }}</b></div>
        <div class="result-row">
          <span class="dg-label">审批链路</span>
          <span class="chain">
            <template v-for="(n, i) in trialResult.routeChain || []" :key="n">
              <span class="chain__node">{{ nodeLabel(n) }}</span>
              <span v-if="i < (trialResult.routeChain || []).length - 1" class="chain__arrow">→</span>
            </template>
          </span>
        </div>
        <div class="result-row"><span class="dg-label">利率方向</span>{{ rateDirectionText(trialResult.rateDirection) }}</div>
        <div class="result-row"><span class="dg-label">命中规则</span>{{ trialResult.matchedRuleName || trialResult.matchedRuleCode || '—' }}</div>
        <div class="result-row"><span class="dg-label">采用 LPR 版本</span>{{ trialResult.lprVersionCode || '—' }}</div>
        <div class="result-row"><span class="dg-label">计算说明</span>{{ trialResult.message || '—' }}</div>
      </div>
      <div v-else class="section-tip" style="margin-top:12px">填写左侧维度后点击"试算",输出终审岗位与审批链路。</div>
    </div>

    <!-- 新增跟踪策略弹窗(策略+首个版本+阈值一并提交,§11.5) -->
    <div class="modal" v-if="policyDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">新增跟踪策略</div>
        <div class="modal__body">
          <div class="sub-title">策略维度</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">策略编号 <span class="req">*</span></label>
              <input class="form-input" v-model="policyDialog.form.policyNo" placeholder="如 P-2026-001" />
            </div>
            <div class="form-field">
              <label class="form-field__label">策略名称 <span class="req">*</span></label>
              <input class="form-input" v-model="policyDialog.form.policyName" />
            </div>
            <div class="form-field">
              <label class="form-field__label">指标编码 <span class="req">*</span></label>
              <select class="form-select" v-model="policyDialog.form.metricCode">
                <option value="*">全行默认</option>
                <option v-for="m in METRIC_CODES" :key="m.code" :value="m.code">{{ m.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">业务类型</label>
              <select class="form-select" v-model="policyDialog.form.businessType">
                <option value="">不限</option>
                <option value="LOAN">贷款</option>
                <option value="DEPOSIT">存款</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">机构编码</label>
              <input class="form-input" v-model="policyDialog.form.orgCode" placeholder="空=通用" />
            </div>
            <div class="form-field">
              <label class="form-field__label">优先级</label>
              <input class="form-input" v-model="policyDialog.form.priority" type="number" />
            </div>
          </div>

          <div class="sub-title" style="margin-top:14px">首个版本(草稿)</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">版本号 <span class="req">*</span></label>
              <input class="form-input" v-model="policyDialog.version.versionCode" placeholder="如 V1" />
            </div>
            <div class="form-field">
              <label class="form-field__label">生效时间 <span class="req">*</span></label>
              <input class="form-input" v-model="policyDialog.version.effectiveFrom" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label">失效时间</label>
              <input class="form-input" v-model="policyDialog.version.effectiveTo" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label">校验频率</label>
              <select class="form-select" v-model="policyDialog.version.checkFrequency">
                <option value="DAILY">每日</option>
                <option value="WEEKLY">每周</option>
                <option value="MONTHLY">每月</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">数据容忍天数</label>
              <input class="form-input" v-model="policyDialog.version.dataToleranceDays" type="number" />
            </div>
          </div>

          <div class="sub-title" style="margin-top:14px">阈值配置</div>
          <table class="table">
            <thead>
              <tr><th>阈值类型</th><th>阈值数值</th><th>比较符</th><th>预警等级</th><th style="width:60px">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="(t, i) in policyDialog.thrRows" :key="i">
                <td>
                  <select class="form-select" v-model="t.thresholdType">
                    <option value="TIME_PROGRESS">时间进度</option>
                    <option value="ACHIEVEMENT_RATE">达成率</option>
                    <option value="CONSECUTIVE_DECLINE">连续下降</option>
                    <option value="NEAR_EXPIRY">临近到期</option>
                  </select>
                </td>
                <td><input class="form-input" v-model="t.thresholdValue" type="number" step="0.01" style="width:120px" /></td>
                <td>
                  <select class="form-select" v-model="t.compareOperator" style="width:90px">
                    <option value=">">&gt;</option>
                    <option value=">=">&gt;=</option>
                    <option value="<">&lt;</option>
                    <option value="<=">&lt;=</option>
                  </select>
                </td>
                <td>
                  <select class="form-select" v-model="t.riskLevel">
                    <option value="NORMAL">正常</option>
                    <option value="WATCH">关注</option>
                    <option value="AT_RISK">风险</option>
                  </select>
                </td>
                <td><button class="btn btn--text" @click="policyDialog.thrRows.splice(i, 1)">删除</button></td>
              </tr>
              <tr v-if="!policyDialog.thrRows.length"><td colspan="5" class="empty-cell">未配置阈值,将使用系统默认阈值</td></tr>
            </tbody>
          </table>
          <button class="btn btn--secondary" style="margin-top:8px" @click="policyDialog.thrRows.push(emptyThreshold())">＋ 添加阈值行</button>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="policyDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="savePolicy">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 版本管理弹窗(版本列表+新增版本+阈值明细,§11.5) -->
    <div class="modal" v-if="versionMgr.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">
          版本管理:{{ versionMgr.policy?.policyNo || '' }} {{ versionMgr.policy?.policyName || '' }}
          <span style="font-size:12px;color:var(--color-text-sub);margin-left:8px">生效区间不得重叠;置生效即对该策略生效</span>
        </div>
        <div class="modal__body">
          <table class="table">
            <thead>
              <tr>
                <th>版本号</th><th>生效时间</th><th>失效时间</th><th>校验频率</th><th>容忍天数</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in versionMgr.versions" :key="v.id">
                <td>{{ v.versionCode || '—' }}</td>
                <td>{{ fmtTime(v.effectiveFrom) }}</td>
                <td>{{ fmtTime(v.effectiveTo) }}</td>
                <td>{{ checkFreqText(v.checkFrequency) }}</td>
                <td class="num">{{ v.dataToleranceDays ?? '—' }}</td>
                <td><span :class="statusBadge(v.status)">{{ statusText(v.status) }}</span></td>
                <td>
                  <button class="btn btn--text" @click="openVersionThresholds(v)">阈值</button>
                  <button v-if="canMaintain && v.status === 'DRAFT'" class="btn btn--text" @click="doVersionStatus(v, 'REVIEW')">送审</button>
                  <button v-if="canReview && v.status === 'REVIEW'" class="btn btn--text" @click="doVersionStatus(v, 'EFFECTIVE')">复核发布</button>
                  <button v-if="canMaintain && v.status === 'EFFECTIVE'" class="btn btn--text" @click="doVersionStatus(v, 'INVALID')">停用</button>
                </td>
              </tr>
              <tr v-if="!versionMgr.versions.length"><td colspan="7" class="empty-cell">暂无版本</td></tr>
            </tbody>
          </table>

          <div v-if="versionMgr.thrPanel.show" style="margin-top:16px;border-top:1px dashed var(--color-border);padding-top:12px">
            <div class="sub-title">阈值明细:版本 {{ versionMgr.thrPanel.versionCode }}</div>
            <table class="table" v-if="versionMgr.thrPanel.rows.length">
              <thead><tr><th>阈值类型</th><th>阈值数值</th><th>比较符</th><th>预警等级</th></tr></thead>
              <tbody>
                <tr v-for="(t, i) in versionMgr.thrPanel.rows" :key="i">
                  <td>{{ thresholdTypeText(t.thresholdType) }}</td>
                  <td class="num">{{ t.thresholdValue }}</td>
                  <td>{{ t.compareOperator || '—' }}</td>
                  <td><span :class="policyRiskBadge(t.riskLevel)">{{ policyRiskText(t.riskLevel) }}</span></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty">该版本未配置阈值,将使用系统默认阈值</div>
          </div>

          <div v-if="versionMgr.addShow" style="margin-top:16px;border-top:1px dashed var(--color-border);padding-top:12px">
            <div class="sub-title">新增版本(草稿)</div>
            <div class="form-grid">
              <div class="form-field">
                <label class="form-field__label">版本号 <span class="req">*</span></label>
                <input class="form-input" v-model="versionMgr.form.versionCode" placeholder="如 V2" />
              </div>
              <div class="form-field">
                <label class="form-field__label">生效时间 <span class="req">*</span></label>
                <input class="form-input" v-model="versionMgr.form.effectiveFrom" type="datetime-local" />
              </div>
              <div class="form-field">
                <label class="form-field__label">失效时间</label>
                <input class="form-input" v-model="versionMgr.form.effectiveTo" type="datetime-local" />
              </div>
              <div class="form-field">
                <label class="form-field__label">校验频率</label>
                <select class="form-select" v-model="versionMgr.form.checkFrequency">
                  <option value="DAILY">每日</option>
                  <option value="WEEKLY">每周</option>
                  <option value="MONTHLY">每月</option>
                </select>
              </div>
              <div class="form-field">
                <label class="form-field__label">数据容忍天数</label>
                <input class="form-input" v-model="versionMgr.form.dataToleranceDays" type="number" />
              </div>
            </div>
            <div class="sub-title" style="margin-top:10px">阈值</div>
            <table class="table">
              <thead>
                <tr><th>阈值类型</th><th>阈值数值</th><th>比较符</th><th>预警等级</th><th style="width:60px">操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="(t, i) in versionMgr.form.thrRows" :key="i">
                  <td>
                    <select class="form-select" v-model="t.thresholdType">
                      <option value="TIME_PROGRESS">时间进度</option>
                      <option value="ACHIEVEMENT_RATE">达成率</option>
                      <option value="CONSECUTIVE_DECLINE">连续下降</option>
                      <option value="NEAR_EXPIRY">临近到期</option>
                    </select>
                  </td>
                  <td><input class="form-input" v-model="t.thresholdValue" type="number" step="0.01" style="width:120px" /></td>
                  <td>
                    <select class="form-select" v-model="t.compareOperator" style="width:90px">
                      <option value=">">&gt;</option>
                      <option value=">=">&gt;=</option>
                      <option value="<">&lt;</option>
                      <option value="<=">&lt;=</option>
                    </select>
                  </td>
                  <td>
                    <select class="form-select" v-model="t.riskLevel">
                      <option value="NORMAL">正常</option>
                      <option value="WATCH">关注</option>
                      <option value="AT_RISK">风险</option>
                    </select>
                  </td>
                  <td><button class="btn btn--text" @click="versionMgr.form.thrRows.splice(i, 1)">删除</button></td>
                </tr>
                <tr v-if="!versionMgr.form.thrRows.length"><td colspan="5" class="empty-cell">未配置阈值,将使用系统默认阈值</td></tr>
              </tbody>
            </table>
            <button class="btn btn--secondary" style="margin-top:8px" @click="versionMgr.form.thrRows.push(emptyThreshold())">＋ 添加阈值行</button>
            <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
              <button class="btn btn--secondary" @click="versionMgr.addShow = false">取消</button>
              <button class="btn btn--primary" @click="savePolicyVersion">保存版本</button>
            </div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="versionMgr.show = false">关闭</button>
          <button class="btn btn--primary" v-if="canMaintain && !versionMgr.addShow" @click="openAddVersion">＋ 新增版本</button>
        </div>
      </div>
    </div>

    <!-- 新增/编辑产品目录弹窗(§8A.5①) -->
    <div class="modal" v-if="productDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">{{ productDialog.id ? '编辑产品' : '新增产品' }}</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">产品编码 <span class="req">*</span></label>
              <input class="form-input" v-model="productDialog.form.productCode" :disabled="!!productDialog.id" placeholder="如 LOAN_A" />
            </div>
            <div class="form-field">
              <label class="form-field__label">产品名称 <span class="req">*</span></label>
              <input class="form-input" v-model="productDialog.form.productName" />
            </div>
            <div class="form-field">
              <label class="form-field__label">业务大类 <span class="req">*</span></label>
              <select class="form-select" v-model="productDialog.form.businessBigType" :disabled="!!productDialog.id">
                <option value="LOAN">贷款</option>
                <option value="DEPOSIT">存款</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">产品线</label>
              <input class="form-input" v-model="productDialog.form.productCategory" placeholder="如 对公贷款/协定/银票保证金" />
            </div>
            <div class="form-field">
              <label class="form-field__label">适用客户类型</label>
              <select class="form-select" v-model="productDialog.form.customerType">
                <option value="">不限</option>
                <option value="INDIVIDUAL">个人</option>
                <option value="CORPORATE_SINGLE">单一法人</option>
                <option value="GROUP">集团</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">币种</label>
              <input class="form-input" v-model="productDialog.form.currency" placeholder="CNY" />
            </div>
            <div class="form-field">
              <label class="form-field__label">默认利率下限(%)</label>
              <input class="form-input" v-model="productDialog.form.defaultMinRate" type="number" step="0.01" />
            </div>
            <div class="form-field">
              <label class="form-field__label">默认利率上限(%)</label>
              <input class="form-input" v-model="productDialog.form.defaultMaxRate" type="number" step="0.01" />
            </div>
            <div class="form-field">
              <label class="form-field__label">默认最短期限(月)</label>
              <input class="form-input" v-model="productDialog.form.defaultMinTermMonths" type="number" />
            </div>
            <div class="form-field">
              <label class="form-field__label">默认最长期限(月)</label>
              <input class="form-input" v-model="productDialog.form.defaultMaxTermMonths" type="number" />
            </div>
            <div class="form-field">
              <label class="form-field__label">生效日</label>
              <input class="form-input" v-model="productDialog.form.effectiveDate" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label">备注</label>
              <input class="form-input" v-model="productDialog.form.remark" />
            </div>
          </div>
          <div class="section-tip">校验:产品编码唯一且一经启用禁改;默认利率下限不得高于上限;客户类型/产品线可空。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="productDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveProduct">保存</button>
        </div>
      </div>
    </div>

    <!-- 新增产品链路草稿弹窗(§8A.5②;含上会条件 JSON 构建器) -->
    <div class="modal" v-if="routeDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">新增产品审批链路</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">产品 <span class="req">*</span></label>
              <select class="form-select" v-model="routeDialog.form.productCode" @change="onRouteProductChange">
                <option value="" disabled>选择产品</option>
                <option v-for="p in enabledProducts" :key="p.productCode" :value="p.productCode">{{ p.productCode }} · {{ p.productName }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">业务大类</label>
              <input class="form-input" v-model="routeDialog.form.businessBigTypeText" disabled />
            </div>
            <div class="form-field">
              <label class="form-field__label">路由模式 <span class="req">*</span></label>
              <select class="form-select" v-model="routeDialog.form.routeMode">
                <option value="CHAINED">链式逐级(CHAINED)</option>
                <option value="DIRECT_VOTE">直接上会(DIRECT_VOTE,存款/保证金 D16b)</option>
              </select>
            </div>
            <div class="form-field" v-if="routeDialog.form.routeMode === 'CHAINED'">
              <label class="form-field__label">起始节点 <span class="req">*</span></label>
              <select class="form-select" v-model="routeDialog.form.startNodeCode">
                <option v-for="n in NODE_OPTIONS" :key="n" :value="n">{{ nodeLabel(n) }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">强制上会(六人小组)</label>
              <select class="form-select" v-model="routeDialog.form.mandatoryVote">
                <option value="N">否</option>
                <option value="Y">是</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">必经总行行长决策</label>
              <select class="form-select" v-model="routeDialog.form.presidentDecision">
                <option value="N">否</option>
                <option value="Y">是</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">优先级(低值优先)</label>
              <input class="form-input" v-model="routeDialog.form.priority" type="number" />
            </div>
            <div class="form-field">
              <label class="form-field__label">生效日</label>
              <input class="form-input" v-model="routeDialog.form.effectiveDate" type="datetime-local" />
            </div>
            <div class="form-field" style="grid-column: span 2">
              <label class="form-field__label">备注</label>
              <input class="form-input" v-model="routeDialog.form.remark" />
            </div>
          </div>

          <div class="sub-title" style="margin-top:14px">上会条件 JSON 构建器(命中即上会,可留空=无条件)</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">金额档</label>
              <select class="form-select" v-model="routeDialog.form.voteAmountTier" @change="syncVoteCondition">
                <option value="">不限</option>
                <option value="LT_1000">&lt;1000万</option>
                <option value="GE_1000_LT_5000">1000万(含)-5000万</option>
                <option value="GE_5000">≥5000万</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">企业类型</label>
              <select class="form-select" v-model="routeDialog.form.voteEnterpriseType" @change="syncVoteCondition">
                <option value="">不限</option>
                <option value="SOE">国企</option>
                <option value="NON_SOE">非国企</option>
              </select>
            </div>
          </div>
          <div class="form-field" style="margin-top:10px">
            <label class="form-field__label">上会条件 JSON</label>
            <textarea class="form-input" v-model="routeDialog.form.voteCondition" rows="3" placeholder='{"amount_tier":"GE_5000","enterprise_type":"SOE"}'></textarea>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-top:6px">
            <button class="btn btn--secondary" @click="parseVoteCondition">从 JSON 回填构建器</button>
          </div>
          <div class="section-tip" style="margin-top:8px">路由引擎读取 vote_condition:金额档按集团批复总额度/申请金额定档(§B18),企业类型取 SOE/NON_SOE;JSON 解析失败按不命中处理。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="routeDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveRoute">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 产品链路复核驳回弹窗(意见必填) -->
    <div class="modal" v-if="routeRejectDialog.show">
      <div class="modal__card">
        <div class="modal__title">复核驳回(退回草稿)</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">驳回意见 <span class="req">*</span></label>
            <textarea class="form-input" v-model="routeRejectDialog.opinion" rows="4" placeholder="请填写驳回原因,将写入配置变更日志"></textarea>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="routeRejectDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="doRouteReject">确认驳回</button>
        </div>
      </div>
    </div>

    <!-- 产品链路模拟路由弹窗(§7.2 矩阵路由,消耗产品链路配置) -->
    <div class="modal" v-if="routeSimDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">模拟路由:{{ routeSimDialog.productCode }}</div>
        <div class="modal__body">
          <div class="trial-form">
            <div class="form-field">
              <label class="form-field__label">存量/新增 <span class="req">*</span></label>
              <select class="form-select" v-model="routeSimDialog.newOrExisting">
                <option value="NEW">新增授信</option>
                <option value="EXISTING">存量授信</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">客户类型</label>
              <select class="form-select" v-model="routeSimDialog.customerType">
                <option value="">通配</option>
                <option value="SOE">国企</option>
                <option value="NON_SOE">非国企</option>
                <option value="PERSONAL">个人</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">担保主类型</label>
              <select class="form-select" v-model="routeSimDialog.guaranteeType">
                <option value="">通配</option>
                <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">申请金额(万元) <span class="req">*</span></label>
              <input class="form-input" v-model="routeSimDialog.amount" type="number" />
            </div>
            <div class="form-field">
              <label class="form-field__label">期限</label>
              <div style="display:flex;gap:4px">
                <input class="form-input" v-model="routeSimDialog.termValue" type="number" style="width:90px" />
                <select class="form-select" v-model="routeSimDialog.termUnit" style="width:90px">
                  <option value="YEAR">年</option>
                  <option value="MONTH">月</option>
                  <option value="DAY">日</option>
                </select>
              </div>
            </div>
            <div class="form-field">
              <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
              <input class="form-input" v-model="routeSimDialog.requestedRate" type="number" step="0.01" />
            </div>
            <div class="form-field" v-if="routeSimDialog.newOrExisting === 'EXISTING'">
              <label class="form-field__label">原执行利率(%)</label>
              <input class="form-input" v-model="routeSimDialog.originalRate" type="number" step="0.01" />
            </div>
            <div class="form-field" style="justify-content:flex-end">
              <button class="btn btn--primary" :disabled="routeSimDialog.loading" @click="runRouteSimulate">试算</button>
            </div>
          </div>

          <div v-if="routeSimResult" class="trial-result">
            <div class="result-row"><span class="dg-label">审批链首节点</span><b>{{ nodeLabel(routeSimResult.startNodeCode) }}</b><span class="badge badge--info">必经</span></div>
            <div class="result-row"><span class="dg-label">终审岗位</span><b>{{ nodeLabel(routeSimResult.finalNodeCode) }}</b></div>
            <div class="result-row">
              <span class="dg-label">审批链路</span>
              <span class="chain">
                <template v-for="(n, i) in routeSimResult.routeChain || []" :key="n">
                  <span class="chain__node">{{ nodeLabel(n) }}</span>
                  <span v-if="i < (routeSimResult.routeChain || []).length - 1" class="chain__arrow">→</span>
                </template>
              </span>
            </div>
            <div class="result-row"><span class="dg-label">利率方向</span>{{ rateDirectionText(routeSimResult.rateDirection) }}</div>
            <div class="result-row"><span class="dg-label">终审边界</span>{{ routeSimResult.boundaryRate != null ? routeSimResult.boundaryRate + '%' : '—' }}</div>
            <div class="result-row"><span class="dg-label">命中规则</span>{{ routeSimResult.matchedRuleName || routeSimResult.matchedRuleCode || '—' }}</div>
            <div class="result-row"><span class="dg-label">计算说明</span>{{ routeSimResult.message || '—' }}</div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="routeSimDialog.show = false">关闭</button>
        </div>
      </div>
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

    <!-- LPR 明细矩阵编辑弹窗(§8A.3:按指标 1Y/5Y+ × 产品逐行;空值=不维护,路由回退版本头表) -->
    <div class="modal" v-if="lprCfgDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">
          LPR 明细矩阵:{{ lprCfgDialog.versionCode }}
          <span style="font-size:12px;color:var(--color-text-sub);margin-left:8px">值域 0.5%–8% 且 0.05 整数倍;空值=该产品该期限不维护,路由回退版本头表</span>
        </div>
        <div class="modal__body">
          <table class="table">
            <thead>
              <tr>
                <th>产品编码</th><th>产品名称</th><th>产品线</th>
                <th>1Y LPR(%)</th><th>5Y+ LPR(%)</th><th>加点 BP(可选)</th><th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in lprCfgDialog.products" :key="p.productCode">
                <td>{{ p.productCode }}</td>
                <td>{{ p.productName || '—' }}</td>
                <td>{{ p.productCategory || '—' }}</td>
                <td><input class="form-input" type="number" step="0.05" min="0.5" max="8" v-model="lprCfgDialog.grid[p.productCode].v1" style="width:96px" /></td>
                <td><input class="form-input" type="number" step="0.05" min="0.5" max="8" v-model="lprCfgDialog.grid[p.productCode].v5" style="width:96px" /></td>
                <td><input class="form-input" type="number" step="1" v-model="lprCfgDialog.grid[p.productCode].bp" style="width:80px" /></td>
                <td><input class="form-input" v-model="lprCfgDialog.grid[p.productCode].remark" style="width:140px" /></td>
              </tr>
              <tr v-if="!lprCfgDialog.products.length">
                <td colspan="7" class="empty-cell">暂无启用产品,请先在产品目录新增并发布启用产品(§P2-4)</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="lprCfgDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveLprConfigs">保存明细</button>
        </div>
      </div>
    </div>

    <!-- 新增矩阵行弹窗 -->
    <div class="modal" v-if="matrixDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增权限矩阵行(草稿)</div>
        <div class="modal__body matrix-dialog__body">
          <div class="sub-title">匹配维度</div>
          <div class="form-grid">
            <div class="form-field" style="grid-column: span 2">
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
                <option value="LT_1000">1000万以下</option>
                <option value="GE_1000_LT_5000">1000万(含)-5000万</option>
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
              <select class="form-select" v-model="matrixDialog.form.guaranteeType">
                <option value="">通配</option>
                <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
              </select>
            </div>
          </div>

          <div class="sub-title" style="margin-top:14px">终审与边界</div>
          <div class="form-grid">
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
              <label class="form-field__label">优先级(低值优先)</label>
              <input class="form-input" v-model="matrixDialog.form.priority" type="number" />
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
            <div class="form-field" style="grid-column: span 2">
              <label class="form-field__label">BP 边界(按 LPR 换算)</label>
              <div style="display:flex;gap:6px">
                <select class="form-select" v-model="matrixDialog.form.bpSign" style="width:70px">
                  <option value="+">+</option>
                  <option value="-">-</option>
                </select>
                <input class="form-input" v-model="matrixDialog.form.boundaryBp" type="number" placeholder="BP 值" />
                <select class="form-select" v-model="matrixDialog.form.lprTerm" style="width:110px">
                  <option value="1Y">1Y LPR</option>
                  <option value="5Y+">5Y+ LPR</option>
                </select>
              </div>
            </div>
          </div>

          <div class="sub-title" style="margin-top:14px">生效与备注</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">生效时间 <span class="req">*</span></label>
              <input class="form-input" v-model="matrixDialog.form.effectiveFrom" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label">备注</label>
              <input class="form-input" v-model="matrixDialog.form.remark" />
            </div>
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
    <div class="modal" v-if="limitDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增产品硬边界草稿</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">产品编码 <span class="req">*</span></label>
            <input class="form-input" v-model="limitDialog.form.productCode" placeholder="如 LOAN-FLOW-001" />
          </div>
          <div class="form-field">
            <label class="form-field__label">产品名称</label>
            <input class="form-input" v-model="limitDialog.form.productName" />
          </div>
          <div class="form-field">
            <label class="form-field__label">业务类型 <span class="req">*</span></label>
            <select class="form-select" v-model="limitDialog.form.businessType">
              <option value="LOAN">贷款(全行不可低于硬边界)</option>
              <option value="DEPOSIT">存款(全行不可高于硬边界)</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">硬边界利率(%) <span class="req">*</span></label>
            <input class="form-input" v-model="limitDialog.form.hardBoundaryRate" type="number" step="0.01" />
          </div>
          <div class="form-field">
            <label class="form-field__label">生效时间 <span class="req">*</span></label>
            <input class="form-input" v-model="limitDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="section-tip">利率方向按业务类型自动确定:贷款=越低越优惠、存款=越高越优惠。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="limitDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveLimit">保存草稿</button>
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
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listLpr, createLpr, submitLpr, publishLpr, disableLpr,
  listLprConfigs, saveLprConfigs,
  listEnabledProducts,
  listMatrix, createMatrix, submitMatrix, publishMatrix, disableMatrix,
  listRuleSets, createRuleSet, submitRuleSet, publishRuleSet, disableRuleSet,
  listProductLimit, createProductLimit, submitProductLimit, publishProductLimit,
  disableProductLimit, rejectProductLimit,
  listChangeLogs,
  matrixRoute,
  listProductCatalog, createProduct, updateProduct, changeProductStatus, deleteProduct,
  listProductRoutes, createProductRoute, submitProductRoute, publishProductRoute,
  rejectProductRoute, disableProductRoute, deleteProductRoute,
  type ConfigChangeLog
} from '@/api/system'
import { GUARANTEE_TYPES, guaranteeTypeText, METRIC_CODES, metricName } from '@/utils/dict'
import {
  configStatusText, configActionText, configTypeText, businessBigTypeText,
  nodeLabel, customerTypeText, amountTierText, termTierText, rateDirectionText,
  businessTypeText, productName
} from '@/utils/dict'
import {
  listTrackingPolicies, createTrackingPolicy, createPolicyVersion,
  changePolicyStatus, changeVersionStatus,
  listPolicyVersions, listPolicyThresholds,
  simulatePolicy, listCommitmentPlans
} from '@/api/commitment'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const currentRoles = computed(() => userStore.userInfo?.roles || [])
const canMaintain = computed(() => currentRoles.value.includes('admin'))
const canReview = computed(() => canMaintain.value || currentRoles.value.includes('config_reviewer'))

const tabs = [
  { key: 'lpr', label: 'LPR 维护' },
  { key: 'matrix', label: '权限矩阵' },
  { key: 'product', label: '产品边界' },
  { key: 'productCenter', label: '产品配置' },
  { key: 'ruleset', label: '规则集' },
  { key: 'policy', label: '跟踪策略' },
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
  return configStatusText(s)
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
  return businessBigTypeText(b)
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
function actionText(a: string) {
  return configActionText(a)
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

// ---------- LPR 明细矩阵(§8A.3:草稿期按版本维护,保存为全量替换;发布后随版本冻结) ----------
const lprCfgDialog = reactive({
  show: false,
  versionId: 0,
  versionCode: '',
  products: [] as any[],
  grid: {} as Record<string, { v1: any; v5: any; bp: any; remark: string }>
})

async function openLprConfig(l: any) {
  lprCfgDialog.versionId = l.id
  lprCfgDialog.versionCode = l.versionCode
  try {
    lprCfgDialog.products = await listEnabledProducts()
  } catch {
    lprCfgDialog.products = []
  }
  const grid: Record<string, { v1: any; v5: any; bp: any; remark: string }> = {}
  for (const p of lprCfgDialog.products) grid[p.productCode] = { v1: '', v5: '', bp: '', remark: '' }
  // 回显既有明细:同一产品两期限值写入同行的 1Y/5Y+ 列
  try {
    const rows = await listLprConfigs(l.id)
    for (const r of rows) {
      const g = grid[r.productType]
      if (!g) continue
      if (r.lprTerm === '1Y') g.v1 = r.lprValue
      else if (r.lprTerm === '5Y+') g.v5 = r.lprValue
      if (r.lprBp != null) g.bp = r.lprBp
      if (r.remark) g.remark = r.remark
    }
  } catch {
    // 明细读取失败不阻断,保持空矩阵,交由保存时后端强校验
  }
  lprCfgDialog.grid = grid
  lprCfgDialog.show = true
}

async function saveLprConfigs() {
  const configs: any[] = []
  for (const p of lprCfgDialog.products) {
    const g = lprCfgDialog.grid[p.productCode]
    const v1 = g.v1 === '' || g.v1 == null ? null : Number(g.v1)
    const v5 = g.v5 === '' || g.v5 == null ? null : Number(g.v5)
    if (v1 == null && v5 == null) continue
    if (v1 != null && !validLprValue(`产品${p.productCode}·1Y`, v1)) return
    if (v5 != null && !validLprValue(`产品${p.productCode}·5Y+`, v5)) return
    const remark = (g.remark || '').trim() || null
    const bp = g.bp === '' || g.bp == null ? null : Number(g.bp)
    if (v1 != null) configs.push({ versionId: lprCfgDialog.versionId, lprTerm: '1Y', productType: p.productCode, lprValue: v1, lprBp: bp, remark })
    if (v5 != null) configs.push({ versionId: lprCfgDialog.versionId, lprTerm: '5Y+', productType: p.productCode, lprValue: v5, lprBp: bp, remark })
  }
  if (!configs.length) {
    ElMessage.warning('请至少维护一个产品的 LPR 值')
    return
  }
  await saveLprConfigs(configs)
  lprCfgDialog.show = false
  ElMessage.success('LPR 明细已保存,发布后随版本冻结生效')
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
const limitDialog = reactive({ show: false, form: {} as any })
function openLimitCreate() {
  limitDialog.form = { productCode: '', productName: '', businessType: 'LOAN', hardBoundaryRate: null, effectiveFrom: '' }
  limitDialog.show = true
}
async function saveLimit() {
  const f = limitDialog.form
  if (!f.productCode || !f.businessType || f.hardBoundaryRate === null || f.hardBoundaryRate === '' || !f.effectiveFrom) {
    ElMessage.warning('产品编码/业务类型/硬边界利率/生效时间必填')
    return
  }
  if (Number(f.hardBoundaryRate) <= 0) {
    ElMessage.warning('硬边界利率必须大于0')
    return
  }
  await createProductLimit({ ...f, hardBoundaryRate: Number(f.hardBoundaryRate) })
  limitDialog.show = false
  ElMessage.success('草稿已保存')
  productStatus.value = 'DRAFT'
  loadProductLimit()
}

// ---------- 产品配置中心(§8A.5:产品目录 + 产品审批链路,P2-4) ----------
const pcTab = ref('catalog')

function productStatusText(s: string) {
  return { ENABLED: '启用', DISABLED: '停用' }[s] || s || '—'
}
function productStatusBadge(s: string) {
  return `badge ${s === 'ENABLED' ? 'badge--success' : 'badge--neutral'}`
}
function productCustomerTypeText(s: string) {
  return { INDIVIDUAL: '个人', CORPORATE_SINGLE: '单一法人', GROUP: '集团' }[s] || s || '—'
}
function routeStatusText(s: string) {
  return { DRAFT: '草稿', PENDING_REVIEW: '待复核', PUBLISHED: '已生效', OBSOLETE: '已停用' }[s] || s || '—'
}
function routeStatusBadge(s: string) {
  const map: Record<string, string> = {
    DRAFT: 'badge--neutral', PENDING_REVIEW: 'badge--warning', PUBLISHED: 'badge--success', OBSOLETE: 'badge--neutral'
  }
  return `badge ${map[s] || 'badge--neutral'}`
}
function voteConditionText(json?: string) {
  if (!json) return '—'
  try {
    const o = JSON.parse(json)
    const parts: string[] = []
    if (o.amount_tier) parts.push(`金额${amountTierText(o.amount_tier)}`)
    if (o.enterprise_type) parts.push(o.enterprise_type === 'SOE' ? '国企' : '非国企')
    return parts.length ? parts.join(' 且 ') : '—'
  } catch {
    return json
  }
}

// 产品目录
const productQuery = reactive({ businessBigType: '', status: '' })
const productCatalog = ref<any[]>([])
async function loadProductCatalog() {
  try {
    productCatalog.value = await listProductCatalog(productQuery.businessBigType || undefined, productQuery.status || undefined)
  } catch {
    productCatalog.value = []
  }
}
const productDialog = reactive({ show: false, id: 0, form: {} as any })
function openProductCreate() {
  productDialog.id = 0
  productDialog.form = {
    productCode: '', productName: '', businessBigType: 'LOAN', productCategory: '',
    customerType: '', currency: 'CNY', defaultMinRate: null, defaultMaxRate: null,
    defaultMinTermMonths: null, defaultMaxTermMonths: null, effectiveDate: '', remark: ''
  }
  productDialog.show = true
}
function openProductEdit(p: any) {
  productDialog.id = p.id
  productDialog.form = { ...p }
  productDialog.show = true
}
async function saveProduct() {
  const f = productDialog.form
  if (!f.productCode?.trim() || !f.productName?.trim() || !f.businessBigType) {
    ElMessage.warning('产品编码/产品名称/业务大类必填')
    return
  }
  if (f.defaultMinRate != null && f.defaultMinRate !== '' && f.defaultMaxRate != null && f.defaultMaxRate !== ''
      && Number(f.defaultMinRate) > Number(f.defaultMaxRate)) {
    ElMessage.warning('默认利率下限不得高于上限')
    return
  }
  const payload: any = { ...f, remark: f.remark?.trim() || null }
  for (const k of ['defaultMinRate', 'defaultMaxRate']) {
    payload[k] = payload[k] === '' || payload[k] == null ? null : Number(payload[k])
  }
  for (const k of ['defaultMinTermMonths', 'defaultMaxTermMonths']) {
    payload[k] = payload[k] === '' || payload[k] == null ? null : Number(payload[k])
  }
  payload.customerType = payload.customerType || null
  payload.productCategory = payload.productCategory?.trim() || null
  payload.currency = payload.currency?.trim() || 'CNY'
  if (productDialog.id) {
    await updateProduct(productDialog.id, payload)
  } else {
    await createProduct(payload)
  }
  productDialog.show = false
  ElMessage.success(productDialog.id ? '产品已更新' : '产品已创建并启用')
  loadProductCatalog()
  loadEnabledProducts()
}
async function doProductStatus(p: any, status: string) {
  await ElMessageBox.confirm(
    status === 'DISABLED'
      ? `停用后新申请不可选,在途审批不受影响(D11)。确认停用 ${p.productName}?`
      : `确认启用 ${p.productName}?`,
    status === 'DISABLED' ? '停用确认' : '启用确认', { type: 'warning' }
  )
  await changeProductStatus(p.id, status)
  ElMessage.success(status === 'DISABLED' ? '已停用' : '已启用')
  loadProductCatalog()
  loadEnabledProducts()
}
async function doProductDelete(p: any) {
  await ElMessageBox.confirm(`确认删除产品 ${p.productCode}?未被申请/矩阵/LPR/边界引用时允许删除,否则仅可停用。`, '删除确认', { type: 'warning' })
  await deleteProduct(p.id)
  ElMessage.success('已删除')
  loadProductCatalog()
}

// 产品审批链路
const routeQuery = reactive({ productCode: '', status: '' })
const productRoutes = ref<any[]>([])
async function loadProductRoutes() {
  try {
    productRoutes.value = await listProductRoutes(routeQuery.productCode || undefined, routeQuery.status || undefined)
  } catch {
    productRoutes.value = []
  }
}
const enabledProducts = ref<any[]>([])
async function loadEnabledProducts() {
  try {
    enabledProducts.value = await listEnabledProducts()
  } catch {
    enabledProducts.value = []
  }
}
const NODE_OPTIONS = ['BRANCH_MANAGER', 'DEPT_GENERAL_MANAGER', 'VICE_PRESIDENT', 'SIX_PEOPLE_GROUP']
const routeDialog = reactive({ show: false, form: {} as any })
function openRouteCreate() {
  routeDialog.form = {
    productCode: '', businessBigTypeText: '', businessBigType: '',
    routeMode: 'CHAINED', startNodeCode: 'BRANCH_MANAGER',
    mandatoryVote: 'N', presidentDecision: 'N',
    voteCondition: '', voteAmountTier: '', voteEnterpriseType: '',
    priority: 0, effectiveDate: '', remark: ''
  }
  routeDialog.show = true
}
function onRouteProductChange() {
  const p = enabledProducts.value.find((x) => x.productCode === routeDialog.form.productCode)
  routeDialog.form.businessBigTypeText = p ? (p.businessBigType === 'LOAN' ? '贷款' : '存款') : ''
  routeDialog.form.businessBigType = p?.businessBigType || ''
}
function syncVoteCondition() {
  const f = routeDialog.form
  const cond: any = {}
  if (f.voteAmountTier) cond.amount_tier = f.voteAmountTier
  if (f.voteEnterpriseType) cond.enterprise_type = f.voteEnterpriseType
  f.voteCondition = Object.keys(cond).length ? JSON.stringify(cond) : ''
}
function parseVoteCondition() {
  const f = routeDialog.form
  f.voteAmountTier = ''
  f.voteEnterpriseType = ''
  if (!f.voteCondition) return
  try {
    const o = JSON.parse(f.voteCondition)
    if (o.amount_tier) f.voteAmountTier = o.amount_tier
    if (o.enterprise_type) f.voteEnterpriseType = o.enterprise_type
  } catch {
    ElMessage.warning('JSON 格式不正确,无法回填构建器')
  }
}
async function saveRoute() {
  const f = routeDialog.form
  if (!f.productCode || !f.routeMode) {
    ElMessage.warning('产品与路由模式必填')
    return
  }
  if (f.routeMode === 'CHAINED' && !f.startNodeCode) {
    ElMessage.warning('链式模式起始节点必填')
    return
  }
  const payload: any = {
    productCode: f.productCode,
    businessBigType: f.businessBigType,
    routeMode: f.routeMode,
    startNodeCode: f.routeMode === 'DIRECT_VOTE' ? null : f.startNodeCode || null,
    mandatoryVote: f.mandatoryVote === 'Y' ? 'Y' : 'N',
    presidentDecision: f.presidentDecision === 'Y' ? 'Y' : 'N',
    voteCondition: f.voteCondition || null,
    priority: f.priority == null || f.priority === '' ? 0 : Number(f.priority),
    effectiveDate: f.effectiveDate || null,
    remark: f.remark?.trim() || null
  }
  await createProductRoute(payload)
  routeDialog.show = false
  ElMessage.success('链路草稿已保存')
  loadProductRoutes()
}
async function doRouteSubmit(r: any) {
  await submitProductRoute(r.id)
  ElMessage.success('已送审,待复核发布')
  loadProductRoutes()
}
async function doRoutePublish(r: any) {
  await ElMessageBox.confirm('发布强制双人复核:发布人不得与创建人为同一人;同产品同生效日旧生效链路自动停用。确认复核发布?', '复核发布确认', { type: 'warning' })
  await publishProductRoute(r.id)
  ElMessage.success('已发布生效')
  loadProductRoutes()
}
async function doRouteDisable(r: any) {
  await ElMessageBox.confirm('确认停用该生效链路?', '停用确认', { type: 'warning' })
  await disableProductRoute(r.id)
  ElMessage.success('已停用')
  loadProductRoutes()
}
async function doRouteDelete(r: any) {
  await ElMessageBox.confirm(`确认删除链路 ${r.productCode} ${fmtTime(r.effectiveDate)}?`, '删除确认', { type: 'warning' })
  await deleteProductRoute(r.id)
  ElMessage.success('已删除')
  loadProductRoutes()
}
const routeRejectDialog = reactive({ show: false, id: 0, opinion: '' })
function openRouteReject(r: any) {
  routeRejectDialog.id = r.id
  routeRejectDialog.opinion = ''
  routeRejectDialog.show = true
}
async function doRouteReject() {
  if (!routeRejectDialog.opinion.trim()) {
    ElMessage.warning('驳回意见必填')
    return
  }
  await rejectProductRoute(routeRejectDialog.id, routeRejectDialog.opinion.trim())
  routeRejectDialog.show = false
  ElMessage.success('已驳回,退回草稿')
  loadProductRoutes()
}

// 产品链路模拟路由(§7.2 矩阵路由,消耗产品链路配置)
const routeSimDialog = reactive({
  show: false, productCode: '', newOrExisting: 'NEW', customerType: '', guaranteeType: '',
  amount: '' as any, termValue: '' as any, termUnit: 'YEAR', requestedRate: '' as any,
  originalRate: '' as any, loading: false
})
const routeSimResult = ref<any>(null)
function openRouteSimulate(r: any) {
  routeSimDialog.productCode = r.productCode
  routeSimDialog.newOrExisting = 'NEW'
  routeSimDialog.customerType = ''
  routeSimDialog.guaranteeType = ''
  routeSimDialog.amount = ''
  routeSimDialog.termValue = ''
  routeSimDialog.termUnit = 'YEAR'
  routeSimDialog.requestedRate = ''
  routeSimDialog.originalRate = ''
  routeSimResult.value = null
  routeSimDialog.show = true
}
async function runRouteSimulate() {
  const d = routeSimDialog
  if (!d.amount || !d.requestedRate) {
    ElMessage.warning('申请金额与申请利率必填')
    return
  }
  const prod = enabledProducts.value.find((p) => p.productCode === d.productCode)
  const payload: any = {
    businessBigType: prod?.businessBigType === 'DEPOSIT' ? 'DEPOSIT' : 'LOAN_PUBLIC',
    newOrExisting: d.newOrExisting,
    customerType: d.customerType || null,
    guaranteeType: d.guaranteeType || null,
    productCode: d.productCode,
    amount: Number(d.amount),
    requestedRate: Number(d.requestedRate)
  }
  if (d.termValue) {
    payload.termValue = Number(d.termValue)
    payload.termUnit = d.termUnit
  }
  if (d.newOrExisting === 'EXISTING' && d.originalRate) {
    payload.originalRate = Number(d.originalRate)
  }
  d.loading = true
  try {
    routeSimResult.value = await matrixRoute(payload)
  } catch {
    routeSimResult.value = null
  } finally {
    d.loading = false
  }
}

// ---------- 跟踪策略(§11.5/§11.7) ----------
const policyList = ref<any[]>([])
const policyMetric = ref('')
async function loadPolicies() {
  try {
    policyList.value = await listTrackingPolicies(policyMetric.value || undefined)
  } catch {
    policyList.value = []
  }
}

function emptyThreshold() {
  return { thresholdType: 'TIME_PROGRESS', thresholdValue: null, compareOperator: '>=', riskLevel: 'WATCH' }
}
function thresholdTypeText(s: string) {
  return { TIME_PROGRESS: '时间进度', ACHIEVEMENT_RATE: '达成率', CONSECUTIVE_DECLINE: '连续下降', NEAR_EXPIRY: '临近到期' }[s] || s || '—'
}
function checkFreqText(s: string) {
  return { DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月' }[s] || s || '—'
}
function policyRiskText(s: string) {
  return { NORMAL: '正常', WATCH: '关注', AT_RISK: '风险', DATA_PENDING: '数据待产', NO_EVALUATION: '暂无评估' }[s] || s || '—'
}
function policyRiskBadge(s: string) {
  const map: Record<string, string> = {
    NORMAL: 'badge--success', WATCH: 'badge--warning', AT_RISK: 'badge--danger', DATA_PENDING: 'badge--neutral'
  }
  return `badge ${map[s] || 'badge--neutral'}`
}

// 新增策略(策略+首个版本+阈值一并提交)
const policyDialog = reactive({ show: false, form: {} as any, version: {} as any, thrRows: [] as any[] })
function openPolicyCreate() {
  policyDialog.form = { policyNo: '', policyName: '', metricCode: '*', businessType: '', orgCode: '', priority: 100 }
  policyDialog.version = { versionCode: 'V1', effectiveFrom: '', effectiveTo: '', checkFrequency: 'DAILY', dataToleranceDays: 7 }
  policyDialog.thrRows = [emptyThreshold()]
  policyDialog.show = true
}
async function savePolicy() {
  const f = policyDialog.form
  const v = policyDialog.version
  if (!f.policyNo?.trim() || !f.policyName?.trim()) {
    ElMessage.warning('策略编号与策略名称必填')
    return
  }
  if (!v.versionCode?.trim() || !v.effectiveFrom) {
    ElMessage.warning('版本号与生效时间必填')
    return
  }
  const thresholds = policyDialog.thrRows
    .filter((t: any) => t.thresholdValue !== null && t.thresholdValue !== '')
    .map((t: any) => ({ ...t, thresholdValue: Number(t.thresholdValue) }))
  await createTrackingPolicy(
    { ...f, businessType: f.businessType || null, orgCode: f.orgCode?.trim() || null, priority: f.priority == null || f.priority === '' ? null : Number(f.priority) },
    { ...v, effectiveTo: v.effectiveTo || null, dataToleranceDays: v.dataToleranceDays === '' || v.dataToleranceDays == null ? null : Number(v.dataToleranceDays) },
    thresholds
  )
  policyDialog.show = false
  ElMessage.success('策略草稿已保存')
  loadPolicies()
}

async function doPolicyStatus(p: any, status: string) {
  if (status === 'EFFECTIVE') {
    await ElMessageBox.confirm('复核发布后该策略生效,同维度旧策略将被替换。确认?', '复核发布确认', { type: 'warning' })
  } else if (status === 'INVALID') {
    await ElMessageBox.confirm('确认停用该生效策略?', '停用确认', { type: 'warning' })
  }
  await changePolicyStatus(p.id, status)
  ElMessage.success(status === 'EFFECTIVE' ? '已发布生效' : status === 'INVALID' ? '已停用' : '已送审,待复核发布')
  loadPolicies()
}

// 版本管理(版本列表+新增版本+阈值明细)
const versionMgr = reactive({
  show: false,
  policy: null as any,
  versions: [] as any[],
  addShow: false,
  form: {} as any,
  thrPanel: { show: false, versionCode: '', rows: [] as any[] }
})
async function openVersionMgr(p: any) {
  versionMgr.policy = p
  versionMgr.addShow = false
  versionMgr.thrPanel.show = false
  try {
    versionMgr.versions = await listPolicyVersions(p.id)
  } catch {
    versionMgr.versions = []
  }
  versionMgr.show = true
}
async function openVersionThresholds(v: any) {
  versionMgr.thrPanel.versionCode = v.versionCode || '—'
  try {
    versionMgr.thrPanel.rows = await listPolicyThresholds(v.id)
  } catch {
    versionMgr.thrPanel.rows = []
  }
  versionMgr.thrPanel.show = true
}
function openAddVersion() {
  versionMgr.form = { versionCode: '', effectiveFrom: '', effectiveTo: '', checkFrequency: 'DAILY', dataToleranceDays: 7, thrRows: [emptyThreshold()] }
  versionMgr.addShow = true
}
async function savePolicyVersion() {
  const v = versionMgr.form
  if (!v.versionCode?.trim() || !v.effectiveFrom) {
    ElMessage.warning('版本号与生效时间必填')
    return
  }
  const thresholds = v.thrRows
    .filter((t: any) => t.thresholdValue !== null && t.thresholdValue !== '')
    .map((t: any) => ({ ...t, thresholdValue: Number(t.thresholdValue) }))
  await createPolicyVersion(versionMgr.policy.id, {
    versionCode: v.versionCode, effectiveFrom: v.effectiveFrom, effectiveTo: v.effectiveTo || null,
    checkFrequency: v.checkFrequency,
    dataToleranceDays: v.dataToleranceDays === '' || v.dataToleranceDays == null ? null : Number(v.dataToleranceDays)
  }, thresholds)
  versionMgr.addShow = false
  versionMgr.versions = await listPolicyVersions(versionMgr.policy.id)
  ElMessage.success('版本已保存')
}
async function doVersionStatus(v: any, status: string) {
  if (status === 'EFFECTIVE') {
    await ElMessageBox.confirm('版本生效区间与其他生效版本不得重叠,后端将强校验。确认复核发布?', '复核发布确认', { type: 'warning' })
  } else if (status === 'INVALID') {
    await ElMessageBox.confirm('确认停用该版本?', '停用确认', { type: 'warning' })
  }
  await changeVersionStatus(v.id, status)
  versionMgr.versions = await listPolicyVersions(versionMgr.policy.id)
  ElMessage.success(status === 'EFFECTIVE' ? '版本已生效' : status === 'INVALID' ? '版本已停用' : '已送审,待复核发布')
}

// 策略试算(§11.7):选历史承诺计划,输出命中策略与预警判定
const planOptions = ref<any[]>([])
const simulatePlanId = ref('' as any)
const simulateResult = ref<any>(null)
async function loadPlanOptions() {
  try {
    // plans 接口按指标展开行,需按计划去重并转驼峰(§11.7 试算以 planId 为准)
    const rows: any[] = await listCommitmentPlans()
    const seen = new Set<number>()
    planOptions.value = (rows || [])
      .filter((r) => r.id && !seen.has(r.id) && seen.add(r.id))
      .map((r) => ({ id: r.id, planNo: r.plan_no, customerNo: r.customer_no }))
  } catch {
    planOptions.value = []
  }
}
async function runPolicySimulate() {
  if (!simulatePlanId.value) return
  try {
    simulateResult.value = await simulatePolicy(Number(simulatePlanId.value))
  } catch {
    simulateResult.value = null
  }
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
  loadProductCatalog()
  loadEnabledProducts()
  loadProductRoutes()
  loadPolicies()
  loadPlanOptions()
  loadChangeLogs()
})
</script>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.card__head { gap: 8px; flex-wrap: wrap; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
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

.matrix-dialog__body { max-height: 68vh; overflow-y: auto; padding-right: 6px; }
.modal__card { max-width: 720px; width: 92vw; }
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
</style>
