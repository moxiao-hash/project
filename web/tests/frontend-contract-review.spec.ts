import { describe, expect, it, vi } from 'vitest'
import type { Material } from '@/types/api'
import { http } from '@/services/http'
import { HttpAgentGateway } from '@/services/planned/httpGateway'
import goalsSource from '@/modules/learning/GoalsView.vue?raw'
import planDetailSource from '@/modules/learning/PlanDetailView.vue?raw'
import materialsSource from '@/modules/materials/MaterialsView.vue?raw'

describe('HttpAgentGateway Java 公共契约映射', () => {
  it('网页结果导入把 Material.id 映射为 materialId', async () => {
    const material: Material = {
      id: 'material-1',
      title: 'Spring Boot 文档',
      materialType: 'WEB_PAGE',
      category: 'REFERENCE',
      privacyLevel: 'NORMAL',
      sourceUrl: 'https://spring.io',
      originalFilename: null,
      mediaType: 'text/html',
      contentLength: 100,
      processingStatus: 'PENDING',
      summary: null,
      tags: [],
      knowledgePoints: [],
      processingWarnings: [],
      contentReference: null,
      failureReason: null,
    }
    vi.spyOn(http, 'post').mockResolvedValueOnce({ data: material })

    const result = await new HttpAgentGateway()
      .importWebResult('result-1', 'REFERENCE', 'NORMAL')

    expect(result).toEqual({ materialId: 'material-1' })
  })
})

describe('前端输入长度与 Java Bean Validation 保持一致', () => {
  it.each([
    ['GoalsView.vue', goalsSource, 'maxlength="100"'],
    ['PlanDetailView.vue', planDetailSource, 'maxlength="160"'],
    ['MaterialsView.vue', materialsSource, 'maxlength="180"'],
  ])('%s 使用后端允许的最大长度', (_filename, source, expected) => {
    expect(source).toContain(expected)
  })
})
