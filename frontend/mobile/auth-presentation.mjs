/** 权限拒绝不引导切换入口；入口提示仅用于身份认证失败。 */
export function authFailure(error,source){
  if(Number(error?.code)===403)return {denied:true,message:'当前账号暂无移动审批权限，请联系管理员核对审批角色及账号配置。'};
  const workspace=source==='oa'?'OA 工作台':'有度工作台';
  if(Number(error?.code)===401)return {denied:false,message:`登录身份已失效，请从${workspace}重新打开应用。`};
  return {denied:false,message:error?.message||`身份认证未完成，请从${workspace}重新打开应用。`};
}
export const showAuthLoading=(booting,busy)=>booting||busy;
