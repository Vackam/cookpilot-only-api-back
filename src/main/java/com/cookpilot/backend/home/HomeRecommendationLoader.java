package com.cookpilot.backend.home;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cookpilot.backend.favorite.FavoriteService;
import com.cookpilot.backend.home.HomeRecommendationRuleEngine.Pick;
import com.cookpilot.backend.home.HomeRecommendationRuleEngine.RecipeCandidate;
import com.cookpilot.backend.recipe.RecipeEntity;
import com.cookpilot.backend.recipe.RecipeRepository;
import com.cookpilot.backend.recommendation.RecommendationRuleEngine;
import com.cookpilot.backend.recommendation.RecommendationRuleEngine.FlavorProfile;
import com.cookpilot.backend.recommendation.profile.RecipeFlavorProfileEntity;
import com.cookpilot.backend.recommendation.profile.RecipeFlavorProfileRepository;
import com.cookpilot.backend.review.PostCookReviewEntity;
import com.cookpilot.backend.review.PostCookReviewRepository;

/**
 * 메인 화면 추천의 DB 조회 전부. 판정은 {@link HomeRecommendationRuleEngine}(순수 함수)가 한다.
 *
 * 문구 생성(Gemini)과 분리된 이유는 트랜잭션 경계다 — 이 클래스가 끝나면 커넥션이 반납되고,
 * 그 뒤에 LLM 을 부른다. 응답을 최대 4초 기다리는 동안 커넥션을 붙잡으면 동시 요청 몇 건으로
 * 풀이 마른다. 재료 추천의 RecommendationDraftLoader 와 같은 이유, 같은 모양이다.
 */
@Component
public class HomeRecommendationLoader {

	private final RecipeRepository recipeRepository;
	private final RecipeFlavorProfileRepository flavorProfileRepository;
	private final PostCookReviewRepository postCookReviewRepository;
	private final FavoriteService favoriteService;

	public HomeRecommendationLoader(RecipeRepository recipeRepository,
			RecipeFlavorProfileRepository flavorProfileRepository,
			PostCookReviewRepository postCookReviewRepository,
			FavoriteService favoriteService) {
		this.recipeRepository = recipeRepository;
		this.flavorProfileRepository = flavorProfileRepository;
		this.postCookReviewRepository = postCookReviewRepository;
		this.favoriteService = favoriteService;
	}

	/**
	 * 추천 후보를 골라 문구 붙이기 직전 상태로 돌려준다.
	 *
	 * @param now 판정 기준 시각. 호출부가 넘겨 응답 전체가 같은 시각을 쓰게 한다
	 */
	@Transactional(readOnly = true)
	public List<RecommendationDraft> loadDrafts(UUID userId, Instant now) {
		List<RecipeEntity> catalog = recipeRepository.findByStatusOrderByTitleAscIdAsc("active");
		if (catalog.isEmpty()) {
			return List.of();
		}

		List<UUID> catalogIds = catalog.stream().map(RecipeEntity::getId).toList();
		Map<UUID, RecipeEntity> recipesById = catalog.stream()
				.collect(Collectors.toMap(RecipeEntity::getId, recipe -> recipe));
		Map<UUID, FlavorProfile> profilesByRecipe = loadProfiles();
		Map<UUID, CookRecord> historyByRecipe = loadHistory(userId);
		Set<UUID> favoriteRecipeIds = favoriteService.findFavoriteRecipeIds(catalogIds);

		// 취향 근거: 내가 4점 이상을 준 레시피들. 이게 비면 유사도가 전부 0이 되고
		// 결과는 카탈로그 기본 노출(DEFAULT)로 떨어진다.
		List<UUID> likedRecipeIds = historyByRecipe.values().stream()
				.filter(record -> record.bestRating() >= HomeRecommendationRuleEngine.LIKED_RATING)
				.map(CookRecord::recipeId)
				.toList();

		List<RecipeCandidate> candidates = catalog.stream()
				.map(recipe -> toCandidate(recipe, profilesByRecipe, historyByRecipe,
						favoriteRecipeIds, likedRecipeIds))
				.toList();

		return HomeRecommendationRuleEngine.pick(candidates, now).stream()
				.map(pick -> toDraft(pick, recipesById, historyByRecipe, favoriteRecipeIds))
				.toList();
	}

