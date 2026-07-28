/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_AGENT_GATEWAY?: 'mock' | 'http'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
