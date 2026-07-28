import { http } from '../http'
import type { AuthResponse, User } from '@/types/api'

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}

export interface LoginRequest {
  email: string
  password: string
}

export const authApi = {
  register(body: RegisterRequest) {
    return http.post<AuthResponse>('/api/auth/register', body).then((r) => r.data)
  },
  login(body: LoginRequest) {
    return http.post<AuthResponse>('/api/auth/login', body).then((r) => r.data)
  },
  me() {
    return http.get<User>('/api/auth/me').then((r) => r.data)
  },
  logout() {
    return http.post<void>('/api/auth/logout').then((r) => r.data)
  },
}
