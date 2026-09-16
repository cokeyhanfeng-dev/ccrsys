import test from 'node:test';import assert from 'node:assert/strict';
import {authFailure,showAuthLoading} from '../auth-presentation.mjs';
test('首次渲染及原生桥等待期间只显示登录中',()=>{assert.equal(showAuthLoading(true,false),true);assert.equal(showAuthLoading(true,true),true);assert.equal(showAuthLoading(false,true),true);assert.equal(showAuthLoading(false,false),false);});
test('OA 与有度权限拒绝均不引导更换工作台',()=>{for(const source of ['oa','youdu']){const value=authFailure({code:403,message:'请从有度工作台进入'},source);assert.equal(value.denied,true);assert.match(value.message,/审批权限/);assert.doesNotMatch(value.message,/有度|OA/);}});
test('会话失效按原入口提示，网络失败保留原因',()=>{assert.match(authFailure({code:401},'oa').message,/OA 工作台/);assert.doesNotMatch(authFailure({code:401},'oa').message,/有度/);assert.match(authFailure({code:401},'youdu').message,/有度工作台/);assert.equal(authFailure({code:0,message:'网络异常，请重试'},'oa').message,'网络异常，请重试');});
