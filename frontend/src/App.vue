<template>
  <div class="mill-app">
    <header class="mill-top">
      <div class="brand">
        <span class="logo">FM</span>
        <div>
          <h1>饲料加工厂 · 配方批次与质检管理</h1>
          <p>配方 → 投料 → 制粒 → 质检 连续生产闭环 · 中控工段</p>
        </div>
      </div>
      <div class="top-meta">
        <span>工段 FM-808</span>
        <span>筒仓 11 座</span>
        <span>班次 甲 / 乙 / 丙</span>
        <span>数据口径 实时</span>
      </div>
    </header>

    <!-- 单页滚动 + 顶部锚点吸顶条 -->
    <nav class="anchor-bar" :class="{ stuck }">
      <a
        v-for="a in anchors"
        :key="a.id"
        :class="{ active: current === a.id }"
        @click="go(a.id)"
      >
        <span class="idx">{{ a.idx }}</span>{{ a.label }}
      </a>
    </nav>

    <main>
      <section id="overview" class="mill-section">
        <OverviewSection />
      </section>
      <section id="recipe" class="mill-section">
        <RecipeSection />
      </section>
      <section id="feeding" class="mill-section">
        <FeedingSection />
      </section>
      <section id="pellet" class="mill-section">
        <PelletSection />
      </section>
      <section id="qc" class="mill-section">
        <QcSection />
      </section>
    </main>

    <div class="mill-foot">
      饲料加工厂中控演示系统 · 配方 / 投料 / 制粒 / 质检 全流程闭环
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import OverviewSection from './views/OverviewSection.vue'
import RecipeSection from './views/RecipeSection.vue'
import FeedingSection from './views/FeedingSection.vue'
import PelletSection from './views/PelletSection.vue'
import QcSection from './views/QcSection.vue'

const anchors = [
  { id: 'overview', idx: '01', label: '概览' },
  { id: 'recipe', idx: '02', label: '配料' },
  { id: 'feeding', idx: '03', label: '投料' },
  { id: 'pellet', idx: '04', label: '制粒' },
  { id: 'qc', idx: '05', label: '质检' }
]

const current = ref('overview')
const stuck = ref(false)
let ticking = false

function onScroll () {
  if (ticking) return
  ticking = true
  window.requestAnimationFrame(() => {
    stuck.value = window.scrollY > 78
    let active = anchors[0].id
    for (const a of anchors) {
      const el = document.getElementById(a.id)
      if (el && el.getBoundingClientRect().top <= 140) {
        active = a.id
      }
    }
    current.value = active
    ticking = false
  })
}

function go (id) {
  const el = document.getElementById(id)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>
