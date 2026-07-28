import { http } from '../http'
import type { Material, MaterialCategory, PrivacyLevel } from '@/types/api'

export interface CreateTextMaterialRequest {
  title: string
  content: string
  category: MaterialCategory
  privacyLevel: PrivacyLevel
}

export interface CreateWebMaterialRequest {
  title: string
  url: string
  category: MaterialCategory
  privacyLevel: PrivacyLevel
}

export const materialsApi = {
  createText(body: CreateTextMaterialRequest) {
    return http.post<Material>('/api/materials/text', body).then((r) => r.data)
  },
  createWeb(body: CreateWebMaterialRequest) {
    return http.post<Material>('/api/materials/web', body).then((r) => r.data)
  },
  uploadFile(
    form: { title: string; category: MaterialCategory; privacyLevel: PrivacyLevel },
    file: File,
  ) {
    const data = new FormData()
    data.append('title', form.title)
    data.append('category', form.category)
    data.append('privacyLevel', form.privacyLevel)
    data.append('file', file)
    return http
      .post<Material>('/api/materials/files', data, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data)
  },
  list() {
    return http.get<Material[]>('/api/materials').then((r) => r.data)
  },
  get(materialId: string) {
    return http.get<Material>(`/api/materials/${materialId}`).then((r) => r.data)
  },
  importWebResult(
    resultId: string,
    body: { category: MaterialCategory; privacyLevel: PrivacyLevel },
  ) {
    return http
      .post<Material>(`/api/web-search-results/${resultId}/import`, body)
      .then((r) => r.data)
  },
}
