export const fmt=(v,d=2)=>v==null||v===''?'—':Number(v).toLocaleString('zh-CN',{maximumFractionDigits:d});
export const rate=v=>v==null||v===''?'—':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:6})+'%';
export const date=v=>v?String(v).replace('T',' ').slice(0,16):'—';
export const first=v=>Array.isArray(v)?v[0]||{}:v||{};
export const asList=v=>Array.isArray(v)?v:[];
export function tasks(data) {
  const grouped=new Map();
  for(const kind of ['approval','vote','president'])for(const row of asList(data?.[kind])){
    const id=String(row.applicationId??'');if(!id)continue;
    const k=kind+':'+id;
    if(!grouped.has(k))grouped.set(k,{...row,id,kind,items:[]});
    grouped.get(k).items.push(...(row.items||[row]));
  }
  return [...grouped.values()].map(a=>({...a,items:uniqueItems(a.items)})).map(a=>({...a,name:a.customerName||a.customerNo||a.pricingCustomerNo||a.applicationNo||'申请',
    amount:a.items.reduce((s,i)=>s+Number(i.pricingAmount||0),0),loan:!(a.businessType?.includes('DEPOSIT')||a.items[0]?.pricingCarrierType==='DEPOSIT_ACCOUNT'),
    node:a.kind==='vote'?'SIX_PEOPLE_GROUP':a.kind==='president'?'PRESIDENT':a.items[0]?.currentNodeCode,
    rates:a.items.map(i=>i.currentApprovalRate??i.approvalRate??i.requestedRate).filter(v=>v!=null)}));
}
export function detail(data,id){const application=first(data.application),customer=first(data.customer);return {...data,id:String(id),application,customer,
  items:uniqueItems(asList(data.siblingItems)),loan:!String(application.businessType||'').includes('DEPOSIT'),
  name:customer.customerName||customer.groupName||application.customerNo||application.groupNo||'申请',node:application.currentNodeCode};}
export function range(rates){if(!rates.length)return '—';const min=Math.min(...rates.map(Number)),max=Math.max(...rates.map(Number));return min===max?rate(min):`${rate(min)}–${rate(max)}`;}
export function validateRates(items,values){const changes={};for(const i of items){const base=Number(i.currentApprovalRate??i.requestedRate);const raw=values[String(i.id)];const v=Number(raw);if(raw===''||raw==null||!Number.isFinite(v)||v<=0||v>36)throw Error('请输入大于 0 且不超过 36% 的利率');const delta=(v-base)*100;if(Math.abs(delta-Math.round(delta))>1e-6)throw Error('请按 1 BP（0.01 个百分点）的整数倍调价');if(v!==base)changes[String(i.id)]=v;}return changes;}

export function uniqueItems(rows){const result=[],index=new Map();for(const row of rows){const id=row.id??row.pricingItemId;if(id==null){result.push({...row});continue;}const key=String(id);if(!index.has(key)){const item={...row,id:key};index.set(key,item);result.push(item);}else if(row.contractNo){const item=index.get(key);item.contractNo=[...new Set([...(item.contractNo?String(item.contractNo).split('、'):[]),row.contractNo])].join('、');}}return result;}
