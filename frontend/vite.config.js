import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: {
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue'],
          icons: ['lucide-vue-next']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.VITE_API_TARGET || 'http://127.0.0.1:18081'
    }
  }
})
