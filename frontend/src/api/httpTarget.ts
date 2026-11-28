import http, { type ApiResponse } from './http'

export type HttpMethodType = 'POST' | 'GET' | 'PUT' | 'PATCH'

export interface HttpTargetView {
  id: number
  name: string
  description: string | null
  url: string
  httpMethod: HttpMethodType
  maskedHeaders: Record<string, string>
  bodyTemplate: string | null
  responseJsonPath: string | null
  defaultTimeoutMs: number
  createdAt: string
  updatedAt: string
}

export interface HttpTargetUpsertPayload {
  name: string
  description?: string | null
  url: string
  httpMethod: HttpMethodType
  headers?: Record<string, string> | null
  bodyTemplate?: string | null
  responseJsonPath?: string | null
  defaultTimeoutMs?: number
}

export interface HttpTargetSmokeTestPayload {
  inputJson?: string
  timeoutMs?: number
}

export interface HttpTargetSmokeTestResult {
  success: boolean
  httpStatus: number | null
  latencyMs: number
  extractedOutput: string | null
  rawResponsePreview: string | null
  errorType: string | null
  errorMessage: string | null
}

export async function listHttpTargetsApi(): Promise<HttpTargetView[]> {
  const { data } = await http.get<ApiResponse<HttpTargetView[]>>('/http-targets')
  return data.data
}

export async function createHttpTargetApi(payload: HttpTargetUpsertPayload): Promise<HttpTargetView> {
  const { data } = await http.post<ApiResponse<HttpTargetView>>('/http-targets', payload)
  return data.data
}

export async function updateHttpTargetApi(
  id: number,
  payload: HttpTargetUpsertPayload,
): Promise<HttpTargetView> {
  const { data } = await http.put<ApiResponse<HttpTargetView>>(`/http-targets/${id}`, payload)
  return data.data
}

export async function deleteHttpTargetApi(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/http-targets/${id}`)
}

export async function smokeTestHttpTargetApi(
  id: number,
  payload?: HttpTargetSmokeTestPayload,
): Promise<HttpTargetSmokeTestResult> {
  const { data } = await http.post<ApiResponse<HttpTargetSmokeTestResult>>(
    `/http-targets/${id}/test`,
    payload ?? {},
  )
  return data.data
}
