import type { PlanDraft } from '@/types/agent'

export function buildPlanRevisionMessage(
  original: PlanDraft,
  edited: PlanDraft,
): string | null {
  const changes: string[] = []
  if (original.title !== edited.title) {
    changes.push(`将计划标题从“${original.title}”改为“${edited.title}”。`)
  }
  if (original.startDate !== edited.startDate) {
    changes.push(`将计划开始日期从 ${original.startDate} 改为 ${edited.startDate}。`)
  }
  if (original.endDate !== edited.endDate) {
    changes.push(`将计划结束日期从 ${original.endDate} 改为 ${edited.endDate}。`)
  }

  const count = Math.max(original.tasks.length, edited.tasks.length)
  for (let index = 0; index < count; index += 1) {
    const before = original.tasks[index]
    const after = edited.tasks[index]
    const number = index + 1
    if (!before && after) {
      changes.push(
        `新增第 ${number} 个任务“${after.title}”，安排在 ${after.scheduledDate}，预计 ${after.estimatedMinutes} 分钟。`,
      )
      continue
    }
    if (before && !after) {
      changes.push(`删除第 ${number} 个任务“${before.title}”。`)
      continue
    }
    if (!before || !after) continue
    if (before.title !== after.title) {
      changes.push(`将第 ${number} 个任务标题从“${before.title}”改为“${after.title}”。`)
    }
    if (before.scheduledDate !== after.scheduledDate) {
      changes.push(
        `将第 ${number} 个任务“${after.title}”的日期从 ${before.scheduledDate} 改为 ${after.scheduledDate}。`,
      )
    }
    if (before.estimatedMinutes !== after.estimatedMinutes) {
      changes.push(
        `将第 ${number} 个任务“${after.title}”的预计时长从 ${before.estimatedMinutes} 分钟改为 ${after.estimatedMinutes} 分钟。`,
      )
    }
  }

  if (changes.length === 0) return null
  return `请按以下明确修改重新生成完整计划草案：\n${changes
    .map((change, index) => `${index + 1}. ${change}`)
    .join('\n')}`
}
