<template>
  <div>
    <div class="section-head">
      <h2>02 配料</h2>
      <span>堆叠占比条 · 点色段微调 ±0.5% · 已发布配方只读，改动走「修订版本」</span>
    </div>

    <div class="panel">
      <div class="panel-title">
        <span>配方选择与营养指标</span>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="recipeId" size="small" style="width:340px" @change="loadRecipe">
            <el-option
              v-for="r in recipes"
              :key="r.id"
              :label="`${r.recipeCode} · ${r.name} · V${r.revisionNo}（${statusName(r.status)}）`"
              :value="r.id"
            />
          </el-select>
          <el-button size="small" @click="openHistory">版本与修订记录</el-button>
        </div>
      </div>

      <div v-if="recipe" class="grid-2">
        <div>
          <div class="param-card">
            <span>版本 / 状态</span>
            <b>
              V{{ recipe.revisionNo }}
              <span class="tag-chip" :class="statusChip(recipe.status)" style="margin-left:6px">
                {{ statusName(recipe.status) }}
              </span>
            </b>
          </div>
          <div class="param-card" style="margin-top:8px"><span>适用畜种</span><b>{{ speciesName(recipe.species) }} / {{ recipe.stage }}</b></div>
          <div class="param-card" style="margin-top:8px"><span>粗蛋白</span><b>{{ recipe.crudeProtein }} %</b></div>
          <div class="param-card" style="margin-top:8px"><span>代谢能</span><b>{{ recipe.metabolicEnergy }} MJ/kg</b></div>
          <div class="param-card" style="margin-top:8px"><span>赖氨酸</span><b>{{ recipe.lysine }} %</b></div>
          <div class="param-card" style="margin-top:8px"><span>吨成本</span><b style="color:#b71c1c">¥ {{ fmt(recipe.costPerTon) }}</b></div>
          <div v-if="recipe.revisionNote" class="mini muted" style="margin-top:8px">
            修订说明：{{ recipe.revisionNote }}（{{ recipe.operatorName || '-' }}）
          </div>

          <!-- 版本操作区：已发布只读走修订，草稿可发布/废弃 -->
          <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap">
            <template v-if="recipe.status === 'RELEASED'">
              <el-button size="small" type="primary" @click="reviseDialog.visible = true">创建修订</el-button>
            </template>
            <template v-else-if="recipe.status === 'DRAFT'">
              <el-button size="small" type="primary" :disabled="!diffOk" @click="openPublish">发布修订</el-button>
              <el-button size="small" type="danger" plain @click="discard">废弃草稿</el-button>
            </template>
          </div>
          <div class="mini muted" style="margin-top:8px">
            <template v-if="recipe.status === 'RELEASED'">
              已发布配方是只读快照，改动请「创建修订」生成 V{{ recipe.revisionNo + 1 }} 草稿。
            </template>
            <template v-else-if="recipe.status === 'DRAFT'">
              草稿 V{{ recipe.revisionNo }} 基于 V{{ recipe.revisionNo - 1 }} 复制，可继续微调；发布后旧版本转为历史快照。
            </template>
          </div>
        </div>

        <div>
          <!-- 横向堆叠占比条 -->
          <div class="stack-bar">
            <div
              v-for="(item, i) in recipe.items"
              :key="item.id"
              class="stack-seg"
              :class="{ selected: selectedId === item.id, locked: item.locked }"
              :style="{ flexGrow: Number(item.ratioPct), background: colorOf(i) }"
              :title="`${item.material.name} ${item.ratioPct}%`"
              @click="selectedId = item.id"
            >
              <span v-if="Number(item.ratioPct) >= 6">{{ item.material.name }} {{ item.ratioPct }}%</span>
              <span v-else>&nbsp;</span>
            </div>
          </div>

          <div class="total-line">
            <span>占比合计</span>
            <span class="big" :class="diffOk ? 'total-ok' : 'total-bad'">{{ total.toFixed(2) }} %</span>
            <span v-if="!diffOk" class="total-bad">
              与 100% 相差 {{ diff.toFixed(2) }} 个百分点（{{ diff > 0 ? '还差' : '超出' }}）
            </span>
            <span v-else class="total-ok">配比合格，可发布</span>
          </div>

          <div class="legend">
            <div
              v-for="(item, i) in recipe.items"
              :key="item.id"
              class="legend-row"
              :class="{ selected: selectedId === item.id }"
              @click="selectedId = item.id"
            >
              <span class="legend-dot" :style="{ background: colorOf(i) }"></span>
              <span class="legend-name">
                {{ item.material.name }}
                <span class="muted mini">({{ item.material.siloCode }} · 上限 {{ item.material.maxRatio }}%)</span>
              </span>
              <b class="mono">{{ Number(item.ratioPct).toFixed(2) }}%</b>
              <el-button-group size="small">
                <el-button :disabled="busy || readonly" @click.stop="adjust(item, -0.5)">−</el-button>
                <el-button :disabled="busy || readonly" @click.stop="adjust(item, 0.5)">+</el-button>
              </el-button-group>
              <el-button
                size="small"
                :type="item.locked ? 'warning' : 'default'"
                :disabled="busy || readonly"
                @click.stop="spread(item)"
              >
                {{ item.locked ? '已锁定' : '锁定平摊' }}
              </el-button>
              <span class="muted mini mono">¥{{ Number(item.costSubtotal).toFixed(2) }}</span>
            </div>
          </div>

          <div class="mini muted" style="margin-top:10px">
            规则：单一原料不得超过其占比上限（鱼粉 ≤ 8%）；合计必须为 100.00%，否则发布校验会拦截并给出差值。
          </div>
        </div>
      </div>
    </div>

    <!-- 创建修订 -->
    <el-dialog v-model="reviseDialog.visible" title="创建修订版本" width="440px">
      <div class="mini muted" style="margin-bottom:10px">
        基于当前生效版本 V{{ recipe?.revisionNo }} 复制一份 V{{ (recipe?.revisionNo || 0) + 1 }} 草稿，
        原版本保持不变；草稿微调后再发布，发布时旧版本转为历史快照。
      </div>
      <div style="margin-bottom:12px">
        <div class="mini muted" style="margin-bottom:6px">修订人</div>
        <el-input v-model="reviseDialog.operatorName" maxlength="32" placeholder="如：王配方" />
      </div>
      <div>
        <div class="mini muted" style="margin-bottom:6px">修订说明</div>
        <el-input
          v-model="reviseDialog.note"
          type="textarea"
          :rows="3"
          maxlength="255"
          placeholder="为什么改：如 鱼粉涨价，下调 1% 并补玉米"
        />
      </div>
      <template #footer>
        <el-button @click="reviseDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="reviseDialog.busy" @click="revise">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 发布修订确认：看清影响后再确认 -->
    <el-dialog v-model="publishDialog.visible" title="发布修订确认" width="520px">
      <div v-if="publishDialog.preview" class="mini">
        <div class="param-card">
          <span>版本变化</span>
          <b>V{{ publishDialog.preview.fromRevisionNo }}（生效中）→ V{{ publishDialog.preview.toRevisionNo }}（新生效）</b>
        </div>
        <div class="param-card" style="margin-top:8px">
          <span>吨成本</span>
          <b>
            ¥{{ fmt(publishDialog.preview.costPerTonBefore) }} → ¥{{ fmt(publishDialog.preview.costPerTonAfter) }}
            <span :style="{ color: costDelta(publishDialog.preview) > 0 ? '#c62828' : '#2e7d32' }">
              （{{ costDelta(publishDialog.preview) > 0 ? '+' : '' }}{{ fmt(costDelta(publishDialog.preview)) }}）
            </span>
          </b>
        </div>
        <div style="margin-top:10px;line-height:1.8">
          发布后对批次的影响（<b>默认冻结，不自动改动任何批次</b>）：<br>
          · <b>{{ publishDialog.preview.plannedBatches }}</b> 个计划中批次将继续使用 V{{ publishDialog.preview.fromRevisionNo }} 快照，
          并标记「待选择」，由操作员逐批决定迁移或冻结；<br>
          · 已开始投料 / 制粒 / 质检的批次<b>不受任何影响</b>，永远保持原版本与原投料明细。
        </div>
      </div>
      <template #footer>
        <el-button @click="publishDialog.visible = false">再想想</el-button>
        <el-button type="primary" :loading="publishDialog.busy" @click="publish">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- 版本链 + 修订记录（含差异） -->
    <el-dialog v-model="history.visible" :title="`版本与修订记录 · ${recipe?.recipeCode || ''}`" width="760px">
      <div class="mini muted" style="margin-bottom:8px">版本链（被取代版本为只读历史快照，批次引用哪版就锁定哪版）</div>
      <el-table :data="history.revisions" size="small" style="margin-bottom:16px">
        <el-table-column label="版本" width="70">
          <template #default="{ row }">V{{ row.revisionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="tag-chip" :class="statusChip(row.status)">{{ statusName(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="吨成本" width="110">
          <template #default="{ row }">¥ {{ fmt(row.costPerTon) }}</template>
        </el-table-column>
        <el-table-column label="修订人" width="90" prop="operatorName" />
        <el-table-column label="修订说明" prop="revisionNote" show-overflow-tooltip />
      </el-table>

      <div class="mini muted" style="margin-bottom:8px">修订记录（每次发布的差异快照）</div>
      <div v-if="!history.logs.length" class="mini muted">暂无修订记录</div>
      <div v-for="log in history.logs" :key="log.id" class="rev-log">
        <div class="rev-log-head">
          <b>V{{ log.fromRevisionNo ?? '-' }} → V{{ log.toRevisionNo }}</b>
          <span class="muted">{{ log.operatorName }} · {{ log.createdAt?.replace('T', ' ') }}</span>
          <span class="muted">吨成本 ¥{{ fmt(log.costBefore) }} → ¥{{ fmt(log.costAfter) }}</span>
          <span class="tag-chip chip-down" v-if="log.affectedBatches > 0">{{ log.affectedBatches }} 个计划批次待选择</span>
          <span class="tag-chip chip-gray" v-else>无计划中批次受影响</span>
        </div>
        <div v-if="log.note" class="mini muted" style="margin:4px 0">说明：{{ log.note }}</div>
        <el-table :data="parseDiff(log.diffJson)" size="small">
          <el-table-column label="原料" prop="materialName" width="110" />
          <el-table-column label="旧占比" width="90">
            <template #default="{ row }">{{ row.ratioBefore == null ? '—' : Number(row.ratioBefore).toFixed(2) + '%' }}</template>
          </el-table-column>
          <el-table-column label="新占比" width="90">
            <template #default="{ row }">{{ row.ratioAfter == null ? '—' : Number(row.ratioAfter).toFixed(2) + '%' }}</template>
          </el-table-column>
          <el-table-column label="变化" width="90">
            <template #default="{ row }">
              <span class="tag-chip" :class="changeChip(row.change)">{{ changeName(row.change) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成本小计 旧 → 新">
            <template #default="{ row }">
              ¥{{ fmt(row.costBefore) }} → ¥{{ fmt(row.costAfter) }}
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchRecipes, fetchRecipe, adjustRatio, spreadRatio, submitRecipe,
  reviseRecipe, discardRevision, fetchRevisions, fetchRevisionLogs, fetchBatches
} from '../api'

const COLORS = ['#b71c1c', '#ef6c00', '#f9a825', '#2e7d32', '#1565c0',
  '#6a1b9a', '#00838f', '#8d6e63', '#455a64', '#c2185b', '#5d4037']
const SPECIES = { SWINE: '猪', POULTRY: '禽', AQUA: '水产', RUMINANT: '反刍' }

const recipes = ref([])
const recipe = ref(null)
const recipeId = ref(null)
const selectedId = ref(null)
const busy = ref(false)

const reviseDialog = ref({ visible: false, busy: false, operatorName: '', note: '' })
const publishDialog = ref({ visible: false, busy: false, preview: null })
const history = ref({ visible: false, revisions: [], logs: [] })

const total = computed(() =>
  (recipe.value?.items || []).reduce((s, i) => s + Number(i.ratioPct || 0), 0)
)
const diff = computed(() => 100 - total.value)
const diffOk = computed(() => Math.abs(diff.value) < 0.005)
/** 已发布/被取代版本是只读快照 */
const readonly = computed(() => recipe.value?.status !== 'DRAFT')

function colorOf (i) {
  return COLORS[i % COLORS.length]
}
function speciesName (s) {
  return SPECIES[s] || s
}
function statusName (s) {
  return { DRAFT: '草稿', RELEASED: '生效中', SUPERSEDED: '已被取代' }[s] || s
}
function statusChip (s) {
  return { DRAFT: 'chip-down', RELEASED: 'chip-pass', SUPERSEDED: 'chip-gray' }[s] || 'chip-gray'
}
function changeName (c) {
  return { ADDED: '新增', REMOVED: '移除', CHANGED: '调占比', SAME: '未变' }[c] || c
}
function changeChip (c) {
  return { ADDED: 'chip-pass', REMOVED: 'chip-reject', CHANGED: 'chip-down', SAME: 'chip-gray' }[c] || 'chip-gray'
}
function fmt (v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function costDelta (p) {
  return Number(p.costPerTonAfter || 0) - Number(p.costPerTonBefore || 0)
}
function parseDiff (json) {
  try {
    return (JSON.parse(json || '{}').items || []).filter(r => r.change !== 'SAME')
  } catch {
    return []
  }
}

async function loadRecipe () {
  if (!recipeId.value) return
  recipe.value = await fetchRecipe(recipeId.value)
  selectedId.value = recipe.value.items?.[0]?.id || null
}

async function refreshList (keepId) {
  recipes.value = await fetchRecipes().catch(() => [])
  if (keepId && recipes.value.some(r => r.id === keepId)) {
    recipeId.value = keepId
  }
}

async function adjust (item, delta) {
  busy.value = true
  try {
    recipe.value = await adjustRatio(recipeId.value, { itemId: item.id, delta })
    ElMessage.success(`${item.material.name} 占比调整 ${delta > 0 ? '+' : ''}${delta}%`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function spread (item) {
  busy.value = true
  try {
    recipe.value = await spreadRatio(recipeId.value, { itemId: item.id, spread: true })
    ElMessage.success(`已锁定 ${item.material.name} ${Number(item.ratioPct).toFixed(2)}%，其余原料自动平摊至 100%`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

/** 创建修订草稿：成功后切到草稿继续微调 */
async function revise () {
  if (!reviseDialog.value.operatorName.trim()) {
    ElMessage.warning('请填写修订人')
    return
  }
  reviseDialog.value.busy = true
  try {
    const draft = await reviseRecipe(recipeId.value, {
      operatorName: reviseDialog.value.operatorName.trim(),
      note: reviseDialog.value.note.trim()
    })
    reviseDialog.value.visible = false
    ElMessage.success(`已创建 V${draft.revisionNo} 修订草稿，可继续微调`)
    await refreshList(draft.id)
    await loadRecipe()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    reviseDialog.value.busy = false
  }
}

/** 废弃草稿 */
async function discard () {
  try {
    await ElMessageBox.confirm(
      `确定废弃 V${recipe.value.revisionNo} 修订草稿？草稿内容将被删除，当前生效版本不受影响。`,
      '废弃草稿',
      { type: 'warning', confirmButtonText: '废弃', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await discardRevision(recipeId.value)
    ElMessage.success('修订草稿已废弃')
    await refreshList()
    const current = recipes.value.find(r => r.recipeCode === recipe.value.recipeCode && r.status === 'RELEASED')
    recipeId.value = (current || recipes.value[0])?.id || null
    await loadRecipe()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 发布前确认：把影响讲清楚（默认冻结 + 待选择） */
async function openPublish () {
  try {
    const chain = await fetchRevisions(recipeId.value)
    const base = chain.find(r => r.id === recipe.value.basedOnId)
    const planned = await plannedBatchCount()
    publishDialog.value.preview = {
      fromRevisionNo: base?.revisionNo ?? recipe.value.revisionNo - 1,
      toRevisionNo: recipe.value.revisionNo,
      costPerTonBefore: base?.costPerTon ?? 0,
      costPerTonAfter: recipe.value.costPerTon,
      plannedBatches: planned
    }
    publishDialog.value.visible = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 计划中且仍引用旧版本的批次数（发布后将标记为待选择） */
async function plannedBatchCount () {
  try {
    const batches = await fetchBatches()
    return batches.filter(b => b.status === 'PLANNED' && b.recipe?.id === recipe.value.basedOnId).length
  } catch {
    return 0
  }
}

async function publish () {
  publishDialog.value.busy = true
  try {
    const released = await submitRecipe(recipeId.value)
    publishDialog.value.visible = false
    ElMessage.success(`V${released.revisionNo} 已发布生效，旧版本已转为历史快照`)
    await refreshList(released.id)
    await loadRecipe()
  } catch (e) {
    ElMessage.error(e.message)
    publishDialog.value.visible = false
    await refreshList(recipeId.value)
    await loadRecipe()
  } finally {
    publishDialog.value.busy = false
  }
}

async function openHistory () {
  if (!recipeId.value) return
  try {
    const [revisions, logs] = await Promise.all([
      fetchRevisions(recipeId.value),
      fetchRevisionLogs(recipeId.value)
    ])
    history.value = { visible: true, revisions, logs }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  recipes.value = await fetchRecipes().catch(() => [])
  if (recipes.value.length) {
    // 默认选中第一个生效版本
    const released = recipes.value.find(r => r.status === 'RELEASED')
    recipeId.value = (released || recipes.value[0]).id
    await loadRecipe()
  }
})
</script>

<style scoped>
.rev-log {
  border: 1px solid #f0e0e0;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.rev-log-head {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
}
</style>
