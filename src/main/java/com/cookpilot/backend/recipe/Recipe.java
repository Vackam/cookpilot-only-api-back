package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.UUID;

public record Recipe(
		UUID id,
		String title,
		String description,
		Double baseServings,
		String imageUrl,
		String cookingMethod,   // 미부여면 null
		String dishType,        // 미부여면 null
		List<String> hashtags,  // 없으면 빈 배열
		List<RecipeIngredient> ingredients,
		List<RecipeStep> steps
) {
}
