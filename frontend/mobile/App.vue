<template><div class="mobile-shell">
<header v-if="profile" class="app-head compact-head" :inert="choice||file ? '' : undefined"><button v-if="selected||page==='messages'" class="icon-button" aria-label="返回" @click="selected?back():navigate('home')"><AppIcon name="back"/></button><div class="head-title"><AppIcon v-if="!selected&&page==='home'" name="home" class="head-home-icon"/><span>{{selected?'审批详情':page==='home'?'审批工作台':page==='mine'?'我的账户':page==='messages'?'我的消息':page==='done'?'已办申请':'待办申请'}}</span></div><div v-if="!selected" class="head-actions"><button v-if="page!=='messages'" class="icon-button" aria-label="消息" @click="navigate('messages')"><AppIcon name="messages"/></button><button v-if="page!=='mine'" class="icon-button" aria-label="刷新" :disabled="loading" @click="refresh"><AppIcon name="refresh" :class="{'is-spinning':loading}"/></button></div></header>
<main v-if="!profile" class="scroll-body"><div v-if="showAuthLoading(booting,authBusy)" class="auth-loading" role="status" aria-live="polite"><span class="auth-spinner"></span><h1>正在安全登录</h1><p>正在验证身份，请稍候…</p></div><div v-else class="login-panel"><div class="brand-mark">审</div><h1>{{authDenied?'暂无审批权限':'登录未完成'}}</h1><div v-if="error" class="error-box" role="alert">{{error}}</div><template v-if="!authDenied"><p v-if="authMode==='oa'" class="subtext">请从 OA 工作台重新打开本应用。</p><button v-else class="primary full-width" @click="login">重新验证有度身份</button></template></div></main>
<template v-else>
<main class="scroll-body" ref="scroller" :inert="choice||file ? '' : undefined"><div v-if="error" class="error-box" role="alert">{{error}}</div><p v-if="loading" class="loading">正在加载…</p>
<DetailView v-if="selected&&record" :key="selected.id" :detail="record" @file="file=$event"/>
<div v-else-if="!selected" class="content">
<template v-if="page==='mine'"><ProfileView :profile="profile" :source="authMode" @messages="navigate('messages')" @logout="logout"/></template>
<MessageList v-else-if="page==='messages'" :key="messageVersion"/>
<template v-else>
<template v-if="page==='home'"><div class="role-line"><div><div class="greeting">{{profile.nickName||profile.userName}}，您好</div><p class="subtext">{{profile.orgName}}</p></div><div class="avatar">{{profile.nickName?.slice(-1)||'审'}}</div></div><section class="summary-card summary-compact"><div class="summary-primary"><span>待我审批</span><div><b>{{loaded?pending.length:'—'}}</b><span>笔申请</span></div></div><div class="summary-types"><button @click="filter='loan';navigate('todo')"><span>贷款</span><b>{{loaded?pending.filter(t=>t.loan).length:'—'}}</b><span>›</span></button><button @click="filter='deposit';navigate('todo')"><span>存款</span><b>{{loaded?pending.filter(t=>!t.loan).length:'—'}}</b><span>›</span></button></div></section><div class="section-heading"><h2>待办申请</h2><button class="text-link" @click="navigate('todo')">查看全部 ›</button></div></template>
<div v-else class="list-toolbar"><label class="list-search"><AppIcon name="search"/><input v-model="query" type="search" aria-label="搜索申请" placeholder="客户名称 / 申请编号"></label><select v-if="page==='todo'" v-model="filter" aria-label="业务类型筛选"><option value="all">全部类型</option><option value="loan">贷款</option><option value="deposit">存款</option></select></div>
<button v-for="item in visible" :key="item.kind+item.id" class="task" @click="open(item)"><div class="task-identity"><h3>{{item.name}}</h3><span class="task-number">{{item.applicationNo}}</span></div><div class="task-context"><span class="business-badge" :class="item.loan?'business-loan':'business-deposit'">{{item.loan?'贷款':'存款'}}</span><span class="tag">{{page==='done'?(stateNames[item.status]||item.status):(nodeNames[item.node]||'待审批')}}</span></div><div class="task-values"><div><div class="value-caption">申请金额（万元）</div><div class="numeric">{{fmt(item.amount)}}</div></div><div><div class="value-caption">{{page==='done'?'办理时间':'申请利率'}}</div><div class="numeric rate-blue" :class="{'small-value':page==='done'}">{{page==='done'?date(item.operationTime):range(item.rates)}}</div></div></div><div class="task-foot"><span>{{page==='done'?'已办记录':`${item.items.length} 个分项`}}</span><span>查看详情 ›</span></div></button>
<div v-if="!loading&&!visible.length" class="empty">{{error?'暂未取得数据，请刷新重试':query?'没有匹配的申请':page==='done'?'暂无已办申请':'暂无待办申请'}}</div><button v-if="page==='done'&&done.length<doneTotal" class="secondary full-width" :disabled="loading" @click="loadDone(true)">加载更多（{{done.length}} / {{doneTotal}}）</button>
</template></div>
</main>
<div v-if="selected&&record&&!record.finished&&selected.kind!=='done'" class="action-bar" :inert="choice||file ? '' : undefined"><button class="secondary" @click="choice='REJECT'">{{selected.kind==='vote'?'否决票':'否决'}}</button><button class="primary" @click="choice='APPROVE'">{{selected.kind==='vote'?'同意票':'同意审批'}}</button></div>
<nav v-if="!selected" class="bottom-nav" :inert="choice||file ? '' : undefined"><button v-for="[key,label,icon] in nav" :key="key" class="nav-button" :class="{active:page===key}" @click="navigate(key)"><AppIcon :name="key" class="nav-icon"/>{{label}}</button></nav>
</template>
<ActionSheet v-if="choice&&record" :detail="record" :task="selected" :choice="choice" @close="closeSheet" @success="success"/>
<FilePreview v-if="file&&record" :file="file" :application-id="record.id" @close="file=null"/>
<div v-if="notice" class="toast" role="status">{{notice}}</div>
</div></template>
<script setup>
import {ref,computed,onMounted,onBeforeUnmount} from 'vue';
import {authFailure,showAuthLoading} from './auth-presentation.mjs';
import {request,session} from './api.mjs';import {getYouduToken} from './youdu.mjs';
import {tasks,detail,fmt,date,range} from './data.mjs';
import {NODE_LABELS as nodeNames,APP_STATUS as stateNames} from '../src/utils/dict';
import AppIcon from './components/AppIcon.vue';import ProfileView from './components/ProfileView.vue';
import MessageList from './components/MessageList.vue';
import DetailView from './components/DetailView.vue';import ActionSheet from './components/ActionSheet.vue';import FilePreview from './components/FilePreview.vue';
const props=defineProps({oaCallback:{type:Object,default:()=>({present:false})}});
const authMode=ref(props.oaCallback.present?'oa':session.source());
if(props.oaCallback.present)session.rememberSource('oa');
let oaTicket=props.oaCallback.ticket;
const booting=ref(true),authDenied=ref(false);
const profile=ref(null),authBusy=ref(false),loading=ref(false),loaded=ref(false),error=ref(''),pending=ref([]),done=ref([]),doneTotal=ref(0),page=ref('home'),query=ref(''),filter=ref('all'),selected=ref(null),record=ref(null),choice=ref(null),file=ref(null),notice=ref(''),scroller=ref(null);
const messageVersion=ref(0);
const nav=[['home','首页','⌂'],['todo','待办','☷'],['done','已办','✓'],['mine','我的','○']];let epoch=0,timer;
const visible=computed(()=>{let rows=page.value==='done'?done.value:pending.value;if(page.value==='todo'&&filter.value!=='all')rows=rows.filter(t=>filter.value==='loan'?t.loan:!t.loan);const q=query.value.trim().toLowerCase();if(q)rows=rows.filter(t=>`${t.name} ${t.applicationNo}`.toLowerCase().includes(q));return page.value==='home'?rows.slice(0,4):rows;});
function expired(){epoch++;profile.value=null;pending.value=[];done.value=[];record.value=null;selected.value=null;choice.value=null;file.value=null;loaded.value=false;loading.value=false;error.value=authMode.value==='oa'?'登录已失效，请从 OA 工作台重新打开应用':'登录已失效，请重新进行有度身份验证';}
function loginFailed(e){const failure=authFailure(e,authMode.value);authDenied.value=failure.denied;error.value=failure.message;}
async function login(){
  if(authBusy.value)return;
  authBusy.value=true;authDenied.value=false;error.value='';
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
  }catch(e){loginFailed(e);}
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
onMounted(async()=>{window.addEventListener('mobile-session-expired',expired);try{if(props.oaCallback.present){session.clear();await login();}else if(session.get()){authBusy.value=true;try{profile.value=await request('/session');await refresh();}catch(e){loginFailed(e);}finally{authBusy.value=false;}}else await login();}finally{booting.value=false;}});
onBeforeUnmount(()=>{epoch++;clearTimeout(timer);window.removeEventListener('mobile-session-expired',expired);});
</script>
