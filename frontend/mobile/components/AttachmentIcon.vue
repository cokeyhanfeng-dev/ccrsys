<template><span class="attachment-type-icon" :class="'attachment-'+kind" aria-hidden="true" v-html="icons[kind]"></span></template>
<script setup>
import {computed} from 'vue';import {attachmentKind} from '../files.mjs';
// 仅注入随代码发布的官方静态 SVG，不插入上传文件内容或用户输入。
import pdf from '../assets/bootstrap-icons/filetype-pdf.svg?raw';
import image from '../assets/bootstrap-icons/file-earmark-image.svg?raw';
import file from '../assets/bootstrap-icons/file-earmark.svg?raw';
const icons={pdf,image,file};const props=defineProps({name:String});const kind=computed(()=>attachmentKind(props.name));
</script>
