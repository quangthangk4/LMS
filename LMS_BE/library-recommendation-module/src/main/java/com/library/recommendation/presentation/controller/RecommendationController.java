package com.library.recommendation.presentation.controller;

import com.library.recommendation.application.GetRecommendationsUseCase;
import com.library.recommendation.dto.response.RecommendationResponse;
import com.library.shared.constant.RoleConstants;
import com.library.shared.dto.ApiResponseApp;
import com.library.shared.util.RequiresAuthentication;
import com.library.shared.util.RequiresRole;
import com.library.shared.util.SecurityEvaluator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
@Validated
public class RecommendationController {

    private final GetRecommendationsUseCase getRecommendationsUseCase;
    private final SecurityEvaluator security;

    @GetMapping
    @RequiresAuthentication
    public ApiResponseApp<List<RecommendationResponse>> getRecommendations(
        @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(20) int limit) {
        return ApiResponseApp.success(
            getRecommendationsUseCase.execute(security.getCurrentUserId(), limit));
    }

    @PostMapping("/refresh")
    @RequiresAuthentication
    public ApiResponseApp<List<RecommendationResponse>> refresh(
        @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(20) int limit) {
        return ApiResponseApp.success(getRecommendationsUseCase.refresh(security.getCurrentUserId(), limit));
    }

    @GetMapping("/librarian-reader")
    @RequiresRole(RoleConstants.LIBRARIAN)
    public ApiResponseApp<List<RecommendationResponse>> getReaderRecommendationsForLibrarian(
        @RequestParam(name = "userId") Long userId,
        @RequestParam(name = "faculty", required = false) String faculty,
        @RequestParam(name = "limit", defaultValue = "3") @Min(1) @Max(20) int limit) {
        return ApiResponseApp.success(getRecommendationsUseCase.execute(userId, faculty, limit));
    }
}
