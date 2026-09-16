<template><section v-if="rows?.length" class="panel quality-panel"><h3>资料校验</h3><p v-if="summary.passed" class="quality-passed"><AppIcon name="done"/>{{summary.issues.length?'已通过 '+summary.passed+' 项校验':'全部 '+summary.passed+' 项校验通过'}}</p><div v-for="(row,i) in summary.issues" :key="i" class="quality-issue" :class="row.level==='BLOCK'?'quality-block':'quality-warn'"><b>{{row.label}}</b><p>{{row.message}}</p></div></section></template>
<script setup>
import {computed} from 'vue';import AppIcon from './AppIcon.vue';import {qualitySummary} from '../quality.mjs';
const props=defineProps(['rows']);const summary=computed(()=>qualitySummary(props.rows));
</script>
