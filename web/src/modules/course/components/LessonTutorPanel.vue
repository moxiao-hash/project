<script setup lang="ts">
import { ref } from 'vue'

import { teachingApi } from '@/services/course'
import { describeError } from '@/services/http'
import AiMarkdownMessage from '@/components/AiMarkdownMessage.vue'

const props = defineProps<{ lessonId: string }>()

const conversationId = ref('')
const question = ref('')
const sending = ref(false)
const error = ref('')
const rounds = ref<Array<{ question: string; answer: string }>>([])

async function send() {
  const message = question.value.trim()
  if (!message || sending.value) return
  sending.value = true
  error.value = ''
  try {
    if (!conversationId.value) {
      const created = await teachingApi.createConversation(props.lessonId)
      conversationId.value = created.conversationId
    }
    const snapshot = await teachingApi.sendMessage(conversationId.value, message)
    rounds.value.push({ question: message, answer: snapshot.answer })
    question.value = ''
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <aside class="tutor">
    <div class="tutor-heading">
      <div class="tutor-avatar">AI</div>
      <div>
        <strong>课内导师</strong>
        <div>只围绕当前课时答疑</div>
      </div>
    </div>
    <div class="tutor-intro">
      可以问“为什么要用 DTO？”或让我换一种方式解释。导师不会假装看过视频。
    </div>
    <div class="rounds">
      <template v-for="(round, index) in rounds" :key="index">
        <div class="student">{{ round.question }}</div>
        <div class="assistant"><AiMarkdownMessage :content="round.answer" /></div>
      </template>
      <div v-if="sending" class="assistant">正在结合本课内容思考…</div>
    </div>
    <div v-if="error" class="alert alert-danger">{{ error }}</div>
    <form class="tutor-form" @submit.prevent="send">
      <textarea
        v-model="question"
        class="textarea"
        rows="3"
        placeholder="问一个当前课时的问题…"
      />
      <button class="btn btn-primary" type="submit" :disabled="sending || !question.trim()">
        {{ sending ? '回答中…' : '提问' }}
      </button>
    </form>
  </aside>
</template>

<style scoped>
.tutor {
  position: sticky;
  top: 76px;
  display: flex;
  max-height: calc(100vh - 96px);
  flex-direction: column;
  padding: 16px;
  border: 1px solid #dfe2f8;
  border-radius: 14px;
  background: linear-gradient(180deg, #f8f8ff, #fff);
}

.tutor-heading {
  display: flex;
  gap: 10px;
  align-items: center;
}

.tutor-heading div:last-child div {
  color: var(--color-text-secondary);
  font-size: 11px;
}

.tutor-avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: var(--color-primary);
  color: white;
  font-size: 11px;
  font-weight: 800;
}

.tutor-intro {
  margin: 14px 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.rounds {
  display: grid;
  gap: 8px;
  overflow-y: auto;
}

.student,
.assistant {
  padding: 9px 10px;
  border-radius: 9px;
  font-size: 12px;
}

.student {
  margin-left: 18px;
  background: var(--color-primary);
  color: white;
}

.assistant {
  margin-right: 12px;
  background: white;
  border: 1px solid var(--color-border);
}

.tutor-form {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}
</style>
