export function qualitySummary(rows = []) {
  const names = {PASS:'通过',WARN:'需关注',BLOCK:'未通过'};
  const normalized = rows.map(row => {
    const level = String(row.ruleLevel ?? row.rule_level ?? '').toUpperCase();
    return {level,label:names[level]||'待核实',message:row.message?.trim() || row.ruleCode || row.rule_code || '未提供校验说明'};
  });
  return {passed:normalized.filter(row=>row.level==='PASS').length,
    issues:normalized.filter(row=>row.level!=='PASS')};
}
