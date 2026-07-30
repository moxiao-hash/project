<script setup lang="ts">
import { computed, ref } from 'vue'

import type { LessonBlock, LessonCheckpointResult } from '@/types/course'

const props = defineProps<{
  block: LessonBlock
  result?: LessonCheckpointResult | null
  submitting: boolean
}>()
const emit = defineEmits<{ submit: [selectedOption: number] }>()

const selected = ref<number | null>(null)
const canSubmit = computed(() => selected.value !== null && !props.submitting)
</script>

<template>
  <section :id="block.key" class="checkpoint">
    <div class="kicker">CHECKPOINT</div>
    <h2>{{ block.title }}</h2>
    <p class="question">{{ block.question }}</p>
    <label
      v-for="(option, index) in block.options"
      :key="option"
      class="option"
      :class="{ selected: selected === index }"
    >
      <input
        v-model="selected"
        type="radio"
        :value="index"
        :data-test="`checkpoint-option-${index}`"
      />
      <span>{{ option }}</span>
    </label>
    <button
      class="btn btn-primary"
      data-test="submit-checkpoint"
      :disabled="!canSubmit"
      @click="selected !== null && emit('submit', selected)"
    >
      {{ submitting ? '判题中…' : '提交答案' }}
    </button>
    <div
      v-if="result"
      class="checkpoint-result"
      :class="result.correct ? 'correct' : 'incorrect'"
    >
      <strong>{{ result.correct ? '回答正确' : '再想一想' }}</strong>
      <span>{{ result.explanation }}</span>
    </div>
  </section>
</template>

<style scoped>
.checkpoint {
  padding: 24px;
  border: 1px solid #cfd4f7;
  border-radius: 14px;
  background: linear-gradient(140deg, #f4f5ff, #fff);
  scroll-margin-top: 76px;
}

.checkpoint + .checkpoint {
  margin-top: 16px;
}

.kicker {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
}

h2 {
  margin-top: 5px;
}

.question {
  font-weight: 600;
}

.option {
  display: flex;
  gap: 9px;
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: white;
  cursor: pointer;
}

.option.selected {
  border-color: var(--color-primary);
}

.checkpoint .btn {
  margin-top: 8px;
}

.checkpoint-result {
  display: grid;
  gap: 3px;
  margin-top: 12px;
  padding: 12px;
  border-radius: 9px;
}

.checkpoint-result.correct {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.checkpoint-result.incorrect {
  background: var(--color-warning-soft);
  color: #8a5a08;
}
</style>
