import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, mkdirSync, createReadStream, existsSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

// 独立入口 /mobile/；部署通过同源 Nginx 直连 CCR，浏览器不持有内部地址。
export default defineConfig({
  root: fileURLToPath(new URL('./mobile', import.meta.url)),
  base: '/mobile/',
  plugins: [vue(), {
    name: 'mobile-pdf-assets',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const match = req.url?.split('?')[0].match(/^\/mobile\/pdf\/(cmaps|standard_fonts)\/([a-zA-Z0-9_.-]+)$/)
        if (!match) return next()
        const file = fileURLToPath(new URL(`./node_modules/pdfjs-dist/${match[1]}/${match[2]}`, import.meta.url))
        if (!existsSync(file)) return next()
        res.setHeader('Content-Type', 'application/octet-stream')
        createReadStream(file).pipe(res)
      })
    },
    closeBundle() {
      const target = fileURLToPath(new URL('./dist-mobile/pdf/', import.meta.url))
      mkdirSync(target, { recursive: true })
      cpSync(fileURLToPath(new URL('./mobile/assets/bootstrap-icons/LICENSE', import.meta.url)), `${target}/BOOTSTRAP-ICONS-LICENSE`)
      for (const directory of ['cmaps', 'standard_fonts']) {
        cpSync(fileURLToPath(new URL(`./node_modules/pdfjs-dist/${directory}`, import.meta.url)), `${target}/${directory}`, { recursive: true })
      }
      cpSync(fileURLToPath(new URL('./node_modules/pdfjs-dist/LICENSE', import.meta.url)), `${target}/LICENSE`)
    }
  }],
  server: { host: '127.0.0.1', port: 13001, strictPort: true,
    proxy: { '/mobile/api/mobile': { target: process.env.CCR_MOBILE_DEV_UPSTREAM || 'http://127.0.0.1:18080', changeOrigin: true,
      rewrite: path => path.replace(/^\/mobile\/api/, '') } } },
  build: { outDir: '../dist-mobile', emptyOutDir: true,
    // 使用 .js 扩展名兼容现有 Nginx MIME 表，避免模块 worker 被当作二进制拦截。
    rollupOptions: { output: { assetFileNames: asset => asset.name?.endsWith('.mjs') ? 'assets/[name]-[hash].js' : 'assets/[name]-[hash][extname]' } } }
})
