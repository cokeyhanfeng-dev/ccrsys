import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 独立入口 /mobile/；部署通过同源 Nginx 直连 CCR，浏览器不持有内部地址。
export default defineConfig({
  root: fileURLToPath(new URL('./mobile', import.meta.url)),
  base: '/mobile/',
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 13001, strictPort: true,
    proxy: { '/mobile/api/mobile': { target: process.env.CCR_MOBILE_DEV_UPSTREAM || 'http://127.0.0.1:18080', changeOrigin: true,
      rewrite: path => path.replace(/^\/mobile\/api/, '') } } },
  build: { outDir: '../dist-mobile', emptyOutDir: true }
})
