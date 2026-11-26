import http, { type ApiResponse } from './http'

export type ProviderType = 'OPENAI_COMPATIBLE' | 'MOCK'

export interface ProviderView {
  id: number
  name: string
  providerType: ProviderType
  baseUrl: string | null
  apiKeyMask: string | null
  defaultModel: string
  pricingJson: string | null
  mockConfigJson: string | null
}

export interface ProviderUpsertPayload {
  name: string
  providerType: ProviderType
  baseUrl?: string | null
  apiKey?: string | null
  defaultModel: string
  pricingJson?: string | null
  mockConfigJson?: string | null
}

export interface SmokeTestResponse {
  content: string | null
  latencyMs: number
  ttftMs: number | null
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  errorType: string
  errorMessage: string | null
}

export async function listProvidersApi(): Promise<ProviderView[]> {
  const res = await http.get<ApiResponse<ProviderView[]>>('/providers')
  return res.data.data
}

export async function createProviderApi(payload: ProviderUpsertPayload): Promise<ProviderView> {
  const res = await http.post<ApiResponse<ProviderView>>('/providers', payload)
  return res.data.data
}

export async function updateProviderApi(
  id: number,
  payload: ProviderUpsertPayload,
): Promise<ProviderView> {
  const res = await http.put<ApiResponse<ProviderView>>(`/providers/${id}`, payload)
  return res.data.data
}

export async function deleteProviderApi(id: number): Promise<void> {
  await http.delete(`/providers/${id}`)
}

export async function smokeTestProviderApi(
  id: number,
  prompt: string,
  model?: string,
): Promise<SmokeTestResponse> {
  const res = await http.post<ApiResponse<SmokeTestResponse>>(`/providers/${id}/smoke-test`, {
    prompt,
    model: model || undefined,
  })
  return res.data.data
}
