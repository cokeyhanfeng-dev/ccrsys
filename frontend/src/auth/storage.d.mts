import type { UserInfo } from '../store/user'

export function authStorage(): Storage
export function readToken(): string
export function readUserInfo(): UserInfo | null
export function clearAuth(): void
export function saveAuth(data: { token: string; userInfo: UserInfo }, persistent?: boolean): void
