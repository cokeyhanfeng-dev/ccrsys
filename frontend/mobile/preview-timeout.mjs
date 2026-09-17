/** PDF 引擎/worker/页面绘制任一阶段停滞，都应结束等待并允许用户重试。 */
export function previewTimeout(promise,millis=20000){
  let timer;
  return Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>{const error=Error('预览处理超时');error.name='PreviewTimeoutError';reject(error);},millis);})]).finally(()=>clearTimeout(timer));
}
