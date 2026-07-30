import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import BilibiliPlayer, { buildBilibiliUrls } from './BilibiliPlayer.vue'

describe('BilibiliPlayer', () => {
  it('uses the official external player and keeps the original page fallback', () => {
    const wrapper = mount(BilibiliPlayer, {
      props: {
        bvid: 'BV14z4y1N7pg',
        page: 15,
        title: '注册接口',
      },
    })

    expect(wrapper.get('iframe').attributes('src')).toBe(
      'https://player.bilibili.com/player.html?bvid=BV14z4y1N7pg&p=15&autoplay=0&danmaku=0',
    )
    const fallback = wrapper.get('[data-test="open-bilibili"]')
    expect(fallback.attributes('href')).toBe(
      'https://www.bilibili.com/video/BV14z4y1N7pg?p=15',
    )
    expect(fallback.attributes('target')).toBe('_blank')
    expect(fallback.attributes('rel')).toBe('noopener noreferrer')
  })

  it('rejects unsafe source values', () => {
    expect(() => buildBilibiliUrls('javascript:alert(1)', 1)).toThrow(
      '无效的 B 站课程定位',
    )
    expect(() => buildBilibiliUrls('BV14z4y1N7pg', 0)).toThrow(
      '无效的 B 站课程定位',
    )
  })
})
