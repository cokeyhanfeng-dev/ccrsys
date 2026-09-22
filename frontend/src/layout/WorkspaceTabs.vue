<template>
  <div class="workspace-header">
    <div class="workspace-header__top">
      <slot name="breadcrumb" />
      <slot name="actions" />
    </div>
    <div class="workspace-tabs" aria-label="已打开页面">
      <div class="workspace-tabs__list" role="tablist">
        <div v-for="tab in tabs" :key="tab.key" class="workspace-tab"
          :class="{ 'is-active': activeKey === tab.key }" @contextmenu.prevent="openMenu($event, tab.key)">
          <button type="button" role="tab" :aria-selected="activeKey === tab.key"
            :title="tab.title + ' · 右键可刷新或批量关闭'" @keydown="openKeyboardMenu($event, tab.key)" @click="router.push(tab.key)"><span class="workspace-tab__label">{{ tab.title }}</span></button>
          <button v-if="!tab.pinned" type="button" class="workspace-tab__close"
            :aria-label="'关闭' + tab.title" @click="runAction('close', tab.key)">×</button>
        </div>
      </div>
      <teleport to="body">
        <div v-if="contextMenu" class="workspace-context-cover" @click="contextMenu = null" @contextmenu.prevent="contextMenu = null">
          <div class="workspace-context-menu" role="menu" :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }" @click.stop>
            <button v-for="item in actions" :key="item.key" type="button" role="menuitem"
              :disabled="item.key !== 'refresh' && !canClose(contextMenu.key, item.key)"
              @click="runAction(item.key, contextMenu.key)">{{ item.label }}</button>
          </div>
        </div>
      </teleport>
    </div>
  </div>
  <KeepAlive :include="cacheNames">
    <component :is="activeTab?.page" :key="activeTab?.page.name" />
  </KeepAlive>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createWorkspacePage } from '@/utils/workspace-page.mjs'
import { openWorkspaceTab, planTabClose } from '@/utils/workspace-tabs.mjs'
import { clearHistoryListOutside } from '@/utils/history-list-state.mjs'

const router = useRouter()
const route = useRoute()
const tabs = shallowRef<any[]>([])
const activeKey = ref('')
let pageSequence = 0
// 每个页面使用独立路由上下文，后台标签的 useRoute 不受当前标签参数影响。
function createPage(location: any) {
  return createWorkspacePage(location, 'WorkspacePage' + ++pageSequence)
}
const activeTab = computed(() => tabs.value.find(tab => tab.key === activeKey.value))
const cacheNames = computed(() => tabs.value.map(tab => tab.page.name))
const actions = [
  { key: 'refresh', label: '刷新' }, { key: 'left', label: '关闭左侧' },
  { key: 'right', label: '关闭右侧' }, { key: 'others', label: '关闭其他' }, { key: 'close', label: '关闭当前' }
]
const contextMenu = ref<{ key: string; x: number; y: number } | null>(null)
function openMenu(event: MouseEvent, key: string) {
  contextMenu.value = { key, x: Math.max(0, Math.min(event.clientX, window.innerWidth - 160)),
    y: Math.max(0, Math.min(event.clientY, window.innerHeight - 210)) }
}
function openKeyboardMenu(event: KeyboardEvent, key: string) {
  if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return
  event.preventDefault()
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  openMenu({ clientX: rect.left, clientY: rect.bottom } as MouseEvent, key)
}
function canClose(key: string, action: string) {
  return planTabClose(tabs.value, activeKey.value, key, action).tabs.length < tabs.value.length
}
watch(() => route.fullPath, async () => {
  if (activeTab.value) activeTab.value.scrollY = window.scrollY
  const next = [...tabs.value]
  if (!next.length) openWorkspaceTab(next, router.resolve('/overview'), createPage)
  openWorkspaceTab(next, router.currentRoute.value, createPage)
  tabs.value = next
  activeKey.value = route.fullPath
  contextMenu.value = null
  const key = activeKey.value
  await nextTick()
  if (key !== activeKey.value) return
  window.scrollTo(0, activeTab.value?.scrollY || 0)
  const active = document.querySelector<HTMLElement>('.workspace-tab.is-active')
  const list = active?.parentElement
  // 只滚动标签栏横轴，避免 scrollIntoView 把业务页面滚回顶部。
  if (active && list) {
    const left = active.offsetLeft - list.offsetLeft
    if (left < list.scrollLeft) list.scrollLeft = left
    else if (left + active.offsetWidth > list.scrollLeft + list.clientWidth) {
      list.scrollLeft = left + active.offsetWidth - list.clientWidth
    }
  }
}, { immediate: true })

