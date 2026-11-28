import http, { type ApiResponse } from './http'

export type SpanKind =
  | 'CASE_ROOT'
  | 'ATTEMPT'
  | 'PROMPT_RENDER'
  | 'TEMPLATE_RENDER'
  | 'LLM_CALL'
  | 'HTTP_CALL'
  | 'RETRIEVAL'
  | 'EVALUATOR'

export type SpanStatus = 'OK' | 'ERROR'

export interface TraceSpanItem {
  id: number
  traceId: string
  spanId: string
  parentSpanId: string | null
  runId: number
  evalResultId: number
  caseKey: string
  attemptNo: number
  spanName: string
  spanKind: SpanKind
  status: SpanStatus
  startTimeMs: number
  endTimeMs: number
  durationMs: number
  ttftMs: number | null
  inputTokens: number | null
  outputTokens: number | null
  estimatedCostUsd: number | null
  inputPayload: string | null
  outputPayload: string | null
  attributesJson: string | null
  errorType: string | null
  errorMessage: string | null
  createdAt: string
}

export interface TraceDetail {
  traceId: string | null
  runId: number | null
  evalResultId: number | null
  caseKey: string | null
  traceIncomplete: boolean
  startTimeMs: number | null
  endTimeMs: number | null
  totalDurationMs: number
  spans: TraceSpanItem[]
}

export async function getResultTraceApi(runId: number, resultId: number): Promise<TraceDetail> {
  const { data } = await http.get<ApiResponse<TraceDetail>>(
    `/runs/${runId}/results/${resultId}/trace`,
  )
  return data.data
}

export async function getTraceByIdApi(traceId: string): Promise<TraceDetail> {
  const { data } = await http.get<ApiResponse<TraceDetail>>(`/traces/${traceId}`)
  return data.data
}
