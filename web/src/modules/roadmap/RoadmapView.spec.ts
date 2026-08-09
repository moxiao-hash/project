import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import NodeView from './NodeView.vue'
import RoadmapView from './RoadmapView.vue'
import StageView from './StageView.vue'
import RoadmapNodeCard from './components/RoadmapNodeCard.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapMap, RoadmapNode, RoadmapStage } from '@/types/roadmap'

vi.mock('@/services/roadmap', () => ({
  roadmapApi: {
    enroll: vi.fn(),
    getCurrentMap: vi.fn(),
    getStage: vi.fn(),
    getNode: vi.fn(),
  },
}))

function node(overrides: Partial<RoadmapNode> = {}): RoadmapNode {
  return {
    id: 'node-java',
    code: 'java-syntax-oop',
    order: 1,
    title: 'Java 语法、面向对象与代码规范',
    objectives: ['能够编写结构清晰的 Java 类'],
    highFrequency: ['封装、继承与多态'],
    commonMistakes: ['把继承当作代码复用的默认方式'],
    searchKeywords: ['黑马程序员 Java 面向对象'],
    estimatedMinutes: 60,
    practiceMinutes: 45,
    difficulty: 'EASY',
    required: true,
    prerequisiteCodes: [],
    availabilityStatus: 'AVAILABLE',
    learningStatus: 'NOT_STARTED',
    checkInStatus: 'MISSING',
    quizStatus: 'NOT_GENERATED',
    artifactStatus: 'NOT_REQUIRED',
    completionStatus: 'INCOMPLETE',
    displayStatus: 'AVAILABLE',
    version: 0,
    ...overrides,
  }
}

function stage(overrides: Partial<RoadmapStage> = {}): RoadmapStage {
  return {
    id: 'stage-java',
    code: 'java-core',
    order: 1,
    title: 'Java 核心与工程基础',
    description: '掌握传统后端所需的 Java 基础',
    graduationProjectTitle: 'Java 命令行学习记录器',
    completedRequiredNodes: 0,
    totalRequiredNodes: 2,
    nodes: [
      node(),
      node({
        id: 'node-collections',
        code: 'java-collections-generics',
        order: 2,
        title: '集合、泛型与常用工具类',
        prerequisiteCodes: ['java-syntax-oop'],
        availabilityStatus: 'LOCKED',
        displayStatus: 'LOCKED',
        required: false,
      }),
    ],
    ...overrides,
  }
}

function fixtureRoadmap(): RoadmapMap {
  return {
    enrollmentId: 'enrollment-1',
    roadmapCode: 'studypilot-java-ai',
    templateVersion: 1,
    title: 'StudyPilot Java + AI 学习路线',
    description: '从传统 Java 后端到可操作项目的 Agent',
    completedRequiredNodes: 0,
    totalRequiredNodes: 2,
    stages: [stage()],
  }
}

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/roadmap', component: { template: '<div />' } },
      { path: '/roadmap/stages/:id', name: 'roadmap-stage', component: { template: '<div />' } },
      { path: '/roadmap/nodes/:id', name: 'roadmap-node', component: { template: '<div />' } },
    ],
  })
}

