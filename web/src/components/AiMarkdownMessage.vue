<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps<{ content: string }>()

const root = ref<HTMLElement | null>(null)
const resetTimers = new Set<number>()

const safeHtml = computed(() =>
  DOMPurify.sanitize(marked.parse(props.content, {
    async: false,
    breaks: true,
    gfm: true,
  })),
)

function codeLanguage(code: HTMLElement): string {
  const languageClass = Array.from(code.classList)
    .find((name) => name.startsWith('language-'))
  const candidate = languageClass?.slice('language-'.length) ?? ''
  return /^[a-z0-9_+#.-]+$/i.test(candidate) ? candidate : '代码'
}

async function decorateRenderedMarkdown() {
  await nextTick()
  if (!root.value) return

  root.value.querySelectorAll<HTMLAnchorElement>('a[href]').forEach((anchor) => {
    const url = new URL(anchor.href, window.location.href)
    if ((url.protocol === 'http:' || url.protocol === 'https:')
      && url.origin !== window.location.origin) {
      anchor.target = '_blank'
      anchor.rel = 'noopener noreferrer'
    }
  })

  root.value.querySelectorAll<HTMLPreElement>('pre').forEach((pre) => {
    if (pre.parentElement?.classList.contains('ai-code-block')) return
    const code = pre.querySelector<HTMLElement>('code')
    if (!code) return

    const label = codeLanguage(code)
    const wrapper = document.createElement('div')
    wrapper.className = 'ai-code-block'
    const toolbar = document.createElement('div')
    toolbar.className = 'ai-code-toolbar'
    const language = document.createElement('span')
    language.textContent = label
    const button = document.createElement('button')
    button.type = 'button'
    button.dataset.copyCode = ''
    button.setAttribute('aria-label', `复制 ${label} 代码`)
    button.textContent = '复制'
    toolbar.append(language, button)
    pre.parentNode?.insertBefore(wrapper, pre)
    wrapper.append(toolbar, pre)
  })
}

async function copyCode(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Element) || !root.value) return
  const button = target.closest<HTMLButtonElement>('[data-copy-code]')
  if (!button || !root.value.contains(button)) return
  const code = button.closest('.ai-code-block')?.querySelector('code')
  if (!code) return

  try {
    await navigator.clipboard.writeText((code.textContent ?? '').replace(/\n$/, ''))
    button.textContent = '已复制'
  } catch {
    button.textContent = '复制失败'
  }

  const timer = window.setTimeout(() => {
    if (button.isConnected) button.textContent = '复制'
    resetTimers.delete(timer)
  }, 1600)
  resetTimers.add(timer)
}

watch(safeHtml, decorateRenderedMarkdown, { immediate: true, flush: 'post' })

onBeforeUnmount(() => {
  resetTimers.forEach((timer) => window.clearTimeout(timer))
  resetTimers.clear()
})
</script>

<template>
  <div
    ref="root"
    class="ai-markdown-message"
    v-html="safeHtml"
    @click="copyCode"
  />
</template>

<style scoped>
.ai-markdown-message {
  min-width: 0;
  overflow-wrap: anywhere;
  line-height: 1.7;
}

.ai-markdown-message :deep(> :first-child) { margin-top: 0; }
.ai-markdown-message :deep(> :last-child) { margin-bottom: 0; }

.ai-markdown-message :deep(h1),
.ai-markdown-message :deep(h2),
.ai-markdown-message :deep(h3),
.ai-markdown-message :deep(h4) {
  margin: 1.15em 0 .55em;
  line-height: 1.35;
}

.ai-markdown-message :deep(h1) { font-size: 1.45em; }
.ai-markdown-message :deep(h2) { font-size: 1.3em; }
.ai-markdown-message :deep(h3) { font-size: 1.15em; }

.ai-markdown-message :deep(p),
.ai-markdown-message :deep(ul),
.ai-markdown-message :deep(ol),
.ai-markdown-message :deep(blockquote),
.ai-markdown-message :deep(table) {
  margin: .65em 0;
}

.ai-markdown-message :deep(ul),
.ai-markdown-message :deep(ol) { padding-left: 1.5em; }
.ai-markdown-message :deep(li + li) { margin-top: .25em; }

.ai-markdown-message :deep(blockquote) {
  padding: .15em 0 .15em 1em;
  border-left: 3px solid var(--color-primary);
  color: var(--color-text-secondary);
}

.ai-markdown-message :deep(a) {
  color: var(--color-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.ai-markdown-message :deep(code) {
  padding: .14em .35em;
  border-radius: 5px;
  background: rgba(68, 73, 94, .1);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: .9em;
}

.ai-markdown-message :deep(.ai-code-block) {
  margin: .85em 0;
  overflow: hidden;
  border: 1px solid #30384b;
  border-radius: 10px;
  background: #151925;
}

.ai-markdown-message :deep(.ai-code-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid #30384b;
  color: #aeb6cc;
  font-size: 11px;
}

.ai-markdown-message :deep(.ai-code-toolbar button) {
  padding: 3px 7px;
  border: 0;
  background: transparent;
  color: #dce2f2;
  cursor: pointer;
  font: inherit;
}

.ai-markdown-message :deep(pre) {
  margin: 0;
  padding: 14px;
  overflow-x: auto;
}

.ai-markdown-message :deep(pre code) {
  padding: 0;
  background: transparent;
  color: #e8ecf5;
  white-space: pre;
}

.ai-markdown-message :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
}

.ai-markdown-message :deep(th),
.ai-markdown-message :deep(td) {
  padding: 7px 10px;
  border: 1px solid var(--color-border);
  text-align: left;
}

.ai-markdown-message :deep(hr) {
  margin: 1em 0;
  border: 0;
  border-top: 1px solid var(--color-border);
}
</style>
