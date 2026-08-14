package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cookpilot.backend.common.NotFoundException;

@Service
@Transactional(readOnly = true)
public class RecipeService {

	public static final int MAX_PAGE_SIZE = 100;

	private final RecipeRepository recipeRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final RecipeStepRepository recipeStepRepository;

	public RecipeService(RecipeRepository recipeRepository,
			RecipeIngredientRepository recipeIngredientRepository,
			RecipeStepRepository recipeStepRepository) {
		this.recipeRepository = recipeRepository;
		this.recipeIngredientRepository = recipeIngredientRepository;
		this.recipeStepRepository = recipeStepRepository;
	}

	public Page<RecipeOverview> findPage(int page, int size) {
		if (page < 0) {
			throw new IllegalArgumentException("page 는 0 이상이어야 합니다: " + page);
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size 는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다: " + size);
		}
		return recipeRepository.findByStatusOrderByTitleAscIdAsc("active", PageRequest.of(page, size))
				.map(this::toOverview);
	}

	public Recipe findById(UUID recipeId) {
		RecipeEntity entity = recipeRepository.findById(recipeId)
				.orElseThrow(() -> new NotFoundException("레시피를 찾을 수 없습니다: " + recipeId));
		return toRecipe(entity);
	}

	private RecipeOverview toOverview(RecipeEntity entity) {
		return new RecipeOverview(
				entity.getId(),
				entity.getTitle(),
				entity.getDescription(),
				entity.getImageUrl());
	}

	private Recipe toRecipe(RecipeEntity entity) {
		List<RecipeIngredient> ingredients = recipeIngredientRepository
				.findByRecipeIdOrderBySortOrderAsc(entity.getId())
				.stream()
				.map(ingredient -> new RecipeIngredient(
						ingredient.getId(),
						ingredient.getName(),
						ingredient.getAmount() == null ? null : ingredient.getAmount().doubleValue(),
						ingredient.getUnit(),
						ingredient.isRequired()))
				.toList();

		List<RecipeStep> steps = recipeStepRepository
				.findByRecipeIdOrderByStepIndexAsc(entity.getId())
				.stream()
				.map(step -> new RecipeStep(
						step.getId(),
						step.getStepIndex(),
						step.getInstruction(),
						step.getTimerSeconds(),
						step.getCautionNote(),
						step.getImageUrl()))
				.toList();

		return new Recipe(
				entity.getId(),
				entity.getTitle(),
				entity.getDescription(),
				entity.getBaseServings().doubleValue(),
				entity.getImageUrl(),
				ingredients,
				steps);
	}
}
