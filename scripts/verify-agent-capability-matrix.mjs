#!/usr/bin/env node

/**
 * 校验 Agent 能力矩阵是否与生产路由、导航白名单和工具注册代码一致。
 * 测试清单自身可能漏项，因此只把生产配置作为事实来源。
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const VALID_CAPABILITIES = new Set([
  'AUTO_READ', 'AUTO_NAVIGATE', 'PREVIEW_WRITE', 'USER_ONLY', 'UNSUPPORTED',
])
const VALID_RISK_LEVELS = new Set(['NONE', 'LOW', 'HIGH'])
const VALID_AUTHENTICITIES = new Set([
  'AGENT_PERMITTED', 'USER_ONLY_SUBMISSION', 'USER_ONLY_REVIEW', 'USER_ONLY_DECISION',
])

function rootPath(root) {
  return root instanceof URL ? fileURLToPath(root) : path.resolve(root)
}

function read(root, relativePath) {
  return fs.readFileSync(path.join(rootPath(root), relativePath), 'utf8')
}

function extractMatches(content, regex, group = 1) {
  return new Set(Array.from(content.matchAll(regex), (match) => match[group]))
}

function parseRows(matrixContent) {
  const lines = matrixContent.split('\n')
  const headerIndex = lines.findIndex((line) => line.includes('| 路由名 (name) |'))
  if (headerIndex < 0) throw new Error('找不到页面能力矩阵表头')

  const rows = []
  for (const line of lines.slice(headerIndex + 2)) {
    if (!line.trim().startsWith('|')) break
    const columns = line.split('|').slice(1, -1).map((column) => column.trim())
    if (columns.length !== 8) throw new Error(`能力矩阵列数不是 8: ${line}`)
    const [routeName, routeKey, capability, readTool, writeTool, risk, authenticity, reason] = columns
    rows.push({ routeName, routeKey, capability, readTool, writeTool, risk, authenticity, reason })
  }
  return rows
}

function toolReferences(value) {
  if (value === '-') return []
  return Array.from(value.matchAll(/`([a-z0-9_]+(?:\.[a-z0-9_]+)+)`/gu), (match) => match[1])
}

export function loadProjectInputs(root) {
  const javaToolDirectory = path.join(rootPath(root), 'backend/src/main/java/com/moxiao/studypilot/agent/tool')
  const javaToolContent = fs.readdirSync(javaToolDirectory)
    .filter((name) => name.endsWith('.java'))
    .map((name) => fs.readFileSync(path.join(javaToolDirectory, name), 'utf8'))
    .join('\n')

  return {
    routerContent: read(root, 'web/src/app/router.ts'),
    dispatcherContent: read(root, 'web/src/modules/assistant/uiActionDispatcher.ts'),
    navigationContent: read(root, 'backend/src/main/java/com/moxiao/studypilot/agent/tool/NavigationToolHandler.java'),
    javaToolContent,
    matrixContent: read(root, 'docs/agent-capability-matrix-v2.md'),
  }
}

export function verifyCapabilityMatrix(inputs) {
  const vueRoutes = extractMatches(inputs.routerContent, /name:\s*['"]([^'"]+)['"]/gu)
  const dispatcherEntries = new Map(Array.from(
    inputs.dispatcherContent.matchAll(/^\s*([A-Z][A-Z0-9_]+):\s*\{\s*name:\s*'([^']+)'/gmu),
    (match) => [match[1], match[2]],
  ))
  const javaRouteKeys = extractMatches(inputs.navigationContent, /Map\.entry\("([A-Z][A-Z0-9_]+)"/gu)
  const configuredTools = extractMatches(
    inputs.javaToolContent,
    /(?:read|write|writeValidated)\(mapper,\s*"([a-z0-9_]+(?:\.[a-z0-9_]+)+)"/gu,
  )
  const descriptorTools = extractMatches(
    inputs.javaToolContent,
    /new AgentToolDescriptor\(\s*"([a-z0-9_]+(?:\.[a-z0-9_]+)+)"/gu,
  )
  const productionTools = new Set([...configuredTools, ...descriptorTools])
  if (vueRoutes.size === 0 || productionTools.size === 0) {
    throw new Error('未能解析生产路由或 Java 工具注册表')
  }

  const rows = parseRows(inputs.matrixContent)
  const rowsByRoute = new Map()
  for (const row of rows) {
    if (rowsByRoute.has(row.routeName)) throw new Error(`路由 ${row.routeName} 在矩阵中重复`)
    rowsByRoute.set(row.routeName, row)
    if (!VALID_CAPABILITIES.has(row.capability)) {
      throw new Error(`路由 ${row.routeName} 的 capability ${row.capability} 不合法`)
    }
    if (!VALID_RISK_LEVELS.has(row.risk)) {
      throw new Error(`路由 ${row.routeName} 的 risk ${row.risk} 不合法`)
    }
    if (!VALID_AUTHENTICITIES.has(row.authenticity)) {
      throw new Error(`路由 ${row.routeName} 的 authenticity ${row.authenticity} 不合法`)
    }
    if (['USER_ONLY', 'UNSUPPORTED'].includes(row.capability)
        && (!row.reason || ['-', '无'].includes(row.reason))) {
      throw new Error(`路由 ${row.routeName} 缺少拒绝代办原因`)
    }

    if (row.routeKey !== 'NONE') {
      if (!javaRouteKeys.has(row.routeKey) || !dispatcherEntries.has(row.routeKey)) {
        throw new Error(`路由 ${row.routeName} 的 routeKey ${row.routeKey} 未同时注册到 Java 与 Vue`)
      }
      if (dispatcherEntries.get(row.routeKey) !== row.routeName) {
        throw new Error(`routeKey ${row.routeKey} 实际映射 ${dispatcherEntries.get(row.routeKey)}，不是 ${row.routeName}`)
      }
    }

    for (const tool of [...toolReferences(row.readTool), ...toolReferences(row.writeTool)]) {
      if (!productionTools.has(tool)) throw new Error(`工具 ${tool} 未注册到生产 Java Tool Registry`)
    }
  }

  const missingRoutes = [...vueRoutes].filter((name) => !rowsByRoute.has(name))
  if (missingRoutes.length > 0) throw new Error(`能力矩阵缺少 Vue 路由: ${missingRoutes.join(', ')}`)
  const extraRoutes = [...rowsByRoute.keys()].filter((name) => !vueRoutes.has(name))
  if (extraRoutes.length > 0) throw new Error(`能力矩阵存在无效 Vue 路由: ${extraRoutes.join(', ')}`)

  const allDocumentedTools = extractMatches(
    inputs.matrixContent,
    /`([a-z0-9_]+(?:\.[a-z0-9_]+)+)`/gu,
  )
  const missingTools = [...productionTools].filter((tool) => !allDocumentedTools.has(tool))
  if (missingTools.length > 0) throw new Error(`能力矩阵缺少生产工具: ${missingTools.join(', ')}`)

  return { routeCount: vueRoutes.size, toolCount: productionTools.size }
}

function main() {
  try {
    const result = verifyCapabilityMatrix(loadProjectInputs(new URL('..', import.meta.url)))
    console.log(`[SUCCESS] 能力矩阵校验通过！覆盖全部 ${result.routeCount} 个页面路由与 ${result.toolCount} 个 Java 工具。`)
  } catch (error) {
    console.error(`[ERROR] 矩阵校验失败: ${error.message}`)
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) main()
