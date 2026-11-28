import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listDatasetCasesApi,
  listDatasetsApi,
  listDatasetVersionsApi,
  type DatasetCaseItem,
  type DatasetItem,
  type DatasetVersionItem,
} from '../api/dataset'
import { listProvidersApi, type ProviderView } from '../api/provider'
import { listRunsApi, type EvalRunSummary } from '../api/run'

export const useConsoleDataStore = defineStore('consoleData', () => {
  const datasets = ref<DatasetItem[]>([])
  const datasetsLoaded = ref(false)

  const providers = ref<ProviderView[]>([])
  const providersLoaded = ref(false)

  const runs = ref<EvalRunSummary[]>([])
  const runsLoaded = ref(false)
  const runNameById = ref<Record<number, string>>({})

  const versionsByDataset = ref<Record<number, DatasetVersionItem[]>>({})
  const versionFetchFailed = ref<Record<number, boolean>>({})
  const casesByVersionKey = ref<Record<string, DatasetCaseItem[]>>({})

  async function fetchDatasets(): Promise<DatasetItem[]> {
    const data = await listDatasetsApi()
    datasets.value = data
    datasetsLoaded.value = true
    return data
  }

  async function fetchProviders(): Promise<ProviderView[]> {
    const data = await listProvidersApi()
    providers.value = data
    providersLoaded.value = true
    return data
  }

  async function fetchRuns(): Promise<EvalRunSummary[]> {
    const data = await listRunsApi()
    runs.value = data
    runsLoaded.value = true
    const nextNames = { ...runNameById.value }
    for (const r of data) {
      nextNames[r.id] = r.name
    }
    runNameById.value = nextNames
    return data
  }

  function rememberRunName(id: number, name: string) {
    runNameById.value = { ...runNameById.value, [id]: name }
  }

  function getCachedRunName(id: number): string | undefined {
    return runNameById.value[id]
  }

  function removeRunCache(id: number) {
    runs.value = runs.value.filter((r) => r.id !== id)
    const nextNames = { ...runNameById.value }
    delete nextNames[id]
    runNameById.value = nextNames
  }

  async function fetchVersions(datasetId: number): Promise<DatasetVersionItem[]> {
    const data = await listDatasetVersionsApi(datasetId)
    versionsByDataset.value = { ...versionsByDataset.value, [datasetId]: data }
    if (versionFetchFailed.value[datasetId]) {
      const next = { ...versionFetchFailed.value }
      delete next[datasetId]
      versionFetchFailed.value = next
    }
    return data
  }

  async function prefetchVersionSummaries(datasetIds: number[]): Promise<void> {
    const targets = datasetIds.filter((id) => versionsByDataset.value[id] === undefined)
    if (targets.length === 0) return
    await Promise.allSettled(
      targets.map(async (id) => {
        try {
          await fetchVersions(id)
        } catch {
          versionFetchFailed.value = { ...versionFetchFailed.value, [id]: true }
        }
      }),
    )
  }

  async function fetchCases(datasetId: number, versionNo: number): Promise<DatasetCaseItem[]> {
    const key = `${datasetId}:${versionNo}`
    const data = await listDatasetCasesApi(datasetId, versionNo)
    casesByVersionKey.value = { ...casesByVersionKey.value, [key]: data }
    return data
  }

  function getCachedVersions(datasetId: number): DatasetVersionItem[] | undefined {
    return versionsByDataset.value[datasetId]
  }

  function isVersionFetchFailed(datasetId: number): boolean {
    return Boolean(versionFetchFailed.value[datasetId])
  }

  function getCachedCases(datasetId: number, versionNo: number): DatasetCaseItem[] | undefined {
    return casesByVersionKey.value[`${datasetId}:${versionNo}`]
  }

  function removeDatasetCache(datasetId: number) {
    datasets.value = datasets.value.filter((d) => d.id !== datasetId)
    const nextVersions = { ...versionsByDataset.value }
    delete nextVersions[datasetId]
    versionsByDataset.value = nextVersions

    const nextFailed = { ...versionFetchFailed.value }
    delete nextFailed[datasetId]
    versionFetchFailed.value = nextFailed

    const prefix = `${datasetId}:`
    const nextCases: Record<string, DatasetCaseItem[]> = {}
    for (const [k, v] of Object.entries(casesByVersionKey.value)) {
      if (!k.startsWith(prefix)) nextCases[k] = v
    }
    casesByVersionKey.value = nextCases
  }

  function resetAll() {
    datasets.value = []
    datasetsLoaded.value = false
    providers.value = []
    providersLoaded.value = false
    runs.value = []
    runsLoaded.value = false
    runNameById.value = {}
    versionsByDataset.value = {}
    versionFetchFailed.value = {}
    casesByVersionKey.value = {}
  }

  return {
    datasets,
    datasetsLoaded,
    providers,
    providersLoaded,
    runs,
    runsLoaded,
    fetchDatasets,
    fetchProviders,
    fetchRuns,
    rememberRunName,
    getCachedRunName,
    removeRunCache,
    fetchVersions,
    prefetchVersionSummaries,
    fetchCases,
    getCachedVersions,
    isVersionFetchFailed,
    getCachedCases,
    removeDatasetCache,
    resetAll,
  }
})
