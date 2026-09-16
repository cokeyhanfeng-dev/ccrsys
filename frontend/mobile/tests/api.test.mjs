import test from 'node:test';import assert from 'node:assert/strict';
import {request,session} from '../api.mjs';
const memory=new Map();Object.defineProperty(globalThis,'sessionStorage',{configurable:true,value:{getItem:k=>memory.get(k),setItem:(k,v)=>memory.set(k,v),removeItem:k=>memory.delete(k)}});globalThis.window=new EventTarget();
test('HTTP200包裹的服务端500仍然提示结果未知，同键重试不改变请求体',async t=>{const calls=[];t.mock.method(globalThis,'fetch',async(url,options)=>{calls.push(options);return new Response(JSON.stringify({code:500,msg:'服务异常'}),{headers:{'Content-Type':'application/json'}});});for(let i=0;i<2;i++)await assert.rejects(request('/approve',{method:'POST',body:{applicationId:'2090000000000000001'},key:'same-key'}),e=>e.uncertain===true);assert.equal(calls[0].headers['Idempotency-Key'],calls[1].headers['Idempotency-Key']);assert.equal(calls[0].body,calls[1].body);});
test('401清理会话并通知页面，业务版本冲突保留登录',async t=>{session.set('fixture');let count=0;const listener=()=>count++;window.addEventListener('mobile-session-expired',listener);let code=1010;t.mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({code,msg:'拒绝'})));await assert.rejects(request('/approve',{method:'POST',body:{}}),e=>e.code===1010&&!e.uncertain);assert.equal(session.get(),'fixture');code=401;await assert.rejects(request('/session'));assert.equal(session.get(),undefined);assert.equal(count,1);window.removeEventListener('mobile-session-expired',listener);});
test('JSON附件按下载响应处理，JSON业务错误不会作为文件预览',async t=>{let file=true;t.mock.method(globalThis,'fetch',async()=>new Response(file?'{}':JSON.stringify({code:403,msg:'无权访问'}),{headers:file?{'Content-Type':'application/json','Content-Disposition':'attachment; filename="test.json"'}:{'Content-Type':'application/json'}}));assert.equal(await(await request('/applications/1/attachments/2',{binary:true})).text(),'{}');file=false;await assert.rejects(request('/applications/1/attachments/2',{binary:true}),e=>e.code===403);});
test('代理回退 HTML、空对象、缺少业务状态或空响应均不可显示审批成功',async t=>{
  let payload='';t.mock.method(globalThis,'fetch',async()=>new Response(payload));
  for(payload of ['<html>登录页</html>','{}','{"data":{}}','null','[]',''])
    await assert.rejects(request('/approve',{method:'POST',body:{applicationId:'1'},key:'same-key'}),e=>e.uncertain&&e.message.includes('响应格式异常'));
});
test('附件代理回退 HTML 和 JSON 成功对象必须报错，不能下载为附件',async t=>{
  let payload='<html>首页</html>';t.mock.method(globalThis,'fetch',async()=>new Response(payload));
  await assert.rejects(request('/applications/1/attachments/2',{binary:true}),/附件响应异常/);
  payload='{"code":200,"data":{}}';
  await assert.rejects(request('/applications/1/attachments/2',{binary:true}),/附件响应异常/);
});
test('真实移动接口 URL、认证头、申请版本和长 ID 保持原样',async t=>{
  session.set('fixture');let call;t.mock.method(globalThis,'fetch',async(url,options)=>{call={url,...options};return new Response('{"code":200}');});
  const body={applicationId:'8100000000000000001',nodeCode:'BRANCH_MANAGER',versionNo:7,rateAdjustments:{'8100000000000000002':3.16}};
  await request('/approve',{method:'POST',body,key:'fixture-key'});
  assert.equal(call.url,'/mobile/api/mobile/approve');assert.equal(call.headers.Authorization,'fixture');
  assert.deepEqual(JSON.parse(call.body),body);assert.equal(call.headers['Idempotency-Key'],'fixture-key');
});
