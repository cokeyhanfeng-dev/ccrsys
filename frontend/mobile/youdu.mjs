/** 对齐信贷看板 youdu_sdk-1.0.js：Android 原生对象 / iOS WVJB，身份仍由后端验票。 */
export function getYouduToken(win = window, timeout = 12000) {
  return new Promise((resolve, reject) => {
    let settled = false, poll, frame, frameTimer, requested = false;
    let bridgeCallback;
    const previous = win.onGetYdToken;
    const finish = (error, token) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(frameTimer);
      clearInterval(poll);
      frame?.remove();
      if (bridgeCallback && Array.isArray(win.WVJBCallbacks)) {
        const index = win.WVJBCallbacks.indexOf(bridgeCallback);
        if (index >= 0) win.WVJBCallbacks.splice(index, 1);
        // 自己创建的空队列不能阻止下次重试重新唤起原生桥。
        if (!win.WVJBCallbacks.length) delete win.WVJBCallbacks;
      }
      if (win.onGetYdToken === receive) win.onGetYdToken = previous;
      error ? reject(error) : resolve(token);
    };
    const receive = raw => {
      if (settled) return;
      try {
        const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (typeof data?.token !== 'string' || !data.token.trim() || data.token.length > 4096) throw Error();
        finish(null, data.token);
      } catch {
        finish(Error('未取得有效的有度身份，请重新打开应用'));
      }
    };
    const timer = setTimeout(() => finish(Error('未能连接有度登录组件，请从有度工作台重新打开应用（身份获取超时）')), timeout);
    win.onGetYdToken = receive;
    const setup = bridge => {
      if (settled || requested) return;
      try {
        if (typeof bridge?.registerHandler !== 'function' || typeof bridge?.callHandler !== 'function') return;
        requested = true;
        bridge.registerHandler('onGetYdToken', receive);
        bridge.callHandler('getYdToken', {}, raw => {
          if (raw?.token || (typeof raw === 'string' && raw.includes('"token"'))) receive(raw);
        });
      } catch {
        finish(Error('无法调用有度身份，请重新打开应用'));
      }
    };
    const probe = () => {
      if (settled || requested) return;
      try {
        if (typeof win.youdu?.getYdToken === 'function') {
          requested = true;
          win.youdu.getYdToken();
        } else if (win.WebViewJavascriptBridge) {
          setup(win.WebViewJavascriptBridge);
        }
      } catch {
        finish(Error('无法调用有度身份，请重新打开应用'));
      }
    };
    // UA 不决定能否登录；部分内嵌 WebView 没有 youdu 标记，原生对象也可能延迟注入。
    probe();
    if (settled || requested) return;
    poll = setInterval(probe, 100);
    const ua = win.navigator?.userAgent || '';
    const ios = /iPhone|iPad|iPod/i.test(ua)
      || (/Macintosh/i.test(ua) && win.navigator?.maxTouchPoints > 1);
    if (ios) {
      bridgeCallback = setup;
      if (Array.isArray(win.WVJBCallbacks) && win.WVJBCallbacks.length) {
        win.WVJBCallbacks.push(bridgeCallback);
      } else {
        win.WVJBCallbacks = [bridgeCallback];
        try {
          frame = win.document.createElement('iframe');
          frame.style.display = 'none';
          frame.src = 'wvjbscheme://__BRIDGE_LOADED__';
          win.document.documentElement.appendChild(frame);
          frameTimer = setTimeout(() => frame?.remove(), 0);
        } catch {
          finish(Error('无法初始化有度登录组件，请从有度重新打开应用'));
        }
      }
    }
  });
}
