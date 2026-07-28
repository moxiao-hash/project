<template>
  <Teleport to="body">
    <div v-if="modelValue" class="dialog-mask" @click.self="onCancel">
      <div class="dialog" role="dialog" aria-modal="true">
        <h3 class="dialog-title">{{ title }}</h3>
        <div class="dialog-body">
          <slot />
        </div>
        <div class="dialog-actions">
          <button class="btn btn-secondary" :disabled="loading" @click="onCancel">
            取消
          </button>
          <button
            class="btn"
            :class="danger ? 'btn-danger' : 'btn-primary'"
            :disabled="loading"
            @click="$emit('confirm')"
          >
            <span v-if="loading" class="spinner spinner-light" />
            {{ confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 写操作确认弹窗。confirmText 必须描述明确的对象和动作（如「确认完成该任务」），
 * 不能只写「确定」。普通聊天中的“确认”不能替代该组件。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    confirmText: string
    danger?: boolean
    loading?: boolean
  }>(),
  { danger: false, loading: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  cancel: []
}>()

function onCancel() {
  emit('update:modelValue', false)
  emit('cancel')
}
</script>

<style scoped>
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 18, 30, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 500;
  padding: 20px;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 480px;
  padding: 22px;
}

.dialog-title {
  font-size: 16px;
  margin-bottom: 12px;
}

.dialog-body {
  font-size: 14px;
  color: var(--color-text);
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  width: 14px;
  height: 14px;
}
</style>
