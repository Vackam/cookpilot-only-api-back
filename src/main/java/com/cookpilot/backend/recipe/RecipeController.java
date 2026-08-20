package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cookpilot.backend.common.PagedResponse;
import com.cookpilot.backend.favorite.FavoriteService;
import com.cookpilot.backend.personalrecipe.PersonalRecipeService;
import com.cookpilot.backend.personalrecipe.PersonalRecipeVersion;

@RestController
@RequestMapping("/api/v1/recipes")
public class RecipeController {

	private final RecipeService recipeService;
	private final PersonalRecipeService personalRecipeService;
	private final FavoriteService favoriteService;
	private final RecipeTagLookup recipeTagLookup;

	public RecipeController(RecipeService recipeService,
			PersonalRecipeService personalRecipeService,
			FavoriteService favoriteService,
			RecipeTagLookup recipeTagLookup) {
		this.recipeService = recipeService;
		this.personalRecipeService = personalRecipeService;
		this.favoriteService = favoriteService;
		this.recipeTagLookup = recipeTagLookup;
	}

	@GetMapping
	public PagedResponse<RecipeSummaryResponse> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		Page<RecipeOverview> recipePage = recipeService.findPage(page, size);
		return PagedResponse.of(recipePage, summarize(recipePage.getContent()));
	}

	@GetMapping("/search")
	public PagedResponse<RecipeSummaryResponse> search(
			@RequestParam(defaultValue = "") String title,
			@RequestParam(defaultValue = "") String ingredient,
			@RequestParam(defaultValue = "") String cookingMethod,
			@RequestParam(defaultValue = "") String dishType,
			@RequestParam(defaultValue = "") String hashtag,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		Page<RecipeOverview> recipePage = recipeService.search(
				title, ingredient, cookingMethod, dishType, hashtag, page, size);
		return PagedResponse.of(recipePage, summarize(recipePage.getContent()));
	}

	private List<RecipeSummaryResponse> summarize(List<RecipeOverview> recipes) {
		if (recipes.isEmpty()) {
			return List.of();
		}
		List<UUID> recipeIds = recipes.stream().map(RecipeOverview::id).toList();
		Map<UUID, PersonalRecipeVersion> latestByRecipe = personalRecipeService.findLatestByRecipes(recipeIds);
		Set<UUID> favoriteRecipeIds = favoriteService.findFavoriteRecipeIds(recipeIds);
		Map<UUID, RecipeTagLookup.RecipeTagSummary> tagsByRecipe = recipeTagLookup.findByRecipes(recipeIds);
		return recipes.stream()
				.map(recipe -> {
					PersonalRecipeVersion latest = latestByRecipe.get(recipe.id());
					RecipeTagLookup.RecipeTagSummary tags =
							tagsByRecipe.getOrDefault(recipe.id(), RecipeTagLookup.EMPTY);
					return new RecipeSummaryResponse(
							recipe.id(),
							recipe.title(),
							recipe.description(),
							recipe.imageUrl(),
							latest != null,
							latest == null ? null : latest.id(),
							favoriteRecipeIds.contains(recipe.id()),
							tags.cookingMethod(),
							tags.dishType(),
							tags.hashtags()
					);
				})
				.toList();
	}

	@GetMapping("/{recipeId}")
	public Recipe get(@PathVariable UUID recipeId) {
		return recipeService.findById(recipeId);
	}
}
