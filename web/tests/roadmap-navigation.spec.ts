import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http } from '@/services/http'
import { roadmapApi } from '@/services/roadmap'
import { courseApi } from '@/services/course'
import router from '@/app/router'
import AppShell from '@/components/AppShell.vue'
import RoadmapView from '@/modules/roadmap/RoadmapView.vue'
import StageView from '@/modules/roadmap/StageView.vue'
import ModuleView from '@/modules/roadmap/ModuleView.vue'
import NodeView from '@/modules/roadmap/NodeView.vue'
import CourseCatalogView from '@/modules/course/CourseCatalogView.vue'
import CourseDetailView from '@/modules/course/CourseDetailView.vue'
import LessonView from '@/modules/course/LessonView.vue'
import LegacyRoadmapBanner from '@/modules/course/components/LegacyRoadmapBanner.vue'
import appShellSource from '@/components/AppShell.vue?raw'
import courseCatalogSource from '@/modules/course/CourseCatalogView.vue?raw'
import courseDetailSource from '@/modules/course/CourseDetailView.vue?raw'
import lessonSource from '@/modules/course/LessonView.vue?raw'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapNode,
  RoadmapStage,
  RoadmapUpgrade,
} from '@/types/roadmap'

const node: RoadmapNode = {
  id: 'node/1',
  code: 'java-syntax-oop',
  order: 1,
  title: 'Java 语法与面向对象',
  objectives: ['掌握 Java 基础语法'],
  highFrequency: ['集合与异常处理'],
  commonMistakes: ['混淆 == 与 equals'],
  searchKeywords: ['Java OOP'],
  estimatedMinutes: 180,
  practiceMinutes: 90,
  difficulty: 'EASY',
  required: true,
  prerequisiteCodes: [],
  availabilityStatus: 'AVAILABLE',
  learningStatus: 'NOT_STARTED',
  checkInStatus: 'MISSING',
  quizStatus: 'NOT_GENERATED',
  artifactStatus: 'NOT_REQUIRED',
  completionStatus: 'INCOMPLETE',
  diagnosticMastered: false,
  displayStatus: 'AVAILABLE',
  version: 0,
}

const stage: RoadmapStage = {
  id: 'stage/1',
  code: 'java-core',
  order: 1,
  title: 'Java 核心基础',
  description: '建立传统 Java 后端开发基础',
  graduationProjectTitle: '命令行学习记录器',
  completedRequiredNodes: 0,
  totalRequiredNodes: 1,
  modules: [],
  nodes: [node],
}

const map: RoadmapMap = {
  enrollmentId: 'enrollment-1',
  roadmapCode: 'studypilot-java-ai',
  templateVersion: 1,
  title: 'Java + AI 全栈学习路线',
  description: '从 Java 后端基础走向可治理的 Python Agent',
  completedRequiredNodes: 0,
  totalRequiredNodes: 64,
  stages: [stage],
}

const enrollment: RoadmapEnrollment = {
  id: 'enrollment-1',
  roadmapCode: 'studypilot-java-ai',
  templateVersion: 1,
  title: 'Java + AI 全栈学习路线',
  status: 'ACTIVE',
  enrolledAt: '2026-08-09T08:00:00Z',
}

const upgrade: RoadmapUpgrade = {
  id: 'upgrade/1',
  sourceVersion: 1,
  targetVersion: 2,
  status: 'PREVIEW',
  unchangedNodeCodes: [],
  addedNodeCodes: ['java-environment-first-program'],
  removedNodeCodes: ['java-syntax-oop'],
  manualReviewNodeCodes: [],
  addedModuleCount: 24,
  removedModuleCount: 0,
  changedModuleCount: 0,
}

