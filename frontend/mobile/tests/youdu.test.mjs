import test from 'node:test';
import assert from 'node:assert/strict';
import {getYouduToken} from '../youdu.mjs';

function iosWindow() {
  const frames = [];
  return {
    navigator: {userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)'},
    frames,
    document: {
      createElement() {return {style: {}, remove() {this.removed = true;}};},
      documentElement: {appendChild(frame) {frames.push(frame);}}
    }
  };
}
function bridge(token) {
  return {
    registerHandler(name, callback) {assert.equal(name, 'onGetYdToken'); this.receive = callback;},
    callHandler(name) {assert.equal(name, 'getYdToken'); this.receive(JSON.stringify({token}));}
  };
}

test('iOS 无 youdu UA 仍可通过已注入桥取得凭证', async () => {
  const win = iosWindow();
  win.WebViewJavascriptBridge = bridge('ios-platform-token');
  assert.equal(await getYouduToken(win, 500), 'ios-platform-token');
});

test('iOS 无 youdu UA 通过 WVJB 初始化后取得凭证并清理', async () => {
  const win = iosWindow();
  const login = getYouduToken(win, 500);
  assert.equal(win.frames[0].src, 'wvjbscheme://__BRIDGE_LOADED__');
  win.WVJBCallbacks[0](bridge('delayed-ios-token'));
  assert.equal(await login, 'delayed-ios-token');
  assert.equal(win.frames[0].removed, true);
  assert.equal(win.WVJBCallbacks, undefined);
});

test('Android 无 youdu UA 且原生对象延迟注入时等待获取', async () => {
  let calls = 0;
  const win = {navigator: {userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit Chrome'}};
  const login = getYouduToken(win, 1000);
  win.youdu = {getYdToken() {calls++; win.onGetYdToken({token: 'android-delayed'});}};
  assert.equal(await login, 'android-delayed');
  assert.equal(calls, 1);
});

test('iPad 桌面 UA 按触屏设备初始化 iOS 桥', async () => {
  const win = iosWindow();
  win.navigator = {userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X)', maxTouchPoints: 5};
  const login = getYouduToken(win, 500);
  win.WVJBCallbacks[0](bridge('ipad-token'));
  assert.equal(await login, 'ipad-token');
});

test('初始化超时清理旧回调，重试可重新唤起桥且忽略迟到凭证', async () => {
  const win = iosWindow();
  const previous = () => {};
  win.onGetYdToken = previous;
  const first = getYouduToken(win, 10);
  const late = win.WVJBCallbacks[0];
  await assert.rejects(first, /超时/);
  assert.equal(win.onGetYdToken, previous);
  assert.equal(win.WVJBCallbacks, undefined);
  const second = getYouduToken(win, 500);
  assert.equal(win.frames.length, 2);
  late(bridge('expired-token'));
  win.WVJBCallbacks[0](bridge('fresh-token'));
  assert.equal(await second, 'fresh-token');
});

test('缺少桥的普通浏览器和原生调用异常均不得登录', async () => {
  await assert.rejects(getYouduToken({navigator: {userAgent: 'Chrome'}}, 5), /超时/);
  await assert.rejects(getYouduToken({WebViewJavascriptBridge: {
    registerHandler() {throw Error('native unavailable');}, callHandler() {}
  }}, 50), /无法调用/);
});
