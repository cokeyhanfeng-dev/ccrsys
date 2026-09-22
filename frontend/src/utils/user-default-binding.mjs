/** 上方基本信息改选时，仅同步默认绑定，保留其他机构岗位。 */
export function syncUserDefaultBinding(form, bindings) {
  const row = bindings.find(binding => binding.isDefault === '1')
  if (!row) return
  row.orgId = form.orgId
  row.postCode = form.roleCode
}

/** 改选默认行或默认行内容时，将当前默认组合回填基本信息。 */
export function syncUserDefaultForm(form, bindings) {
  const row = bindings.find(binding => binding.isDefault === '1')
  if (!row) return
  form.orgId = row.orgId
  form.roleCode = row.postCode
}
