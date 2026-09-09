const KEY='ccr_mobile_token',SOURCE='ccr_mobile_auth_source';
export const session={get:()=>sessionStorage.getItem(KEY),set:(token,source='youdu')=>{sessionStorage.setItem(KEY,token);sessionStorage.setItem(SOURCE,source==='oa'?'oa':'youdu');},rememberSource:source=>sessionStorage.setItem(SOURCE,source==='oa'?'oa':'youdu'),source:()=>sessionStorage.getItem(SOURCE)==='oa'?'oa':'youdu',clear:()=>sessionStorage.removeItem(KEY)};
export class ApiError extends Error { constructor(message,code,uncertain=false){super(message);this.code=code;this.uncertain=uncertain;} }
export const newKey=()=>globalThis.crypto?.randomUUID?.() || `m-${Date.now()}-${Math.random().toString(16).slice(2)}`;
export async function request(path,{method='GET',body,key,binary=false,signal}={}) {
  const controller=new AbortController();
  const abort=()=>controller.abort();signal?.addEventListener('abort',abort,{once:true});if(signal?.aborted)abort();
  const timer=setTimeout(abort,binary?90000:30000);
  const token=session.get();
  try {
    const response=await fetch(`${import.meta.env?.BASE_URL||'/mobile/'}api/mobile${path}`,{method,signal:controller.signal,cache:'no-store',
      headers:{...(token?{Authorization:token}:{}),...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},
      body:body?JSON.stringify(body):undefined});
    if(binary&&response.ok&&(response.headers.has('content-disposition')||!response.headers.get('content-type')?.includes('application/json')))return await response.blob();
    const data=await response.json().catch(()=>null);
    const code=data?.code??response.status;
    if(!response.ok||code!==200) {
      if(code===401){session.clear();window.dispatchEvent(new Event('mobile-session-expired'));}
      throw new ApiError(data?.msg||`请求失败（${code}）`,code,method==='POST'&&(response.status>=500||(Number(code)>=500&&Number(code)<600)));
    }
    return data.data;
  } catch(error) {
    if(error instanceof ApiError)throw error;
    throw new ApiError(controller.signal.aborted?'请求已中断或超时，请重新核对办理状态':'网络异常，请重试',0,method==='POST');
  } finally {clearTimeout(timer);signal?.removeEventListener('abort',abort);}
}
