<template><h2>我的消息</h2><p class="subtext gap">最近 200 条通知</p><p v-if="busy" class="loading">正在加载…</p><p v-if="error" class="error-box">{{error}}</p><section v-for="row in rows" :key="row.id" class="panel gap"><p class="body-copy">{{row.messageContent}}</p><p class="subtext">{{date(row.createTime)}}</p></section><p v-if="!busy&&!error&&!rows.length" class="empty">暂无消息</p></template>
<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue';import {request} from '../api.mjs';import {date} from '../data.mjs';
const rows=ref([]),error=ref(''),busy=ref(true),controller=new AbortController();let active=true;
onMounted(async()=>{try{const data=await request('/messages',{signal:controller.signal});if(active)rows.value=data;}catch(e){if(active)error.value=e.message;}finally{if(active)busy.value=false;}});onBeforeUnmount(()=>{active=false;controller.abort();});
</script>
