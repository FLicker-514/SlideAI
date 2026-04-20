import request from './request'

export interface User {
  id: string
  userName: string
  email: string
  vipLevel?: number
  status?: number
}

export interface LoginRes {
  token: string
  user: User
  role: string
}

export const accountApi = {
  sendCode(email: string, purpose: 'register' | 'reset') {
    return request.post<{ code: number; message: string }>('/account/send-code', { email, purpose })
  },
  register(data: { username: string; email: string; password: string; verificationCode: string }) {
    return request.post<{ code: number; message: string }>('/account/register', data)
  },
  login(data: { email: string; password: string; loginType?: string }) {
    return request.post<{ code: number; message: string; data: LoginRes }>('/account/login', data)
  },
  getUserInfo() {
    return request.get<{ code: number; message: string; data: User }>('/account/info')
  },
  resetPassword(data: { email: string; verificationCode: string; newPassword: string }) {
    return request.post<{ code: number; message: string }>('/account/reset-password', data)
  },
  deleteAccount() {
    return request.post<{ code: number; message: string }>('/account/delete')
  },
  updateProfile(data: { userName: string }) {
    return request.put<{ code: number; message: string; data: User }>('/account/profile', data)
  }
}
