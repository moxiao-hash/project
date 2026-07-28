<template>
  <div class="error-state">
    <div class="empty-icon">⚠️</div>
    <div class="error-title">{{ title }}</div>
    <div class="error-desc">{{ message }}</div>
    <button v-if="retryable" class="btn btn-secondary" @click="$emit('retry')">
      重试
    </button>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    message: string
    retryable?: boolean
  }>(),
  { title: '加载失败', retryable: true },
)

defineEmits<{ retry: [] }>()
</script>

<style scoped>
.error-state {
  text-align: center;
  padding: 48px 24px;
}
.empty-icon {
  font-size: 36px;
  margin-bottom: 8px;
}
.error-title {
  font-size: 15px;
  font-weight: 600;
}
.error-desc {
  color: var(--color-text-secondary);
  font-size: 13px;
  margin: 6px 0 16px;
}
</style>
