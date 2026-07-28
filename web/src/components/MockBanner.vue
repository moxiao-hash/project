<template>
  <div v-if="gatewayMode === 'mock'" class="mock-banner">
    <span class="mock-badge">Mock</span>
    <span>
      该能力由阶段 8 Java Agent 门面提供，当前为本地 Mock 演示，数据不会写入服务器。
      切换 <code>VITE_AGENT_GATEWAY=http</code> 即可联调真实接口。
    </span>
  </div>
</template>

<script setup lang="ts">
import { gatewayMode } from '@/services/planned'
</script>

<style scoped>
.mock-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--color-warning-soft);
  border: 1px solid #f3ddad;
  color: #92600a;
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-size: 13px;
  margin-bottom: 16px;
}

.mock-badge {
  background: var(--color-warning);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  border-radius: 4px;
  padding: 2px 6px;
  flex-shrink: 0;
}

code {
  background: rgba(0, 0, 0, 0.06);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 12px;
}
</style>
