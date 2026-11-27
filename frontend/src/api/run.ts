import http, { type ApiResponse } from './http'

export type EvalRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'CANCELLED'

export type EvalResultStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'EXEC_SUCCEEDED'
  | 'EXEC_FAILED'
  | 'SKIPPED'

export type EvalAttemptStatus =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMEOUT'
  | 'INTERRUPTED_UNKNOWN'

export interface EvalRunSummary {
  id: number
  name: string
  runType: string
  status: EvalRunStatus
  statusReason: string | null
  datasetId: number
  datasetNameSnapshot: string
  datasetVersionNo: number
  targetType: string
  providerId: number
  providerNameSnapshot: string
  modelSnapshot: string
  concurrency: number
  totalCases: number
  completedCases: number
  succeededCases: number
  failedCases: number
  skippedCases: number
  totalInputTokens: number
  totalOutputTokens: number
  totalCostUsd: number
  avgLatencyMs: number | null
  p95LatencyMs: number | null
  budgetExceeded: boolean
  budgetExceededReason: string | null
  interruptedAttemptCount: number
  rateLimitDegraded: boolean
  cancelRequested: boolean
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface EvalRunDetail extends EvalRunSummary {
  datasetVersionId: number
  targetConfigSnapshotJson: string
  maxAttempts: number
  requestTimeoutMs: number
  rateLimitPerSec: number
  rateLimitBurst: number
  maxCostUsd: number | null
  maxTotalTokens: number | null
  retryBaseDelayMs: number
  retryMaxDelayMs: number
  updatedAt: string
}

export interface EvalResultItem {
  id: number
  runId: number
  caseKey: string
  caseHash: string
  status: EvalResultStatus
  scoreConclusion: string
  attemptCount: number
  hasInterruptedUnknown: boolean
  outputText: string | null
  latencyMs: number | null
  ttftMs: number | null
  inputTokens: number
  outputTokens: number
  estimatedCostUsd: number
  errorType: string | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface EvalAttemptItem {
  id: number
  runId: number
  evalResultId: number
  caseKey: string
  attemptNo: number
  status: EvalAttemptStatus
  latencyMs: number | null
  ttftMs: number | null
  inputTokens: number
  outputTokens: number
  estimatedCostUsd: number
  errorType: string | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface CreateEvalRunPayload {
  name?: string
  datasetId: number
  datasetVersionNo: number
  targetType?: 'PROMPT'
  providerId: number
  model?: string
  systemPrompt?: string
  userPromptTemplate?: string
  concurrency?: number
  maxAttempts?: number
  requestTimeoutMs?: number
  rateLimitPerSec?: number
  rateLimitBurst?: number
  maxCostUsd?: number | null
  maxTotalTokens?: number | null
}

export function isActiveRunStatus(status: EvalRunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

export async function listRunsApi(status?: EvalRunStatus): Promise<EvalRunSummary[]> {
  const { data } = await http.get<ApiResponse<EvalRunSummary[]>>('/runs', {
    params: status ? { status } : undefined,
  })
  return data.data
}

export async function createRunApi(payload: CreateEvalRunPayload): Promise<EvalRunDetail> {
  const { data } = await http.post<ApiResponse<EvalRunDetail>>('/runs', payload)
  return data.data
}

export async function getRunDetailApi(id: number): Promise<EvalRunDetail> {
  const { data } = await http.get<ApiResponse<EvalRunDetail>>(`/runs/${id}`)
  return data.data
}

export async function listRunResultsApi(
  id: number,
  params?: { status?: EvalResultStatus; q?: string },
): Promise<EvalResultItem[]> {
  const { data } = await http.get<ApiResponse<EvalResultItem[]>>(`/runs/${id}/results`, {
    params,
  })
  return data.data
}

export async function listResultAttemptsApi(
  runId: number,
  resultId: number,
): Promise<EvalAttemptItem[]> {
  const { data } = await http.get<ApiResponse<EvalAttemptItem[]>>(
    `/runs/${runId}/results/${resultId}/attempts`,
  )
  return data.data
}

export async function cancelRunApi(id: number): Promise<EvalRunDetail> {
  const { data } = await http.post<ApiResponse<EvalRunDetail>>(`/runs/${id}/cancel`)
  return data.data
}

export async function deleteRunApi(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/runs/${id}`)
}
