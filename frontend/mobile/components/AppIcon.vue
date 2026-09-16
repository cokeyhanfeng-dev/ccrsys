<template><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name]||paths.mine"/></svg></template>
<script setup>
defineProps({name:String});
const paths={search:'M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0m-2 4 6 6',home:'M3 10 12 3l9 7M5 9v12h5v-7h4v7h5V9',todo:'M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01',done:'m5 12 4 4L19 6',mine:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2',messages:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',refresh:'M20 4v5h-5M19.2 8A8 8 0 1 0 20 15',logout:'M9 4H4v16h5M9 12h12m-4-4 4 4-4 4',chevron:'m9 5 7 7-7 7',back:'m15 5-7 7 7 7',shield:'m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Zm-4 9 3 3 5-6'};
</script>
