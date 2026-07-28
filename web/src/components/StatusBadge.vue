<template>
  <span class="badge" :class="badgeClass">{{ label }}</span>
</template>

<script setup lang="ts">
defineProps<{
  label: string
  badgeClass?: string
}>()
</script>
