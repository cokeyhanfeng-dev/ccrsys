export function parseCodeCallback(href: string): {
  code: string
  valid: boolean
  cleanUrl: string
  target: string
} | null

export function bootstrapCodeLogin(options: {
  href: string
  replace: (url: string) => void
  login: (code: string) => Promise<void>
  clear: () => void
}): Promise<string>
