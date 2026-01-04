const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080'

export interface User {
  id: string
  email: string
  displayName: string | null
  isPremium: boolean
}

export interface AuthResponse {
  token: string
  user: User
}

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function fetchApi<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null

  const res = await fetch(`${API_URL}${endpoint}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: 'Request failed' }))
    throw new ApiError(res.status, error.error || 'Request failed')
  }

  return res.json()
}

export const api = {
  async signup(email: string, password: string, displayName: string): Promise<AuthResponse> {
    const response = await fetchApi<AuthResponse>('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    })
    localStorage.setItem('token', response.token)
    return response
  },

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await fetchApi<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    localStorage.setItem('token', response.token)
    return response
  },

  async getMe(): Promise<User> {
    return fetchApi<User>('/api/auth/me')
  },

  logout() {
    localStorage.removeItem('token')
  },

  getToken(): string | null {
    return typeof window !== 'undefined' ? localStorage.getItem('token') : null
  },
}
