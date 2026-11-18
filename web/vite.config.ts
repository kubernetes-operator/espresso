import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버: 백엔드 3개 서비스로 프록시 (운영/compose 는 nginx 가 담당)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/auth': 'http://localhost:8081',
      '/api/core': 'http://localhost:8082',
      '/api/stl': 'http://localhost:8083',
    },
  },
  build: { chunkSizeWarningLimit: 4000 },
})
