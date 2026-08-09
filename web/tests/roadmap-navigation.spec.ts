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
import NodeView from '@/modules/roadmap/NodeView.vue'
import CourseCatalogView from '@/modules/course/CourseCatalogView.vue'
import CourseDetailView from '@/modules/course/CourseDetailView.vue'
import LessonView from '@/modules/course/LessonView.vue'
import appShellSource from '@/components/AppShell.vue?raw'
import courseCatalogSource from '@/modules/course/CourseCatalogView.vue?raw'
import courseDetailSource from '@/modules/course/CourseDetailView.vue?raw'
import lessonSource from '@/modules/course/LessonView.vue?raw'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapNode,
  RoadmapStage,
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

describe('roadmapApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('enrolls through the Java public API with only the roadmap identity', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: enrollment })

    await expect(roadmapApi.enroll()).resolves.toEqual(enrollment)

    expect(post).toHaveBeenCalledExactlyOnceWith('/api/roadmap-enrollments', {
      roadmapCode: 'studypilot-java-ai',
      templateVersion: 1,
    })
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
  const cases = [
    ['/roadmap', 'roadmap', '学习路线', RoadmapView],
    ['/roadmap/stages/stage%2F1', 'roadmap-stage', '路线阶段', StageView],
    ['/roadmap/nodes/node%2F1', 'roadmap-node', '学习节点', NodeView],
  ] as const

  it.each(cases)('resolves %s to the intended lazy page', async (path, name, title, view) => {
    const resolved = router.resolve(path)
    const record = resolved.matched.at(-1)
    const loader = record?.components?.default

    expect(resolved.name).toBe(name)
    expect(resolved.meta.title).toBe(title)
    expect(typeof loader).toBe('function')
    await expect((loader as () => Promise<{ default: unknown }>)()).resolves.toMatchObject({
      default: view,
    })
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
    expect(groupLinks(0)).toEqual(['/', '/notifications'])
    expect(groupLinks(1)).toEqual(['/roadmap', '/today', '/materials', '/mastery'])
    expect(wrapper.findAll('a[href="/today"]')).toHaveLength(1)
    expect(wrapper.find('a[href="/courses"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/roadmap"]').classes()).toContain('active')
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

  it('uses one identical status banner in all three legacy views without removing their APIs', () => {
    const banner = /<aside class="legacy-roadmap-banner" role="status">[\s\S]*?<\/aside>/
    const banners = legacySources.map((source) => source.match(banner)?.[0].replace(/\s+/g, ' '))

    expect(new Set(banners).size).toBe(1)
    expect(banners[0]).toContain('to="/roadmap"')
    expect(courseCatalogSource).toContain('courseApi.listCourses()')
    expect(courseDetailSource).toContain('courseApi.getCourse(')
    expect(lessonSource).toContain('courseApi.updateProgress(')
    expect(lessonSource).toContain('courseApi.submitCheckpoint(')
    expect(lessonSource).toContain('courseApi.generateLessonQuiz(')
  })

  it.each([
    ['/courses', CourseCatalogView, 'listCourses'],
    ['/courses/java-ai', CourseDetailView, 'getCourse'],
    ['/lessons/lesson-1', LessonView, 'getLesson'],
  ] as const)('shows the roadmap status banner on %s', async (path, view, apiMethod) => {
    vi.spyOn(courseApi, apiMethod).mockReturnValue(new Promise(() => undefined) as never)
    const legacyRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path, component: view },
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

    const banner = wrapper.find('[role="status"]')
    expect(banner.isVisible()).toBe(true)
    expect(banner.text()).toContain('Java + AI 学习路线')
    expect(banner.find('a').attributes('href')).toBe('/roadmap')
    expect(courseApi[apiMethod]).toHaveBeenCalledOnce()
  })
})
