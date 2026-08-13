package com.cookpilot.backend.home;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/home")
public class HomeRecipeController {

	private final HomeRecipeService homeRecipeService;
	private final HomeRecommendationService homeRecommendationService;

	public HomeRecipeController(HomeRecipeService homeRecipeService,
			HomeRecommendationService homeRecommendationService) {
		this.homeRecipeService = homeRecipeService;
		this.homeRecommendationService = homeRecommendationService;
	}

	@GetMapping("/recent-recipes")
	public List<RecentRecipeResponse> recentRecipes() {
		return homeRecipeService.findRecentRecipes();
	}

	@GetMapping("/recommendations")
	public List<RecommendedRecipeResponse> recommendations() {
		return homeRecommendationService.findRecommendations();
	}
}
