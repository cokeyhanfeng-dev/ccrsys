/** 刷新只读基线，保留目标值；OTHER 无数值基线。 */
export function syncCommitmentBaselines(commitments, contribution) {
  const current = new Map(contribution.map(row => [row.metricCode, row.metricValue]))
  for (const commitment of commitments) {
    commitment.baselineValue = commitment.metricCode === 'OTHER'
      ? '' : String(current.get(commitment.metricCode) ?? 0)
  }
}
