package com.library.recommendation.application;

import com.library.recommendation.dto.response.RecommendationResponse;
import java.util.List;

public interface GetRecommendationsUseCase {
    List<RecommendationResponse> execute(Long userId, int limit);

    List<RecommendationResponse> execute(Long userId, String faculty, int limit);

    List<RecommendationResponse> refresh(Long userId, int limit);
}
