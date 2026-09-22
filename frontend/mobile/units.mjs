export function contributionUnit(row,isRatio=false,isCount=false){
  if(isCount)return '户';
  if(isRatio||['RATE','RATIO'].includes(row.valueType))return '%';
  return {AVG_BALANCE:'万元·日均',INCOME:'万元',CONTRIBUTION_AMOUNT:'万元'}[row.valueType]||'单位未提供';
}
export function guaranteeTone(value){
  const text=String(value||'');
  if(/MORTGAGE|抵押/.test(text))return 'mortgage';
  if(/PLEDGE|CERTIFICATE_DEPOSIT|质押/.test(text))return 'pledge';
  if(/GUARANTEE|保证/.test(text))return 'guarantee';
  if(/CREDIT|信用/.test(text))return 'credit';
  return 'neutral';
}