async function runAction(action: string, key: string) {
  contextMenu.value = null
  const target = tabs.value.find(tab => tab.key === key)
  if (!target) return
  if (action === 'refresh') {
    if (target.path === '/history') clearHistoryListOutside('/overview')
    target.page = createPage(router.resolve(key))
    target.scrollY = 0
    tabs.value = [...tabs.value]
    if (key === activeKey.value) { await nextTick(); window.scrollTo(0, 0) }
    return
  }
  const plan = planTabClose(tabs.value, activeKey.value, key, action)
  // 先完成导航；路由守卫拒绝时保留原标签。
  if (plan.activeKey !== activeKey.value) {
    const failure = await router.push(plan.activeKey)
    if (failure) return
  }
  if (tabs.value.some(tab => tab.path === '/history' && !plan.tabs.includes(tab))) clearHistoryListOutside('/overview')
  tabs.value = plan.tabs
}
function dismissMenu(event: KeyboardEvent) { if (event.key === 'Escape') contextMenu.value = null }
onMounted(() => window.addEventListener('keydown', dismissMenu))
onUnmounted(() => window.removeEventListener('keydown', dismissMenu))
</script>

<style scoped>
.workspace-header { padding: 0 20px;
  margin: -16px -20px 20px; background: var(--color-surface); border-bottom: 1px solid var(--color-border-light);
  box-shadow: 0 1px 4px rgba(0, 21, 41, .06); position: sticky; top: 0; z-index: 9; }
.workspace-header__top { display: flex; align-items: center; gap: 20px; min-height: 52px; border-bottom: 1px solid var(--color-border-light); }
.workspace-tabs { min-width: 0; padding: 4px 0; }
@media (max-width: 767px) {
  .workspace-header { margin: -12px -16px 12px; gap: 10px; padding: 0 16px; }
}
.workspace-tabs__list { display: flex; align-items: center; gap: 4px; overflow-x: auto; padding: 4px 0; }
.workspace-tab { display: flex; flex: none; align-items: center; height: 30px;
  border: 1px solid var(--color-border-light); border-radius: 1px; background: #f7f9fc;
  color: var(--color-text-sub); font-size: 13px; }
.workspace-tab.is-active { border-color: #d5e3f7; background: #edf4ff; color: var(--color-primary); }
.workspace-tab button { display: inline-flex; align-items: center; background: transparent; border: 0;
  color: inherit; cursor: pointer; padding: 0 10px; height: 28px; font: inherit; white-space: nowrap; }
.workspace-tab button[role="tab"] { gap: 7px; max-width: 240px; }
.workspace-tab__label { overflow: hidden; text-overflow: ellipsis; }
.workspace-tab.is-active button[role="tab"]::before { content: ''; width: 7px; height: 7px;
  border-radius: 50%; background: currentColor; flex: none; }
.workspace-tab .workspace-tab__close { justify-content: center; width: 18px; height: 18px;
  padding: 0; margin-right: 5px; border-radius: 50%; font-size: 15px; line-height: 1; opacity: .65; }
.workspace-tab .workspace-tab__close:hover { background: #dce8f8; opacity: 1; }
.workspace-tab button:focus-visible { outline: 2px solid var(--color-primary); outline-offset: -2px; }
.workspace-context-cover { position: fixed; inset: 0; z-index: 3000; }
.workspace-context-menu { position: absolute; width: 150px; padding: 5px; border: 1px solid #e2e7ef; border-radius: 5px; background: #fff; box-shadow: 0 4px 18px #0002; }
.workspace-context-menu button { display: block; width: 100%; padding: 8px 12px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.workspace-context-menu button:hover:not(:disabled) { background: #edf4ff; }
.workspace-context-menu button:disabled { color: #aaa; cursor: default; }
</style>
