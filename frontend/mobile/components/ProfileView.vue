<template><section class="profile-card"><div class="profile-identity"><div class="profile-avatar">{{(profile.nickName||profile.userName||'审').slice(-1)}}</div><div><h1>{{profile.nickName||profile.userName}}</h1><p>{{profile.orgName||'机构未登记'}}</p></div></div><div class="profile-roles"><span v-for="role in profile.roles" :key="role">{{roleNames[role]||role}}</span></div></section><section class="profile-settings"><div class="profile-row"><span>登录账号</span><strong>{{profile.userName}}</strong></div><div class="profile-row"><span>登录方式</span><strong>{{source==='oa'?'OA 工作台':'有度工作台'}}</strong></div><button class="profile-row" @click="$emit('messages')"><span class="profile-menu-label"><AppIcon name="messages"/>我的消息</span><AppIcon name="chevron"/></button></section><button class="logout-button" @click="$emit('logout')"><AppIcon name="logout"/>退出登录</button></template>
<script setup>
import AppIcon from './AppIcon.vue';
import {ROLE_TEXT as roleNames} from '../../src/utils/dict';
defineProps(['profile','source']);defineEmits(['logout','messages']);
</script>
