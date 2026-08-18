import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

// Axios 统一封装:携带 token、统一错误提示
const service = axios.create({
  baseURL: '/api',
  timeout: 15000
})

service.interceptors.request.use((config) => {
  const token = localStorage.getItem('ccr_token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端统一返回 R:{ code, msg, data }
    if (res.code === 200) {
      return res.data
    }
    // 会话过期/未登录(Sa-Token 经全局异常处理返回业务码 401):清 token 并跳登录页
    if (res.code === 401) {
      localStorage.removeItem('ccr_token')
      // 已在登录页:401 即账号或密码错误,明确提示,避免静默失败
      if (window.location.pathname.startsWith('/login')) {
        ElMessage.error(res.msg || '用户名或密码错误')
        return Promise.reject(new Error(res.msg || '用户名或密码错误'))
      }
      const redirect = window.location.pathname + window.location.search
      window.location.href = '/login?redirect=' + encodeURIComponent(redirect)
      return Promise.reject(new Error(res.msg || '登录已过期'))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg))
  },
  (error) => {
    if (error.response?.status === 401 || error.response?.data?.code === 401) {
      localStorage.removeItem('ccr_token')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    } else {
      ElMessage.error(error.response?.data?.msg || '网络异常')
    }
    return Promise.reject(error)
  }
)

// 泛型封装
export function request<T = any>(config: AxiosRequestConfig): Promise<T> {
  return service(config) as Promise<T>
}

export function get<T = any>(url: string, params?: object): Promise<T> {
  return request<T>({ url, method: 'get', params })
}

export function post<T = any>(url: string, data?: object): Promise<T> {
  return request<T>({ url, method: 'post', data })
}

export function put<T = any>(url: string, data?: object): Promise<T> {
  return request<T>({ url, method: 'put', data })
}

export function del<T = any>(url: string, params?: object): Promise<T> {
  return request<T>({ url, method: 'delete', params })
}

// 文件下载(独立 axios 调用,绕开 R 包装拦截器;从 Content-Disposition 取文件名)
export async function download(url: string): Promise<void> {
  const token = localStorage.getItem('ccr_token')
  try {
    const resp = await axios.get(`/api${url}`, {
      responseType: 'blob',
      headers: token ? { Authorization: token } : {}
    })
    const blob = resp.data as Blob
    // 后端出错时返回 JSON(R 包装),按错误处理
    if (blob.type.includes('application/json')) {
      const err = JSON.parse(await blob.text())
      ElMessage.error(err.msg || '导出失败')
      return Promise.reject(new Error(err.msg))
    }
    const dispo: string = resp.headers['content-disposition'] || ''
    const match = dispo.match(/filename\*=UTF-8''([^;]+)/)
    const filename = match ? decodeURIComponent(match[1]) : 'export.xlsx'
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filename
    link.click()
    URL.revokeObjectURL(link.href)
  } catch (e: any) {
    const data = e?.response?.data
    if (data instanceof Blob && data.type.includes('application/json')) {
      const err = JSON.parse(await data.text())
      ElMessage.error(err.msg || '导出失败')
    } else if (!e?.response) {
      ElMessage.error('网络异常')
    } else {
      ElMessage.error(e.response?.status === 403 ? '无导出权限' : '导出失败')
    }
    return Promise.reject(e)
  }
}

export default request
