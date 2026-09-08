import assert from 'node:assert/strict'
import test from 'node:test'

import {
  loadProjectInputs,
  verifyCapabilityMatrix,
} from './verify-agent-capability-matrix.mjs'

const project = loadProjectInputs(new URL('..', import.meta.url))

test('accepts the checked-in capability matrix', () => {
  assert.doesNotThrow(() => verifyCapabilityMatrix(project))
})

test('rejects a route key that is not registered by both Java and Vue', () => {
  const changed = {
    ...project,
    matrixContent: project.matrixContent.replace(
      '| dashboard | DASHBOARD |',
      '| dashboard | UNKNOWN_ROUTE |',
    ),
  }

  assert.throws(() => verifyCapabilityMatrix(changed), /routeKey.*UNKNOWN_ROUTE/u)
})

test('rejects a matrix tool reference absent from the production registry', () => {
  const changed = {
    ...project,
    matrixContent: project.matrixContent.replace(
      '`schedule.today.get`',
      '`missing.tool.get`',
    ),
  }

  assert.throws(() => verifyCapabilityMatrix(changed), /missing\.tool\.get.*未注册/u)
})

test('rejects a production tool omitted by the matrix', () => {
  const changed = {
    ...project,
    matrixContent: project.matrixContent.replaceAll('`governance.health.get`', '-'),
  }

  assert.throws(() => verifyCapabilityMatrix(changed), /缺少.*governance\.health\.get/u)
})
