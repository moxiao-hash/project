import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WorkspaceArtifactsView from './WorkspaceArtifactsView.vue'
import { roadmapApi } from '@/services/roadmap'
import type { ProjectWorkspace, RoadmapArtifactSummaryItem } from '@/types/roadmap'

vi.mock('@/services/roadmap', () => ({
  roadmapApi: {
    listWorkspaces: vi.fn(),
    registerWorkspace: vi.fn(),
    listArtifacts: vi.fn(),
  },
}))

describe('WorkspaceArtifactsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders workspaces and provides open-results-panel-trigger to reveal real API-backed workspace-results-panel', async () => {
    const mockWorkspaces: ProjectWorkspace[] = [
      {
        id: 'ws-1',
        name: 'StudyPilot Backend',
        rootPath: '/Users/test/project',
        status: 'ACTIVE',
        createdAt: '2026-09-22T08:00:00Z',
      },
    ]

    const mockArtifacts: RoadmapArtifactSummaryItem[] = [
      {
        id: 'artifact-1',
        workspaceId: 'ws-1',
        status: 'EVALUATED',
        submissionVersion: 1,
        roadmapNode: {
          id: 'node-1',
          moduleId: 'mod-1',
          stageId: 'stage-1',
          title: 'Java 基础环境搭建',
          moduleTitle: 'Java 核心',
          stageTitle: '第一阶段',
        },
        rubricScore: 85,
        rubricFeedback: '符合规范，测试全部通过',
        createdAt: '2026-09-22T08:10:00Z',
      },
    ]

    vi.mocked(roadmapApi.listWorkspaces).mockResolvedValue(mockWorkspaces)
    vi.mocked(roadmapApi.listArtifacts).mockResolvedValue(mockArtifacts)

    const wrapper = mount(WorkspaceArtifactsView)
    await flushPromises()

    expect(roadmapApi.listWorkspaces).toHaveBeenCalledTimes(1)
    expect(roadmapApi.listArtifacts).toHaveBeenCalledTimes(1)

    // Trigger exists
    const trigger = wrapper.find('[data-testid="open-results-panel-trigger"]')
    expect(trigger.exists()).toBe(true)

    // Panel is initially not visible / not rendered
    expect(wrapper.find('[data-testid="workspace-results-panel"]').exists()).toBe(false)

    // Click trigger to reveal real results panel
    await trigger.trigger('click')
    await flushPromises()

    const panel = wrapper.find('[data-testid="workspace-results-panel"]')
    expect(panel.exists()).toBe(true)
    expect(panel.isVisible()).toBe(true)

    // Verify privacy-minimized summary fields are rendered
    expect(panel.text()).toContain('Java 基础环境搭建')
    expect(panel.text()).toContain('85 分')
    expect(panel.text()).toContain('符合规范，测试全部通过')
    expect(panel.text()).toContain('EVALUATED')
    expect(panel.text()).toContain('版本 v1')

    // Must NOT contain private details or inferred test evidence
    expect(panel.text()).not.toContain('测试状态：已验证')
  })

  it('renders truthful empty state inside workspace-results-panel when API returns empty 200 list', async () => {
    vi.mocked(roadmapApi.listWorkspaces).mockResolvedValue([])
    vi.mocked(roadmapApi.listArtifacts).mockResolvedValue([])

    const wrapper = mount(WorkspaceArtifactsView)
    await flushPromises()

    const trigger = wrapper.find('[data-testid="open-results-panel-trigger"]')
    expect(trigger.exists()).toBe(true)

    await trigger.trigger('click')
    await flushPromises()

    const panel = wrapper.find('[data-testid="workspace-results-panel"]')
    expect(panel.exists()).toBe(true)
    expect(panel.find('[data-testid="workspace-results-empty"]').exists()).toBe(true)
    expect(panel.find('[data-testid="workspace-results-error"]').exists()).toBe(false)
    expect(panel.text()).toContain('尚未提交实践成果')
  })

  it('renders distinct error/retry state and does not swallow API failures into empty state', async () => {
    vi.mocked(roadmapApi.listWorkspaces).mockResolvedValue([])
    vi.mocked(roadmapApi.listArtifacts).mockRejectedValue(new Error('500 Internal Server Error'))

    const wrapper = mount(WorkspaceArtifactsView)
    await flushPromises()

    const trigger = wrapper.find('[data-testid="open-results-panel-trigger"]')
    await trigger.trigger('click')
    await flushPromises()

    const panel = wrapper.find('[data-testid="workspace-results-panel"]')
    expect(panel.exists()).toBe(true)

    // Must render error state, NOT empty state!
    const errorState = panel.find('[data-testid="workspace-results-error"]')
    expect(errorState.exists()).toBe(true)
    expect(panel.find('[data-testid="workspace-results-empty"]').exists()).toBe(false)
    expect(errorState.text()).toContain('500 Internal Server Error')

    // Click retry button
    const retryBtn = errorState.find('button')
    expect(retryBtn.exists()).toBe(true)

    vi.mocked(roadmapApi.listArtifacts).mockResolvedValue([])
    await retryBtn.trigger('click')
    await flushPromises()

    expect(roadmapApi.listArtifacts).toHaveBeenCalledTimes(2)
    expect(panel.find('[data-testid="workspace-results-empty"]').exists()).toBe(true)
    expect(panel.find('[data-testid="workspace-results-error"]').exists()).toBe(false)
  })
})
