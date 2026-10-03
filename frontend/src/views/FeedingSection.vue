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
          <el-select v-model="batchId" size="small" style="width:300px" @change="loadBatch">
            <el-option v-for="b in batches" :key="b.id" :label="batchOptionLabel(b)" :value="b.id" />
          </el-select>
          <el-button size="small" @click="loadBatch">刷新</el-button>
          <el-button size="small" @click="loadMigrationHistory">版本记录</el-button>
        </div>
      </div>

      <div v-if="batch" class="batch-version-card">
        <div class="mini">
          批次 <b>{{ batch.batchNo }}</b> · 配方 <b>{{ batch.recipe.recipeCode }} V{{ batch.recipe.versionNo }}</b>
          · {{ batch.recipe.name }} · 计划产量 {{ batch.plannedTons }} 吨 ·
          状态 <span class="tag-chip chip-run">{{ statusName(batch.status) }}</span> ·
          已投 {{ doneCount }} / {{ batch.feedings.length }} 道 ·
          计划成本 <b style="color:#b71c1c">¥{{ fmt(batch.plannedCost) }}</b>
        </div>
        <div class="version-actions">
          <el-tag size="small" :type="freezeTagType" effect="plain">
            {{ freezeText }}
          </el-tag>
          <el-button
            v-if="batch.migrationAvailable"
            size="small"
            type="warning"
            @click="openMigration('MIGRATE')"
          >
            可迁移到 {{ batch.currentRecipe.recipeCode }} V{{ batch.currentRecipe.versionNo }}
          </el-button>
          <el-button
            v-else-if="['KEEP', 'STARTED'].includes(batch.versionFreezeReason) && batch.currentRecipe && batch.currentRecipe.id !== batch.recipe.id"
            size="small"
            disabled
          >
            不可迁移
          </el-button>
          <el-button
            v-if="batch.versionFreezeReason === 'DEFAULT' && batch.currentRecipe && batch.currentRecipe.id !== batch.recipe.id"
            size="small"
            @click="openMigration('KEEP')"
          >
            继续使用旧版本
          </el-button>
          <span v-if="batch.migrationBlockReason && batch.currentRecipe && batch.currentRecipe.id !== batch.recipe.id"
                class="mini muted">
            {{ batch.migrationBlockReason }}
          </span>
        </div>
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
          <div v-if="item.plannedCost" class="mini muted">计划成本 ¥{{ fmt(item.plannedCost) }}</div>
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

    <el-dialog v-model="migrationDialog" :title="migrationForm.action === 'MIGRATE' ? '确认迁移到新配方版本' : '确认继续使用原版本快照'" width="680px">
      <el-alert
        v-if="migrationForm.action === 'MIGRATE'"
        title="迁移仅允许未开始称量/投料的计划批次。提交后会重新生成投料明细、计划用量和计划成本；一旦开始投料，版本将永久冻结。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
      />
      <el-alert
        v-else
        title="确认后本批将继续按当前旧版本快照排产，投料明细、计划用量和成本不变，之后即使新版仍可用也不能再迁移；开始投料后同样永久冻结。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
      />
      <el-descriptions v-if="batch && batch.currentRecipe" :column="2" border size="small">
        <el-descriptions-item label="当前版本">
          {{ batch.recipe.recipeCode }} V{{ batch.recipe.versionNo }} · ¥{{ fmt(batch.recipe.costPerTon) }}/吨
        </el-descriptions-item>
        <el-descriptions-item label="新版本">
          {{ batch.currentRecipe.recipeCode }} V{{ batch.currentRecipe.versionNo }} · ¥{{ fmt(batch.currentRecipe.costPerTon) }}/吨
        </el-descriptions-item>
        <el-descriptions-item label="当前计划成本">¥{{ fmt(batch.plannedCost) }}</el-descriptions-item>
        <el-descriptions-item label="新版本预计成本">¥{{ fmt(newPlannedCost) }}</el-descriptions-item>
        <el-descriptions-item label="投料明细">
          {{ migrationForm.action === 'MIGRATE' ? `删除旧待投明细，按新版本重建 ${batch.currentRecipe.items.length} 道` : '保持现有明细不变' }}
        </el-descriptions-item>
        <el-descriptions-item label="版本追溯">每次确认均写入迁移记录，可从版本记录查看最终采用版本</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="82px" style="margin-top:14px">
        <el-form-item label="操作人" required>
          <el-input v-model="migrationForm.operatorName" placeholder="请输入确认操作人" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="migrationForm.reason" type="textarea" :rows="2" placeholder="可填写本次版本选择原因" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="migrationForm.impactConfirmed">
            我已看清版本、投料明细、计划用量和成本影响
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="migrationDialog = false">取消</el-button>
        <el-button :type="migrationForm.action === 'MIGRATE' ? 'warning' : 'primary'"
                   :loading="migrationBusy" @click="submitMigration">
          {{ migrationForm.action === 'MIGRATE' ? '确认迁移并重建明细' : '确认继续旧版本' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyDialog" title="配方版本决策与迁移记录" width="760px">
      <el-table :data="migrationHistory" size="small" border max-height="420">
        <el-table-column label="时间" width="155">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="migrationTag(row.action)">{{ migrationActionName(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最终版本" width="130">
          <template #default="{ row }">{{ row.toRecipe.recipeCode }} V{{ row.toRecipe.versionNo }}</template>
        </el-table-column>
        <el-table-column label="成本变化" width="190">
          <template #default="{ row }">
            ¥{{ fmt(row.oldPlannedCost) }} → ¥{{ fmt(row.newPlannedCost) }}
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="90" />
        <el-table-column prop="reason" label="说明" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-dialog>

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
import {
  fetchBatches,
  fetchBatch,
  feeding,
  decideBatchRecipeVersion,
  fetchBatchRecipeMigrations
} from '../api'
import { useFeedingCountdown } from '../composables/useFeedingCountdown'

const TOTAL = 5
const CIRC = 2 * Math.PI * 18

const batches = ref([])
const batch = ref(null)
const batchId = ref(null)
const sectionEl = ref(null)
const migrationDialog = ref(false)
const historyDialog = ref(false)
const migrationBusy = ref(false)
const migrationHistory = ref([])
const migrationForm = ref({
  action: 'MIGRATE',
  operatorName: '',
  reason: '',
  impactConfirmed: false
})
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
const newPlannedCost = computed(() => {
  if (!batch.value?.currentRecipe) return 0
  return Number(batch.value.currentRecipe.costPerTon || 0) * Number(batch.value.plannedTons || 0)
})
const freezeText = computed(() => {
  if (!batch.value) return ''
  return {
    DEFAULT: '默认冻结旧版本 · 可主动迁移',
    KEEP: '已确认继续旧版本 · 不可再迁移',
    STARTED: '已开工 · 版本永久冻结'
  }[batch.value.versionFreezeReason] || '已选择新版本，待开工冻结'
})
const freezeTagType = computed(() => {
  if (batch.value?.versionFreezeReason === 'DEFAULT') return 'warning'
  if (batch.value?.versionFreezeReason === 'STARTED') return 'danger'
  return batch.value?.recipeVersionLocked ? 'info' : 'success'
})

function statusName (s) {
  return { PLANNED: '待排产', FEEDING: '投料中', PELLETING: '制粒中', QC: '质检中', RECHECK: '复检中', DONE: '已完成' }[s] || s
}
function fmt (v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function batchOptionLabel (b) {
  const newer = b.migrationAvailable ? ' · 有新修订' : ''
  return `${b.batchNo} · ${b.recipe.recipeCode} V${b.recipe.versionNo} · ${b.recipe.name}${newer}`
}
function migrationActionName (a) {
  return { KEEP: '保留旧版', MIGRATE: '迁移新版', FREEZE: '开工冻结' }[a] || a
}
function migrationTag (a) {
  return { KEEP: 'info', MIGRATE: 'warning', FREEZE: 'danger' }[a] || 'info'
}
function formatTime (v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
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

function openMigration (action) {
  if (!batch.value?.currentRecipe) return
  migrationForm.value = {
    action,
    operatorName: batch.value.operatorName || '',
    reason: action === 'MIGRATE' ? '确认新修订配比、计划用量和成本后迁移' : '操作员确认本批继续使用原版本快照',
    impactConfirmed: false
  }
  migrationDialog.value = true
}

async function submitMigration () {
  if (!migrationForm.value.operatorName.trim()) {
    ElMessage.warning('请填写确认操作人')
    return
  }
  if (!migrationForm.value.impactConfirmed) {
    ElMessage.warning('请先勾选已看清影响')
    return
  }
  migrationBusy.value = true
  try {
    const action = migrationForm.value.action
    const targetRecipeId = action === 'MIGRATE' ? batch.value.currentRecipe.id : batch.value.recipe.id
    batch.value = await decideBatchRecipeVersion(batchId.value, {
      ...migrationForm.value,
      action,
      targetRecipeId,
      expectedLockVersion: batch.value.lockVersion
    })
    ElMessage.success(action === 'MIGRATE'
      ? '迁移成功：投料明细、计划用量和计划成本已按新版本重建'
      : '已确认继续使用原版本快照')
    migrationDialog.value = false
    batches.value = await fetchBatches().catch(() => [])
  } catch (e) {
    ElMessage.error(e.message)
    await loadBatch()
  } finally {
    migrationBusy.value = false
  }
}

async function loadMigrationHistory () {
  if (!batchId.value) return
  migrationHistory.value = await fetchBatchRecipeMigrations(batchId.value)
  historyDialog.value = true
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
  const pending = batches.value.find(b => b.migrationAvailable)
    || batches.value.find(b => (b.feedings || []).some(f => f.status === 'PENDING'))
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

<style scoped>
.batch-version-card {
  margin-bottom:10px;
  padding:10px 12px;
  border:1px solid #eee;
  border-left:4px solid #b71c1c;
  border-radius:8px;
  background:#fffafa;
  display:flex;
  flex-direction:column;
  gap:8px;
}
.version-actions {
  display:flex;
  align-items:center;
  gap:8px;
  flex-wrap:wrap;
}
</style>
