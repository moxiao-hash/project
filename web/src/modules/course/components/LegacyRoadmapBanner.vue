<template>
  <aside class="legacy-roadmap-banner" role="status">
    <div>
      <strong>旧版课程入口</strong>
      <span>现有课程与学习记录仍可使用；新的主学习流程已迁移到路线图。</span>
    </div>
    <RouterLink to="/roadmap">前往 Java + AI 学习路线 →</RouterLink>
  </aside>
</template>

<style scoped>
.legacy-roadmap-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
  padding: 14px 16px;
  border: 1px solid #c7d2fe;
  border-radius: 12px;
  background: #eef2ff;
}

.legacy-roadmap-banner div,
.legacy-roadmap-banner span {
  display: grid;
  gap: 3px;
}

.legacy-roadmap-banner span {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.legacy-roadmap-banner a {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
}

@media (max-width: 720px) {
  .legacy-roadmap-banner {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