describe('roadmapApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('enrolls through the Java public API with only the roadmap identity', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: enrollment })

    await expect(roadmapApi.enroll()).resolves.toEqual(enrollment)

    expect(post).toHaveBeenCalledExactlyOnceWith('/api/roadmap-enrollments', {
      roadmapCode: 'studypilot-java-ai',
      templateVersion: 2,
    })
  })

  it('loads available upgrades through the Java public API', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: [upgrade] })

    await expect(roadmapApi.getUpgrades()).resolves.toEqual([upgrade])

    expect(get).toHaveBeenCalledExactlyOnceWith('/api/roadmaps/current/upgrades')
  })

  it('confirms an encoded upgrade id through its dedicated Java endpoint', async () => {
    const completed = { ...upgrade, status: 'COMPLETED' as const }
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: completed })

    await expect(roadmapApi.confirmUpgrade('upgrade/1')).resolves.toEqual(completed)

    expect(post).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/upgrades/upgrade%2F1/confirm',
    )
  })

  it('loads the current map only through the Java public API', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: map })

    await expect(roadmapApi.getCurrentMap()).resolves.toEqual(map)

    expect(get).toHaveBeenCalledExactlyOnceWith('/api/roadmaps/current/map')
  })

  it('encodes the stage ID and returns the response data', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: stage })

    await expect(roadmapApi.getStage('stage/1 with space')).resolves.toEqual(stage)

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/stages/stage%2F1%20with%20space',
    )
  })

  it('encodes the module ID and returns the response data', async () => {
    const module = {
      id: 'module/1',
      stageId: 'stage/1',
      code: 'java-language-start',
      order: 1,
      title: 'Java 语言起步',
      description: '从环境搭建到数组遍历',
      completedRequiredNodes: 1,
      totalRequiredNodes: 7,
      displayStatus: 'IN_PROGRESS',
      milestoneNode: node,
      nodes: [node],
    }
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: module })

    await expect(roadmapApi.getModule('module/1 with space')).resolves.toEqual(module)

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/modules/module%2F1%20with%20space',
    )
  })

  it('encodes the node ID and returns the response data', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: node })

    await expect(roadmapApi.getNode('node/1 with space')).resolves.toEqual(node)

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/nodes/node%2F1%20with%20space',
    )
  })

  it('propagates a rejected HTTP promise unchanged', async () => {
    const failure = new Error('roadmap unavailable')
    vi.spyOn(http, 'get').mockRejectedValueOnce(failure)

    await expect(roadmapApi.getCurrentMap()).rejects.toBe(failure)
  })
})

describe('production roadmap routes', () => {
  it('makes module pages reachable with the shared id route parameter', () => {
    const resolved = router.resolve('/roadmap/modules/module-1')

    expect(resolved.name).toBe('roadmap-module')
    expect(resolved.meta.title).toBe('路线模块')
    expect(resolved.params).toEqual({ id: 'module-1' })
    expect(typeof resolved.matched.at(-1)?.components?.default).toBe('function')
    expect(router.resolve({ name: 'roadmap-module', params: { id: 'module-1' } }).href)
      .toBe('/roadmap/modules/module-1')
  })

  const cases = [
    ['/roadmap', 'roadmap', 'Java + AI 学习路线', RoadmapView, undefined],
    ['/roadmap/stages/stage-1', 'roadmap-stage', '路线阶段', StageView, 'stage-1'],
    ['/roadmap/modules/module-1', 'roadmap-module', '路线模块', ModuleView, 'module-1'],
    ['/roadmap/nodes/node-1', 'roadmap-node', '知识节点', NodeView, 'node-1'],
  ] as const

  it.each(cases)('resolves %s to the intended lazy page', async (path, name, title, view, id) => {
    const resolved = router.resolve(path)
    const record = resolved.matched.at(-1)
    const loader = record?.components?.default

    expect(resolved.name).toBe(name)
    expect(resolved.meta.title).toBe(title)
    if (id) expect(resolved.params.id).toBe(id)
    expect(typeof loader).toBe('function')
    await expect((loader as () => Promise<{ default: unknown }>)()).resolves.toMatchObject({
      default: view,
    })
  })

  it.each([
    ['roadmap-stage', 'stage-1', '/roadmap/stages/stage-1'],
    ['roadmap-module', 'module-1', '/roadmap/modules/module-1'],
    ['roadmap-node', 'node-1', '/roadmap/nodes/node-1'],
  ] as const)('generates %s links from the shared id parameter', (name, id, href) => {
    expect(() => router.resolve({ name, params: { id } })).not.toThrow()
    const resolved = router.resolve({ name, params: { id } })

    expect(resolved.href).toBe(href)
    expect(resolved.params).toEqual({ id })
  })

  it.each([
    ['/courses', 'courses', undefined, undefined],
    ['/courses/java-ai', 'course-detail', 'slug', 'java-ai'],
    ['/lessons/lesson-1', 'lesson', 'lessonId', 'lesson-1'],
  ] as const)('keeps legacy route %s reachable with its original identity', (path, name, param, value) => {
    const resolved = router.resolve(path)

    expect(resolved.name).toBe(name)
    expect(resolved.meta.legacy).toBe(true)
    expect(resolved.meta.public).toBeUndefined()
    expect(resolved.matched.at(-1)?.name).toBe(name)
    if (param) expect(resolved.params[param]).toBe(value)
  })
})