	private Map<UUID, FlavorProfile> loadProfiles() {
		return flavorProfileRepository.findAll().stream()
				.collect(Collectors.toMap(RecipeFlavorProfileEntity::getRecipeId,
						profile -> new FlavorProfile(
								profile.getCuisine(),
								profile.getDishType(),
								profile.getCookingMethods(),
								profile.getSauceBases())));
	}

	/** 레시피별 마지막 조리 시각과 최고 평점. 평점이 없는 리뷰는 0으로 본다. */
	private Map<UUID, CookRecord> loadHistory(UUID userId) {
		Map<UUID, CookRecord> historyByRecipe = new HashMap<>();
		for (PostCookReviewEntity review : postCookReviewRepository
				.findByUserIdOrderByCreatedAtDesc(userId)) {
			int rating = review.getRating() == null ? 0 : review.getRating();
			historyByRecipe.merge(review.getRecipeId(),
					new CookRecord(review.getRecipeId(), review.getCookedAt(), rating),
					CookRecord::merge);
		}
		return historyByRecipe;
	}

	private RecipeCandidate toCandidate(RecipeEntity recipe,
			Map<UUID, FlavorProfile> profilesByRecipe,
			Map<UUID, CookRecord> historyByRecipe,
			Set<UUID> favoriteRecipeIds,
			List<UUID> likedRecipeIds) {
		CookRecord history = historyByRecipe.get(recipe.getId());
		FlavorProfile profile = profilesByRecipe.get(recipe.getId());

		double bestSimilarity = 0;
		UUID similarTo = null;
		if (profile != null && history == null) {
			// 유사도는 "안 만들어본 것"을 고를 때만 쓴다. 이미 만든 레시피는 평점으로 판정한다.
			for (UUID likedRecipeId : likedRecipeIds) {
				double similarity = RecommendationRuleEngine.profileSimilarity(
						profile, profilesByRecipe.get(likedRecipeId), false);
				if (similarity > bestSimilarity) {
					bestSimilarity = similarity;
					similarTo = likedRecipeId;
				}
			}
		}

		return new RecipeCandidate(
				recipe.getId(),
				history == null ? null : history.lastCookedAt(),
				history == null ? 0 : history.bestRating(),
				favoriteRecipeIds.contains(recipe.getId()),
				bestSimilarity,
				similarTo);
	}

	private RecommendationDraft toDraft(Pick pick,
			Map<UUID, RecipeEntity> recipesById,
			Map<UUID, CookRecord> historyByRecipe,
			Set<UUID> favoriteRecipeIds) {
		RecipeEntity recipe = recipesById.get(pick.recipeId());
		CookRecord history = historyByRecipe.get(pick.recipeId());
		boolean revisit = "AGAIN".equals(pick.slot());
		String source = revisit ? "HISTORY" : (pick.similarTo() == null ? "DEFAULT" : "TASTE");
		RecipeEntity similarTo = pick.similarTo() == null
				? null : recipesById.get(pick.similarTo());

		return new RecommendationDraft(
				recipe.getId(),
				recipe.getTitle(),
				recipe.getDescription(),
				recipe.getImageUrl(),
				pick.slot(),
				source,
				similarTo == null ? null : similarTo.getTitle(),
				favoriteRecipeIds.contains(recipe.getId()),
				history == null ? null : history.lastCookedAt(),
				history == null ? null : history.bestRating());
	}

	/** 문구가 붙기 전 추천 1건. 엔티티가 아니라 값 타입이라 트랜잭션 밖으로 그대로 나간다. */
	public record RecommendationDraft(
			UUID recipeId,
			String title,
			String description,
			String imageUrl,
			String slot,
			String source,
			String similarToTitle,
			boolean favorite,
			Instant lastCookedAt,
			Integer bestRating
	) {
	}

	/** 레시피 1건에 대한 내 조리 이력 요약. */
	private record CookRecord(UUID recipeId, Instant lastCookedAt, int bestRating) {

		/** 같은 레시피의 기록 둘을 합친다: 조리 시각은 최신, 평점은 최고. */
		CookRecord merge(CookRecord other) {
			return new CookRecord(recipeId,
					lastCookedAt.isAfter(other.lastCookedAt())
							? lastCookedAt : other.lastCookedAt(),
					Math.max(bestRating, other.bestRating()));
		}
	}
}
