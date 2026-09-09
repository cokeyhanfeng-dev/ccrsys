<template><div v-for="([label,key]) in fields" :key="key" class="data-row"><span>{{label}}</span><span>{{display(key,value[key])}}</span></div></template>
<script setup>
import {guaranteeTypeText,termUnitText,productName,metricName} from '../../src/utils/dict';
defineProps({value:{type:Object,default:()=>({})},fields:{type:Array,default:()=>[]}});
const converters={guaranteeType:guaranteeTypeText,termUnit:termUnitText,productCode:productName,metricCode:metricName};
function display(key,value){return value==null||value===''?'—':converters[key]?converters[key](String(value)):value;}
</script>
