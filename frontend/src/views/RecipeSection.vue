<template>
  <div>
    <div class="section-head">
      <h2>02 配料</h2>
      <span>堆叠占比条 · 点色段微调 ±0.5% · 锁定某原料后自动平摊其余至 100%</span>
    </div>

    <div class="panel">
      <div class="panel-title">
        <span>配方选择与营养指标</span>
        <el-select v-model="recipeId" size="small" style="width:300px" @change="loadRecipe">
          <el-option v-for="r in recipes" :key="r.id" :label="`${r.recipeCode} · ${r.name}`" :value="r.id" />
        </el-select>
      </div>

      <div v-if="recipe" class="grid-2">
        <div>
          <div class="param-card"><span>适用畜种</span><b>{{ speciesName(recipe.species) }} / {{ recipe.stage }}</b></div>
          <div class="param-card" style="margin-top:8px"><span>粗蛋白</span><b>{{ recipe.crudeProtein }} %</b></div>
          <div class="param-card" style="margin-top:8px"><span>代谢能</span><b>{{ recipe.metabolicEnergy }} MJ/kg</b></div>
          <div class="param-card" style="margin-top:8px"><span>赖氨酸</span><b>{{ recipe.lysine }} %</b></div>
          <div class="param-card" style="margin-top:8px"><span>吨成本</span><b style="color:#b71c1c">¥ {{ fmt(recipe.costPerTon) }}</b></div>
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
            <el-button size="small" type="primary" style="margin-left:auto" @click="submit">发布校验</el-button>
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
              <span class="muted mini mono">¥{{ Number(item.costSubtotal).toFixed(2) }}</span>
            </div>
          </div>

          <div class="mini muted" style="margin-top:10px">
            规则：单一原料不得超过其占比上限（鱼粉 ≤ 8%）；合计必须为 100.00%，否则发布校验会拦截并给出差值。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchRecipes, fetchRecipe, adjustRatio, spreadRatio, submitRecipe } from '../api'

const COLORS = ['#b71c1c', '#ef6c00', '#f9a825', '#2e7d32', '#1565c0',
  '#6a1b9a', '#00838f', '#8d6e63', '#455a64', '#c2185b', '#5d4037']
const SPECIES = { SWINE: '猪', POULTRY: '禽', AQUA: '水产', RUMINANT: '反刍' }

const recipes = ref([])
const recipe = ref(null)
const recipeId = ref(null)
const selectedId = ref(null)
const busy = ref(false)

const total = computed(() =>
  (recipe.value?.items || []).reduce((s, i) => s + Number(i.ratioPct || 0), 0)
)
const diff = computed(() => 100 - total.value)
const diffOk = computed(() => Math.abs(diff.value) < 0.005)

function colorOf (i) {
  return COLORS[i % COLORS.length]
}
function speciesName (s) {
  return SPECIES[s] || s
}
function fmt (v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function loadRecipe () {
  if (!recipeId.value) return
  recipe.value = await fetchRecipe(recipeId.value)
  selectedId.value = recipe.value.items?.[0]?.id || null
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

async function submit () {
  try {
    recipe.value = await submitRecipe(recipeId.value)
    ElMessage.success('占比合计 100.00%，配方发布成功')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  recipes.value = await fetchRecipes().catch(() => [])
  if (recipes.value.length) {
    recipeId.value = recipes.value[0].id
    await loadRecipe()
  }
})
</script>
