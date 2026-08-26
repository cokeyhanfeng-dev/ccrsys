import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/request'
import { ACTIVE_METRIC_CODES, registerMetricDict } from '@/utils/dict'

export interface MetricDefinition {
  metricCode: string
  metricName: string
  valueType: string
  metricScope: string | null
  unit: string
  currentCalcVersion: string
}

// 指标字典:承诺/跟踪策略指标下拉的权威来源(ccr_metric_definition)
// load() 拉取启用指标并注册给 dict.ts(metricName 展示优先字典);
// 接口未加载/失败时回退静态 METRIC_CODES,行为与改造前一致;幂等(loaded 防重复拉取)。
export const useMetricDict = defineStore('metricDict', () => {
  const list = ref(ACTIVE_METRIC_CODES)
  const loaded = ref(false)

  async function load() {
    if (loaded.value) return
    try {
      const data = await get<MetricDefinition[]>('/ccr/metric-definitions/enabled')
      if (data?.length) {
        list.value = data.map((m) => ({ code: m.metricCode, name: m.metricName }))
        registerMetricDict(list.value)
        loaded.value = true
      }
    } catch {
      // 拦截器已提示;保持静态回退
    }
  }

  // 管理页(指标字典 tab)增改停用后强制刷新下拉,忽略幂等
  async function reload() {
    loaded.value = false
    await load()
  }

  return { list, loaded, load, reload }
})
