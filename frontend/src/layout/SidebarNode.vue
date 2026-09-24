<template>
  <el-sub-menu v-if="item.menuType === 'M'" :index="String(item.id)">
    <template #title><el-icon><component :is="item.icon || 'FolderOpened'" /></el-icon><span>{{ item.menuName }}</span></template>
    <SidebarNode v-for="child in item.children" :key="child.id" :item="child" :roles="roles" />
  </el-sub-menu>
  <el-menu-item v-else :index="item.path" @click="router.push(item.path)">
    <el-icon><component :is="item.icon || 'Menu'" /></el-icon><span>{{ navigationTitle(item, roles) }}</span>
  </el-menu-item>
</template>
<script setup lang="ts">
import { useRouter } from 'vue-router'
import { navigationTitle } from '@/utils/navigation.mjs'
defineProps<{item: any, roles: string[]}>()
const router = useRouter()
</script>
