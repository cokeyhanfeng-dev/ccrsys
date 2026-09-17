<template><div class="pdf-reader"><div class="pdf-controls"><button aria-label="上一页" :disabled="pageNo<=1||busy" @click="turn(-1)">‹</button><span>{{pageNo}} / {{pages||'—'}}</span><button aria-label="下一页" :disabled="pageNo>=pages||busy" @click="turn(1)">›</button><span class="pdf-control-spacer"></span><button aria-label="缩小" :disabled="zoom<=1" @click="changeZoom(zoom-.25)">−</button><button class="pdf-percent" aria-label="恢复适应宽度" @click="changeZoom(1)">{{Math.round(zoom*100)}}%</button><button aria-label="放大" :disabled="zoom>=3" @click="changeZoom(zoom+.25)">＋</button></div><div v-if="error" class="error-box pdf-error" role="alert">{{error}}<button @click="retry">重新加载</button><a :href="url" download="attachment.pdf">下载 PDF</a></div><p class="pdf-loading" role="status">{{busy ? phase+'…' : ' '}}</p><div ref="viewport" class="pdf-viewport" aria-label="PDF 阅读区域，可双指缩放和单指拖动" @touchstart="start" @touchmove.prevent="move" @touchend="end" @touchcancel="end"><canvas ref="canvas" :style="{width:baseWidth*zoom+'px',height:baseHeight*zoom+'px'}"/></div><p class="preview-hint">双指缩放 · 放大后单指拖动 · 点击百分比恢复</p></div></template>
<script setup>
import {ref,onMounted,onBeforeUnmount,nextTick} from 'vue';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';
import {previewTimeout} from '../preview-timeout.mjs';
import {clampZoom,touchDistance,zoomAnchor} from '../preview-zoom.mjs';

const props=defineProps({url:String});
const viewport=ref(null),canvas=ref(null),pageNo=ref(1),pages=ref(0),zoom=ref(1),baseWidth=ref(1),baseHeight=ref(1),busy=ref(true),phase=ref('正在加载阅读器'),error=ref('');
let task,pdfDocument,currentPage,renderTask,disposed=false,gesture,renderTimer,sequence=0;
async function render(){
  if(!currentPage||disposed)return;
  const turn=++sequence;busy.value=true;phase.value=`正在绘制第 ${pageNo.value} 页`;
  if(renderTask){renderTask.cancel();try{await previewTimeout(renderTask.promise);}catch{}}
  if(disposed||turn!==sequence)return;
  const source=currentPage.getViewport({scale:1});
  const density=Math.min(window.devicePixelRatio||1,2);
  // 限制单页画布像素，避免超大扫描件耗尽移动 WebView 内存。
  const scale=Math.min(baseWidth.value/source.width*zoom.value*density,Math.sqrt(8000000/(source.width*source.height)));
  const view=currentPage.getViewport({scale});
  try{
    // 完成离屏渲染后再替换可见画布，取消/失败不能擦掉上一帧。
    const buffer=window.document.createElement('canvas');buffer.width=Math.ceil(view.width);buffer.height=Math.ceil(view.height);
    const context=buffer.getContext('2d');if(!context)throw Error('canvas');
    renderTask=currentPage.render({canvasContext:context,viewport:view});
    await previewTimeout(renderTask.promise);
    if(disposed||turn!==sequence)return;
    canvas.value.width=buffer.width;canvas.value.height=buffer.height;
    canvas.value.getContext('2d').drawImage(buffer,0,0);error.value='';
  }catch(e){if(!disposed&&turn===sequence&&e.name!=='RenderingCancelledException'){renderTask?.cancel();error.value=e.name==='PreviewTimeoutError'?'PDF 绘制超时，请重试或下载查看':'PDF 页面渲染失败，请重试或下载查看';}}
  finally{if(turn===sequence)busy.value=false;}
}