async function mountAt(component: object, path = '/roadmap') {
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  return { wrapper: mount(component, { global: { plugins: [router] } }), router }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('roadmap read views', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows graph and semantic list views from the same model with an accessible toggle', async () => {
    vi.mocked(roadmapApi.getCurrentMap).mockResolvedValue(fixtureRoadmap())
    const { wrapper } = await mountAt(RoadmapView)
    await flushPromises()

    const graphToggle = wrapper.get('button[aria-controls="roadmap-graph"]')
    const listToggle = wrapper.get('button[aria-controls="roadmap-list"]')
    expect(graphToggle.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="roadmap-graph"]').text()).toContain('Java 语法')
    expect(wrapper.text()).toContain('已完成 0 / 2 个必修节点')

    await listToggle.trigger('click')
    expect(listToggle.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('#roadmap-list').element.tagName).toBe('SECTION')
    expect(wrapper.get('#roadmap-list').find('ol').exists()).toBe(true)
    expect(wrapper.get('#roadmap-list').text()).toContain('Java 语法')
  })

  it('renders visible statuses, optional styling, and links only unlocked nodes', async () => {
    const router = makeRouter()
    await router.push('/roadmap')
    await router.isReady()

    const available = mount(RoadmapNodeCard, {
      props: { node: node() },
      global: { plugins: [router] },
    })
    const locked = mount(RoadmapNodeCard, {
      props: { node: stage().nodes[1], compact: true },
      global: { plugins: [router] },
    })

    expect(available.get('a').attributes('href')).toBe('/roadmap/nodes/node-java')
    expect(available.text()).toContain('可开始')
    expect(locked.find('a').exists()).toBe(false)
    expect(locked.text()).toContain('已锁定')
    expect(locked.text()).toContain('完成前置节点后解锁：java-syntax-oop')
    expect(locked.classes()).toContain('roadmap-node-card--optional')
    expect(locked.classes()).toContain('roadmap-node-card--compact')
  })

  it('shows a retryable error and prevents duplicate map requests while loading', async () => {
    const pending = deferred<RoadmapMap>()
    vi.mocked(roadmapApi.getCurrentMap)
      .mockRejectedValueOnce(new Error('offline'))
      .mockReturnValueOnce(pending.promise)
    const { wrapper } = await mountAt(RoadmapView)
    await flushPromises()

    expect(wrapper.text()).toContain('学习路线加载失败')
    const retry = wrapper.get('[data-testid="roadmap-retry"]')
    await retry.trigger('click')
    await retry.trigger('click')
    expect(roadmapApi.getCurrentMap).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('正在整理学习路线')
    pending.resolve(fixtureRoadmap())
    await flushPromises()
  })

  it('requires explicit enrollment, prevents duplicate enrollment, then reloads the map', async () => {
    const notFound = { response: { status: 404 } }
    const enrollment = deferred<Awaited<ReturnType<typeof roadmapApi.enroll>>>()
    vi.mocked(roadmapApi.getCurrentMap)
      .mockRejectedValueOnce(notFound)
      .mockResolvedValueOnce(fixtureRoadmap())
    vi.mocked(roadmapApi.enroll).mockReturnValue(enrollment.promise)
    const { wrapper } = await mountAt(RoadmapView)
    await flushPromises()

    expect(roadmapApi.enroll).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('开启 Java + AI 学习路线')
    const button = wrapper.get('[data-testid="roadmap-enroll"]')
    await button.trigger('click')
    await button.trigger('click')
    expect(roadmapApi.enroll).toHaveBeenCalledTimes(1)
    expect(button.attributes()).toHaveProperty('disabled')

    enrollment.resolve({
      id: 'enrollment-1', roadmapCode: 'studypilot-java-ai', templateVersion: 1,
      title: 'StudyPilot Java + AI 学习路线', status: 'ACTIVE', enrolledAt: '2026-08-09T10:00:00Z',
    })
    await flushPromises()
    expect(roadmapApi.getCurrentMap).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('StudyPilot Java + AI 学习路线')
  })

  it('keeps the enrollment empty state when enrollment fails', async () => {
    vi.mocked(roadmapApi.getCurrentMap).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(roadmapApi.enroll).mockRejectedValue(new Error('enroll failed'))
    const { wrapper } = await mountAt(RoadmapView)
    await flushPromises()
    await wrapper.get('[data-testid="roadmap-enroll"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('开启失败')
    expect(wrapper.find('[data-testid="roadmap-enroll"]').exists()).toBe(true)
  })

  it('does not reload the roadmap when enrollment finishes after unmount', async () => {
    const pendingEnrollment = deferred<Awaited<ReturnType<typeof roadmapApi.enroll>>>()
    vi.mocked(roadmapApi.getCurrentMap).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(roadmapApi.enroll).mockReturnValue(pendingEnrollment.promise)
    const { wrapper } = await mountAt(RoadmapView)
    await flushPromises()
    await wrapper.get('[data-testid="roadmap-enroll"]').trigger('click')
    wrapper.unmount()

    pendingEnrollment.resolve({
      id: 'enrollment-1', roadmapCode: 'studypilot-java-ai', templateVersion: 1,
      title: 'StudyPilot Java + AI 学习路线', status: 'ACTIVE', enrolledAt: '2026-08-09T10:00:00Z',
    })
    await flushPromises()

    expect(roadmapApi.getCurrentMap).toHaveBeenCalledTimes(1)
  })

  it('loads a stage by route id and exposes its learning contract', async () => {
    vi.mocked(roadmapApi.getStage).mockResolvedValue(stage())
    const { wrapper } = await mountAt(StageView, '/roadmap/stages/stage-java')
    await flushPromises()

    expect(roadmapApi.getStage).toHaveBeenCalledWith('stage-java')
    expect(wrapper.text()).toContain('掌握传统后端所需的 Java 基础')
    expect(wrapper.text()).toContain('必修进度 0 / 2')
    expect(wrapper.text()).toContain('毕业项目：Java 命令行学习记录器')
    expect(wrapper.text()).toContain('集合、泛型')
  })

  it('ignores a stale stage response after the route changes', async () => {
    const first = deferred<RoadmapStage>()
    vi.mocked(roadmapApi.getStage)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(stage({ id: 'stage-spring', title: 'Spring Boot 工程实战' }))
    const { wrapper, router } = await mountAt(StageView, '/roadmap/stages/stage-java')
    await router.push('/roadmap/stages/stage-spring')
    await flushPromises()
    first.resolve(stage())
    await flushPromises()

    expect(wrapper.text()).toContain('Spring Boot 工程实战')
    expect(wrapper.text()).not.toContain('Java 核心与工程基础')
  })

  it('loads a node by route id and shows all study guidance without mutation actions', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node({ prerequisiteCodes: ['java-basics'] }))
    vi.mocked(roadmapApi.getCurrentMap).mockResolvedValue({
      ...fixtureRoadmap(),
      stages: [stage({ nodes: [node({ id: 'node-basics', code: 'java-basics' })] })],
    })
    const { wrapper } = await mountAt(NodeView, '/roadmap/nodes/node-java')
    await flushPromises()

    expect(roadmapApi.getNode).toHaveBeenCalledWith('node-java')
    expect(wrapper.text()).toContain('能够编写结构清晰的 Java 类')
    expect(wrapper.text()).toContain('封装、继承与多态')
    expect(wrapper.text()).toContain('把继承当作代码复用的默认方式')
    expect(wrapper.text()).toContain('黑马程序员 Java 面向对象')
    expect(wrapper.get('[data-testid="node-prerequisites"] a').attributes('href'))
      .toBe('/roadmap/nodes/node-basics')
    expect(wrapper.text()).toContain('打卡、测验和必交成果全部满足后才完成节点')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('resolves prerequisite codes to current-roadmap node ids and never creates bogus links', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node({
      id: 'node-advanced',
      prerequisiteCodes: ['java-syntax-oop', 'retired-node-code'],
    }))
    vi.mocked(roadmapApi.getCurrentMap).mockResolvedValue(fixtureRoadmap())
    const { wrapper } = await mountAt(NodeView, '/roadmap/nodes/node-advanced')
    await flushPromises()

    const prerequisites = wrapper.get('[data-testid="node-prerequisites"]')
    expect(prerequisites.findAll('a')).toHaveLength(1)
    expect(prerequisites.get('a').attributes('href')).toBe('/roadmap/nodes/node-java')
    expect(prerequisites.text()).toContain('retired-node-code')
    expect(prerequisites.text()).toContain('当前路线中未找到该前置节点')
  })

  it('keeps node details readable when prerequisite resolution is unavailable', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node({ prerequisiteCodes: ['java-basics'] }))
    vi.mocked(roadmapApi.getCurrentMap).mockRejectedValue(new Error('map unavailable'))
    const { wrapper } = await mountAt(NodeView, '/roadmap/nodes/node-java')
    await flushPromises()

    expect(wrapper.text()).toContain('Java 语法、面向对象与代码规范')
    expect(wrapper.get('[data-testid="node-prerequisites"]').find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('前置节点链接暂时无法解析')
  })

  it('renders primary node content without waiting for optional prerequisite resolution', async () => {
    const pendingMap = deferred<RoadmapMap>()
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node({ prerequisiteCodes: ['java-basics'] }))
    vi.mocked(roadmapApi.getCurrentMap).mockReturnValue(pendingMap.promise)
    const { wrapper } = await mountAt(NodeView, '/roadmap/nodes/node-java')
    await flushPromises()

    expect(wrapper.text()).toContain('Java 语法、面向对象与代码规范')
    expect(wrapper.text()).not.toContain('正在加载学习节点')
    expect(wrapper.text()).toContain('前置节点链接暂时无法解析')
  })

  it('does not fetch the roadmap map when a node has no prerequisites', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node({ prerequisiteCodes: [] }))
    const { wrapper } = await mountAt(NodeView, '/roadmap/nodes/node-java')
    await flushPromises()

    expect(wrapper.text()).toContain('无，可直接开始')
    expect(roadmapApi.getCurrentMap).not.toHaveBeenCalled()
  })

  it('does not apply prerequisite links resolved for a previous route', async () => {
    const oldMap = deferred<RoadmapMap>()
    vi.mocked(roadmapApi.getNode)
      .mockResolvedValueOnce(node({ id: 'node-old', prerequisiteCodes: ['java-syntax-oop'] }))
      .mockResolvedValueOnce(node({ id: 'node-new', title: '新节点', prerequisiteCodes: [] }))
    vi.mocked(roadmapApi.getCurrentMap).mockReturnValue(oldMap.promise)
    const { wrapper, router } = await mountAt(NodeView, '/roadmap/nodes/node-old')
    await flushPromises()
    await router.push('/roadmap/nodes/node-new')
    await flushPromises()
    oldMap.resolve(fixtureRoadmap())
    await flushPromises()

    expect(wrapper.text()).toContain('新节点')
    expect(wrapper.get('[data-testid="node-prerequisites"]').find('a').exists()).toBe(false)
    expect(roadmapApi.getCurrentMap).toHaveBeenCalledTimes(1)
  })

  it('renders stable not-found states for missing stage and node', async () => {
    vi.mocked(roadmapApi.getStage).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(roadmapApi.getNode).mockRejectedValue({ response: { status: 404 } })
    const stageView = await mountAt(StageView, '/roadmap/stages/missing-stage')
    const nodeView = await mountAt(NodeView, '/roadmap/nodes/missing-node')
    await flushPromises()

    expect(stageView.wrapper.text()).toContain('未找到该路线阶段')
    expect(nodeView.wrapper.text()).toContain('未找到该学习节点')
  })
})
