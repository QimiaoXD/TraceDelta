import http, { type ApiResponse } from './http'

export interface LoginResponse {
  token: string
  username: string
}

export interface MeResponse {
  username: string
}

export async function loginApi(username: string, password: string): Promise<LoginResponse> {
  const res = await http.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })
  return res.data.data
}

export async function meApi(): Promise<MeResponse> {
  const res = await http.get<ApiResponse<MeResponse>>('/auth/me')
  return res.data.data
}
