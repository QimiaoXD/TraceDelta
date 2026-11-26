import http, { type ApiResponse } from './http'

export interface DatasetItem {
  id: number
  name: string
  description: string | null
  createdAt: string
}

export interface DatasetVersionItem {
  id: number
  datasetId: number
  versionNo: number
  note: string | null
  caseCount: number
  createdAt: string
}

export interface DatasetCaseItem {
  id: number
  datasetVersionId: number
  caseKey: string
  inputJson: string
  messagesJson: string | null
  referenceAnswer: string | null
  expectedDocIdsJson: string | null
  assertionsJson: string
  tagsJson: string | null
  caseHash: string
}

export async function listDatasetsApi(): Promise<DatasetItem[]> {
  const res = await http.get<ApiResponse<DatasetItem[]>>('/datasets')
  return res.data.data
}

export async function createDatasetApi(name: string, description: string): Promise<DatasetItem> {
  const res = await http.post<ApiResponse<DatasetItem>>('/datasets', { name, description })
  return res.data.data
}

export async function deleteDatasetApi(datasetId: number): Promise<void> {
  await http.delete(`/datasets/${datasetId}`)
}

export async function listDatasetVersionsApi(datasetId: number): Promise<DatasetVersionItem[]> {
  const res = await http.get<ApiResponse<DatasetVersionItem[]>>(`/datasets/${datasetId}/versions`)
  return res.data.data
}

export async function importDatasetVersionApi(
  datasetId: number,
  format: string,
  content: string,
  note: string,
): Promise<DatasetVersionItem> {
  const res = await http.post<ApiResponse<DatasetVersionItem>>(
    `/datasets/${datasetId}/versions/import`,
    { format, content, note },
  )
  return res.data.data
}

export async function listDatasetCasesApi(
  datasetId: number,
  versionNo: number,
): Promise<DatasetCaseItem[]> {
  const res = await http.get<ApiResponse<DatasetCaseItem[]>>(
    `/datasets/${datasetId}/versions/${versionNo}/cases`,
  )
  return res.data.data
}
