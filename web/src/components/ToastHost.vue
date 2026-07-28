<template>
  <Teleport to="body">
    <div class="toast-host">
      <TransitionGroup name="toast">
        <div
          v-for="toast in store.toasts"
          :key="toast.id"
          class="toast"
          :class="`toast-${toast.type}`"
          @click="store.dismiss(toast.id)"
        >
          {{ toast.message }}
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { useToastStore } from '@/stores/toast'

const store = useToastStore()
</script>

<style scoped>
.toast-host {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 360px;
}

.toast {
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 13px;
  box-shadow: var(--shadow-lg);
  cursor: pointer;
  background: var(--color-surface);
  border-left: 4px solid var(--color-info);
}

.toast-success { border-left-color: var(--color-success); }
.toast-error { border-left-color: var(--color-danger); }
.toast-warning { border-left-color: var(--color-warning); }

.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
