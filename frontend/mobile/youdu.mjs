/** 与 digitalDashboard 的 getYdToken / onGetYdToken 协议一致，仅实现登录桥。 */
export function getYouduToken(win=window, timeout=12000) {
  return new Promise((resolve,reject)=>{
    let settled=false,poll,frame;
    const previous=win.onGetYdToken;
    const finish=(error,token)=>{
      if(settled)return;settled=true;clearTimeout(timer);clearInterval(poll);frame?.remove();
      if(win.onGetYdToken===receive)win.onGetYdToken=previous;
      error?reject(error):resolve(token);
    };
    const receive=raw=>{
      if(settled)return;
      try {const data=typeof raw==='string'?JSON.parse(raw):raw;
        if(typeof data?.token!=='string'||!data.token.trim()||data.token.length>4096)throw Error();
        finish(null,data.token);
      }catch{finish(Error('未取得有效的有度身份，请重新打开应用'));}
    };
    const timer=setTimeout(()=>finish(Error('有度身份获取超时，请从有度重新打开应用')),timeout);
    win.onGetYdToken=receive;
    const setup=bridge=>{
      if(settled)return;
      bridge.registerHandler('onGetYdToken',receive);
      bridge.callHandler('getYdToken',{},raw=>{if(raw?.token||(typeof raw==='string'&&raw.includes('"token"')))receive(raw);});
    };
    try {
      if(win.youdu?.getYdToken){win.youdu.getYdToken();return;}
      if(!/youdu/i.test(win.navigator.userAgent)){finish(Error('请从有度工作台打开移动利率审批'));return;}
      if(/iPhone|iPad|iPod/i.test(win.navigator.userAgent)){
        if(win.WebViewJavascriptBridge)setup(win.WebViewJavascriptBridge);
        else if(win.WVJBCallbacks)win.WVJBCallbacks.push(setup);
        else {win.WVJBCallbacks=[setup];frame=win.document.createElement('iframe');frame.style.display='none';frame.src='wvjbscheme://__BRIDGE_LOADED__';win.document.documentElement.appendChild(frame);}
      }else{
        poll=setInterval(()=>{if(win.youdu?.getYdToken){clearInterval(poll);try{win.youdu.getYdToken();}catch{finish(Error('无法调用有度身份，请重新打开应用'));}}},100);
      }
    }catch{finish(Error('无法调用有度身份，请重新打开应用'));}
  });
}
