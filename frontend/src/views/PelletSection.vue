<template>
  <div>
    <div class="section-head">
      <h2>04 制粒</h2>
      <span>工艺参数面板（温度 / 蒸汽压力 / 环模孔径）· 环模寿命消耗条 · 超寿命上限不允许继续制粒</span>
    </div>

    <div class="grid-2">
      <div class="panel">
        <div class="panel-title">
          <span>工艺参数</span>
          <el-select v-model="batchId" size="small" style="width:240px" @change="loadPellet">
            <el-option v-for="p in pellets" :key="p.id"
                       :label="`${p.batch.batchNo} · ${p.dieCode}`" :value="p.batch.id" />
          </el-select>
        </div>

        <div v-if="pellet">
          <div class="slider-row">
            <span>调质温度 (℃)</span>
            <el-slider v-model="temp" :min="60" :max="95" :step="0.5" @change="apply" />
            <span class="sv">{{ temp.toFixed(1) }}</span>
          </div>
          <div class="slider-row">
            <span>蒸汽压力 (MPa)</span>
            <el-slider v-model="pressure" :min="0.10" :max="0.60" :step="0.01" @change="apply" />
            <span class="sv">{{ pressure.toFixed(2) }}</span>
          </div>
          <div class="slider-row">
            <span>环模孔径 (mm)</span>
            <el-slider v-model="aperture" :min="1.5" :max="6.0" :step="0.1" @change="apply" />
            <span class="sv">{{ aperture.toFixed(1) }}</span>
          </div>

          <div class="param-card" style="margin-top:10px">
            <span>推荐环模寿命消耗</span>
            <b style="color:#b71c1c">{{ plan.recommendWearTons }} 吨</b>
          </div>
          <div class="param-card" style="margin-top:8px">
            <span>磨损系数 / 预计累计</span>
            <b>{{ plan.wearRate }} × · {{ plan.projectedUsedTons }} 吨</b>
          </div>
          <div class="param-card" style="margin-top:8px">
            <span>预计颗粒硬度 / 粉化率</span>
            <b>{{ pellet.hardness }} N · {{ pellet.powderRate }} %</b>
          </div>
          <div class="mini" :style="{ marginTop: '8px', color: plan.overLife ? '#c62828' : '#2e7d32' }">
            {{ plan.message }}
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-title">
          <span>环模寿命消耗</span>
          <span v-if="pellet" class="tag-chip" :class="life > 100 ? 'chip-reject' : (life > 85 ? 'chip-down' : 'chip-pass')">
            {{ life.toFixed(1) }}%
          </span>
        </div>

        <div v-if="pellet">
          <div class="life-bar">
            <div class="life-fill" :class="life > 100 ? 'over' : (life > 85 ? 'warn' : '')"
                 :style="{ width: Math.min(life, 100) + '%' }"></div>
            <div class="life-text">
              {{ pellet.dieCode }} · 已用 {{ pellet.dieUsedTons }} 吨 / 上限 {{ pellet.dieLifeTons }} 吨
            </div>
          </div>

          <div class="param-card" style="margin-top:12px">
            <span>剩余可用</span><b>{{ remain }} 吨</b>
          </div>
          <div class="param-card" style="margin-top:8px">
            <span>本批制粒产量</span><b>{{ pellet.outputTons ?? '—' }} 吨</b>
          </div>
          <div class="param-card" style="margin-top:8px">
            <span>工段状态</span><b>{{ pelletStatus }}</b>
          </div>

          <div style="margin-top:14px;display:flex;gap:8px;align-items:center">
            <el-input-number v-model="outputTons" :min="1" :step="1" :precision="2" size="small" style="width:150px" />
            <el-button size="small" type="primary" :loading="busy" @click="produce">开始制粒</el-button>
          </div>
          <div class="mini muted" style="margin-top:10px">
            规则：环模累计产量 + 本批产量超过寿命上限时，直接拦截并标注 BLOCKED，需先更换环模。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchPellets, fetchPelletByBatch, adjustPellet, producePellet } from '../api'

const pellets = ref([])
const pellet = ref(null)
const batchId = ref(null)
const temp = ref(80)
const pressure = ref(0.3)
const aperture = ref(3.0)
const outputTons = ref(10)
const plan = ref({})
const busy = ref(false)

const life = computed(() => {
  const p = pellet.value
  if (!p || !p.dieLifeTons) return 0
  return (Number(p.dieUsedTons) / Number(p.dieLifeTons)) * 100
})
const remain = computed(() => {
  const p = pellet.value
  if (!p) return 0
  return (Number(p.dieLifeTons) - Number(p.dieUsedTons)).toFixed(2)
})
const pelletStatus = computed(() =>
  ({ IDLE: '待机', RUNNING: '制粒中', DONE: '已完成', BLOCKED: '已锁死（超限）' }[pellet.value?.status] || '-')
)

async function loadPellet () {
  if (!batchId.value) return
  pellet.value = await fetchPelletByBatch(batchId.value)
  temp.value = Number(pellet.value.temperingTemp)
  pressure.value = Number(pellet.value.steamPressure)
  aperture.value = Number(pellet.value.dieAperture)
  outputTons.value = Number(pellet.value.batch.plannedTons)
  await apply()
}

async function apply () {
  const res = await adjustPellet(batchId.value, {
    temperingTemp: temp.value,
    steamPressure: pressure.value,
    dieAperture: aperture.value,
    outputTons: outputTons.value
  })
  pellet.value = res.pellet
  plan.value = res
}

async function produce () {
  busy.value = true
  try {
    pellet.value = await producePellet(batchId.value, outputTons.value)
    ElMessage.success(`制粒完成，环模累计产量 ${pellet.value.dieUsedTons} 吨`)
    await loadPellet()
  } catch (e) {
    ElMessage.error(e.message)
    await loadPellet()
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  pellets.value = await fetchPellets().catch(() => [])
  const target = pellets.value.find(p => p.status === 'IDLE') || pellets.value[0]
  if (target) {
    batchId.value = target.batch.id
    await loadPellet()
  }
})
</script>
