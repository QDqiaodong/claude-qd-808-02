<template>
  <div>
    <div class="section-head">
      <h2>01 概览</h2>
      <span>今日批次 / 累计产量 / 合格率 · 近 7 日产量走势（纯 SVG 手绘）</span>
    </div>

    <div class="metric-row">
      <div class="metric-card">
        <div class="label">今日批次</div>
        <div class="value">{{ dash.todayBatchCount ?? '-' }}<span class="unit">批</span></div>
        <div class="foot">在制工段 {{ dash.runningCount ?? 0 }} 个 · 缺料品种 {{ dash.lowStockCount ?? 0 }} 个</div>
      </div>
      <div class="metric-card">
        <div class="label">累计产量</div>
        <div class="value">{{ fmt(dash.cumulativeOutputTons) }}<span class="unit">吨</span></div>
        <div class="foot">按批次实际产量汇总，含在制批次计划量</div>
      </div>
      <div class="metric-card">
        <div class="label">质检合格率</div>
        <div class="value">{{ fmt(dash.passRate) }}<span class="unit">%</span></div>
        <div class="foot">合格批次数 / 已判定批次数</div>
      </div>
    </div>

    <div class="panel" style="margin-top:14px">
      <div class="panel-title">
        <span>近 7 日产量折线（吨）</span>
        <span class="muted mini">峰值 {{ maxTon }} 吨 · 均值 {{ avgTon }} 吨</span>
      </div>
      <svg :viewBox="`0 0 ${W} ${H}`" width="100%" :height="H" role="img">
        <!-- 网格 -->
        <line v-for="g in gridLines" :key="'g' + g.y"
              :x1="padL" :y1="g.y" :x2="W - padR" :y2="g.y"
              stroke="#eceff1" stroke-width="1" />
        <text v-for="g in gridLines" :key="'t' + g.y"
              :x="padL - 8" :y="g.y + 4" text-anchor="end"
              font-size="10" fill="#9ca3af">{{ g.v }}</text>

        <!-- 面积 -->
        <path :d="areaPath" fill="rgba(183,28,28,0.10)" />
        <!-- 折线 -->
        <path :d="linePath" fill="none" stroke="#b71c1c" stroke-width="2.5"
              stroke-linejoin="round" stroke-linecap="round" />
        <!-- 点 + 标签 -->
        <g v-for="p in points" :key="p.date">
          <circle :cx="p.x" :cy="p.y" r="4.5" fill="#fff" stroke="#b71c1c" stroke-width="2.5" />
          <text :x="p.x" :y="p.y - 12" text-anchor="middle" font-size="11" fill="#8e1414" font-weight="700">
            {{ p.tons }}
          </text>
          <text :x="p.x" :y="H - 10" text-anchor="middle" font-size="11" fill="#6b7280">
            {{ p.short }}
          </text>
          <text :x="p.x" :y="H - 10 + 14" text-anchor="middle" font-size="10" fill="#bdbdbd">
            {{ p.batchCount }} 批
          </text>
        </g>
      </svg>
    </div>

    <div class="grid-2" style="margin-top:14px">
      <div class="panel">
        <div class="panel-title"><span>按畜种配方成本画像</span></div>
        <el-table :data="species" size="small" max-height="220">
          <el-table-column prop="speciesName" label="畜种" width="80" />
          <el-table-column prop="recipeCount" label="配方数" width="80" />
          <el-table-column prop="releasedCount" label="已发布" width="80" />
          <el-table-column label="平均吨成本">
            <template #default="{ row }">¥ {{ fmt(row.avgCost) }}</template>
          </el-table-column>
          <el-table-column label="成本区间">
            <template #default="{ row }">¥ {{ fmt(row.minCost) }} ~ {{ fmt(row.maxCost) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="panel">
        <div class="panel-title"><span>筒仓缺料预警</span></div>
        <el-table :data="lowStock" size="small" max-height="220">
          <el-table-column prop="siloCode" label="筒仓" width="90" />
          <el-table-column prop="name" label="原料" />
          <el-table-column label="库存吨" width="90">
            <template #default="{ row }">{{ fmt(row.stockTons) }}</template>
          </el-table-column>
          <el-table-column label="安全库存" width="90">
            <template #default="{ row }">
              <span style="color:#c62828;font-weight:700">{{ fmt(row.safetyStock) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!lowStock.length" class="muted mini" style="padding:8px 0">
          全部筒仓库存充足，无缺料预警。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchDashboard, fetchSpeciesStats, fetchLowStock } from '../api'

const dash = ref({})
const species = ref([])
const lowStock = ref([])

const W = 900
const H = 280
const padL = 46
const padR = 24
const padT = 26
const padB = 40

const trend = computed(() => (dash.value.trend || []).slice(-7))
const maxTon = computed(() => {
  const v = trend.value.map(t => Number(t.outputTons || 0))
  return v.length ? Math.max(...v) : 0
})
const avgTon = computed(() => {
  const v = trend.value.map(t => Number(t.outputTons || 0))
  return v.length ? (v.reduce((a, b) => a + b, 0) / v.length).toFixed(1) : '0.0'
})

const gridLines = computed(() => {
  const top = Math.max(maxTon.value * 1.15, 10)
  return [0, 0.25, 0.5, 0.75, 1].map(r => ({
    v: Math.round(top * r),
    y: padT + (1 - r) * (H - padT - padB)
  }))
})

const points = computed(() => {
  const list = trend.value
  if (!list.length) return []
  const top = Math.max(maxTon.value * 1.15, 10)
  const innerW = W - padL - padR
  const step = list.length > 1 ? innerW / (list.length - 1) : 0
  return list.map((t, i) => {
    const tons = Number(t.outputTons || 0)
    return {
      date: t.date,
      short: String(t.date || '').slice(5),
      batchCount: t.batchCount,
      tons: tons.toFixed(1),
      x: list.length > 1 ? padL + step * i : padL + innerW / 2,
      y: padT + (1 - tons / top) * (H - padT - padB)
    }
  })
})

const linePath = computed(() =>
  points.value.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
)

const areaPath = computed(() => {
  const ps = points.value
  if (!ps.length) return ''
  const base = H - padB
  return `${linePath.value} L ${ps[ps.length - 1].x.toFixed(1)} ${base} L ${ps[0].x.toFixed(1)} ${base} Z`
})

function fmt (v) {
  if (v === undefined || v === null) return '-'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

onMounted(async () => {
  const [d, s, l] = await Promise.all([
    fetchDashboard().catch(() => ({})),
    fetchSpeciesStats().catch(() => []),
    fetchLowStock().catch(() => [])
  ])
  dash.value = d || {}
  species.value = s || []
  lowStock.value = l || []
})
</script>
