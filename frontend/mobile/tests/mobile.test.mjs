import test from 'node:test';import assert from 'node:assert/strict';
import {tasks,detail,validateRates} from '../data.mjs';import {previewType} from '../files.mjs';import {getYouduToken} from '../youdu.mjs';
test('申请按渠道聚合并保持长整数ID',()=>{const rows=tasks({approval:[{applicationId:'2090000000000000001',pricingAmount:10,currentNodeCode:'SECRETARY'},{applicationId:'2090000000000000001',pricingAmount:20,currentNodeCode:'SECRETARY'}],vote:[{applicationId:'2090000000000000001',roundId:'2090000000000000002'}]});assert.equal(rows.length,2);assert.equal(rows[0].id,'2090000000000000001');assert.equal(rows[0].amount,30);assert.equal(rows[1].kind,'vote');});
test('1BP允许原始小数精度且拒绝半BP和非法值',()=>{const items=[{id:'2090000000000000001',requestedRate:3.055001}];assert.deepEqual(validateRates(items,{'2090000000000000001':3.065001}),{'2090000000000000001':3.065001});for(const v of ['',0,37,3.06,'bad'])assert.throws(()=>validateRates(items,{'2090000000000000001':v}));});
test('详情使用申请版本和申请节点',()=>{const result=detail({application:[{versionNo:8,currentNodeCode:'SECRETARY'}],customer:[{customerName:'测试客户'}],siblingItems:[]},'2090000000000000001');assert.equal(result.application.versionNo,8);assert.equal(result.node,'SECRETARY');assert.equal(result.name,'测试客户');});
test('已结束申请保留历史节点时优先展示最终状态并关闭操作入口',()=>{
  for(const status of ['REJECTED','VETOED','APPROVED','FINAL','CLOSED']){
    const result=detail({application:[{applicationStatus:status,currentNodeCode:'VICE_PRESIDENT'}]},'1');
    assert.equal(result.badgeCode,status);assert.equal(result.finished,true);
    assert.equal(result.node,'VICE_PRESIDENT');
  }
});
test('在途申请跟随后端当前节点，兼容综合支行与无秘书岗链路',()=>{
  for(const node of ['PARENT_BRANCH_MANAGER','SIX_PEOPLE_GROUP','SECRETARY']){
    const result=detail({application:[{applicationStatus:'PROCESSING',currentNodeCode:node}]},'1');
    assert.equal(result.badgeCode,node);assert.equal(result.finished,false);
  }
  const result=detail({application:[{applicationStatus:'PARTIAL_APPROVED'}]},'1');
  assert.equal(result.badgeCode,'PARTIAL_APPROVED');assert.equal(result.finished,false);
});
test('附件不信任HTML或SVG扩展名',()=>{assert.equal(previewType(new TextEncoder().encode('<svg><script>')),null);assert.equal(previewType(new TextEncoder().encode('%PDF-1.7')),'application/pdf');assert.equal(previewType(new Uint8Array([255,216,255])),'image/jpeg');});
test('有度安卓回调获取token并恢复回调',async()=>{const previous=()=>{};const win={navigator:{userAgent:'youdu Android'},onGetYdToken:previous,youdu:{getYdToken(){win.onGetYdToken(JSON.stringify({token:'platform-token'}));}}};assert.equal(await getYouduToken(win),'platform-token');assert.equal(win.onGetYdToken,previous);});
test('普通浏览器拒绝免密冒名；无效回调拒绝；回调超时拒绝',async()=>{await assert.rejects(getYouduToken({navigator:{userAgent:'Chrome'}},5),/有度/);const win={navigator:{userAgent:'youdu'},youdu:{getYdToken(){win.onGetYdToken('{"account":"admin"}');}}};await assert.rejects(getYouduToken(win),/身份/);await assert.rejects(getYouduToken({navigator:{userAgent:'youdu'},youdu:{getYdToken(){}}},5),/超时/);});
test('有度iOS桥成功与重复回调',async()=>{const win={navigator:{userAgent:'youdu iPhone'},WebViewJavascriptBridge:{registerHandler(name,handler){this.handler=handler;},callHandler(){this.handler('{"token":"ios-token"}');this.handler('{"token":"second"}');}}};assert.equal(await getYouduToken(win),'ios-token');});

test('同分项多合同只计一次金额并保留合同号',()=>{const data=detail({siblingItems:[{id:'91',pricingAmount:100,contractNo:'A'},{id:'91',pricingAmount:100,contractNo:'B'}]},'1');assert.equal(data.items.length,1);assert.equal(data.items[0].contractNo,'A、B');assert.equal(data.items[0].pricingAmount,100);});
test('材料合并数仓和申请补录他行融资，委员待办显示已调整利率',()=>{
  const result=detail({otherLoans:[{lenderName:'虚构银行甲',balanceAmount:100}],appOtherLoans:[{lenderName:'虚构银行乙',balanceAmount:50}]},'1');
  assert.equal(result.otherLoans.length,2);assert.equal(result.otherLoans[1].balanceAmount,50);
  const [task]=tasks({vote:[{applicationId:'1',pricingItemId:'2',requestedRate:3.05,currentApprovalRate:3.15}]});
  assert.deepEqual(task.rates,[3.15]);
});
