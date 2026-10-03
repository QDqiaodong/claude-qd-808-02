<template>
  <div ref="sectionEl">
    <div class="section-head">
      <h2>03 投料</h2>
      <span>投料口大按钮阵列 · 按投料顺序称量 · 环形倒计时 · 误差超 ±2% 拦截并记录异常</span>
    </div>

    <div class="panel">
      <div class="panel-title">
        <span>批次与投料口</span>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="batchId" size="small" style="width:280px" @change="loadBatch">
            <el-option v-for="b in batches" :key="b.id" :label="`${b.batchNo} · ${b.recipe.name}`" :value="b.id" />
          </el-select>
          <el-button size="small" @click="loadBatch">刷新</el-button>
        </div>
      </div>

      <div v-if="batch" class="mini muted" style="margin-bottom:10px">
        批次 {{ batch.batchNo }} · 配方 {{ batch.recipe.recipeCode }} · 计划产量 {{ batch.plannedTons }} 吨 ·
        状态 <span class="tag-chip chip-run">{{ statusName(batch.status) }}</span> ·
        已投 {{ doneCount }} / {{ batch.feedings.length }} 道
      </div>

      <div class="port-grid" v-if="batch">
        <div
          v-for="item in batch.feedings"
          :key="item.id"
          class="port-btn"
          :class="portClass(item)"
          @click="startWeigh(item)"
        >
          <div class="seq">{{ item.portNo }}</div>
          <div class="mname">{{ item.material.name }}</div>
          <div class="plan">计划 {{ Number(item.plannedKg).toFixed(0) }} kg</div>
          <div class="plan" v-if="item.actualKg">实投 {{ Number(item.actualKg).toFixed(0) }} kg</div>
          <div class="tag">
            <span :class="tagClass(item)">{{ feedStatusName(item) }}</span>
            <span v-if="item.deviationRate != null && item.status !== 'PENDING'" class="mini mono"
                  :style="{ marginLeft: 6, color: Math.abs(item.deviationRate) > 2 ? '#c62828' : '#2e7d32' }">
              误差 {{ Number(item.deviationRate) > 0 ? '+' : '' }}{{ Number(item.deviationRate).toFixed(2) }}%
            </span>
          </div>
          <div v-if="item.abnormalNote" class="mini" style="color:#c62828;margin-top:6px">
            {{ item.abnormalNote }}
          </div>

          <!-- 环形倒计时：状态按批次 + 投料序号隔离，避免切换批次串批 -->
          <svg v-if="isCounting(item)" class="ring" width="46" height="46" viewBox="0 0 46 46">
            <circle cx="23" cy="23" r="18" fill="none" stroke="#ffe0b2" stroke-width="4" />
            <circle cx="23" cy="23" r="18" fill="none" stroke="#ef6c00" stroke-width="4" stroke-linecap="round"
                    :stroke-dasharray="CIRC" :stroke-dashoffset="CIRC * (1 - countdownLeft(item) / TOTAL)"
                    transform="rotate(-90 23 23)" />
            <text x="23" y="27" text-anchor="middle">{{ countdownLeft(item) }}</text>
          </svg>
        </div>
      </div>

      <div class="mini muted" style="margin-top:12px">
        规则：必须按投料口顺序依次投料；单道实际称重与计划量偏差超过 ±2% 会被拦截并记为异常（按钮变红）。
      </div>
    </div>

    <el-dialog v-model="dialog.visible" :title="dialogTitle" width="420px" @close="closeDialog">
      <div v-if="dialog.item">
        <div class="mini muted" style="margin-bottom:8px">
          所属批次：{{ dialog.batchNo }}
        </div>
        <div class="param-card">
          <span>{{ dialog.item.portNo }} · {{ dialog.item.material.name }}</span>
          <b>计划 {{ Number(dialog.item.plannedKg).toFixed(2) }} kg</b>
        </div>
        <div style="margin-top:14px">
          <div class="mini muted" style="margin-bottom:6px">实际称重（kg）</div>
          <el-input-number v-model="dialog.actual" :min="0" :step="10" :precision="2" style="width:100%" />
        </div>
        <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap">
          <el-button size="small" @click="preset(0)">按计划量</el-button>
          <el-button size="small" @click="preset(1.2)">偏差 +1.2%</el-button>
          <el-button size="small" @click="preset(-1.5)">偏差 −1.5%</el-button>
          <el-button size="small" type="danger" plain @click="preset(3.2)">偏差 +3.2%（触发异常）</el-button>
        </div>
        <div class="mini" style="margin-top:10px">
          预计误差：
          <b :style="{ color: Math.abs(estDeviation) > 2 ? '#c62828' : '#2e7d32' }">
            {{ estDeviation > 0 ? '+' : '' }}{{ estDeviation.toFixed(2) }}%
          </b>
          <span v-if="Math.abs(estDeviation) > 2" style="color:#c62828"> · 超出 ±2%，提交后将被拦截</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.busy" @click="submitFeed">确认投料</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchBatches, fetchBatch, feeding } from '../api'