async function showPage(){
  busy.value=true;error.value='';phase.value=`正在读取第 ${pageNo.value} 页`;
  try{
    const page=await previewTimeout(pdfDocument.getPage(pageNo.value));if(disposed)return;currentPage=page;
    const source=page.getViewport({scale:1});const padding=getComputedStyle(viewport.value);baseWidth.value=Math.max(100,viewport.value.clientWidth-parseFloat(padding.paddingLeft)-parseFloat(padding.paddingRight));baseHeight.value=baseWidth.value*source.height/source.width;
    zoom.value=1;viewport.value.scrollTo(0,0);await render();
  }catch{if(!disposed){error.value='PDF 页面读取失败，请下载后查看';busy.value=false;}}
}
function schedule(){clearTimeout(renderTimer);renderTimer=setTimeout(render,180);}
async function changeZoom(value,point){
  const box=viewport.value;if(!box)return;const before=zoom.value,after=clampZoom(value);
  const x=point?.x??box.clientWidth/2,y=point?.y??box.clientHeight/2,left=zoomAnchor(box.scrollLeft,x,before,after),top=zoomAnchor(box.scrollTop,y,before,after);
  zoom.value=after;await nextTick();if(disposed)return;box.scrollLeft=left;box.scrollTop=top;schedule();
}
function start(event){
  const t=event.touches,box=viewport.value;
  if(t.length>=2){const r=box.getBoundingClientRect();gesture={distance:touchDistance(t),zoom:zoom.value,point:{x:(t[0].clientX+t[1].clientX)/2-r.left,y:(t[0].clientY+t[1].clientY)/2-r.top}};}
  else if(t.length===1)gesture={x:t[0].clientX,y:t[0].clientY,left:box.scrollLeft,top:box.scrollTop};
}
function move(event){
  const t=event.touches;if(!gesture)return;
  if(t.length>=2&&gesture.distance>0)changeZoom(gesture.zoom*touchDistance(t)/gesture.distance,gesture.point);
  else if(t.length===1&&gesture.x!=null){viewport.value.scrollLeft=gesture.left+gesture.x-t[0].clientX;viewport.value.scrollTop=gesture.top+gesture.y-t[0].clientY;}
}
function end(event){gesture=null;if(event.touches?.length)start(event);schedule();}
async function turn(delta){if(busy.value)return;pageNo.value+=delta;await showPage();}
async function load(){
  busy.value=true;error.value='';phase.value='正在加载阅读器';
  try{
    const {getDocument,GlobalWorkerOptions}=await previewTimeout(import('pdfjs-dist/legacy/build/pdf.mjs'));
    if(disposed)return;GlobalWorkerOptions.workerSrc=workerUrl;
    phase.value='正在读取 PDF';const response=await previewTimeout(fetch(props.url));if(!response.ok)throw Error('attachment');
    const data=new Uint8Array(await previewTimeout(response.arrayBuffer()));if(disposed)return;
    phase.value='正在解析 PDF';task=getDocument({data,cMapUrl:import.meta.env.BASE_URL+'pdf/cmaps/',cMapPacked:true,standardFontDataUrl:import.meta.env.BASE_URL+'pdf/standard_fonts/',useWorkerFetch:false,isImageDecoderSupported:false,isEvalSupported:false});
    pdfDocument=await previewTimeout(task.promise);if(disposed)return;pages.value=pdfDocument.numPages;await nextTick();await showPage();
  }catch(e){if(!disposed){error.value=e.name==='PasswordException'?'PDF 已加密，请下载后输入密码查看':e.name==='PreviewTimeoutError'?'PDF 读取超时，请重试或下载查看':'PDF 阅读器加载或文件解析失败，请重试；仍失败请下载查看';busy.value=false;}}
}
async function retry(){clearTimeout(renderTimer);renderTask?.cancel();try{await previewTimeout(task?.destroy(),3000);}catch{}currentPage=null;pageNo.value=1;await load();}
onMounted(load);
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(renderTimer);renderTask?.cancel();task?.destroy().catch(()=>{});});
</script>
