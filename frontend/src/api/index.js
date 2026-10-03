import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

// 统一拆掉 ApiResponse 包装：成功返回 data，失败抛出中文 message
http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.ok === false) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body && body.data !== undefined ? body.data : body
  },
  (err) => {
    const message = err.response && err.response.data && err.response.data.message
      ? err.response.data.message
      : (err.message || '网络异常')
    return Promise.reject(new Error(message))
  }
)

/** 原料与筒仓 */
export const fetchMaterials = () => http.get('/materials')
export const fetchLowStock = () => http.get('/materials/low-stock')

/** 配方 */
export const fetchRecipes = () => http.get('/recipes')
export const fetchRecipe = (id) => http.get(`/recipes/${id}`)
export const adjustRatio = (id, payload) => http.post(`/recipes/${id}/adjust`, payload)
export const spreadRatio = (id, payload) => http.post(`/recipes/${id}/spread`, payload)
export const submitRecipe = (id) => http.post(`/recipes/${id}/submit`)
export const fetchSpeciesStats = () => http.get('/recipes/stats/species')

/** 批次与投料 */
export const fetchBatches = () => http.get('/batches')
export const fetchBatch = (id) => http.get(`/batches/${id}`)
export const feeding = (id, payload) => http.post(`/batches/${id}/feeding`, payload)
export const fetchFeedErrorStats = () => http.get('/batches/stats/feed-error')

/** 制粒 */
export const fetchPellets = () => http.get('/pellets')
export const fetchPelletByBatch = (batchId) => http.get(`/pellets/batch/${batchId}`)
export const adjustPellet = (batchId, payload) => http.post(`/pellets/batch/${batchId}/adjust`, payload)
export const producePellet = (batchId, outputTons) =>
  http.post(`/pellets/batch/${batchId}/produce`, null, { params: outputTons ? { outputTons } : {} })

/** 质检 */
export const fetchQcSamples = () => http.get('/qc/samples')
export const judgeSample = (id, payload) => http.post(`/qc/samples/${id}/judge`, payload)
export const fetchFailDistribution = () => http.get('/qc/stats/fail-distribution')

/** 退货复检闭环 */
export const fetchRechecks = () => http.get('/qc/rechecks')
export const createRecheck = (sampleId, payload) => http.post(`/qc/samples/${sampleId}/rechecks`, payload)
export const measureRecheck = (id, payload) => http.post(`/qc/rechecks/${id}/measure`, payload)
export const concludeRecheck = (id, payload) => http.post(`/qc/rechecks/${id}/conclude`, payload)

/** 概览 */
export const fetchDashboard = () => http.get('/stats/dashboard')