import { useFeedingCountdown } from '../composables/useFeedingCountdown'

const TOTAL = 5
const CIRC = 2 * Math.PI * 18

const batches = ref([])
const batch = ref(null)
const batchId = ref(null)
const sectionEl = ref(null)
let feedingObserver = null

const {
  counting,
  dialog,
  dialogTitle,
  countdownLeft,
  isCounting,
  startWeigh: startOwnerWeigh,
  closeDialog,
  clearAllCountdowns,
  submitFeed
} = useFeedingCountdown({
  total: TOTAL,
  getCurrentBatch: () => batchId.value,
  loadBatch,
  sendFeedingAction: feeding,
  notify: ({ type, message }) => ElMessage[type](message)
})

const doneCount = computed(() =>
  (batch.value?.feedings || []).filter(f => f.status === 'DONE').length
)
const estDeviation = computed(() => {
  const item = dialog.item
  if (!item) return 0
  const planned = Number(item.plannedKg)
  if (!planned) return 0
  return ((Number(dialog.actual) - planned) / planned) * 100
})

function statusName (s) {
  return { PLANNED: '待排产', FEEDING: '投料中', PELLETING: '制粒中', QC: '质检中', RECHECK: '复检中', DONE: '已完成' }[s] || s
}
function feedStatusName (item) {
  if (isCounting(item)) return '称量中'
  return { PENDING: '待投', WEIGHING: '称量中', DONE: '已投', ABNORMAL: '异常' }[item.status] || item.status
}
function tagClass (item) {
  if (isCounting(item)) return 'tag-chip chip-down'
  return {
    PENDING: 'tag-chip chip-gray',
    WEIGHING: 'tag-chip chip-down',
    DONE: 'tag-chip chip-pass',
    ABNORMAL: 'tag-chip chip-reject'
  }[item.status]
}
function portClass (item) {
  const cls = []
  if (isCounting(item)) cls.push('weighing')
  else if (item.status === 'DONE') cls.push('done')
  else if (item.status === 'ABNORMAL') cls.push('abnormal')
  if (!operable(item)) cls.push('disabled')
  return cls.join(' ')
}

/** 只有「待投/异常」且上一道已完成的投料口可发起新倒计时；已进入称重点可直接补开弹窗 */
function operable (item) {
  if (!batch.value || isCounting(item)) return false
  if (item.status === 'WEIGHING') return true
  if (item.status !== 'PENDING' && item.status !== 'ABNORMAL') return false
  if (item.status === 'ABNORMAL') return true
  const prev = batch.value.feedings.find(f => f.feedSeq === item.feedSeq - 1)
  return !prev || prev.status === 'DONE'
}

async function loadBatch () {
  if (!batchId.value) return
  batch.value = await fetchBatch(batchId.value)
}

async function startWeigh (item) {
  if (!batch.value || !operable(item)) {
    if (item.status !== 'DONE') ElMessage.warning('请按投料口顺序依次投料')
    return
  }
  const fresh = await startOwnerWeigh(batch.value, item)
  if (fresh) batch.value = fresh
}

function preset (rate) {
  const planned = Number(dialog.item.plannedKg)
  dialog.actual = Number((planned * (1 + rate / 100)).toFixed(2))
}

onMounted(async () => {
  batches.value = await fetchBatches().catch(() => [])
  const pending = batches.value.find(b => (b.feedings || []).some(f => f.status === 'PENDING'))
  const target = pending || batches.value[0]
  if (target) {
    batchId.value = target.id
    await loadBatch()
  }

  if ('IntersectionObserver' in window && sectionEl.value) {
    feedingObserver = new IntersectionObserver(entries => {
      const entry = entries[0]
      if (!entry?.isIntersecting) {
        clearAllCountdowns(true).then(loadBatch).catch(() => {})
      }
    }, { threshold: 0 })
    feedingObserver.observe(sectionEl.value)
  }
})

onBeforeUnmount(() => {
  feedingObserver?.disconnect()
  clearAllCountdowns(false)
})
</script>
