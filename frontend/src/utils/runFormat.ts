import type { IconName } from '../components/TdIcon.vue'
import type { EvalAttemptStatus, EvalResultStatus, EvalRunStatus } from '../api/run'

export interface StatusMeta {
  label: string
  tone: 'neutral' | 'success' | 'warning' | 'danger'
  icon: IconName
}

export function runStatusMeta(status: EvalRunStatus): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: '排队中', tone: 'neutral', icon: 'clock' }
    case 'RUNNING':
      return { label: '运行中', tone: 'warning', icon: 'activity' }
    case 'COMPLETED':
      return { label: '已完成', tone: 'success', icon: 'check-circle' }
    case 'PARTIAL_FAILED':
      return { label: '部分失败', tone: 'warning', icon: 'alert-triangle' }
    case 'FAILED':
      return { label: '已失败', tone: 'danger', icon: 'alert-circle' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'neutral', icon: 'x' }
  }
}

export function resultStatusMeta(status: EvalResultStatus): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: '待执行', tone: 'neutral', icon: 'clock' }
    case 'RUNNING':
      return { label: '执行中', tone: 'warning', icon: 'activity' }
    case 'EXEC_SUCCEEDED':
      return { label: '执行成功', tone: 'success', icon: 'check-circle' }
    case 'EXEC_FAILED':
      return { label: '执行失败', tone: 'danger', icon: 'alert-circle' }
    case 'SKIPPED':
      return { label: '已跳过', tone: 'warning', icon: 'alert-triangle' }
  }
}

export function attemptStatusMeta(status: EvalAttemptStatus): StatusMeta {
  switch (status) {
    case 'RUNNING':
      return { label: '执行中', tone: 'warning', icon: 'activity' }
    case 'SUCCEEDED':
      return { label: '成功', tone: 'success', icon: 'check-circle' }
    case 'TIMEOUT':
      return { label: '超时', tone: 'danger', icon: 'clock' }
    case 'FAILED':
      return { label: '失败', tone: 'danger', icon: 'alert-circle' }
    case 'INTERRUPTED_UNKNOWN':
      return { label: '未知中断', tone: 'warning', icon: 'alert-triangle' }
  }
}

export function statusTagClass(tone: StatusMeta['tone']): string {
  if (tone === 'success') return 'status-tag status-tag--success'
  if (tone === 'warning') return 'status-tag status-tag--warning'
  if (tone === 'danger') return 'status-tag status-tag--danger'
  return 'status-tag'
}

export function formatLatency(ms?: number | null): string {
  if (ms === null || ms === undefined) return '—'
  return `${ms} ms`
}

export function formatCostUsd(cost?: number | null): string {
  if (cost === null || cost === undefined) return '$0.0000'
  const num = Number(cost)
  if (Number.isNaN(num)) return '—'
  if (num === 0) return '$0.0000'
  if (num < 0.0001) return `$${num.toFixed(6)}`
  return `$${num.toFixed(4)}`
}

export function formatBudgetReason(reason?: string | null): string {
  if (reason === 'COST_BUDGET_EXCEEDED') return '触发费用预算上限'
  if (reason === 'TOKEN_BUDGET_EXCEEDED') return '触发 Token 预算上限'
  return reason || '触发预算上限'
}
