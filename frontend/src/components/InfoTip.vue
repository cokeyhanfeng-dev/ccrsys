<template>
  <el-tooltip
    effect="dark"
    :placement="placement"
    :show-after="80"
    popper-class="info-tip__popper"
  >
    <template #content><slot>{{ content }}</slot></template>
    <span class="info-tip" tabindex="0" role="img" :aria-label="ariaLabel">
      <el-icon :size="14"><WarningFilled /></el-icon>
    </span>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'

defineOptions({ name: 'InfoTip' })

const props = defineProps({
  /** 说明文案;多行或带格式内容用默认插槽传入 */
  content: { type: String, default: '' },
  placement: { type: String, default: 'top' },
})

const ariaLabel = computed(() => (props.content ? `说明:${props.content}` : '查看说明'))
</script>
