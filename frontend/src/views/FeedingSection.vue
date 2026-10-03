<template>
  <div ref="sectionEl">
    <div class="section-head">
      <h2>03 投料</h2>
      <span>投料口大按钮阵列 · 按投料顺序称量 · 配方修订后计划批次可选择迁移或冻结</span>
    </div>

    <div class="panel">
      <div class="panel-title">
        <span>批次与投料口</span>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="batchId" size="small" style="width:320px" @change="loadBatch">
            <el-option
              v-for="b in batches"
              :key="b.id"
              :label="`${b.batchNo} · ${b.recipe.name} · V${b.recipe.revisionNo}${b.migrationStatus === 'PENDING' ? '（有新修订）' : ''}`"
              :value="b.id"
            />
          </el-select>
          <el-button size="small" @click="loadBatch">刷新</el-button>
          <el-button size="small" :disabled="!batch" @click="openMigrations">迁移记录</el-button>
        </div>
      </div>

      <div v-if="batch" class="mini muted" style="margin-bottom:10px">
        批次 {{ batch.batchNo }} · 配方 {{ batch.recipe.recipeCode }}
        <span class="tag-chip chip-run" style="margin:0 4px">V{{ batch.recipe.revisionNo }}</span>
        · 计划产量 {{ batch.plannedTons }} 吨 ·
        状态 <span class="tag-chip chip-run">{{ statusName(batch.status) }}</span> ·
        已投 {{ doneCount }} / {{ batch.feedings.length }} 道
      </div>

      <!-- 配方修订提示条：默认冻结在原版本，操作员看清影响后选择 -->
      <el-alert
        v-if="batch && batch.migrationStatus === 'PENDING'"
        type="warning"
        :closable="false"
        style="margin-bottom:12px"
      >
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
          <span>
            配方 {{ batch.recipe.recipeCode }} 已发布新修订，本批次目前<b>仍冻结在 V{{ batch.recipe.revisionNo }} 快照</b>，
            投料明细与计划量未变。请选择迁移或确认冻结。
          </span>
          <el-button size="small" type="warning" @click="openMigration">查看差异并选择</el-button>
        </div>
      </el-alert>
      <el-alert
        v-else-if="batch && batch.migrationStatus === 'FROZEN'"
        type="info"
        :closable="false"
        style="margin-bottom:12px"
      >
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
          <span>已确认<b>冻结在 V{{ batch.recipe.revisionNo }}</b>（继续使用原版本快照）。批次未投料前仍可改选迁移。</span>
          <el-button size="small" @click="openMigration">重新选择</el-button>
        </div>
      </el-alert>
      <el-alert
        v-else-if="batch && batch.migrationStatus === 'MIGRATED'"
        type="success"
        :closable="false"
        style="margin-bottom:12px"
      >
        <span>已迁移到 V{{ batch.recipe.revisionNo }}，投料明细与计划用量已按新版本重新生成。</span>
      </el-alert>
      <el-alert
        v-else-if="batch && batch.recipe.status === 'SUPERSEDED'"
        type="info"
        :closable="false"
        style="margin-bottom:12px"
      >
        <span>
          本批次采用 V{{ batch.recipe.revisionNo }}（已有更新版本）。批次已开始{{ statusName(batch.status) }}，
          <b>按规程锁定原版本与原投料明细</b>，不可迁移。
        </span>
      </el-alert>

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

    <!-- 配方迁移选择：看清新旧版本差异后确认 -->
    <el-dialog v-model="migration.visible" title="配方版本选择" width="720px">
      <div v-if="migration.preview" class="mini">
        <div class="param-card">
          <span>版本变化</span>
          <b>
            V{{ migration.preview.fromRevisionNo }}（当前采用）→ V{{ migration.preview.toRevisionNo }}（最新生效）
          </b>
        </div>
        <div style="display:flex;gap:8px;margin-top:8px">
          <div class="param-card" style="flex:1">
            <span>吨成本</span>
            <b>
              ¥{{ fmt(migration.preview.costPerTonBefore) }} → ¥{{ fmt(migration.preview.costPerTonAfter) }}
            </b>
          </div>
          <div class="param-card" style="flex:1">
            <span>批次计划成本（{{ migration.preview.plannedTons }} 吨）</span>
            <b>
              ¥{{ fmt(migration.preview.costBefore) }} → ¥{{ fmt(migration.preview.costAfter) }}
              <span :style="{ color: planCostDelta > 0 ? '#c62828' : '#2e7d32' }">
                （{{ planCostDelta > 0 ? '+' : '' }}{{ fmt(planCostDelta) }}）
              </span>
            </b>
          </div>
        </div>

        <el-table :data="changedItems" size="small" style="margin-top:10px">
          <el-table-column label="原料" prop="materialName" width="100" />
          <el-table-column label="占比 旧 → 新" width="140">
            <template #default="{ row }">
              {{ pct(row.ratioBefore) }} → {{ pct(row.ratioAfter) }}
            </template>
          </el-table-column>
          <el-table-column label="计划投料 旧 → 新" width="180">
            <template #default="{ row }">
              {{ kg(row.plannedKgBefore) }} → {{ kg(row.plannedKgAfter) }}
            </template>
          </el-table-column>
          <el-table-column label="变化">
            <template #default="{ row }">
              <span class="tag-chip" :class="changeChip(row.change)">{{ changeName(row.change) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!changedItems.length" class="muted" style="margin-top:10px">新旧版本明细一致，仅版本号变化。</div>

        <div style="margin-top:12px;line-height:1.8">
          <b>迁移到 V{{ migration.preview.toRevisionNo }}</b>：投料明细、计划用量全部按新版本重新生成（当前全部未投料，可安全重建）；<br>
          <b>保持 V{{ migration.preview.fromRevisionNo }}</b>：确认冻结在原版本快照，投料明细不变。已开始投料/制粒/质检的批次不允许迁移。
        </div>
        <div style="margin-top:10px;display:flex;gap:8px;align-items:center">
          <span class="muted">操作员</span>
          <el-input v-model="migration.operatorName" maxlength="32" placeholder="如：李班长" style="width:180px" size="small" />
        </div>
      </div>
      <template #footer>
        <el-button @click="migration.visible = false">再想想</el-button>
        <el-button :loading="migration.busy" @click="choose('FREEZE')">保持 V{{ migration.preview?.fromRevisionNo }}（冻结）</el-button>
        <el-button type="primary" :loading="migration.busy" @click="choose('MIGRATE')">
          迁移到 V{{ migration.preview?.toRevisionNo }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 迁移/冻结记录 -->
    <el-dialog v-model="migrations.visible" :title="`迁移记录 · ${batch?.batchNo || ''}`" width="680px">
      <div v-if="!migrations.list.length" class="mini muted">暂无迁移/冻结记录</div>
      <el-table v-else :data="migrations.list" size="small">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ row.createdAt?.replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <span class="tag-chip" :class="row.action === 'MIGRATE' ? 'chip-pass' : 'chip-gray'">
              {{ row.action === 'MIGRATE' ? '迁移新版本' : '冻结原版本' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="110">
          <template #default="{ row }">V{{ row.fromRevisionNo }} → V{{ row.toRevisionNo }}</template>
        </el-table-column>
        <el-table-column label="计划成本 前 → 后" width="180">
          <template #default="{ row }">¥{{ fmt(row.costBefore) }} → ¥{{ fmt(row.costAfter) }}</template>
        </el-table-column>
        <el-table-column label="操作员" width="80" prop="operatorName" />
        <el-table-column label="说明" prop="reason" show-overflow-tooltip />
      </el-table>
      <div class="mini muted" style="margin-top:10px">
        批次当前采用 {{ batch?.recipe.recipeCode }} V{{ batch?.recipe.revisionNo }}（{{ recipeStatusName(batch?.recipe.status) }}）。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchBatches, fetchBatch, feeding,
  fetchMigrationPreview, migrateBatch, fetchBatchMigrations
} from '../api'
import { useFeedingCountdown } from '../composables/useFeedingCountdown'

const TOTAL = 5
const CIRC = 2 * Math.PI * 18

const batches = ref([])
const batch = ref(null)
const batchId = ref(null)
const sectionEl = ref(null)
let feedingObserver = null

const migration = ref({ visible: false, busy: false, preview: null, operatorName: '' })
const migrations = ref({ visible: false, list: [] })

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
/** 只展示有变化的明细行 */
const changedItems = computed(() =>
  (migration.value.preview?.items || []).filter(i => i.change !== 'SAME')
)
const planCostDelta = computed(() => {
  const p = migration.value.preview
  if (!p) return 0
  return Number(p.costAfter || 0) - Number(p.costBefore || 0)
})

function statusName (s) {
  return { PLANNED: '待排产', FEEDING: '投料中', PELLETING: '制粒中', QC: '质检中', RECHECK: '复检中', DONE: '已完成' }[s] || s
}
function recipeStatusName (s) {
  return { DRAFT: '草稿', RELEASED: '生效中', SUPERSEDED: '已被取代' }[s] || s
}
function changeName (c) {
  return { ADDED: '新增原料', REMOVED: '移除原料', CHANGED: '调占比', SAME: '未变' }[c] || c
}
function changeChip (c) {
  return { ADDED: 'chip-pass', REMOVED: 'chip-reject', CHANGED: 'chip-down', SAME: 'chip-gray' }[c] || 'chip-gray'
}
function fmt (v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function pct (v) {
  return v == null ? '—' : Number(v).toFixed(2) + '%'
}
function kg (v) {
  return v == null ? '—' : Number(v).toFixed(0) + ' kg'
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

/** 打开迁移选择：先拉差异预览，让操作员看清影响 */
async function openMigration () {
  try {
    const preview = await fetchMigrationPreview(batchId.value)
    if (!preview.canMigrate) {
      ElMessage.warning(preview.blockReason || '当前批次不允许迁移')
      return
    }
    migration.value.preview = preview
    migration.value.visible = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 迁移或冻结：确认后刷新批次与列表，版本/明细与后端一致 */
async function choose (action) {
  if (!migration.value.operatorName.trim()) {
    ElMessage.warning('请填写操作员')
    return
  }
  migration.value.busy = true
  try {
    const fresh = await migrateBatch(batchId.value, {
      action,
      operatorName: migration.value.operatorName.trim()
    })
    migration.value.visible = false
    batch.value = fresh
    ElMessage.success(action === 'MIGRATE'
      ? `已迁移到 V${fresh.recipe.revisionNo}，投料明细已按新版本重新生成`
      : `已确认冻结在 V${fresh.recipe.revisionNo}，继续使用原版本快照`)
    batches.value = await fetchBatches().catch(() => batches.value)
  } catch (e) {
    ElMessage.error(e.message)
    migration.value.visible = false
    await loadBatch()
    batches.value = await fetchBatches().catch(() => batches.value)
  } finally {
    migration.value.busy = false
  }
}

async function openMigrations () {
  if (!batchId.value) return
  try {
    migrations.value.list = await fetchBatchMigrations(batchId.value)
    migrations.value.visible = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  batches.value = await fetchBatches().catch(() => [])
  // 优先定位有待选择迁移的批次，其次有待投料的批次
  const pendingMigration = batches.value.find(b => b.migrationStatus === 'PENDING')
  const pendingFeed = batches.value.find(b => (b.feedings || []).some(f => f.status === 'PENDING'))
  const target = pendingMigration || pendingFeed || batches.value[0]
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
