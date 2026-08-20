package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.UUID;

public record RecipeSummaryResponse(
		UUID id,
		String title,
		String description,
		String imageUrl,
		boolean hasPersonalVersion,
		UUID latestPersonalVersionId,
		boolean favorite,
		String cookingMethod,   // 미부여면 null
		String dishType,        // 미부여면 null
		List<String> hashtags   // 없으면 빈 배열
) {
}
