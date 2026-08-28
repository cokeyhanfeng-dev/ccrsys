<template>
  <div>
    <div class="section-head">
      <div class="section-title">参数管理</div>
      <InfoTip>
        LPR / 权限矩阵 / 产品边界 / 利率规则集版本管理(草稿→送审→复核发布→停用,发布强制双人复核:发布人≠创建人)。
        /system/** 接口仅系统管理员可访问(复核发布放行配置复核人),其他角色操作将提示无权限。
      </InfoTip>
    </div>

    <!-- §UI审查:页签统一为 .segmented 分段控件,与 flow/run-log 一致,解决小屏换行 -->
    <div class="segmented">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="segmented__item"
        :class="{ 'segmented__item--active': activeTab === t.key }"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ========== LPR 维护 ========== -->
    <div v-if="activeTab === 'lpr'" class="card" v-loading="loading.lpr">
      <div class="card-toolbar">
        <span class="card-toolbar__title">LPR 参数</span>
        <span class="card-toolbar__sub">计划财务部人工维护,PRD D12</span>
        <select class="form-select" v-model="lprStatus" style="width:140px" @change="loadLpr" aria-label="状态筛选">
          <option value="">全部状态</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openLprCreate">＋ 新增 LPR 草稿</button>
        </div>
      </div>
      <table class="table table--full">
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
              <button v-if="canMaintain && l.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doSubmit('lpr', l.id)">{{ pendingText(`submit:lpr:${l.id}`, '送审') }}</button>
              <button v-if="canReview && l.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doPublish('lpr', l.id)">{{ pendingText(`publish:lpr:${l.id}`, '复核发布') }}</button>
              <button v-if="canMaintain && l.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doDisable('lpr', l.id)">{{ pendingText(`disable:lpr:${l.id}`, '停用') }}</button>
            </td>
          </tr>
          <tr v-if="!lprList.length"><td colspan="10" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 权限矩阵 ========== -->
    <div v-if="activeTab === 'matrix'" class="card" v-loading="loading.matrix">
      <div class="card-toolbar">
        <span class="card-toolbar__title">权限矩阵</span>
        <!-- §UI审查:默认 value="" 实为不过滤加载全部,文案改「全部状态」与行为一致 -->
        <span class="card-toolbar__sub">LPR±BP 路由阈值;生效行禁止原位修改,调整=新建行发布替换</span>
        <select class="form-select" v-model="matrixStatus" style="width:140px" @change="loadMatrix" aria-label="状态筛选">
          <option value="">全部状态</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openMatrixCreate">＋ 新增矩阵行</button>
        </div>
      </div>
      <!-- §UI审查:13 列宽表横向滚动 + 关键列 nowrap/ellipsis -->
      <table class="table table--wide">
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
            <td class="col-ellipsis" :title="productName(m.productCode, '通配')">{{ productName(m.productCode, '通配') }}</td>
            <td>{{ amountTierText(m.amountTier, '通配') }}</td>
            <td>{{ termTierText(m.termTier, '通配') }}</td>
            <td class="col-ellipsis" :title="guaranteeTypeText(m.guaranteeType, '通配')">{{ guaranteeTypeText(m.guaranteeType, '通配') }}</td>
            <td>{{ nodeLabel(m.startNodeCode) }}</td>
            <td class="col-ellipsis" :title="boundaryText(m)">{{ boundaryText(m) }}</td>
            <td class="num">{{ m.priority }}</td>
            <td><span :class="statusBadge(m.status)">{{ statusText(m.status) }}</span></td>
            <td>
              <button v-if="canMaintain && m.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doSubmit('matrix', m.id)">{{ pendingText(`submit:matrix:${m.id}`, '送审') }}</button>
              <button v-if="canReview && m.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doPublish('matrix', m.id)">{{ pendingText(`publish:matrix:${m.id}`, '复核发布') }}</button>
              <button v-if="canMaintain && m.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doDisable('matrix', m.id)">{{ pendingText(`disable:matrix:${m.id}`, '停用') }}</button>
            </td>
          </tr>
          <tr v-if="!matrixList.length"><td colspan="13" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 产品边界(§12.13 ③) ========== -->
    <div v-if="activeTab === 'product'" class="card" v-loading="loading.product">
      <div class="card-toolbar">
        <span class="card-toolbar__title">产品业务硬边界</span>
        <span class="card-toolbar__sub">全行业务硬边界,任何节点调价/矩阵边界不得突破</span>
        <select class="form-select" v-model="productStatus" style="width:140px" @change="loadProductLimit" aria-label="状态筛选">
          <option value="">全部状态</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openLimitCreate">＋ 新增产品边界草稿</button>
        </div>
      </div>
      <!-- §UI审查:11 列宽表横向滚动 -->
      <table class="table table--wide">
        <thead>
          <tr>
            <th>产品编码</th><th>产品名称</th><th>业务类型</th><th>硬边界利率</th><th>利率方向</th>
            <th>生效时间</th><th>失效时间</th><th>状态</th><th>发布人</th><th>发布时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in productList" :key="p.id">
            <td>{{ p.productCode }}</td>
            <td class="col-ellipsis" :title="p.productName || '—'">{{ p.productName || '—' }}</td>
            <td>{{ businessTypeText(p.businessType) }}</td>
            <td class="num">{{ p.hardBoundaryRate }}%</td>
            <td>{{ rateDirectionText(p.rateDirection) }}</td>
            <td>{{ fmtTime(p.effectiveFrom) }}</td>
            <td>{{ fmtTime(p.effectiveTo) }}</td>
            <td><span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
            <td>{{ p.publishBy ?? '—' }}</td>
            <td>{{ fmtTime(p.publishTime) }}</td>
            <td>
              <button v-if="canMaintain && p.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doSubmit('product', p.id)">{{ pendingText(`submit:product:${p.id}`, '送审') }}</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doPublish('product', p.id)">{{ pendingText(`publish:product:${p.id}`, '复核发布') }}</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="openReject(p.id)">驳回</button>
              <button v-if="canMaintain && p.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doDisable('product', p.id)">{{ pendingText(`disable:product:${p.id}`, '停用') }}</button>
            </td>
          </tr>
          <tr v-if="!productList.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 产品配置中心(§8A.5:产品目录 + 产品审批链路) ========== -->
    <div v-if="activeTab === 'productCenter'" class="card" v-loading="loading.catalog || loading.route">
      <!-- §UI审查:子页签同步统一为 .segmented -->
      <div class="segmented" style="margin-bottom:16px">
        <button class="segmented__item" :class="{ 'segmented__item--active': pcTab === 'catalog' }" @click="pcTab = 'catalog'">产品目录</button>
        <button class="segmented__item" :class="{ 'segmented__item--active': pcTab === 'route' }" @click="pcTab = 'route'">产品审批链路</button>
      </div>

      <!-- 产品目录(§8A.5①:申请页产品下拉/LPR明细/权限矩阵/产品边界的权威来源) -->
      <div v-if="pcTab === 'catalog'">
        <div class="card-toolbar">
          <span class="card-toolbar__title">产品目录</span>
          <span class="card-toolbar__sub">产品编码一经启用禁改;停用后新申请不可选,在途审批不受影响 D11</span>
          <select class="form-select" v-model="productQuery.businessBigType" style="width:130px" @change="loadProductCatalog" aria-label="业务大类筛选">
            <option value="">全部业务</option>
            <option value="LOAN">贷款</option>
            <option value="DEPOSIT">存款</option>
          </select>
          <select class="form-select" v-model="productQuery.status" style="width:120px" @change="loadProductCatalog" aria-label="状态筛选">
            <option value="">全部状态</option>
            <option value="ENABLED">启用</option>
            <option value="DISABLED">停用</option>
          </select>
          <div class="card-toolbar__actions">
            <button v-if="canMaintain" class="btn btn--primary" @click="openProductCreate">＋ 新增产品</button>
          </div>
        </div>
        <table class="table table--full">
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
              <td>{{ currencyText(p.currency || 'CNY') }}</td>
              <td class="num">{{ p.defaultMinRate != null || p.defaultMaxRate != null ? `${p.defaultMinRate ?? '—'} ~ ${p.defaultMaxRate ?? '—'}` : '—' }}</td>
              <td class="num">{{ p.defaultMinTermMonths != null || p.defaultMaxTermMonths != null ? `${p.defaultMinTermMonths ?? '—'} ~ ${p.defaultMaxTermMonths ?? '—'}` : '—' }}</td>
              <td>{{ fmtTime(p.effectiveDate) }}</td>
              <td><span :class="productStatusBadge(p.status)">{{ productStatusText(p.status) }}</span></td>
              <td>
                <button v-if="canMaintain" class="btn btn--text" @click="openProductEdit(p)">编辑</button>
                <button v-if="canMaintain && p.status === 'ENABLED'" class="btn btn--text" :disabled="!!pending" @click="doProductStatus(p, 'DISABLED')">{{ pendingText(`prodstatus:${p.id}`, '停用') }}</button>
                <button v-if="canMaintain && p.status !== 'ENABLED'" class="btn btn--text" :disabled="!!pending" @click="doProductStatus(p, 'ENABLED')">{{ pendingText(`prodstatus:${p.id}`, '启用') }}</button>
                <button v-if="canMaintain && p.status === 'DISABLED'" class="btn btn--text" :disabled="!!pending" @click="doProductDelete(p)">{{ pendingText(`proddelete:${p.id}`, '删除') }}</button>
              </td>
            </tr>
            <tr v-if="!productCatalog.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 产品审批链路(§8A.5②:路由引擎读取替代硬编码;支行行长节点恒必经,B13) -->
      <div v-if="pcTab === 'route'">
        <div class="card-toolbar">
          <span class="card-toolbar__title">产品审批链路</span>
          <span class="card-toolbar__sub">配置特殊审批要求(上会/行长决策);未配置时按默认链路运行——对公贷款=链式逐级、存款/保证金=直接上会(草稿→送审→复核发布→停用;同产品同生效日仅一版生效)</span>
          <select class="form-select" v-model="routeQuery.productCode" style="width:200px" @change="loadProductRoutes" aria-label="产品筛选">
            <option value="">全部产品</option>
            <option v-for="p in productCatalog" :key="p.productCode" :value="p.productCode">{{ p.productCode }} · {{ p.productName }}</option>
          </select>
          <select class="form-select" v-model="routeQuery.status" style="width:120px" @change="loadProductRoutes" aria-label="状态筛选">
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="PENDING_REVIEW">待复核</option>
            <option value="PUBLISHED">已生效</option>
            <option value="OBSOLETE">已停用</option>
          </select>
          <div class="card-toolbar__actions">
            <button v-if="canMaintain" class="btn btn--primary" @click="openRouteCreate">＋ 新增链路</button>
          </div>
        </div>
        <!-- §UI审查:11 列宽表横向滚动 -->
        <table class="table table--wide">
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
              <td class="col-ellipsis" :title="voteConditionText(r.voteCondition)">{{ voteConditionText(r.voteCondition) }}</td>
              <td class="num">{{ r.priority ?? '—' }}</td>
              <td>{{ fmtTime(r.effectiveDate) }}</td>
              <td><span :class="routeStatusBadge(r.status)">{{ routeStatusText(r.status) }}</span></td>
              <td>
                <button class="btn btn--text" @click="openRouteSimulate(r)">模拟路由</button>
                <button v-if="canMaintain && r.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doRouteSubmit(r)">{{ pendingText(`routesubmit:${r.id}`, '送审') }}</button>
                <button v-if="canReview && r.status === 'PENDING_REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doRoutePublish(r)">{{ pendingText(`routepublish:${r.id}`, '复核发布') }}</button>
                <button v-if="canReview && r.status === 'PENDING_REVIEW'" class="btn btn--text" :disabled="!!pending" @click="openRouteReject(r)">驳回</button>
                <button v-if="canMaintain && r.status === 'PUBLISHED'" class="btn btn--text" :disabled="!!pending" @click="doRouteDisable(r)">{{ pendingText(`routedisable:${r.id}`, '停用') }}</button>
                <button v-if="canMaintain && r.status !== 'PUBLISHED'" class="btn btn--text" :disabled="!!pending" @click="doRouteDelete(r)">{{ pendingText(`routedelete:${r.id}`, '删除') }}</button>
              </td>
            </tr>
            <tr v-if="!productRoutes.length"><td colspan="11" class="empty-cell">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ========== 规则集 ========== -->
    <div v-if="activeTab === 'ruleset'" class="card" v-loading="loading.ruleset">
      <div class="card-toolbar">
        <span class="card-toolbar__title">利率规则集</span>
        <span class="card-toolbar__sub">发布前自动连续性校验:区间连续、无空档、无重叠</span>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openSetCreate">＋ 新增规则集草稿</button>
        </div>
      </div>
      <table class="table table--full">
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
              <button v-if="canMaintain && s.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doSubmit('ruleset', s.id)">{{ pendingText(`submit:ruleset:${s.id}`, '送审') }}</button>
              <button v-if="canReview && s.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doPublish('ruleset', s.id)">{{ pendingText(`publish:ruleset:${s.id}`, '复核发布') }}</button>
              <button v-if="canMaintain && s.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doDisable('ruleset', s.id)">{{ pendingText(`disable:ruleset:${s.id}`, '停用') }}</button>
            </td>
          </tr>
          <tr v-if="!ruleSets.length"><td colspan="9" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 跟踪策略(§11.5/§11.7) ========== -->
    <div v-if="activeTab === 'policy'" class="card" v-loading="loading.policy">
      <div class="card-toolbar">
        <span class="card-toolbar__title">跟踪策略</span>
        <span class="card-toolbar__sub">版本化:草稿→送审→复核发布→停用;匹配优先级 指标+业务+机构 &gt; 指标+业务 &gt; 指标默认 &gt; 全行默认*</span>
        <select class="form-select" v-model="policyMetric" style="width:170px" @change="loadPolicies" aria-label="指标筛选">
          <option value="">全部指标</option>
          <option value="*">全行默认</option>
          <option v-for="m in metricDict" :key="m.code" :value="m.code">{{ m.name }}</option>
        </select>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openPolicyCreate">＋ 新增策略</button>
        </div>
      </div>
      <table class="table table--full">
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
              <button v-if="canMaintain && p.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doPolicyStatus(p, 'REVIEW')">{{ pendingText(`policystatus:${p.id}`, '送审') }}</button>
              <button v-if="canReview && p.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doPolicyStatus(p, 'EFFECTIVE')">{{ pendingText(`policystatus:${p.id}`, '复核发布') }}</button>
              <button v-if="canMaintain && p.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doPolicyStatus(p, 'INVALID')">{{ pendingText(`policystatus:${p.id}`, '停用') }}</button>
            </td>
          </tr>
          <tr v-if="!policyList.length"><td colspan="8" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>

      <!-- 策略试算(§11.7):选历史承诺计划,输出命中策略与预警判定 -->
      <div class="trial-result">
        <div class="form-group-title">策略试算</div>
        <div class="simulate-bar">
          <select class="form-select" v-model="simulatePlanId" style="width:260px" aria-label="历史承诺计划">
            <option value="">选择历史承诺计划</option>
            <option v-for="pl in planOptions" :key="pl.id" :value="pl.id">{{ pl.planNo }} · {{ pl.customerNo || '—' }}</option>
          </select>
          <button class="btn btn--primary" :disabled="!simulatePlanId" @click="runPolicySimulate">试算</button>
          <span class="form-hint">按计划冻结的指标逐项匹配当前生效策略并判定预警等级</span>
        </div>
        <div v-if="simulateResult" style="margin-top:12px">
          <div class="desc-grid desc-grid--3">
            <div>
              <div class="desc-item__label">计划</div>
              <div class="desc-item__value">{{ simulateResult.planNo }} · 冻结策略版本 {{ simulateResult.frozenPolicyVersionId || '—' }}</div>
            </div>
          </div>
          <table class="table table--full" style="margin-top:8px" v-if="simulateResult.metrics?.length">
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
          <div v-else class="empty-line" style="margin-top:8px">该计划无承诺指标数据</div>
        </div>
      </div>
    </div>

    <!-- ========== 指标字典(§9;admin 配置化) ========== -->
    <div v-if="activeTab === 'metricDict'" class="card" v-loading="loading.metric">
      <div class="card-toolbar">
        <span class="card-toolbar__title">指标字典</span>
        <span class="card-toolbar__sub">数仓按字典推送指标数据,新增指标在此登记即可,前端承诺/跟踪下拉无需改代码;编码一经创建不可改</span>
        <select class="form-select" v-model="metricQuery.status" style="width:120px" @change="loadMetricDefs" aria-label="状态筛选">
          <option value="">全部状态</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
        </select>
        <input class="form-input" v-model="metricQuery.keyword" placeholder="编码/名称" style="width:180px" @keyup.enter="loadMetricDefs" />
        <button class="btn btn--secondary" @click="loadMetricDefs">查询</button>
        <div class="card-toolbar__actions">
          <button v-if="canMaintain" class="btn btn--primary" @click="openMetricCreate">＋ 新增指标</button>
        </div>
      </div>
      <table class="table table--full">
        <thead>
          <tr>
            <th>指标编码</th><th>指标名称</th><th>值类型</th><th>适用范围</th><th>单位</th><th>折算版本</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in metricDefs" :key="m.id">
            <td>{{ m.metricCode }}</td>
            <td>{{ m.metricName }}</td>
            <td>{{ metricValueTypeText(m.valueType) }}</td>
            <td>{{ m.metricScope ? metricScopeText(m.metricScope) : '—' }}</td>
            <td>{{ m.unit || '—' }}</td>
            <td>{{ m.currentCalcVersion || '—' }}</td>
            <td><span :class="metricStatusBadge(m.status)">{{ metricStatusText(m.status) }}</span></td>
            <td>
              <button v-if="canMaintain" class="btn btn--text" @click="openMetricEdit(m)">编辑</button>
              <button v-if="canMaintain && m.status === 'ACTIVE'" class="btn btn--text" :disabled="!!pending" @click="doMetricStatus(m, 'DISABLED')">{{ pendingText(`metricstatus:${m.id}`, '停用') }}</button>
              <button v-if="canMaintain && m.status === 'DISABLED'" class="btn btn--text" :disabled="!!pending" @click="doMetricStatus(m, 'ACTIVE')">{{ pendingText(`metricstatus:${m.id}`, '启用') }}</button>
            </td>
          </tr>
          <tr v-if="!metricDefs.length"><td colspan="8" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 变更日志(§8A.2) ========== -->
    <div v-if="activeTab === 'changelog'" class="card" v-loading="loading.changelog">
      <div class="card-toolbar">
        <span class="card-toolbar__title">配置变更日志</span>
        <span class="card-toolbar__sub">LPR/矩阵/规则集/产品边界全量留痕</span>
        <select class="form-select" v-model="logQuery.configType" style="width:150px" @change="loadChangeLogs" aria-label="配置域筛选">
          <option value="">全部配置域</option>
          <option v-for="t in configTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
        </select>
        <input class="form-input" v-model="logQuery.configId" type="number" placeholder="配置记录ID" style="width:130px" @keyup.enter="loadChangeLogs" />
        <button class="btn btn--secondary" @click="loadChangeLogs">查询</button>
      </div>
      <table class="table table--full">
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
      <div class="card-toolbar">
        <span class="card-toolbar__title">矩阵路由试算</span>
        <span class="card-toolbar__sub">输入业务维度,输出终审岗位与完整链路</span>
      </div>
      <div class="trial-form">
        <div class="form-field">
          <label class="form-field__label" for="trial-biztype">业务大类 <span class="req">*</span></label>
          <select id="trial-biztype" class="form-select" v-model="trial.businessBigType">
            <option value="LOAN_PUBLIC">对公贷款</option>
            <option value="LOAN_PERSONAL">个人贷款</option>
            <option value="DEPOSIT">存款</option>
            <option value="MARGIN">保证金</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-new">存量/新增 <span class="req">*</span></label>
          <select id="trial-new" class="form-select" v-model="trial.newOrExisting">
            <option value="NEW">新增授信</option>
            <option value="EXISTING">存量授信</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-custype">客户类型</label>
          <select id="trial-custype" class="form-select" v-model="trial.customerType">
            <option value="">通配</option>
            <option value="SOE">国企</option>
            <option value="NON_SOE">非国企</option>
            <option value="PERSONAL">个人</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-guarantee">担保主类型</label>
          <select id="trial-guarantee" class="form-select" v-model="trial.guaranteeType">
            <option value="">通配</option>
            <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-amount">申请金额(万元) <span class="req">*</span></label>
          <input id="trial-amount" class="form-input" v-model="trial.amount" type="number" autocomplete="off" />
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-term">期限</label>
          <div style="display:flex;gap:4px">
            <input id="trial-term" class="form-input" v-model="trial.termValue" type="number" style="width:90px" autocomplete="off" />
            <select class="form-select" v-model="trial.termUnit" style="width:90px" aria-label="期限单位">
              <option value="YEAR">年</option>
              <option value="MONTH">月</option>
              <option value="DAY">日</option>
            </select>
          </div>
        </div>
        <div class="form-field">
          <label class="form-field__label" for="trial-rate">申请利率(%) <span class="req">*</span></label>
          <input id="trial-rate" class="form-input" v-model="trial.requestedRate" type="number" step="0.01" autocomplete="off" />
        </div>
        <div class="form-field" v-if="trial.newOrExisting === 'EXISTING'">
          <label class="form-field__label" for="trial-origrate">原执行利率(%)</label>
          <input id="trial-origrate" class="form-input" v-model="trial.originalRate" type="number" step="0.01" autocomplete="off" />
        </div>
        <div class="form-field" style="justify-content:flex-end">
          <button class="btn btn--primary" @click="runTrial">试算</button>
        </div>
      </div>

      <div v-if="trialResult" class="trial-result">
        <!-- 试算结果:键值统一 desc-grid,审批链路用 design-system 流程节点链 -->
        <div class="desc-grid desc-grid--3">
          <div>
            <div class="desc-item__label">审批链首节点</div>
            <div class="desc-item__value">{{ nodeLabel(trialResult.startNodeCode) }} <span class="badge badge--info">必经</span></div>
          </div>
          <div>
            <div class="desc-item__label">终审岗位</div>
            <div class="desc-item__value">{{ nodeLabel(trialResult.finalNodeCode) }}</div>
          </div>
          <div>
            <div class="desc-item__label">利率方向</div>
            <div class="desc-item__value">{{ rateDirectionText(trialResult.rateDirection) }}</div>
          </div>
          <div>
            <div class="desc-item__label">命中规则</div>
            <div class="desc-item__value">{{ trialResult.matchedRuleName || trialResult.matchedRuleCode || '—' }}</div>
          </div>
          <div>
            <div class="desc-item__label">采用 LPR 版本</div>
            <div class="desc-item__value">{{ trialResult.lprVersionCode || '—' }}</div>
          </div>
          <div class="desc-item--full">
            <div class="desc-item__label">审批链路</div>
            <div class="flow-steps">
              <template v-for="(n, i) in trialResult.routeChain || []" :key="n">
                <span class="flow-node">{{ nodeLabel(n) }}</span>
                <span v-if="i < (trialResult.routeChain || []).length - 1" class="chain__arrow">→</span>
              </template>
            </div>
          </div>
          <div class="desc-item--full">
            <div class="desc-item__label">计算说明</div>
            <div class="desc-item__value" style="font-weight:400">{{ trialResult.message || '—' }}</div>
          </div>
        </div>
      </div>
      <div v-else class="section-tip" style="margin-top:12px">填写上方维度后点击"试算",输出终审岗位与审批链路。</div>
    </div>

    <!-- 新增跟踪策略弹窗(策略+首个版本+阈值一并提交,§11.5) -->
    <div class="modal" v-if="policyDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">新增跟踪策略</div>
        <div class="modal__body">
          <div class="form-group-title">策略维度</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="pd-policyNo">策略编号 <span class="req">*</span></label>
              <input id="pd-policyNo" class="form-input" v-model="policyDialog.form.policyNo" placeholder="如 P-2026-001" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-policyName">策略名称 <span class="req">*</span></label>
              <input id="pd-policyName" class="form-input" v-model="policyDialog.form.policyName" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-metricCode">指标编码 <span class="req">*</span></label>
              <select id="pd-metricCode" class="form-select" v-model="policyDialog.form.metricCode">
                <option value="*">全行默认</option>
                <option v-for="m in metricDict" :key="m.code" :value="m.code">{{ m.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-bizType">业务类型</label>
              <select id="pd-bizType" class="form-select" v-model="policyDialog.form.businessType">
                <option value="">不限</option>
                <option value="LOAN">贷款</option>
                <option value="DEPOSIT">存款</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-orgCode">机构编码</label>
              <input id="pd-orgCode" class="form-input" v-model="policyDialog.form.orgCode" placeholder="空=通用" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-priority">优先级</label>
              <input id="pd-priority" class="form-input" v-model="policyDialog.form.priority" type="number" autocomplete="off" />
            </div>
          </div>

          <div class="form-group-title">首个版本(草稿)</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="pd-verCode">版本号 <span class="req">*</span></label>
              <input id="pd-verCode" class="form-input" v-model="policyDialog.version.versionCode" placeholder="如 V1" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-effFrom">生效时间 <span class="req">*</span></label>
              <input id="pd-effFrom" class="form-input" v-model="policyDialog.version.effectiveFrom" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-effTo">失效时间</label>
              <input id="pd-effTo" class="form-input" v-model="policyDialog.version.effectiveTo" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-checkFreq">校验频率</label>
              <select id="pd-checkFreq" class="form-select" v-model="policyDialog.version.checkFrequency">
                <option value="DAILY">每日</option>
                <option value="WEEKLY">每周</option>
                <option value="MONTHLY">每月</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="pd-tolerance">数据容忍天数</label>
              <input id="pd-tolerance" class="form-input" v-model="policyDialog.version.dataToleranceDays" type="number" autocomplete="off" />
            </div>
          </div>

          <div class="form-group-title">阈值配置</div>
          <table class="table table--full">
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
          <table class="table table--full">
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
                  <button v-if="canMaintain && v.status === 'DRAFT'" class="btn btn--text" :disabled="!!pending" @click="doVersionStatus(v, 'REVIEW')">{{ pendingText(`versionstatus:${v.id}`, '送审') }}</button>
                  <button v-if="canReview && v.status === 'REVIEW'" class="btn btn--text" :disabled="!!pending" @click="doVersionStatus(v, 'EFFECTIVE')">{{ pendingText(`versionstatus:${v.id}`, '复核发布') }}</button>
                  <button v-if="canMaintain && v.status === 'EFFECTIVE'" class="btn btn--text" :disabled="!!pending" @click="doVersionStatus(v, 'INVALID')">{{ pendingText(`versionstatus:${v.id}`, '停用') }}</button>
                </td>
              </tr>
              <tr v-if="!versionMgr.versions.length"><td colspan="7" class="empty-cell">暂无版本</td></tr>
            </tbody>
          </table>

          <div v-if="versionMgr.thrPanel.show" style="margin-top:16px;border-top:1px dashed var(--color-border);padding-top:12px">
            <div class="form-group-title">阈值明细:版本 {{ versionMgr.thrPanel.versionCode }}</div>
            <table class="table table--full" v-if="versionMgr.thrPanel.rows.length">
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
            <div v-else class="empty-line">该版本未配置阈值,将使用系统默认阈值</div>
          </div>

          <div v-if="versionMgr.addShow" style="margin-top:16px;border-top:1px dashed var(--color-border);padding-top:12px">
            <div class="form-group-title">新增版本(草稿)</div>
            <div class="form-grid">
              <div class="form-field">
                <label class="form-field__label" for="vm-verCode">版本号 <span class="req">*</span></label>
                <input id="vm-verCode" class="form-input" v-model="versionMgr.form.versionCode" placeholder="如 V2" autocomplete="off" />
              </div>
              <div class="form-field">
                <label class="form-field__label" for="vm-effFrom">生效时间 <span class="req">*</span></label>
                <input id="vm-effFrom" class="form-input" v-model="versionMgr.form.effectiveFrom" type="datetime-local" />
              </div>
              <div class="form-field">
                <label class="form-field__label" for="vm-effTo">失效时间</label>
                <input id="vm-effTo" class="form-input" v-model="versionMgr.form.effectiveTo" type="datetime-local" />
              </div>
              <div class="form-field">
                <label class="form-field__label" for="vm-checkFreq">校验频率</label>
                <select id="vm-checkFreq" class="form-select" v-model="versionMgr.form.checkFrequency">
                  <option value="DAILY">每日</option>
                  <option value="WEEKLY">每周</option>
                  <option value="MONTHLY">每月</option>
                </select>
              </div>
              <div class="form-field">
                <label class="form-field__label" for="vm-tolerance">数据容忍天数</label>
                <input id="vm-tolerance" class="form-input" v-model="versionMgr.form.dataToleranceDays" type="number" autocomplete="off" />
              </div>
            </div>
            <div class="form-group-title">阈值</div>
            <table class="table table--full">
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
              <label class="form-field__label" for="prod-code">产品编码 <span class="req">*</span></label>
              <input id="prod-code" class="form-input" v-model="productDialog.form.productCode" :disabled="!!productDialog.id" placeholder="如 LOAN_A" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-name">产品名称 <span class="req">*</span></label>
              <input id="prod-name" class="form-input" v-model="productDialog.form.productName" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-biztype">业务大类 <span class="req">*</span></label>
              <select id="prod-biztype" class="form-select" v-model="productDialog.form.businessBigType" :disabled="!!productDialog.id">
                <option value="LOAN">贷款</option>
                <option value="DEPOSIT">存款</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-category">产品线</label>
              <input id="prod-category" class="form-input" v-model="productDialog.form.productCategory" placeholder="如 对公贷款/协定/银票保证金" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-custype">适用客户类型</label>
              <select id="prod-custype" class="form-select" v-model="productDialog.form.customerType">
                <option value="">不限</option>
                <option value="INDIVIDUAL">个人</option>
                <option value="CORPORATE_SINGLE">单一法人</option>
                <option value="GROUP">集团</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-currency">币种</label>
              <input id="prod-currency" class="form-input" v-model="productDialog.form.currency" placeholder="CNY" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-minRate">默认利率下限(%)</label>
              <input id="prod-minRate" class="form-input" v-model="productDialog.form.defaultMinRate" type="number" step="0.01" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-maxRate">默认利率上限(%)</label>
              <input id="prod-maxRate" class="form-input" v-model="productDialog.form.defaultMaxRate" type="number" step="0.01" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-minTerm">默认最短期限(月)</label>
              <input id="prod-minTerm" class="form-input" v-model="productDialog.form.defaultMinTermMonths" type="number" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-maxTerm">默认最长期限(月)</label>
              <input id="prod-maxTerm" class="form-input" v-model="productDialog.form.defaultMaxTermMonths" type="number" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-effDate">生效日</label>
              <input id="prod-effDate" class="form-input" v-model="productDialog.form.effectiveDate" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="prod-remark">备注</label>
              <input id="prod-remark" class="form-input" v-model="productDialog.form.remark" autocomplete="off" />
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

    <!-- 新增/编辑指标字典弹窗(§9;编码一经创建不可改) -->
    <div class="modal" v-if="metricDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">{{ metricDialog.id ? '编辑指标' : '新增指标' }}</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="mt-code">指标编码 <span class="req">*</span></label>
              <input id="mt-code" class="form-input" v-model="metricDialog.form.metricCode" :disabled="!!metricDialog.id" placeholder="如 PUBLIC_DEPOSIT_AVG" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mt-name">指标名称 <span class="req">*</span></label>
              <input id="mt-name" class="form-input" v-model="metricDialog.form.metricName" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mt-valueType">值类型 <span class="req">*</span></label>
              <select id="mt-valueType" class="form-select" v-model="metricDialog.form.valueType">
                <option value="CONTRIBUTION_AMOUNT">折算</option>
                <option value="AVG_BALANCE">业务余额</option>
                <option value="INCOME">收入</option>
                <option value="RATIO">派生比值</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mt-scope">适用范围</label>
              <select id="mt-scope" class="form-select" v-model="metricDialog.form.metricScope">
                <option value="">不限</option>
                <option value="PUBLIC">对公</option>
                <option value="PRIVATE_SELF">本人对私</option>
                <option value="RELATED">关联人</option>
                <option value="GROUP">集团</option>
                <option value="GROUP_MEMBER">集团成员</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mt-unit">单位</label>
              <input id="mt-unit" class="form-input" v-model="metricDialog.form.unit" placeholder="万元/户/%" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mt-calcVer">折算版本</label>
              <input id="mt-calcVer" class="form-input" v-model="metricDialog.form.currentCalcVersion" placeholder="V1.0" autocomplete="off" />
            </div>
          </div>
          <div class="section-tip">校验:指标编码全局唯一且一经创建不可改(防历史承诺跟踪错位);停用后新承诺/新策略不可选,历史跟踪不受影响。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="metricDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveMetric">保存</button>
        </div>
      </div>
    </div>

    <!-- 新增产品链路草稿弹窗(§8A.5②;人性化:字段说明/上会条件可视化/保存前预览) -->
    <div class="modal" v-if="routeDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">新增产品审批链路</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="rt-product">产品 <span class="req">*</span></label>
              <select id="rt-product" class="form-select" v-model="routeDialog.form.productCode" @change="onRouteProductChange">
                <option value="" disabled>选择产品</option>
                <option v-for="p in enabledProducts" :key="p.productCode" :value="p.productCode">{{ p.productCode }} · {{ p.productName }}</option>
              </select>
              <div class="form-hint">选择后自动带出业务大类，并按默认规则预填路由模式</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-biztype">业务大类</label>
              <!-- 只读展示:随产品选择自动带出,改用 form-static 替代灰 input -->
              <div class="form-static" id="rt-biztype">{{ routeDialog.form.businessBigTypeText || '—' }}</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-routeMode">路由模式 <span class="req">*</span></label>
              <select id="rt-routeMode" class="form-select" v-model="routeDialog.form.routeMode">
                <option value="CHAINED">链式逐级（推荐·贷款）</option>
                <option value="DIRECT_VOTE">直接上会（推荐·存款/保证金）</option>
              </select>
              <div class="form-hint">链式逐级=按金额/利率逐级上送，权限内岗位即可终审；直接上会=必经支行行长后直接进入六人小组表决</div>
            </div>
            <div class="form-field" v-if="routeDialog.form.routeMode === 'CHAINED'">
              <label class="form-field__label" for="rt-startNode">起始节点 <span class="req">*</span></label>
              <select id="rt-startNode" class="form-select" v-model="routeDialog.form.startNodeCode">
                <option v-for="n in NODE_OPTIONS" :key="n" :value="n">{{ nodeLabel(n) }}</option>
              </select>
              <div class="form-hint">审批起点岗位，默认支行行长（所有申请必经支行行长）</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-mandatoryVote">强制上会（六人小组）</label>
              <select id="rt-mandatoryVote" class="form-select" v-model="routeDialog.form.mandatoryVote">
                <option value="N">否（按金额/利率匹配）</option>
                <option value="Y">是（一律上会表决）</option>
              </select>
              <div class="form-hint">开启后无论金额/利率是否在权限内，一律必经六人小组表决（≥4票），适合大额/高风险业务</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-president">必经总行行长决策</label>
              <select id="rt-president" class="form-select" v-model="routeDialog.form.presidentDecision">
                <option value="N">否</option>
                <option value="Y">是</option>
              </select>
              <div class="form-hint">凡上会申请必经行长决策（全局强制）；此开关用于给权限内终审业务额外增加行长环节</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-priority">优先级（低值优先）</label>
              <input id="rt-priority" class="form-input" v-model="routeDialog.form.priority" type="number" autocomplete="off" />
              <div class="form-hint">多条生效链路并存时数值小者优先，一般填 0</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-effDate">生效日</label>
              <input id="rt-effDate" class="form-input" v-model="routeDialog.form.effectiveDate" type="datetime-local" />
              <div class="form-hint">链路生效时间，不填则保存后立即生效</div>
            </div>
            <div class="form-field" style="grid-column: span 2">
              <label class="form-field__label" for="rt-remark">备注</label>
              <input id="rt-remark" class="form-input" v-model="routeDialog.form.remark" autocomplete="off" />
            </div>
          </div>

          <div class="form-group-title">上会条件(选填)</div>
          <div class="form-hint" style="margin:-6px 0 10px">满足条件时该申请必经六人小组表决</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="rt-amountTier">定档金额档</label>
              <select id="rt-amountTier" class="form-select" v-model="routeDialog.form.voteAmountTier" @change="syncVoteCondition">
                <option value="">不限</option>
                <option value="LT_1000">&lt;1000万</option>
                <option value="GE_1000_LT_5000">1000万(含)-5000万</option>
                <option value="GE_5000">≥5000万</option>
              </select>
              <div class="form-hint">申请定档金额所在档位（集团按集团总授信，其他按总授信额度）；大额业务一般需上会</div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rt-enterpriseType">企业类型</label>
              <select id="rt-enterpriseType" class="form-select" v-model="routeDialog.form.voteEnterpriseType" @change="syncVoteCondition">
                <option value="">不限</option>
                <option value="SOE">国企</option>
                <option value="NON_SOE">非国企</option>
              </select>
              <div class="form-hint">仅该企业类型触发上会，其余按权限内终审</div>
            </div>
          </div>
          <div class="form-field" style="margin-top:10px">
            <label class="form-field__label" for="rt-condPreview">当前条件（中文预览）</label>
            <div class="section-tip" id="rt-condPreview">{{ voteConditionText(routeDialog.form.voteCondition) }}</div>
          </div>
          <!-- §UI审查:底层 JSON 折叠为高级区域,默认收起,降低非技术管理员突兀感 -->
          <details class="advanced-json">
            <summary>高级:底层 JSON(无需手改,构建器自动生成)</summary>
            <div class="form-field" style="margin-top:10px">
              <label class="form-field__label" for="rt-json">底层 JSON</label>
              <textarea id="rt-json" class="form-input" v-model="routeDialog.form.voteCondition" rows="2" placeholder='{"amount_tier":"GE_5000","enterprise_type":"SOE"}'></textarea>
            </div>
            <div style="display:flex;justify-content:flex-end;margin-top:6px">
              <button class="btn btn--secondary" @click="parseVoteCondition">从 JSON 回填构建器</button>
            </div>
          </details>
          <div class="section-tip" style="margin-top:8px">未配置链路时自动按默认链路运行：对公贷款=链式逐级、存款/保证金=直接上会，无需强制配置。路由引擎读取 vote_condition：金额档按集团批复总额度/申请金额定档，企业类型取 SOE/NON_SOE；JSON 解析失败按不命中处理。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="routeDialog.show = false">取消</button>
          <button class="btn btn--secondary" @click="openRoutePreview">预览实际链路</button>
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
            <label class="form-field__label" for="rt-reject">驳回意见 <span class="req">*</span></label>
            <textarea id="rt-reject" class="form-input" v-model="routeRejectDialog.opinion" rows="4" placeholder="请填写驳回原因"></textarea>
            <div class="form-hint">驳回原因将写入配置变更日志</div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" :disabled="!!pending" @click="routeRejectDialog.show = false">取消</button>
          <button class="btn btn--primary" :disabled="!!pending" @click="doRouteReject">{{ pendingText(`routereject:${routeRejectDialog.id}`, '确认驳回') }}</button>
        </div>
      </div>
    </div>

    <!-- 产品链路模拟路由弹窗(§7.2 矩阵路由,消耗产品链路配置) -->
    <div class="modal" v-if="routeSimDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">模拟路由:{{ routeSimDialog.productCode }}</div>
        <div class="modal__body">
          <div class="section-tip" style="margin-bottom:10px">预览基于当前已生效链路；产品未配置链路时按默认链路运行（对公贷款=链式逐级、存款/保证金=直接上会）。填好金额与利率后点「试算」查看实际审批走向。</div>
          <div class="trial-form">
            <div class="form-field">
              <label class="form-field__label" for="rsim-new">存量/新增 <span class="req">*</span></label>
              <select id="rsim-new" class="form-select" v-model="routeSimDialog.newOrExisting">
                <option value="NEW">新增授信</option>
                <option value="EXISTING">存量授信</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rsim-custype">客户类型</label>
              <select id="rsim-custype" class="form-select" v-model="routeSimDialog.customerType">
                <option value="">通配</option>
                <option value="SOE">国企</option>
                <option value="NON_SOE">非国企</option>
                <option value="PERSONAL">个人</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rsim-guarantee">担保主类型</label>
              <select id="rsim-guarantee" class="form-select" v-model="routeSimDialog.guaranteeType">
                <option value="">通配</option>
                <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rsim-amount">申请金额(万元) <span class="req">*</span></label>
              <input id="rsim-amount" class="form-input" v-model="routeSimDialog.amount" type="number" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rsim-term">期限</label>
              <div style="display:flex;gap:4px">
                <input id="rsim-term" class="form-input" v-model="routeSimDialog.termValue" type="number" style="width:90px" autocomplete="off" />
                <select class="form-select" v-model="routeSimDialog.termUnit" style="width:90px" aria-label="期限单位">
                  <option value="YEAR">年</option>
                  <option value="MONTH">月</option>
                  <option value="DAY">日</option>
                </select>
              </div>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="rsim-rate">申请利率(%) <span class="req">*</span></label>
              <input id="rsim-rate" class="form-input" v-model="routeSimDialog.requestedRate" type="number" step="0.01" autocomplete="off" />
            </div>
            <div class="form-field" v-if="routeSimDialog.newOrExisting === 'EXISTING'">
              <label class="form-field__label" for="rsim-origrate">原执行利率(%)</label>
              <input id="rsim-origrate" class="form-input" v-model="routeSimDialog.originalRate" type="number" step="0.01" autocomplete="off" />
            </div>
            <div class="form-field" style="justify-content:flex-end">
              <button class="btn btn--primary" :disabled="routeSimDialog.loading" @click="runRouteSimulate">试算</button>
            </div>
          </div>

          <div v-if="routeSimResult" class="trial-result">
            <div class="desc-grid desc-grid--3">
              <div>
                <div class="desc-item__label">审批链首节点</div>
                <div class="desc-item__value">{{ nodeLabel(routeSimResult.startNodeCode) }} <span class="badge badge--info">必经</span></div>
              </div>
              <div>
                <div class="desc-item__label">终审岗位</div>
                <div class="desc-item__value">{{ nodeLabel(routeSimResult.finalNodeCode) }}</div>
              </div>
              <div>
                <div class="desc-item__label">利率方向</div>
                <div class="desc-item__value">{{ rateDirectionText(routeSimResult.rateDirection) }}</div>
              </div>
              <div>
                <div class="desc-item__label">终审边界</div>
                <div class="desc-item__value desc-item__value--num">{{ routeSimResult.boundaryRate != null ? routeSimResult.boundaryRate + '%' : '—' }}</div>
              </div>
              <div>
                <div class="desc-item__label">命中规则</div>
                <div class="desc-item__value">{{ routeSimResult.matchedRuleName || routeSimResult.matchedRuleCode || '—' }}</div>
              </div>
              <div class="desc-item--full">
                <div class="desc-item__label">审批链路</div>
                <div class="flow-steps">
                  <template v-for="(n, i) in routeSimResult.routeChain || []" :key="n">
                    <span class="flow-node">{{ nodeLabel(n) }}</span>
                    <span v-if="i < (routeSimResult.routeChain || []).length - 1" class="chain__arrow">→</span>
                  </template>
                </div>
              </div>
              <div class="desc-item--full">
                <div class="desc-item__label">计算说明</div>
                <div class="desc-item__value" style="font-weight:400">{{ routeSimResult.message || '—' }}</div>
              </div>
            </div>
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
            <label class="form-field__label" for="lpr-verCode">版本号 <span class="req">*</span></label>
            <input id="lpr-verCode" class="form-input" v-model="lprDialog.form.versionCode" placeholder="如 LPR-2026-08" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="lpr-1y">一年期 LPR(%) <span class="req">*</span></label>
            <input id="lpr-1y" class="form-input" v-model="lprDialog.form.lpr1y" type="number" step="0.05" min="0.5" max="8" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="lpr-5y">五年期以上 LPR(%) <span class="req">*</span></label>
            <input id="lpr-5y" class="form-input" v-model="lprDialog.form.lpr5y" type="number" step="0.05" min="0.5" max="8" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="lpr-effFrom">生效时间 <span class="req">*</span></label>
            <input id="lpr-effFrom" class="form-input" v-model="lprDialog.form.effectiveFrom" type="datetime-local" />
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
          <table class="table table--full">
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
                <td colspan="7" class="empty-cell">暂无启用产品,请先在产品目录新增并发布启用产品</td>
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
          <div class="form-group-title">匹配维度</div>
          <div class="form-grid">
            <div class="form-field" style="grid-column: span 2">
              <label class="form-field__label" for="mx-no">行编码 <span class="req">*</span></label>
              <input id="mx-no" class="form-input" v-model="matrixDialog.form.matrixNo" placeholder="如 MX-LOAN-NEW-001" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-biztype">业务大类 <span class="req">*</span></label>
              <select id="mx-biztype" class="form-select" v-model="matrixDialog.form.businessBigType">
                <option value="LOAN_PUBLIC">对公贷款</option>
                <option value="LOAN_PERSONAL">个人贷款</option>
                <option value="DEPOSIT">存款</option>
                <option value="MARGIN">保证金</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-new">存量/新增 <span class="req">*</span></label>
              <select id="mx-new" class="form-select" v-model="matrixDialog.form.newOrExisting">
                <option value="NEW">新增授信</option>
                <option value="EXISTING">存量授信</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-custype">客户类型</label>
              <select id="mx-custype" class="form-select" v-model="matrixDialog.form.customerType">
                <option value="">通配</option>
                <option value="SOE">国企</option>
                <option value="NON_SOE">非国企</option>
                <option value="PERSONAL">个人</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-amountTier">金额档</label>
              <select id="mx-amountTier" class="form-select" v-model="matrixDialog.form.amountTier">
                <option value="">通配</option>
                <option value="LT_1000">1000万以下</option>
                <option value="GE_1000_LT_5000">1000万(含)-5000万</option>
                <option value="GE_5000">5000万及以上</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-termTier">期限档</label>
              <select id="mx-termTier" class="form-select" v-model="matrixDialog.form.termTier">
                <option value="">通配</option>
                <option value="1Y">1年</option>
                <option value="3Y">3年</option>
                <option value="5Y">5年</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-guarantee">担保主类型</label>
              <select id="mx-guarantee" class="form-select" v-model="matrixDialog.form.guaranteeType">
                <option value="">通配</option>
                <option v-for="t in GUARANTEE_TYPES" :key="t.code" :value="t.code">{{ t.name }}</option>
              </select>
            </div>
          </div>

          <div class="form-group-title">终审与边界</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="mx-finalNode">终审岗位 <span class="req">*</span></label>
              <select id="mx-finalNode" class="form-select" v-model="matrixDialog.form.startNodeCode">
                <option value="BRANCH_MANAGER">支行行长</option>
                <option value="DEPT_GENERAL_MANAGER">部门总经理</option>
                <option value="VICE_PRESIDENT">分管行长</option>
                <option value="SIX_PEOPLE_GROUP">六人小组</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-priority">优先级(低值优先)</label>
              <input id="mx-priority" class="form-input" v-model="matrixDialog.form.priority" type="number" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-boundaryType">边界类型</label>
              <select id="mx-boundaryType" class="form-select" v-model="matrixDialog.form.boundaryType">
                <option value="RATE">直接利率</option>
                <option value="SPREAD">存量降幅</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-minRate">绝对利率下限(%)</label>
              <input id="mx-minRate" class="form-input" v-model="matrixDialog.form.boundaryMinRate" type="number" step="0.01" autocomplete="off" />
            </div>
            <div class="form-field" style="grid-column: span 2">
              <label class="form-field__label" for="mx-bp">BP 边界(按 LPR 换算)</label>
              <div style="display:flex;gap:6px">
                <select class="form-select" v-model="matrixDialog.form.bpSign" style="width:70px" aria-label="BP 正负号">
                  <option value="+">+</option>
                  <option value="-">-</option>
                </select>
                <input id="mx-bp" class="form-input" v-model="matrixDialog.form.boundaryBp" type="number" placeholder="BP 值" autocomplete="off" />
                <select class="form-select" v-model="matrixDialog.form.lprTerm" style="width:110px" aria-label="LPR 期限">
                  <option value="1Y">1Y LPR</option>
                  <option value="5Y+">5Y+ LPR</option>
                </select>
              </div>
            </div>
          </div>

          <div class="form-group-title">生效与备注</div>
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label" for="mx-effFrom">生效时间 <span class="req">*</span></label>
              <input id="mx-effFrom" class="form-input" v-model="matrixDialog.form.effectiveFrom" type="datetime-local" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="mx-remark">备注</label>
              <input id="mx-remark" class="form-input" v-model="matrixDialog.form.remark" autocomplete="off" />
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
            <label class="form-field__label" for="rs-code">规则集编码 <span class="req">*</span></label>
            <input id="rs-code" class="form-input" v-model="setDialog.form.setCode" placeholder="如 RS-2026-08" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="rs-name">规则集名称 <span class="req">*</span></label>
            <input id="rs-name" class="form-input" v-model="setDialog.form.setName" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="rs-effFrom">生效时间</label>
            <input id="rs-effFrom" class="form-input" v-model="setDialog.form.effectiveFrom" type="datetime-local" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="rs-remark">备注</label>
            <input id="rs-remark" class="form-input" v-model="setDialog.form.remark" autocomplete="off" />
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
            <label class="form-field__label" for="pl-code">产品编码 <span class="req">*</span></label>
            <input id="pl-code" class="form-input" v-model="limitDialog.form.productCode" placeholder="如 LOAN-FLOW-001" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="pl-name">产品名称</label>
            <input id="pl-name" class="form-input" v-model="limitDialog.form.productName" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="pl-bizType">业务类型 <span class="req">*</span></label>
            <select id="pl-bizType" class="form-select" v-model="limitDialog.form.businessType">
              <option value="LOAN">贷款(全行不可低于硬边界)</option>
              <option value="DEPOSIT">存款(全行不可高于硬边界)</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label" for="pl-rate">硬边界利率(%) <span class="req">*</span></label>
            <input id="pl-rate" class="form-input" v-model="limitDialog.form.hardBoundaryRate" type="number" step="0.01" autocomplete="off" />
          </div>
          <div class="form-field">
            <label class="form-field__label" for="pl-effFrom">生效时间 <span class="req">*</span></label>
            <input id="pl-effFrom" class="form-input" v-model="limitDialog.form.effectiveFrom" type="datetime-local" />
          </div>
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
            <label class="form-field__label" for="pl-reject">驳回意见 <span class="req">*</span></label>
            <textarea id="pl-reject" class="form-input" v-model="rejectDialog.opinion" rows="4" placeholder="请填写驳回原因"></textarea>
            <div class="form-hint">驳回原因将写入配置变更日志</div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" :disabled="!!pending" @click="rejectDialog.show = false">取消</button>
          <button class="btn btn--primary" :disabled="!!pending" @click="doReject">{{ pendingText(`reject:${rejectDialog.id}`, '确认驳回') }}</button>
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
  listMetricDefinitions, createMetricDefinition, updateMetricDefinition, changeMetricStatus,
  type ConfigChangeLog
} from '@/api/system'
import { GUARANTEE_TYPES, guaranteeTypeText, metricName } from '@/utils/dict'
import { useMetricDict } from '@/store/metricDict'
import {
  configStatusText, configActionText, configTypeText, businessBigTypeText,
  nodeLabel, customerTypeText, amountTierText, termTierText, rateDirectionText,
  businessTypeText, productName, currencyText
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
  { key: 'metricDict', label: '指标字典' },
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
// §UI审查:各 tab 表格加载态
const loading = reactive({ lpr: false, matrix: false, product: false, ruleset: false, changelog: false, catalog: false, route: false, policy: false, metric: false })
// §UI审查:行内操作按钮 pendingId 防连点
const pending = ref('')
function pendingText(key: string, normal: string) {
  return pending.value === key ? '处理中…' : normal
}
async function withPending<T>(key: string, fn: () => Promise<T>): Promise<T | undefined> {
  if (pending.value) return
  pending.value = key
  try {
    return await fn()
  } finally {
    pending.value = ''
  }
}

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
  loading.lpr = true
  try {
    lprList.value = await listLpr(lprStatus.value || undefined)
  } catch {
    lprList.value = []
  } finally {
    loading.lpr = false
  }
}
async function loadMatrix() {
  loading.matrix = true
  try {
    matrixList.value = await listMatrix(matrixStatus.value || undefined)
  } catch {
    matrixList.value = []
  } finally {
    loading.matrix = false
  }
}
async function loadRuleSets() {
  loading.ruleset = true
  try {
    ruleSets.value = await listRuleSets()
  } catch {
    ruleSets.value = []
  } finally {
    loading.ruleset = false
  }
}
async function loadProductLimit() {
  loading.product = true
  try {
    productList.value = await listProductLimit(productStatus.value || undefined)
  } catch {
    productList.value = []
  } finally {
    loading.product = false
  }
}
const logQuery = reactive({ configType: '', configId: '' as number | '' })
async function loadChangeLogs() {
  loading.changelog = true
  try {
    changeLogs.value = await listChangeLogs(logQuery.configType || undefined, logQuery.configId || undefined)
  } catch {
    changeLogs.value = []
  } finally {
    loading.changelog = false
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
  await withPending(`submit:${kind}:${id}`, async () => {
    await apiOf(kind).submit(id)
    ElMessage.success('已送审,待复核发布')
    apiOf(kind).reload()
  })
}
async function doPublish(kind: Kind, id: number) {
  await withPending(`publish:${kind}:${id}`, async () => {
    await ElMessageBox.confirm(
      '发布强制双人复核:发布人不得与创建人为同一人;发布后同维度旧生效版本自动停用。确认复核发布?',
      '复核发布确认',
      { type: 'warning' }
    )
    await apiOf(kind).publish(id)
    ElMessage.success('已发布生效')
    apiOf(kind).reload()
  })
}
async function doDisable(kind: Kind, id: number) {
  await withPending(`disable:${kind}:${id}`, async () => {
    await ElMessageBox.confirm('确认停用该生效版本?', '停用确认', { type: 'warning' })
    await apiOf(kind).disable(id)
    ElMessage.success('已停用')
    apiOf(kind).reload()
  })
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
  await withPending(`reject:${rejectDialog.id}`, async () => {
    await rejectProductLimit(rejectDialog.id, rejectDialog.opinion.trim())
    rejectDialog.show = false
    ElMessage.success('已驳回,退回草稿')
    loadProductLimit()
  })
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
  loading.catalog = true
  try {
    productCatalog.value = await listProductCatalog(productQuery.businessBigType || undefined, productQuery.status || undefined)
  } catch {
    productCatalog.value = []
  } finally {
    loading.catalog = false
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
  await withPending(`prodstatus:${p.id}`, async () => {
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
  })
}
async function doProductDelete(p: any) {
  await withPending(`proddelete:${p.id}`, async () => {
    await ElMessageBox.confirm(`确认删除产品 ${p.productCode}?未被申请/矩阵/LPR/边界引用时允许删除,否则仅可停用。`, '删除确认', { type: 'warning' })
    await deleteProduct(p.id)
    ElMessage.success('已删除')
    loadProductCatalog()
  })
}

// 指标字典管理(§9:数仓按 ccr_metric_definition 字典推送指标数据,admin 前台配置化)
const metricQuery = reactive({ status: '', keyword: '' })
const metricDefs = ref<any[]>([])
async function loadMetricDefs() {
  loading.metric = true
  try {
    metricDefs.value = await listMetricDefinitions(metricQuery.status || undefined, metricQuery.keyword?.trim() || undefined)
  } catch {
    metricDefs.value = []
  } finally {
    loading.metric = false
  }
}
function metricStatusText(s: string) {
  return { ACTIVE: '启用', DISABLED: '停用' }[s] || s || '—'
}
function metricStatusBadge(s: string) {
  return `badge ${s === 'ACTIVE' ? 'badge--success' : 'badge--neutral'}`
}
function metricValueTypeText(s?: string) {
  return { AVG_BALANCE: '业务余额', INCOME: '收入', CONTRIBUTION_AMOUNT: '折算', RATIO: '派生比值' }[s || ''] || s || '—'
}
function metricScopeText(s: string) {
  return { PUBLIC: '对公', PRIVATE_SELF: '本人对私', RELATED: '关联人', GROUP: '集团', GROUP_MEMBER: '集团成员' }[s] || s || '—'
}
const metricDialog = reactive({ show: false, id: 0, form: {} as any })
function openMetricCreate() {
  metricDialog.id = 0
  metricDialog.form = {
    metricCode: '', metricName: '', valueType: 'CONTRIBUTION_AMOUNT', metricScope: '',
    unit: '万元', currentCalcVersion: 'V1.0'
  }
  metricDialog.show = true
}
function openMetricEdit(m: any) {
  metricDialog.id = m.id
  metricDialog.form = { ...m }
  metricDialog.show = true
}
async function saveMetric() {
  const f = metricDialog.form
  if (!f.metricCode?.trim() || !f.metricName?.trim() || !f.valueType) {
    ElMessage.warning('指标编码/指标名称/值类型必填')
    return
  }
  const payload: any = {
    ...f,
    metricScope: f.metricScope?.trim() || null,
    unit: f.unit?.trim() || null,
    currentCalcVersion: f.currentCalcVersion?.trim() || 'V1.0'
  }
  if (metricDialog.id) {
    await updateMetricDefinition(metricDialog.id, payload)
  } else {
    await createMetricDefinition(payload)
  }
  metricDialog.show = false
  ElMessage.success(metricDialog.id ? '指标已更新' : '指标已创建并启用')
  loadMetricDefs()
  useMetricDict().reload()
}
async function doMetricStatus(m: any, status: string) {
  await withPending(`metricstatus:${m.id}`, async () => {
    await ElMessageBox.confirm(
      status === 'DISABLED'
        ? `停用后新承诺/新策略不可选,历史承诺跟踪不受影响。确认停用 ${m.metricName}?`
        : `确认启用 ${m.metricName}?`,
      status === 'DISABLED' ? '停用确认' : '启用确认', { type: 'warning' }
    )
    await changeMetricStatus(m.id, status)
    ElMessage.success(status === 'DISABLED' ? '已停用' : '已启用')
    loadMetricDefs()
    useMetricDict().reload()
  })
}

// 产品审批链路
const routeQuery = reactive({ productCode: '', status: '' })
const productRoutes = ref<any[]>([])
async function loadProductRoutes() {
  loading.route = true
  try {
    productRoutes.value = await listProductRoutes(routeQuery.productCode || undefined, routeQuery.status || undefined)
  } catch {
    productRoutes.value = []
  } finally {
    loading.route = false
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
  // 需求四:按业务大类自动预填默认——贷款=链式逐级、起始支行行长;存款/保证金=直接上会
  const isDeposit = p?.businessBigType === 'DEPOSIT'
  routeDialog.form.routeMode = isDeposit ? 'DIRECT_VOTE' : 'CHAINED'
  routeDialog.form.startNodeCode = isDeposit ? '' : 'BRANCH_MANAGER'
  routeDialog.form.mandatoryVote = 'N'
  routeDialog.form.presidentDecision = 'N'
}
function syncVoteCondition() {
  const f = routeDialog.form
  const cond: any = {}
  if (f.voteAmountTier) cond.amount_tier = f.voteAmountTier
  if (f.voteEnterpriseType) cond.enterprise_type = f.voteEnterpriseType
  f.voteCondition = Object.keys(cond).length ? JSON.stringify(cond) : ''
}
// 需求四:保存前预览实际链路——按当前表单产品打开模拟路由,展示生效/默认链路的实际走向
function openRoutePreview() {
  const f = routeDialog.form
  if (!f.productCode) {
    ElMessage.warning('请先选择产品')
    return
  }
  routeSimDialog.productCode = f.productCode
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
  await withPending(`routesubmit:${r.id}`, async () => {
    await submitProductRoute(r.id)
    ElMessage.success('已送审,待复核发布')
    loadProductRoutes()
  })
}
async function doRoutePublish(r: any) {
  await withPending(`routepublish:${r.id}`, async () => {
    await ElMessageBox.confirm('发布强制双人复核:发布人不得与创建人为同一人;同产品同生效日旧生效链路自动停用。确认复核发布?', '复核发布确认', { type: 'warning' })
    await publishProductRoute(r.id)
    ElMessage.success('已发布生效')
    loadProductRoutes()
  })
}
async function doRouteDisable(r: any) {
  await withPending(`routedisable:${r.id}`, async () => {
    await ElMessageBox.confirm('确认停用该生效链路?', '停用确认', { type: 'warning' })
    await disableProductRoute(r.id)
    ElMessage.success('已停用')
    loadProductRoutes()
  })
}
async function doRouteDelete(r: any) {
  await withPending(`routedelete:${r.id}`, async () => {
    await ElMessageBox.confirm(`确认删除链路 ${r.productCode} ${fmtTime(r.effectiveDate)}?`, '删除确认', { type: 'warning' })
    await deleteProductRoute(r.id)
    ElMessage.success('已删除')
    loadProductRoutes()
  })
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
  await withPending(`routereject:${routeRejectDialog.id}`, async () => {
    await rejectProductRoute(routeRejectDialog.id, routeRejectDialog.opinion.trim())
    routeRejectDialog.show = false
    ElMessage.success('已驳回,退回草稿')
    loadProductRoutes()
  })
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
// 贡献度指标字典(§9;ccr_metric_definition 权威来源,store 拉取,失败回退静态)
const metricDict = computed(() => useMetricDict().list)
async function loadPolicies() {
  loading.policy = true
  try {
    policyList.value = await listTrackingPolicies(policyMetric.value || undefined)
  } catch {
    policyList.value = []
  } finally {
    loading.policy = false
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
  await withPending(`policystatus:${p.id}`, async () => {
    if (status === 'EFFECTIVE') {
      await ElMessageBox.confirm('复核发布后该策略生效,同维度旧策略将被替换。确认?', '复核发布确认', { type: 'warning' })
    } else if (status === 'INVALID') {
      await ElMessageBox.confirm('确认停用该生效策略?', '停用确认', { type: 'warning' })
    }
    await changePolicyStatus(p.id, status)
    ElMessage.success(status === 'EFFECTIVE' ? '已发布生效' : status === 'INVALID' ? '已停用' : '已送审,待复核发布')
    loadPolicies()
  })
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
  await withPending(`versionstatus:${v.id}`, async () => {
    if (status === 'EFFECTIVE') {
      await ElMessageBox.confirm('版本生效区间与其他生效版本不得重叠,后端将强校验。确认复核发布?', '复核发布确认', { type: 'warning' })
    } else if (status === 'INVALID') {
      await ElMessageBox.confirm('确认停用该版本?', '停用确认', { type: 'warning' })
    }
    await changeVersionStatus(v.id, status)
    versionMgr.versions = await listPolicyVersions(versionMgr.policy.id)
    ElMessage.success(status === 'EFFECTIVE' ? '版本已生效' : status === 'INVALID' ? '版本已停用' : '已送审,待复核发布')
  })
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
  useMetricDict().load()
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
  loadMetricDefs()
})
</script>

<style scoped>
.trial-form { display: flex; flex-wrap: wrap; gap: 12px 20px; align-items: flex-end; }
.trial-form .form-field { min-width: 160px; }
.trial-result { margin-top: 16px; border-top: 1px dashed var(--color-border); padding-top: 12px; }
/* 试算查询行:条件 + 按钮 + 说明 */
.simulate-bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
/* 描述列表中占满整行的项(审批链路/计算说明) */
.desc-item--full { grid-column: 1 / -1; }
.chain__arrow { color: var(--color-text-light); align-self: center; }
.modal__card--wide { width: 720px; max-width: 92vw; } /* §UI审查:弹窗宽度与 user.vue 统一为 720px */
.json-compare { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.json-compare__title { font-weight: 600; margin-bottom: 6px; }
.json-view { background: var(--color-bg, #f8fafc); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 10px; font-size: 12px; max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-all; margin: 0; }

.matrix-dialog__body { max-height: 68vh; overflow-y: auto; padding-right: 6px; }
.modal__card { max-width: 720px; width: 92vw; }
/* §UI审查:宽表横向滚动 + 关键列不换行/省略 */
.table--wide { min-width: 100%; }
.table--wide th, .table--wide td { white-space: nowrap; }
.table--wide td.col-ellipsis { max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
/* §UI审查:高级 JSON 折叠区 */
.advanced-json { margin-top: 10px; padding: 8px 10px; border: 1px dashed var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg, #f8fafc); }
.advanced-json summary { cursor: pointer; font-size: 13px; color: var(--color-text-sub); user-select: none; }
.advanced-json summary:hover { color: var(--color-primary); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
/* 768px 断点:双列表单/新旧值对比/试算表收为单列 */
@media (max-width: 768px) {
  .form-grid { grid-template-columns: 1fr; }
  .form-grid .form-field { grid-column: auto !important; }
  .json-compare { grid-template-columns: 1fr; }
  .trial-form .form-field { min-width: 0; flex: 1 1 100%; }
}
</style>
