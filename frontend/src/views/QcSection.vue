<template>
  <div>
    <div class="section-head">
      <h2>05 质检</h2>
      <span>抽样批次卡片 · 实测值 vs 标准区间（超标标红）· CSS 圆形印章判定（霉菌毒素超标只能盖退货章）· 退货批次复检放行闭环</span>
    </div>

    <div class="qc-grid">
      <div v-for="s in samples" :key="s.id" class="qc-card">
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <b style="font-size:15px">{{ s.sampleNo }}</b>
            <div class="mini muted">
              批次 {{ s.batch.batchNo }} · {{ s.batch.recipe.name }} · {{ s.sampler }} ·
              {{ String(s.sampleTime || '').replace('T', ' ').slice(0, 16) }}
            </div>
          </div>
          <span class="tag-chip" :class="conclusionChip(s.conclusion)">{{ conclusionName(s.conclusion) }}</span>
        </div>

        <el-table :data="s.items" size="small" :cell-class-name="cellClass" style="margin-top:10px">
          <el-table-column prop="itemName" label="检验项" width="90" />
          <el-table-column prop="measuredValue" label="实测值" width="110">
            <template #default="{ row }">
              {{ Number(row.measuredValue).toFixed(3) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="标准区间" width="140">
            <template #default="{ row }">
              {{ Number(row.stdLower).toFixed(3) }} ~ {{ Number(row.stdUpper).toFixed(3) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="判定" width="70">
            <template #default="{ row }">
              <span :class="row.qualified ? 'chip-pass' : 'chip-reject'" class="tag-chip">
                {{ row.qualified ? '合格' : '超标' }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div class="conclusion-area">
          <div style="flex:1">
            <div style="display:flex;gap:8px;flex-wrap:wrap">
              <el-button size="small" type="success" :disabled="!canPass(s)" @click="judge(s, 'PASS')">盖合格章</el-button>
              <el-button size="small" type="warning" :disabled="!canDowngrade(s)" @click="judge(s, 'DOWNGRADE')">盖降级章</el-button>
              <el-button size="small" type="danger" :disabled="!canReject(s)" @click="judge(s, 'REJECT')">盖退货章</el-button>
            </div>
            <div class="stamp-hint">
              处理去向：{{ s.disposition || '待判定' }}
              <span v-if="mycoFailed(s)" style="color:#c62828"> · 霉菌毒素超标，按规程只能判退货，不允许人工改判合格</span>
              <span v-else-if="failedCount(s)" style="color:#ef6c00">
                · 存在 {{ failedCount(s) }} 项超标，不能判合格
              </span>
            </div>
            <div class="mini muted" v-if="s.remark">备注：{{ s.remark }}</div>
          </div>

          <!-- CSS 绘制的圆形印章 -->
          <div class="stamp-wrap">
            <div class="stamp" :class="stampClass(s)">
              <span class="stamp-title">{{ stampTitle(s) }}</span>
              <span class="stamp-sub">饲料质检专用</span>
              <span class="stamp-date">{{ stampDate(s) }}</span>
            </div>
          </div>
        </div>

        <!-- ============ 退货复检闭环：仅初检结论为退货的批次进入 ============ -->
        <div v-if="s.conclusion === 'REJECT'" class="recheck-area">
          <div class="recheck-head">
            <b>退货复检</b>
            <template v-if="latestRecheck(s)">
              <span class="tag-chip" :class="recheckStatusChip(latestRecheck(s))">
                {{ recheckStatusName(latestRecheck(s)) }}
              </span>
              <span class="mini muted">{{ latestRecheck(s).recheckNo }}</span>
            </template>
            <span v-else class="tag-chip chip-gray">未发起复检</span>
          </div>

          <!-- 未发起：历史退货批次兼容显示 -->
          <template v-if="!latestRecheck(s)">
            <div class="mini muted" style="margin-bottom:8px">
              该批次初检判退货，尚未发起复检。发起后由不同于初检判定人的复核员重新检验，初检记录留痕不改写。
            </div>
            <el-button size="small" type="primary" plain @click="openCreate(s)">发起复检</el-button>
          </template>

          <!-- 进行中：待复核 / 复检中 -->
          <template v-else-if="isActive(latestRecheck(s))">
            <div class="recheck-panel">
              <div class="mini">
                发起人 <b>{{ latestRecheck(s).initiator }}</b> ·
                {{ fmtTime(latestRecheck(s).createdAt) }} · 原因：{{ latestRecheck(s).reason }}
              </div>
              <div class="mini muted" style="margin-top:4px">
                初检判定人 {{ s.sampler }}，复核员须与其不同
                <span v-if="latestRecheck(s).reviewer"> · 复核员 <b>{{ latestRecheck(s).reviewer }}</b></span>
              </div>

              <el-table v-if="latestRecheck(s).items && latestRecheck(s).items.length"
                        :data="latestRecheck(s).items" size="small" :cell-class-name="cellClass"
                        style="margin-top:8px">
                <el-table-column prop="itemName" label="复检项" width="90" />
                <el-table-column prop="measuredValue" label="复检实测值" width="110">
                  <template #default="{ row }">
                    {{ Number(row.measuredValue).toFixed(3) }} {{ row.unit }}
                  </template>
                </el-table-column>
                <el-table-column label="标准区间" width="140">
                  <template #default="{ row }">
                    {{ Number(row.stdLower).toFixed(3) }} ~ {{ Number(row.stdUpper).toFixed(3) }} {{ row.unit }}
                  </template>
                </el-table-column>
                <el-table-column label="判定" width="70">
                  <template #default="{ row }">
                    <span :class="row.qualified ? 'chip-pass' : 'chip-reject'" class="tag-chip">
                      {{ row.qualified ? '合格' : '超标' }}
                    </span>
                  </template>
                </el-table-column>
              </el-table>

              <div style="display:flex;gap:8px;margin-top:8px;flex-wrap:wrap">
                <el-button size="small" type="primary" plain @click="openMeasure(s, latestRecheck(s))">
                  {{ latestRecheck(s).items && latestRecheck(s).items.length ? '修正复检值' : '录入复检值' }}
                </el-button>
                <el-button size="small" type="danger"
                           :disabled="!latestRecheck(s).items || !latestRecheck(s).items.length"
                           @click="openConclude(latestRecheck(s))">
                  提交最终结论
                </el-button>
              </div>
              <div v-if="!latestRecheck(s).items || !latestRecheck(s).items.length" class="mini muted" style="margin-top:6px">
                待复核：请先录入复检检验值，再提交最终结论。
              </div>
            </div>
          </template>

          <!-- 已完成：最终结论生效，不能覆盖 -->
          <template v-else>
            <div class="recheck-panel recheck-done">
              <div class="recheck-final">
                <div class="stamp stamp-mini" :class="finalStampClass(latestRecheck(s))">
                  <span class="stamp-title">{{ finalName(latestRecheck(s).finalConclusion) }}</span>
                  <span class="stamp-sub">复检结论</span>
                  <span class="stamp-date">{{ String(latestRecheck(s).reviewedAt || '').slice(0, 10) }}</span>
                </div>
                <div style="flex:1">
                  <div class="mini">
                    复核员 <b>{{ latestRecheck(s).reviewer }}</b>（初检判定人 {{ s.sampler }}）·
                    {{ fmtTime(latestRecheck(s).reviewedAt) }}
                  </div>
                  <div class="mini" style="margin-top:4px">
                    最终去向：<b>{{ latestRecheck(s).finalDisposition }}</b>
                    <span v-if="latestRecheck(s).remark" class="muted"> · {{ latestRecheck(s).remark }}</span>
                  </div>
                  <div class="mini muted" style="margin-top:4px">
                    发起人 {{ latestRecheck(s).initiator }} · 原因：{{ latestRecheck(s).reason }} ·
                    结论已生效，不能覆盖
                  </div>
                </div>
              </div>

              <el-table :data="latestRecheck(s).items" size="small" :cell-class-name="cellClass"
                        style="margin-top:8px">
                <el-table-column prop="itemName" label="复检项" width="90" />
                <el-table-column prop="measuredValue" label="复检实测值" width="110">
                  <template #default="{ row }">
                    {{ Number(row.measuredValue).toFixed(3) }} {{ row.unit }}
                  </template>
                </el-table-column>
                <el-table-column label="标准区间" width="140">
                  <template #default="{ row }">
                    {{ Number(row.stdLower).toFixed(3) }} ~ {{ Number(row.stdUpper).toFixed(3) }} {{ row.unit }}
                  </template>
                </el-table-column>
                <el-table-column label="判定" width="70">
                  <template #default="{ row }">
                    <span :class="row.qualified ? 'chip-pass' : 'chip-reject'" class="tag-chip">
                      {{ row.qualified ? '合格' : '超标' }}
                    </span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>

          <div v-if="rechecksOf(s).length > 1" class="mini muted" style="margin-top:6px">
            历史复检 {{ rechecksOf(s).length - 1 }} 次，最新复检单为准。
          </div>
        </div>
      </div>
    </div>

    <!-- 发起复检 -->
    <el-dialog v-model="createDlg.visible" title="发起退货复检" width="440px">
      <el-form label-width="90px">
        <el-form-item label="抽样单">
          <b>{{ createDlg.sample && createDlg.sample.sampleNo }}</b>
          <span class="mini muted" style="margin-left:8px">
            批次 {{ createDlg.sample && createDlg.sample.batch.batchNo }} · 初检退货
          </span>
        </el-form-item>
        <el-form-item label="发起人" required>
          <el-input v-model="createDlg.initiator" maxlength="32" placeholder="发起人姓名" />
        </el-form-item>
        <el-form-item label="复检原因" required>
          <el-input v-model="createDlg.reason" type="textarea" :rows="3" maxlength="255"
                    placeholder="例如：供应商对霉菌毒素初检结果有异议，申请换样复检" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDlg.saving" @click="submitCreate">发起复检</el-button>
      </template>
    </el-dialog>

    <!-- 录入复检检验值 -->
    <el-dialog v-model="measureDlg.visible" title="录入复检检验值" width="560px">
      <el-form label-width="90px">
        <el-form-item label="复检单">
          <b>{{ measureDlg.recheck && measureDlg.recheck.recheckNo }}</b>
          <span class="mini muted" style="margin-left:8px">
            初检判定人 {{ measureDlg.recheck && measureDlg.recheck.firstInspector }}，复核员须与其不同
          </span>
        </el-form-item>
        <el-form-item label="复核员" required>
          <el-input v-model="measureDlg.reviewer" maxlength="32" placeholder="复核员姓名"
                    :disabled="!!(measureDlg.recheck && measureDlg.recheck.reviewer)" />
        </el-form-item>
      </el-form>
      <el-table :data="measureDlg.rows" size="small">
        <el-table-column prop="itemName" label="检验项" width="90" />
        <el-table-column label="初检值" width="110">
          <template #default="{ row }">
            <span :class="{ 'bad-text': row.originBad }">
              {{ Number(row.originValue).toFixed(3) }} {{ row.unit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="标准区间" width="150">
          <template #default="{ row }">
            {{ Number(row.stdLower).toFixed(3) }} ~ {{ Number(row.stdUpper).toFixed(3) }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="复检实测值">
          <template #default="{ row }">
            <el-input-number v-model="row.value" :min="0" :precision="3" :step="0.1"
                             controls-position="right" style="width:150px" />
          </template>
        </el-table-column>
      </el-table>
      <div class="mini muted" style="margin-top:8px">
        初检原始记录留痕不改写，复检值另存于复检单。
      </div>
      <template #footer>
        <el-button @click="measureDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="measureDlg.saving" @click="submitMeasure">保存复检值</el-button>
      </template>
    </el-dialog>

    <!-- 提交最终结论 -->
    <el-dialog v-model="concludeDlg.visible" title="提交复检最终结论" width="480px">
      <el-form label-width="90px">
        <el-form-item label="复检单">
          <b>{{ concludeDlg.recheck && concludeDlg.recheck.recheckNo }}</b>
          <span class="mini muted" style="margin-left:8px">
            复核员 {{ concludeDlg.recheck && concludeDlg.recheck.reviewer }}
          </span>
        </el-form-item>
        <el-form-item label="最终结论" required>
          <el-select v-model="concludeDlg.conclusion" placeholder="选择最终结论" style="width:100%">
            <el-option label="放行（全部项目恢复标准范围）" value="RELEASE" />
            <el-option label="降级（霉菌毒素合格但仍有项目超标）" value="DOWNGRADE" />
            <el-option label="继续退货" value="REJECT" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理去向">
          <el-input v-model="concludeDlg.disposition" maxlength="64"
                    :placeholder="defaultDisposition" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="concludeDlg.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <div class="recheck-rule">
        <template v-if="concludeMycoFailed">霉菌毒素复检仍超标，按规程只能继续退货。</template>
        <template v-else-if="concludeFailedCount === 0">全部项目已恢复标准范围，应当放行。</template>
        <template v-else>霉菌毒素合格，仍有 {{ concludeFailedCount }} 项超标，可判降级（需明确去向）或继续退货。</template>
        结论提交后立即生效，并发或重复提交只有第一个生效。
      </div>
      <template #footer>
        <el-button @click="concludeDlg.visible = false">取消</el-button>
        <el-button type="danger" :loading="concludeDlg.saving" @click="submitConclude">提交最终结论</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchQcSamples, judgeSample,
  fetchRechecks, createRecheck, measureRecheck, concludeRecheck
} from '../api'

const samples = ref([])
const rechecks = ref([])

function conclusionName (c) {
  return { PASS: '合格', DOWNGRADE: '降级', REJECT: '退货' }[c] || '待判定'
}
function conclusionChip (c) {
  return { PASS: 'chip-pass', DOWNGRADE: 'chip-down', REJECT: 'chip-reject' }[c] || 'chip-gray'
}
function failedItems (s) {
  return (s.items || []).filter(i => !i.qualified)
}
function failedCount (s) {
  return failedItems(s).length
}
function mycoFailed (s) {
  return failedItems(s).some(i => i.itemCode === 'MYCOTOXIN')
}
function canPass (s) {
  return failedCount(s) === 0
}
function canDowngrade (s) {
  return failedCount(s) > 0 && !mycoFailed(s)
}
function canReject (s) {
  return failedCount(s) > 0
}
function cellClass ({ row, column }) {
  if (column.property === 'measuredValue' && !row.qualified) return 'item-cell-bad'
  return ''
}
function stampClass (s) {
  return { PASS: 'pass', DOWNGRADE: 'downgrade', REJECT: 'reject' }[s.conclusion] || 'none'
}
function stampTitle (s) {
  return { PASS: '合格', DOWNGRADE: '降级', REJECT: '退货' }[s.conclusion] || '待判定'
}
function stampDate (s) {
  return String(s.sampleTime || '').slice(0, 10) || '——'
}
function fmtTime (t) {
  return String(t || '').replace('T', ' ').slice(0, 16)
}

async function judge (s, conclusion) {
  try {
    const updated = await judgeSample(s.id, { conclusion })
    const idx = samples.value.findIndex(x => x.id === s.id)
    if (idx >= 0) samples.value[idx] = updated
    ElMessage.success(`${s.sampleNo} 判定为${conclusionName(conclusion)}，去向：${updated.disposition}`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* ================= 退货复检闭环 ================= */

const recheckMap = computed(() => {
  const m = {}
  for (const r of rechecks.value) {
    (m[r.sampleId] = m[r.sampleId] || []).push(r)
  }
  return m
})
function rechecksOf (s) {
  return recheckMap.value[s.id] || []
}
function latestRecheck (s) {
  return rechecksOf(s)[0] || null
}
function isActive (r) {
  return r && r.status !== 'DONE'
}
function recheckStatusName (r) {
  return { PENDING: '待复核', MEASURING: '复检中', DONE: '已完成' }[r.status] || r.status
}
function recheckStatusChip (r) {
  return { PENDING: 'chip-run', MEASURING: 'chip-down', DONE: 'chip-pass' }[r.status] || 'chip-gray'
}
function finalName (c) {
  return { RELEASE: '放行', DOWNGRADE: '降级', REJECT: '退货' }[c] || '—'
}
function finalStampClass (r) {
  return { RELEASE: 'pass', DOWNGRADE: 'downgrade', REJECT: 'reject' }[r.finalConclusion] || 'none'
}

/* ---- 发起复检 ---- */
const createDlg = ref({ visible: false, sample: null, initiator: '', reason: '', saving: false })

function openCreate (s) {
  createDlg.value = { visible: true, sample: s, initiator: '', reason: '', saving: false }
}

async function submitCreate () {
  const d = createDlg.value
  if (!d.initiator.trim() || !d.reason.trim()) {
    ElMessage.warning('请填写发起人和复检原因')
    return
  }
  d.saving = true
  try {
    await createRecheck(d.sample.id, { initiator: d.initiator.trim(), reason: d.reason.trim() })
    ElMessage.success('复检单已发起，状态：待复核')
    d.visible = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    d.saving = false
  }
}

/* ---- 录入复检检验值 ---- */
const measureDlg = ref({ visible: false, recheck: null, reviewer: '', rows: [], saving: false })

function openMeasure (s, r) {
  const measured = {}
  for (const it of r.items || []) measured[it.itemCode] = Number(it.measuredValue)
  measureDlg.value = {
    visible: true,
    recheck: r,
    reviewer: r.reviewer || '',
    saving: false,
    rows: (s.items || []).map(i => ({
      itemCode: i.itemCode,
      itemName: i.itemName,
      unit: i.unit,
      stdLower: i.stdLower,
      stdUpper: i.stdUpper,
      originValue: i.measuredValue,
      originBad: !i.qualified,
      value: measured[i.itemCode] !== undefined ? measured[i.itemCode] : null
    }))
  }
}

async function submitMeasure () {
  const d = measureDlg.value
  if (!d.reviewer.trim()) {
    ElMessage.warning('请填写复核员')
    return
  }
  if (d.rows.some(r => r.value === null || r.value === undefined)) {
    ElMessage.warning('请完整录入全部检验项的复检实测值')
    return
  }
  d.saving = true
  try {
    await measureRecheck(d.recheck.id, {
      reviewer: d.reviewer.trim(),
      items: d.rows.map(r => ({ itemCode: r.itemCode, measuredValue: r.value }))
    })
    ElMessage.success('复检检验值已保存，初检记录保持原样')
    d.visible = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    d.saving = false
  }
}

/* ---- 提交最终结论 ---- */
const concludeDlg = ref({ visible: false, recheck: null, conclusion: '', disposition: '', remark: '', saving: false })

const concludeFailedItems = computed(() => {
  const r = concludeDlg.value.recheck
  return r ? (r.items || []).filter(i => !i.qualified) : []
})
const concludeFailedCount = computed(() => concludeFailedItems.value.length)
const concludeMycoFailed = computed(() =>
  concludeFailedItems.value.some(i => i.itemCode === 'MYCOTOXIN'))
const defaultDisposition = computed(() =>
  ({ RELEASE: '成品仓入库', DOWNGRADE: '降级销售', REJECT: '回机处理' }[concludeDlg.value.conclusion] || '按结论自动带出'))

function openConclude (r) {
  concludeDlg.value = { visible: true, recheck: r, conclusion: '', disposition: '', remark: '', saving: false }
}

async function submitConclude () {
  const d = concludeDlg.value
  if (!d.conclusion) {
    ElMessage.warning('请选择最终结论')
    return
  }
  d.saving = true
  try {
    const done = await concludeRecheck(d.recheck.id, {
      reviewer: d.recheck.reviewer,
      conclusion: d.conclusion,
      disposition: d.disposition.trim() || null,
      remark: d.remark.trim() || null
    })
    ElMessage.success(`复检结论「${finalName(done.finalConclusion)}」已生效，去向：${done.finalDisposition}`)
    d.visible = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
    await reload()
  } finally {
    d.saving = false
  }
}

async function reload () {
  const [s, r] = await Promise.all([
    fetchQcSamples().catch(() => samples.value),
    fetchRechecks().catch(() => rechecks.value)
  ])
  samples.value = s
  rechecks.value = r
}

onMounted(reload)
</script>
