<template><div class="mobile-shell">
<header class="app-head" :inert="choice||file ? '' : undefined"><button v-if="selected" class="back-label" @click="back">‹ 返回</button><div class="head-title">{{selected?'审批详情':'移动利率审批'}}<small>客户贡献度与利率决策</small></div><button v-if="profile&&!selected" class="icon-button" @click="navigate('messages')">消息</button><button v-if="profile&&!selected" class="icon-button" :disabled="loading" @click="refresh">刷新</button></header>
<main v-if="!profile" class="scroll-body"><div class="login-panel"><div class="brand-mark">审</div><h1>移动利率审批</h1><p class="subtext">{{authMode==='oa'?'OA 身份认证':'OA / 有度免密认证'}} · 审批人员专用</p><div v-if="error" class="error-box" role="alert">{{error}}</div><p v-if="authMode==='oa'" class="subtext">{{authBusy?'正在验证 OA 身份…':'请从 OA 工作台重新打开本应用，取得新的登录票据。'}}</p><button v-else class="primary full-width" :disabled="authBusy" @click="login">{{authBusy?'正在验证有度身份…':'有度免密登录'}}</button><button v-if="authMode==='oa'&&!authBusy" class="secondary full-width" @click="useYoudu">使用有度登录</button></div></main>
<template v-else>
<main class="scroll-body" ref="scroller" :inert="choice||file ? '' : undefined"><div v-if="error" class="error-box" role="alert">{{error}}</div><p v-if="loading" class="loading">正在加载…</p>
<DetailView v-if="selected&&record" :key="selected.id" :detail="record" @file="file=$event"/>
<div v-else-if="!selected" class="content">
<template v-if="page==='mine'"><div class="panel"><div class="avatar">{{profile.nickName?.slice(-1)||'审'}}</div><h2>{{profile.nickName||profile.userName}}</h2><p class="subtext">{{profile.orgName||'—'}}</p><div class="notice info">{{profile.roles.map(r=>roleNames[r]||r).join(' / ')}}</div><p class="subtext">仅展示本人可办理任务。审批去向、利率权限和处理结果由后台校验。</p><button class="secondary full-width gap" @click="logout">退出登录</button></div></template>
<MessageList v-else-if="page==='messages'" :key="messageVersion"/>
<template v-else>
<template v-if="page==='home'"><div class="role-line"><div><div class="greeting">{{profile.nickName||profile.userName}}，您好</div><p class="subtext">{{profile.orgName}}</p></div><div class="avatar">{{profile.nickName?.slice(-1)||'审'}}</div></div><section class="summary-card"><div class="summary-top">待我审批 <span class="live">{{loading?'更新中':'已加载'}}</span></div><div class="summary-numbers"><b class="big-number">{{loaded?pending.length:'—'}}</b><span>笔申请</span></div><div class="summary-divider"></div><div class="summary-bottom"><span>贷款 <b>{{pending.filter(t=>t.loan).length}}</b></span><span>存款 <b>{{pending.filter(t=>!t.loan).length}}</b></span></div></section><div class="section-heading"><h2>待办申请</h2><button class="text-link" @click="navigate('todo')">查看全部 ›</button></div></template>
<template v-else><h2>{{page==='done'?'我的已办':'待我审批'}}</h2><input class="gap" v-model="query" aria-label="搜索申请" placeholder="搜索当前列表的客户或申请编号"><div v-if="page==='todo'" class="filters gap"><button v-for="[key,label] in [['all','全部'],['loan','贷款'],['deposit','存款']]" :key="key" class="chip" :class="{active:filter===key}" @click="filter=key">{{label}}</button></div></template>
<button v-for="item in visible" :key="item.kind+item.id" class="task" @click="open(item)"><div class="task-top"><span class="task-type">{{item.loan?'贷款':'存款'}} · {{item.applicationNo}}</span><span class="tag">{{page==='done'?(stateNames[item.status]||item.status):(nodeNames[item.node]||'待审批')}}</span></div><h3>{{item.name}}</h3><div class="task-values"><div><div class="value-caption">申请金额（万元）</div><div class="numeric">{{fmt(item.amount)}}</div></div><div><div class="value-caption">{{page==='done'?'办理时间':'申请利率'}}</div><div class="numeric rate-blue" :class="{'small-value':page==='done'}">{{page==='done'?date(item.operationTime):range(item.rates)}}</div></div></div><div class="task-foot"><span>{{page==='done'?'已办记录':`${item.items.length} 个分项`}}</span><span>查看详情 ›</span></div></button>
<div v-if="!loading&&!visible.length" class="empty">{{error?'暂未取得数据，请刷新重试':query?'没有匹配的申请':page==='done'?'暂无已办申请':'暂无待办申请'}}</div><button v-if="page==='done'&&done.length<doneTotal" class="secondary full-width" :disabled="loading" @click="loadDone(true)">加载更多（{{done.length}} / {{doneTotal}}）</button>
</template></div>
</main>
<div v-if="selected&&record&&selected.kind!=='done'" class="action-bar" :inert="choice||file ? '' : undefined"><button class="secondary" @click="choice='REJECT'">{{selected.kind==='vote'?'否决票':'否决'}}</button><button class="primary" @click="choice='APPROVE'">{{selected.kind==='vote'?'同意票':'同意审批'}}</button></div>
<nav v-if="!selected" class="bottom-nav" :inert="choice||file ? '' : undefined"><button v-for="[key,label,icon] in nav" :key="key" class="nav-button" :class="{active:page===key}" @click="navigate(key)"><span class="nav-icon">{{icon}}</span>{{label}}</button></nav>
</template>
<ActionSheet v-if="choice&&record" :detail="record" :task="selected" :choice="choice" @close="closeSheet" @success="success"/>
<FilePreview v-if="file&&record" :file="file" :application-id="record.id" @close="file=null"/>
<div v-if="notice" class="toast" role="status">{{notice}}</div>
</div></template>
<script setup>
import {ref,computed,onMounted,onBeforeUnmount} from 'vue';
import {request,session} from './api.mjs';import {getYouduToken} from './youdu.mjs';
import {tasks,detail,fmt,date,range} from './data.mjs';
import {ROLE_TEXT as roleNames,NODE_LABELS as nodeNames,APP_STATUS as stateNames} from '../src/utils/dict';
import MessageList from './components/MessageList.vue';
import DetailView from './components/DetailView.vue';import ActionSheet from './components/ActionSheet.vue';import FilePreview from './components/FilePreview.vue';
const props=defineProps({oaCallback:{type:Object,default:()=>({present:false})}});
const authMode=ref(props.oaCallback.present?'oa':session.source());
if(props.oaCallback.present)session.rememberSource('oa');
let oaTicket=props.oaCallback.ticket;
const profile=ref(null),authBusy=ref(false),loading=ref(false),loaded=ref(false),error=ref(''),pending=ref([]),done=ref([]),doneTotal=ref(0),page=ref('home'),query=ref(''),filter=ref('all'),selected=ref(null),record=ref(null),choice=ref(null),file=ref(null),notice=ref(''),scroller=ref(null);
const messageVersion=ref(0);
const nav=[['home','首页','⌂'],['todo','待办','☷'],['done','已办','✓'],['mine','我的','○']];let epoch=0,timer;
const visible=computed(()=>{let rows=page.value==='done'?done.value:pending.value;if(page.value==='todo'&&filter.value!=='all')rows=rows.filter(t=>filter.value==='loan'?t.loan:!t.loan);const q=query.value.trim().toLowerCase();if(q)rows=rows.filter(t=>`${t.name} ${t.applicationNo}`.toLowerCase().includes(q));return page.value==='home'?rows.slice(0,4):rows;});
function expired(){epoch++;profile.value=null;pending.value=[];done.value=[];record.value=null;selected.value=null;choice.value=null;file.value=null;loaded.value=false;loading.value=false;error.value=authMode.value==='oa'?'登录已失效，请从 OA 工作台重新打开应用':'登录已失效，请重新进行有度身份验证';}
function useYoudu(){authMode.value='youdu';session.rememberSource('youdu');login();}
async function login(){
  if(authBusy.value)return;
  authBusy.value=true;error.value='';
  try{
    let data;
    if(authMode.value==='oa'){
      const ticket=oaTicket;oaTicket=undefined;
      if(props.oaCallback.error)throw Error(props.oaCallback.error);
      if(!ticket)throw Error('请从 OA 工作台重新打开应用，取得新的登录票据');
      data=await request('/oa/login',{method:'POST',body:{ticket}});
    }else{
      const token=await getYouduToken();
      data=await request('/login',{method:'POST',body:{token}});
    }
    session.set(data.token,authMode.value);profile.value=await request('/session');page.value='home';await refresh();
  }catch(e){error.value=e.message;}
  finally{authBusy.value=false;}
}
async function refresh(){if(page.value==='messages'){messageVersion.value++;return;}if(page.value==='done')return loadDone();const turn=++epoch;loading.value=true;error.value='';try{const data=await request('/tasks');if(turn!==epoch)return;pending.value=tasks(data);loaded.value=true;}catch(e){if(turn===epoch)error.value=e.message;}finally{if(turn===epoch)loading.value=false;}}
async function loadDone(more=false){const turn=++epoch;loading.value=true;error.value='';try{const data=await request(`/done?page=${more?Math.floor(done.value.length/20)+1:1}&size=20`);if(turn!==epoch)return;const rows=data.rows.map(r=>({...r,id:String(r.applicationId),name:r.customerName||r.applicationNo,kind:'done',loan:!r.businessType?.includes('DEPOSIT'),amount:r.pricingAmount,items:[],rates:[]}));done.value=more?[...done.value,...rows]:rows;doneTotal.value=data.total;}catch(e){if(turn===epoch)error.value=e.message;}finally{if(turn===epoch)loading.value=false;}}
function navigate(next){page.value=next;query.value='';error.value='';scroller.value?.scrollTo(0,0);if(next!=='mine')refresh();}
async function open(item){const turn=++epoch;selected.value=item;record.value=null;loading.value=true;error.value='';scroller.value?.scrollTo(0,0);try{const data=await request(`/applications/${item.id}`);if(turn===epoch)record.value=detail(data,item.id);}catch(e){if(turn===epoch)error.value=e.message;}finally{if(turn===epoch)loading.value=false;}}
function back(){epoch++;selected.value=null;record.value=null;choice.value=null;file.value=null;loading.value=false;error.value='';refresh();}
function closeSheet(uncertain){choice.value=null;if(uncertain)back();}
function success(){notice.value='操作已提交，请以刷新后的状态为准';clearTimeout(timer);timer=setTimeout(()=>notice.value='',3500);back();}
async function logout(){try{await request('/logout',{method:'POST'});session.clear();expired();error.value='已退出登录';}catch(e){error.value=e.message;}}
onMounted(async()=>{window.addEventListener('mobile-session-expired',expired);if(props.oaCallback.present){session.clear();await login();}else if(session.get()){authBusy.value=true;try{profile.value=await request('/session');await refresh();}catch(e){error.value=e.message;}finally{authBusy.value=false;}}else await login();});
onBeforeUnmount(()=>{epoch++;clearTimeout(timer);window.removeEventListener('mobile-session-expired',expired);});
</script>