describe('roadmap-first navigation', () => {
  it('renders the exact overview and learning navigation while highlighting nested roadmap pages', async () => {
    const shellRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/roadmap/nodes/:nodeId', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await shellRouter.push('/roadmap/nodes/node-1')
    await shellRouter.isReady()

    const wrapper = mount(AppShell, {
      global: { plugins: [createPinia(), shellRouter] },
    })
    const groups = wrapper.findAll('.nav-group-title')

    function groupLinks(groupIndex: number) {
      const links: string[] = []
      let sibling = groups[groupIndex].element.nextElementSibling
      while (sibling && !sibling.classList.contains('nav-group-title')) {
        if (sibling.matches('a.nav-item')) links.push(sibling.getAttribute('href') ?? '')
        sibling = sibling.nextElementSibling
      }
      return links
    }

    expect(groups.map((group) => group.text())).toEqual(['总览', '学习', 'AI 助手'])
    expect(groupLinks(0)).toEqual(['/dashboard', '/notifications'])
    expect(groupLinks(1)).toEqual([
      '/roadmap', '/today', '/materials', '/wrong-questions', '/mastery',
    ])
    expect(wrapper.find('a[href="/wrong-questions"]').text()).toContain('错题集')
    expect(wrapper.find('a[href="/"]').text()).toContain('Agent 首页')
    expect(wrapper.findAll('a[href="/today"]')).toHaveLength(1)
    expect(wrapper.find('a[href="/courses"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/roadmap"]').classes()).toContain('active')
    wrapper.findAll('.sidebar a.nav-item').forEach((link) => {
      const visibleLabel = link.find('span:not(.nav-icon):not(.nav-mock)').text()
      expect(link.attributes('aria-label')).toBe(visibleLabel)
      expect(link.get('.nav-icon').attributes('aria-hidden')).toBe('true')
    })
  })

  it('keeps the navigation declaration free of hidden course and duplicate today entries', () => {
    expect(appShellSource.match(/to: '\/today'/g)).toHaveLength(1)
    expect(appShellSource).not.toContain("to: '/courses'")
  })
})

describe('legacy course entry guidance', () => {
  const legacySources = [courseCatalogSource, courseDetailSource, lessonSource]

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reuses the legacy roadmap banner in all three views without removing their APIs', () => {
    legacySources.forEach((source) => {
      expect(source).toContain('<LegacyRoadmapBanner />')
      expect(source).not.toContain('.legacy-roadmap-banner')
    })
    expect(courseCatalogSource).toContain('courseApi.listCourses()')
    expect(courseDetailSource).toContain('courseApi.getCourse(')
    expect(lessonSource).toContain('courseApi.updateProgress(')
    expect(lessonSource).toContain('courseApi.submitCheckpoint(')
    expect(lessonSource).toContain('courseApi.generateLessonQuiz(')
  })

  it('exposes one visible status message linking to the current roadmap', async () => {
    const bannerRouter = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/roadmap', component: { template: '<div />' } }],
    })
    await bannerRouter.push('/roadmap')
    await bannerRouter.isReady()

    const wrapper = mount(LegacyRoadmapBanner, {
      global: { plugins: [bannerRouter] },
    })

    expect(wrapper.get('[role="status"]').isVisible()).toBe(true)
    expect(wrapper.text()).toContain('旧版课程入口')
    expect(wrapper.text()).toContain('现有课程与学习记录仍可使用')
    expect(wrapper.get('a').text()).toBe('前往 Java + AI 学习路线 →')
    expect(wrapper.get('a').attributes('href')).toBe('/roadmap')
  })

  it.each([
    ['/courses', '/courses', CourseCatalogView, 'listCourses', undefined],
    ['/courses/:slug', '/courses/java-ai', CourseDetailView, 'getCourse', 'java-ai'],
    ['/lessons/:lessonId', '/lessons/lesson-1', LessonView, 'getLesson', 'lesson-1'],
  ] as const)('shows the roadmap status banner on %s', async (routePath, path, view, apiMethod, id) => {
    vi.spyOn(courseApi, apiMethod).mockReturnValue(new Promise(() => undefined) as never)
    const legacyRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: routePath, component: view },
        { path: '/roadmap', component: { template: '<div />' } },
      ],
    })
    await legacyRouter.push(path)
    await legacyRouter.isReady()

    const wrapper = shallowMount(view, {
      global: {
        plugins: [createPinia(), legacyRouter],
        stubs: { RouterLink: false },
      },
    })
    await flushPromises()

    expect(wrapper.findComponent(LegacyRoadmapBanner).exists()).toBe(true)
    if (id) expect(courseApi[apiMethod]).toHaveBeenCalledExactlyOnceWith(id)
    else expect(courseApi[apiMethod]).toHaveBeenCalledExactlyOnceWith()
  })
})
