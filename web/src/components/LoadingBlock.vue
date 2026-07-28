<template>
  <div class="loading-block">
    <span class="spinner" />
    <span>{{ text }}</span>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ text?: string }>(), { text: '加载中…' })
</script>
