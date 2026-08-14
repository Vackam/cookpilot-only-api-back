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

	public RecipeController(RecipeService recipeService,
			PersonalRecipeService personalRecipeService,
			FavoriteService favoriteService) {
		this.recipeService = recipeService;
		this.personalRecipeService = personalRecipeService;
		this.favoriteService = favoriteService;
	}

	@GetMapping
	public PagedResponse<RecipeSummaryResponse> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		Page<RecipeOverview> recipePage = recipeService.findPage(page, size);
		List<RecipeOverview> recipes = recipePage.getContent();
		List<UUID> recipeIds = recipes.stream().map(RecipeOverview::id).toList();
		Map<UUID, PersonalRecipeVersion> latestByRecipe = personalRecipeService.findLatestByRecipes(recipeIds);
		Set<UUID> favoriteRecipeIds = favoriteService.findFavoriteRecipeIds(recipeIds);
		List<RecipeSummaryResponse> items = recipes.stream()
				.map(recipe -> {
					PersonalRecipeVersion latest = latestByRecipe.get(recipe.id());
					return new RecipeSummaryResponse(
							recipe.id(),
							recipe.title(),
							recipe.description(),
							recipe.imageUrl(),
							latest != null,
							latest == null ? null : latest.id(),
							favoriteRecipeIds.contains(recipe.id())
					);
				})
				.toList();
		return PagedResponse.of(recipePage, items);
	}

	@GetMapping("/{recipeId}")
	public Recipe get(@PathVariable UUID recipeId) {
		return recipeService.findById(recipeId);
	}
}
