<template>
  <div>
    <div class="section-head">
      <h2>02 配料</h2>
      <span>已发布配方只读 · 修订生成新版本 · 未开工批次默认冻结旧版本，确认后才可迁移</span>
    </div>

    <div class="panel">
      <div class="panel-title">
        <span>配方版本与营养指标</span>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="recipeId" size="small" style="width:330px" @change="loadRecipe(false)">
            <el-option
              v-for="r in recipes"
              :key="r.id"
              :label="versionLabel(r)"
              :value="r.id"
            />
          </el-select>
          <el-button size="small" @click="loadRecipe(false)">刷新</el-button>
        </div>
      </div>

      <div v-if="recipe" class="version-banner" :class="bannerClass">
        <div>
          <b>{{ recipe.recipeCode }} V{{ recipe.versionNo }}</b>
          <el-tag size="small" :type="statusTag" effect="plain" style="margin-left:8px">
            {{ statusName(recipe.status) }}
          </el-tag>
          <span v-if="recipe.currentFlag === 1" class="mini" style="margin-left:8px">当前排产版本</span>
          <span v-else-if="recipe.status === 'SUPERSEDED'" class="mini" style="margin-left:8px">历史只读快照</span>
          <span v-else class="mini" style="margin-left:8px">修订草稿 · 不影响已排产批次</span>
        </div>
        <div>
          <el-button v-if="recipe.currentFlag === 1" size="small" type="warning" @click="openRevision">
            发起修订
          </el-button>
          <el-button v-if="recipe.status === 'DRAFT'" size="small" type="primary" @click="openPublish">
            发布修订
          </el-button>
          <el-button size="small" @click="loadDiffs" :disabled="!recipe.sourceRecipeId">
            查看修订差异
          </el-button>
        </div>
      </div>

      <div v-if="recipe.changeSummary" class="mini muted" style="margin:8px 0">
        修订说明：{{ recipe.changeSummary }}
        <span v-if="recipe.publishedBy"> · 发布人：{{ recipe.publishedBy }}</span>
      </div>

      <div class="grid-2">
        <div>
          <div class="param-card"><span>适用畜种</span><b>{{ speciesName(recipe.species) }} / {{ recipe.stage }}</b></div>
          <div class="param-card" style="margin-top:8px"><span>粗蛋白</span><b>{{ recipe.crudeProtein }} %</b></div>
          <div class="param-card" style="margin-top:8px"><span>代谢能</span><b>{{ recipe.metabolicEnergy }} MJ/kg</b></div>
          <div class="param-card" style="margin-top:8px"><span>赖氨酸</span><b>{{ recipe.lysine }} %</b></div>
          <div class="param-card" style="margin-top:8px">
            <span>版本吨成本</span><b style="color:#b71c1c">¥ {{ fmt(recipe.costPerTon) }}</b>
          </div>
          <div class="mini muted" style="margin-top:10px;line-height:1.8">
            发布策略：修订发布后旧版本立即转为历史快照；尚未开工的计划批次不会自动换版，
            默认继续按旧版本排产，由操作员在投料页逐项确认影响后迁移。
          </div>
        </div>

        <div>
          <div class="stack-bar" :class="{ readonly: recipe.status !== 'DRAFT' }">
            <div
              v-for="(item, i) in recipe.items"
              :key="item.id"
              class="stack-seg"
              :class="{ selected: selectedId === item.id, locked: item.locked }"
              :style="{ flexGrow: Number(item.ratioPct), background: colorOf(i) }"
              :title="`${item.material.name} ${item.ratioPct}%`"
              @click="recipe.status === 'DRAFT' && (selectedId = item.id)"
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
            <span v-else-if="recipe.status === 'DRAFT'" class="total-ok">配比合格，可发布修订</span>
            <span v-else class="muted mini">已发布版本只读，不能直接调比例</span>
          </div>

          <div class="legend">
            <div
              v-for="(item, i) in recipe.items"
              :key="item.id"
              class="legend-row"
              :class="{ selected: selectedId === item.id }"
              @click="recipe.status === 'DRAFT' && (selectedId = item.id)"
            >
              <span class="legend-dot" :style="{ background: colorOf(i) }"></span>
              <span class="legend-name">
                {{ item.material.name }}
                <span class="muted mini">({{ item.material.siloCode }} · 上限 {{ item.material.maxRatio }}%)</span>
              </span>
              <b class="mono">{{ Number(item.ratioPct).toFixed(2) }}%</b>
              <template v-if="recipe.status === 'DRAFT'">
                <el-button-group size="small">
                  <el-button :disabled="busy" @click.stop="adjust(item, -0.5)">−</el-button>
                  <el-button :disabled="busy" @click.stop="adjust(item, 0.5)">+</el-button>
                </el-button-group>
                <el-button
                  size="small"
                  :type="item.locked ? 'warning' : 'default'"
                  :disabled="busy"
                  @click.stop="spread(item)"
                >
                  {{ item.locked ? '已锁定' : '锁定平摊' }}
                </el-button>
              </template>
              <span v-else class="muted mini">只读</span>
              <span class="muted mini mono">¥{{ Number(item.costSubtotal).toFixed(2) }}</span>
            </div>
          </div>
        </div>
      </div>

      <el-divider v-if="diffs.length" content-position="left">修订前后差异</el-divider>
      <el-table v-if="diffs.length" :data="diffs" size="small" border>
        <el-table-column prop="itemName" label="项目" width="130" />
        <el-table-column label="变化" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="changeTag(row.changeType)">{{ changeName(row.changeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="旧值">
          <template #default="{ row }">{{ diffOld(row) }}</template>
        </el-table-column>
        <el-table-column label="新值">
          <template #default="{ row }">{{ diffNew(row) }}</template>
        </el-table-column>
        <el-table-column label="成本小计变化 元/吨" width="210">
          <template #default="{ row }">
            <span :style="{ color: costDeltaColor(row) }">
              {{ fmt(row.oldCostSubtotal) }} → {{ fmt(row.newCostSubtotal) }}
              （{{ costDelta(row) > 0 ? '+' : '' }}{{ fmt(costDelta(row)) }}）
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="revisionDialog" title="发起配方修订" width="460px">
      <div class="mini muted" style="line-height:1.8;margin-bottom:12px">
        将复制当前已发布版本生成草稿。原版本保持只读；多人同时发起时只允许一个草稿。
      </div>
      <el-form label-width="82px">
        <el-form-item label="操作人" required>
          <el-input v-model="revisionForm.operatorName" placeholder="例如：工艺员" />
        </el-form-item>
        <el-form-item label="修订原因">
          <el-input v-model="revisionForm.changeSummary" type="textarea" :rows="3"
                    placeholder="说明本次调整目的，例如降低鱼粉用量" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revisionDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="createRevision">创建草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="publishDialog" title="发布修订并确认批次影响" width="560px">
      <el-alert
        title="发布后旧版本成为历史快照。未开工计划批次默认继续使用旧版本，不会自动迁移；已投料、制粒或质检批次永远保持原版本和原明细。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
      />
      <el-form label-width="82px">
        <el-form-item label="发布人" required>
          <el-input v-model="revisionForm.operatorName" placeholder="请输入发布人" />
        </el-form-item>
        <el-form-item label="修订说明">
          <el-input v-model="revisionForm.changeSummary" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="publish">确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchRecipes,
  fetchRecipe,
  fetchRecipeDiffs,
  adjustRatio,
  spreadRatio,
  submitRecipe,
  createRecipeRevision
} from '../api'

const COLORS = ['#b71c1c', '#ef6c00', '#f9a825', '#2e7d32', '#1565c0',
  '#6a1b9a', '#00838f', '#8d6e63', '#455a64', '#c2185b', '#5d4037']
const SPECIES = { SWINE: '猪', POULTRY: '禽', AQUA: '水产', RUMINANT: '反刍' }

const recipes = ref([])
const recipe = ref(null)
const recipeId = ref(null)
const selectedId = ref(null)
const busy = ref(false)
const diffs = ref([])
const revisionDialog = ref(false)
const publishDialog = ref(false)
const revisionForm = ref({ operatorName: '工艺员', changeSummary: '' })

const total = computed(() =>
  (recipe.value?.items || []).reduce((s, i) => s + Number(i.ratioPct || 0), 0)
)
const diff = computed(() => 100 - total.value)
const diffOk = computed(() => Math.abs(diff.value) < 0.005)
const bannerClass = computed(() => ({
  draft: recipe.value?.status === 'DRAFT',
  old: recipe.value?.status === 'SUPERSEDED'
}))
const statusTag = computed(() => ({ DRAFT: 'warning', RELEASED: 'success', SUPERSEDED: 'info' }[recipe.value?.status]))

function colorOf (i) {
  return COLORS[i % COLORS.length]
}
function speciesName (s) {
  return SPECIES[s] || s
}
function statusName (s) {
  return { DRAFT: '草稿', RELEASED: '已发布', SUPERSEDED: '已被替代' }[s] || s
}
function versionLabel (r) {
  const status = r.status === 'DRAFT' ? '草稿' : (r.currentFlag === 1 ? '当前' : '历史')
  return `${r.recipeCode} V${r.versionNo} · ${r.name} · ${status}`
}
function fmt (v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function changeName (t) {
  return { ADDED: '新增', REMOVED: '删除', MODIFIED: '调整' }[t] || t
}
function changeTag (t) {
  return { ADDED: 'success', REMOVED: 'danger', MODIDED: 'warning', MODIFIED: 'warning' }[t] || 'info'
}
function diffOld (row) {
  if (row.oldRatioPct != null) return `${Number(row.oldRatioPct).toFixed(2)}%`
  return row.oldValue ? `${row.oldValue} ${row.unit || ''}` : '—'
}
function diffNew (row) {
  if (row.newRatioPct != null) return `${Number(row.newRatioPct).toFixed(2)}%`
  return row.newValue ? `${row.newValue} ${row.unit || ''}` : '—'
}
function costDelta (row) {
  if (row.oldCostSubtotal == null && row.newCostSubtotal == null) return 0
  return Number(row.newCostSubtotal || 0) - Number(row.oldCostSubtotal || 0)
}
function costDeltaColor (row) {
  const delta = costDelta(row)
  if (Math.abs(delta) < 0.005) return '#666'
  return delta > 0 ? '#c62828' : '#2e7d32'
}

async function refreshRecipes (selectId) {
  recipes.value = await fetchRecipes().catch(() => [])
  if (selectId) recipeId.value = selectId
  else if (!recipeId.value && recipes.value.length) recipeId.value = recipes.value[0].id
  await loadRecipe(false)
}

async function loadRecipe (showMessage = false) {
  if (!recipeId.value) return
  recipe.value = await fetchRecipe(recipeId.value)
  selectedId.value = recipe.value.items?.[0]?.id || null
  diffs.value = []
  if (recipe.value.sourceRecipeId) {
    diffs.value = await fetchRecipeDiffs(recipe.value.id).catch(() => [])
  }
  if (showMessage) ElMessage.success('配方版本已刷新')
}

async function loadDiffs () {
  if (!recipe.value?.sourceRecipeId) {
    ElMessage.info('初始发布版本没有修订差异')
    return
  }
  diffs.value = await fetchRecipeDiffs(recipe.value.id)
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

function openRevision () {
  revisionForm.value = { operatorName: revisionForm.value.operatorName || '工艺员', changeSummary: '' }
  revisionDialog.value = true
}

async function createRevision () {
  if (!revisionForm.value.operatorName?.trim()) {
    ElMessage.warning('请填写操作人')
    return
  }
  busy.value = true
  try {
    const draft = await createRecipeRevision(recipeId.value, revisionForm.value)
    ElMessage.success('已创建修订草稿，请在草稿上调整占比后发布')
    revisionDialog.value = false
    await refreshRecipes(draft.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

function openPublish () {
  publishDialog.value = true
}

async function publish () {
  if (!revisionForm.value.operatorName?.trim()) {
    ElMessage.warning('请填写发布人')
    return
  }
  busy.value = true
  try {
    const released = await submitRecipe(recipeId.value, revisionForm.value)
    ElMessage.success('修订已发布；未开工批次默认冻结旧版本，请到投料页确认是否迁移')
    publishDialog.value = false
    await refreshRecipes(released.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

onMounted(() => refreshRecipes())
</script>

<style scoped>
.version-banner {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:12px;
  margin-top:12px;
  padding:10px 12px;
  border:1px solid #e5e5e5;
  border-left:4px solid #2e7d32;
  border-radius:8px;
  background:#f7fbf7;
}
.version-banner.draft { border-left-color:#ef6c00; background:#fff8ed; }
.version-banner.old { border-left-color:#999; background:#f6f6f6; }
.stack-bar.readonly { cursor:not-allowed; opacity:.82; }
:deep(.el-table .cell) { line-height:1.45; }
</style>
