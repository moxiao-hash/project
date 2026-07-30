<script lang="ts">
const BVID_PATTERN = /^BV[0-9A-Za-z]{10}$/

export function buildBilibiliUrls(bvid: string, page: number) {
  if (!BVID_PATTERN.test(bvid) || !Number.isInteger(page) || page < 1) {
    throw new Error('无效的 B 站课程定位')
  }
  return {
    embed: `https://player.bilibili.com/player.html?bvid=${bvid}&p=${page}&autoplay=0&danmaku=0`,
    original: `https://www.bilibili.com/video/${bvid}?p=${page}`,
  }
}
</script>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  bvid: string
  page: number
  title: string
}>()

const urls = computed(() => buildBilibiliUrls(props.bvid, props.page))
</script>

<template>
  <div class="video-shell">
    <div class="video-frame">
      <iframe
        :src="urls.embed"
        :title="title"
        sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
        allow="autoplay; fullscreen; picture-in-picture"
        referrerpolicy="strict-origin-when-cross-origin"
        allowfullscreen
      />
    </div>
    <div class="video-footer">
      <span>视频由 B 站官方站外播放器提供，不消耗 Tavily 搜索额度。</span>
      <a
        :href="urls.original"
        target="_blank"
        rel="noopener noreferrer"
        data-test="open-bilibili"
      >
        播放器不可用？前往 B 站原页面
      </a>
    </div>
  </div>
</template>

<style scoped>
.video-shell {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: #0d1020;
}

.video-frame {
  position: relative;
  aspect-ratio: 16 / 9;
}

iframe {
  width: 100%;
  height: 100%;
  border: 0;
}

.video-footer {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
  color: #aab0c4;
  font-size: 12px;
}

.video-footer a {
  color: #c7d2fe;
  white-space: nowrap;
}

@media (max-width: 640px) {
  .video-footer {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
