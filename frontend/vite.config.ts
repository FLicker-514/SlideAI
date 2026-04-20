import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  server: {
    proxy: {
      '/api/llm': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      '/api/style': {
        target: 'http://localhost:8084',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      '/api/document': {
        target: 'http://localhost:8085',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')  // /api/document/... -> /document/...
      },
      // template-service 已配置 context-path: /api，需保留 /api 前缀
      '/api/designer': {
        target: 'http://localhost:8086',
        changeOrigin: true
      },
      '/api/template': {
        target: 'http://localhost:8086',
        changeOrigin: true
      },
      // component-service 已配置 context-path: /api，需保留 /api 前缀
      '/api/component': {
        target: 'http://localhost:8087',
        changeOrigin: true
      },
      '/api/rag': {
        target: 'http://localhost:8088',
        changeOrigin: true
      },
      '/api/web-search': {
        target: 'http://localhost:8089',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
