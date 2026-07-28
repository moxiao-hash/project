<template>
  <div class="empty-state">
    <div class="empty-icon">{{ icon }}</div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc">{{ description }}</div>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    icon?: string
    title: string
    description?: string
  }>(),
  { icon: '🗂️', description: '' },
)
</script>

<style scoped>
.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}
.empty-desc {
  margin-top: 4px;
  font-size: 13px;
}
.empty-action {
  margin-top: 14px;
}
</style>
