import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { nextTick, ref } from 'vue'
import { useFeedingCountdown } from '../src/composables/useFeedingCountdown.js'

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))
const flush = (ms = 20) => new Promise(resolve => setTimeout(resolve, ms))

function makeItem (id, feedSeq) {
  return {
    id,
    feedSeq,
    portNo: `P${feedSeq}`,
    plannedKg: 100,
    material: { name: `原料${id}` },
    status: 'PENDING'
  }
}

function makeBatch (id, items) {
  return { id, batchNo: `B${id}`, feedings: items }
}

function createHarness ({ currentId = 1, interval = 1, total = 3 } = {}) {
  const currentBatchId = ref(currentId)
  const calls = []
  const store = new Map()

  async function sendFeedingAction (id, payload) {
    const ownerId = Number(id)
    calls.push({ id: ownerId, ...payload })
    const batch = store.get(ownerId)
    const item = batch.feedings.find(f => f.feedSeq === payload.feedSeq)
    if (payload.action === 'WEIGH') item.status = 'WEIGHING'
    if (payload.action === 'CANCEL') item.status = 'PENDING'
    return structuredClone(batch)
  }

  const countdown = useFeedingCountdown({
    total,
    interval,
    getCurrentBatch: () => currentBatchId.value,
    loadBatch: async () => {},
    sendFeedingAction,
    notify: () => {}
  })

  return {
    currentBatchId,
    calls,
    countdown,
    registerBatches: (...batches) => batches.forEach(batch => store.set(batch.id, batch))
  }
}

describe('useFeedingCountdown', () => {
  it('快速往返切换批次时，倒计时按批次和投料序号隔离', async () => {
    const batch1 = makeBatch(1, [makeItem(11, 1)])
    const batch2 = makeBatch(2, [makeItem(21, 1)])
    const harness = createHarness({ currentId: 1, total: 100, interval: 10 })
    harness.registerBatches(batch1, batch2)
    const { countdown, currentBatchId } = harness

    await countdown.startWeigh(batch1, batch1.feedings[0])
    currentBatchId.value = 2
    assert.equal(countdown.isCounting(batch2.feedings[0]), false)
    assert.equal(countdown.countdownLeft(batch2.feedings[0]), 0)

    await countdown.startWeigh(batch2, batch2.feedings[0])
    currentBatchId.value = 1
    assert.ok(countdown.isCounting(batch1.feedings[0]))
    assert.equal(countdown.countdownLeft(batch1.feedings[0]), 100)
    currentBatchId.value = 2
    assert.ok(countdown.isCounting(batch2.feedings[0]))
    assert.equal(countdown.countdownLeft(batch2.feedings[0]), 100)
    assert.equal(harness.calls.filter(c => c.action === 'WEIGH').length, 2)
  })

  it('一个弹窗未关闭时其他批次倒计时结束，弹窗仍保持原归属', async () => {
    const batch1 = makeBatch(1, [makeItem(11, 1)])
    const batch2 = makeBatch(2, [makeItem(21, 1)])
    const harness = createHarness({ currentId: 1, total: 3, interval: 1 })
    harness.registerBatches(batch1, batch2)
    const { countdown } = harness

    await countdown.startWeigh(batch1, batch1.feedings[0])
    await flush()
    assert.equal(countdown.dialog.batchId, 1)
    assert.equal(countdown.dialog.item.id, 11)

    countdown.openDialog(2, 'B2', batch2.feedings[0])
    assert.equal(countdown.dialog.batchId, 1)
    countdown.closeDialog()
    assert.equal(countdown.dialog.batchId, 2)
    assert.equal(countdown.dialog.item.id, 21)
  })

  it('弹窗打开后再切换批次，确认动作仍提交给弹窗所属批次', async () => {
    const batch1 = makeBatch(1, [makeItem(11, 1)])
    const batch2 = makeBatch(2, [makeItem(21, 1)])
    const harness = createHarness({ currentId: 1 })
    harness.registerBatches(batch1, batch2)
    const { countdown, currentBatchId } = harness

    await countdown.startWeigh(batch1, batch1.feedings[0])
    await flush()
    assert.equal(countdown.dialog.batchId, 1)

    currentBatchId.value = 2
    await nextTick()
    await countdown.submitFeed()
    const confirm = harness.calls.find(c => c.action === 'CONFIRM')
    assert.deepEqual(confirm, {
      id: 1,
      action: 'CONFIRM',
      feedSeq: 1,
      actualKg: 100
    })
  })

  it('离开投料区时清理全部计时器并取消后端称量', async () => {
    const batch1 = makeBatch(1, [makeItem(11, 1)])
    const batch2 = makeBatch(2, [makeItem(21, 1)])
    const harness = createHarness({ currentId: 1, total: 100, interval: 10 })
    harness.registerBatches(batch1, batch2)
    const { countdown } = harness

    await countdown.startWeigh(batch1, batch1.feedings[0])
    await countdown.startWeigh(batch2, batch2.feedings[0])
    assert.equal(Object.keys(countdown.counting.value).length, 2)

    await countdown.clearAllCountdowns(true)
    assert.deepEqual(countdown.counting.value, {})
    const cancels = harness.calls.filter(c => c.action === 'CANCEL')
    assert.equal(cancels.length, 2)
    assert.ok(cancels.some(c => c.id === 1 && c.feedSeq === 1))
    assert.ok(cancels.some(c => c.id === 2 && c.feedSeq === 1))

    await sleep(20)
    assert.equal(countdown.dialog.visible, false)
  })
})
