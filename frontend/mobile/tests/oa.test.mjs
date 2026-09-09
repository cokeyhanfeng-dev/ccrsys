import test from 'node:test';
import assert from 'node:assert/strict';
import {consumeOaCallback} from '../oa.mjs';
function consume(href){let cleaned;const result=consumeOaCallback({href},{state:{keep:true},replaceState(state,_,url){assert.deepEqual(state,{keep:true});cleaned=url;}});return {result,cleaned};}
test('OA ticket精确解码并清理地址，保留其他业务参数',()=>{const {result,cleaned}=consume('https://example.test/mobile/?ticket=a%2Bb%26c%3D%23&tab=todo#detail');assert.equal(result.ticket,'a+b&c=#');assert.equal(cleaned,'/mobile/?tab=todo#detail');});
test('兼容oaToken以及hash路由回调',()=>{assert.equal(consume('https://example.test/mobile/?oaToken=one').result.ticket,'one');const {result,cleaned}=consume('https://example.test/mobile/#/callback?ticket=one&tab=todo');assert.equal(result.ticket,'one');assert.equal(cleaned,'/mobile/#/callback?tab=todo');});
test('空票、重复参数、别名冲突均清理并拒绝',()=>{for(const query of ['ticket=','ticket=a&ticket=b','ticket=a&oaToken=b','ticket='+'x'.repeat(4097)]){const {result,cleaned}=consume('https://example.test/mobile/?'+query);assert.equal(result.present,true);assert.ok(result.error);assert.equal(result.ticket,undefined);assert.equal(cleaned,'/mobile/');}assert.ok(consume('https://example.test/mobile/?ticket=a#/callback?ticket=b').result.error);});
test('普通name参数和包含ticket字样的业务参数不视作身份',()=>{const {result,cleaned}=consume('https://example.test/mobile/?name=admin&redirect=ticket');assert.equal(result.present,false);assert.equal(cleaned,undefined);});
