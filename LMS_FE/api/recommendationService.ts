import axiosInstance from './axiosInstance';
import { ApiResponse } from './publicationTypes';

export interface RecommendedPublication {
  publicationId: string;
  title: string;
  coverImageUrl: string | null;
  publicationYear: number | null;
  availableItems: number;
  ratingAverage: number;
  ratingCount: number;
  borrowCount: number;
  authorNames: string[];
}

const recommendationService = {
  refreshRecommendations: async (limit = 10): Promise<ApiResponse<RecommendedPublication[]>> => {
    return axiosInstance.post('/recommendations/refresh', null, { params: { limit } });
  },
  getRecommendations: async (limit = 10): Promise<ApiResponse<RecommendedPublication[]>> => {
    return axiosInstance.get(`/recommendations?limit=${limit}`);
  },
  getReaderRecommendationsForLibrarian: async (
    userId: string | number,
    faculty?: string | null,
    limit = 3,
  ): Promise<ApiResponse<RecommendedPublication[]>> => {
    return axiosInstance.get('/recommendations/librarian-reader', {
      params: { userId, faculty: faculty || undefined, limit },
    });
  },
};

export default recommendationService;
