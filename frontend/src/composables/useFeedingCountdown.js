import { computed, reactive, ref } from 'vue'

/**
 * 投料倒计时状态。所有计时与弹窗上下文都以批次 ID + 投料序号作为归属键；
 * 切换批次仅切换展示对象，不取消其他批次的倒计时，切回后仍可看到剩余进度。
 * 离开投料区属于放弃当前操作，会统一清理并通知后端取消称量。
 */
export function useFeedingCountdown (options = {}) {
  const {
    total = 5,
    interval = 1000,
    getCurrentBatch = () => null,
    loadBatch = async () => {},
    sendFeedingAction,
    notify
  } = options

  const counting = ref({})
  const timers = new Map()
  const startingKeys = new Set()
  let activeDialogTimer = null
  const dialog = reactive({
    visible: false,
    busy: false,
    batchId: null,
    batchNo: '',
    item: null,
    actual: 0
  })

  const dialogTitle = computed(() =>
    dialog.batchNo ? `实际称重 → 确认投料（${dialog.batchNo}）` : '实际称重 → 确认投料'
  )

  function countdownKey (batchId, feedSeq) {
    return `${batchId}:${feedSeq}`
  }

  function activeKey (item) {
    const batchId = getCurrentBatch()
    return batchId == null || item == null ? null : countdownKey(batchId, item.feedSeq)
  }

  function countdownLeft (item) {
    const key = activeKey(item)
    return key ? counting.value[key] || 0 : 0
  }

  function isCounting (item) {
    return countdownLeft(item) > 0
  }

  function setCountdown (ownerId, feedSeq, left) {
    const key = countdownKey(ownerId, feedSeq)
    const next = { ...counting.value }
    if (left > 0) {
      next[key] = left
    } else {
      delete next[key]
    }
    counting.value = next
  }

  function startCountdown (ownerId, ownerNo, item) {
    const key = countdownKey(ownerId, item.feedSeq)
    if (timers.has(key)) return

    setCountdown(ownerId, item.feedSeq, total)
    const timer = globalThis.setInterval(() => {
      const left = (counting.value[key] || 0) - 1
      setCountdown(ownerId, item.feedSeq, left)
      if (left <= 0) {
        const current = timers.get(key)
        if (current) {
          globalThis.clearInterval(current)
          timers.delete(key)
        }
        openDialog(ownerId, ownerNo, item)
      }
    }, interval)
    timers.set(key, timer)
  }

  async function startWeigh (owner, item) {
    const ownerId = owner?.id
    if (ownerId == null || !item) return

    const key = countdownKey(ownerId, item.feedSeq)
    if (timers.has(key) || startingKeys.has(key)) return
    if (item.status === 'WEIGHING') {
      openDialog(ownerId, owner.batchNo, item)
      return
    }

    startingKeys.add(key)
    try {
      const fresh = await sendFeedingAction(ownerId, { action: 'WEIGH', feedSeq: item.feedSeq })
      const freshItem = fresh.feedings.find(f => f.id === item.id) || item
      startCountdown(ownerId, fresh.batchNo || owner.batchNo, freshItem)
      return fresh
    } catch (e) {
      notify?.({ type: 'warning', message: e.message })
    } finally {
      startingKeys.delete(key)
    }
  }

  function showDialog (ownerId, ownerNo, item) {
    dialog.batchId = ownerId
    dialog.batchNo = ownerNo
    dialog.item = item
    dialog.actual = Number(item.plannedKg)
    dialog.visible = true
    activeDialogTimer = null
  }

  function openDialog (ownerId, ownerNo, item) {
    if (!dialog.visible) {
      showDialog(ownerId, ownerNo, item)
      return
    }
    activeDialogTimer = { ownerId, ownerNo, item }
  }

  function closeDialog () {
    dialog.visible = false
    dialog.batchId = null
    dialog.batchNo = ''
    dialog.item = null
    const pending = activeDialogTimer
    activeDialogTimer = null
    if (pending) {
      showDialog(pending.ownerId, pending.ownerNo, pending.item)
    }
  }

  async function clearAllCountdowns (cancelOnServer = false) {
    if (!timers.size) return
    const active = []
    timers.forEach((timer, key) => {
      globalThis.clearInterval(timer)
      active.push(key)
    })
    timers.clear()
    activeDialogTimer = null
    counting.value = {}

    if (!cancelOnServer || !sendFeedingAction) return
    await Promise.allSettled(active.map(async key => {
      const [ownerId, feedSeq] = key.split(':')
      await sendFeedingAction(ownerId, { action: 'CANCEL', feedSeq: Number(feedSeq) })
    }))
  }

  async function submitFeed () {
    const ownerId = dialog.batchId
    const item = dialog.item
    if (ownerId == null || !item) return

    dialog.busy = true
    try {
      await sendFeedingAction(ownerId, {
        action: 'CONFIRM',
        feedSeq: item.feedSeq,
        actualKg: Number(dialog.actual)
      })
      notify?.({ type: 'success', message: `${item.portNo} ${item.material.name} 投料完成` })
      activeDialogTimer = null
      closeDialog()
      if (ownerId === getCurrentBatch()) {
        await loadBatch()
      }
    } catch (e) {
      notify?.({ type: 'error', message: e.message })
      if (ownerId === getCurrentBatch()) {
        await loadBatch()
      }
    } finally {
      dialog.busy = false
    }
  }

  return {
    counting,
    dialog,
    dialogTitle,
    countdownKey,
    countdownLeft,
    isCounting,
    startCountdown,
    startWeigh,
    openDialog,
    closeDialog,
    clearAllCountdowns,
    submitFeed
  }
}
