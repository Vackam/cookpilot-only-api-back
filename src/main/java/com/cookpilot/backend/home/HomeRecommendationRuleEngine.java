package com.cookpilot.backend.home;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 메인 화면 레시피 추천의 판정 규칙(순수 함수, DB 무관).
 *
 * 조리 전 재료 추천(`recommendation` 패키지)과는 다른 문제다. 저쪽은 "이 레시피의 양을
 * 얼마로 바꿀까"이고 여기는 "무슨 레시피를 보여줄까"라, 근거 임계값도 랭킹도 공유하지 않는다.
 * 공유하는 것은 맛 프로파일 유사도 함수 하나뿐이다.
 *
 * 카탈로그가 작아서(현재 8건) "안 만들어본 것"만 채우면 몇 번 요리한 뒤 후보가 마른다.
 * 그래서 슬롯을 NEW 2 + AGAIN 1 로 나누고, 한쪽이 모자라면 다른 쪽으로 채운다.
 */
final class HomeRecommendationRuleEngine {

	/** 메인 화면에 동시에 노출할 추천 개수. */
	static final int SLOT_COUNT = 3;

	/** 그중 "새로 도전" 에 배정하는 칸 수. 나머지는 "다시 만들기". */
	static final int NEW_SLOTS = 2;

	/** 이 점수 이상이면 만족한 조리로 본다(재료 추천의 근거 기준과 같은 값). */
	static final int LIKED_RATING = 4;

	/** 최근 이 기간 안에 만든 레시피는 "다시 만들기"로 다시 올리지 않는다. */
	static final Duration RECENTLY_COOKED = Duration.ofDays(14);

	/** 즐겨찾기 가산점. 순위를 뒤집을 만큼 크지 않게 유사도 한 축(0.15)보다 작게 둔다. */
	static final double FAVORITE_BONUS = 0.10;

	private HomeRecommendationRuleEngine() {
	}

	/**
	 * 판정 입력용 레시피 1건.
	 *
	 * @param lastCookedAt 만든 적 없으면 null
	 * @param bestRating 만든 적 없으면 0
	 * @param tasteSimilarity 내가 만족했던 레시피들과의 최대 프로파일 유사도(0~1)
	 * @param similarTo 그 유사도가 어느 레시피에서 나왔는지(문구용). 0 이면 null
	 */
	record RecipeCandidate(
			UUID recipeId,
			Instant lastCookedAt,
			int bestRating,
			boolean favorite,
			double tasteSimilarity,
			UUID similarTo
	) {
		boolean cooked() {
			return lastCookedAt != null;
		}
	}

	/** 판정 결과 1건. 문구는 아직 붙지 않았다. */
	record Pick(UUID recipeId, String slot, UUID similarTo) {
	}

	/**
	 * 후보를 슬롯에 배정한다. 모자라면 총 개수가 {@link #SLOT_COUNT} 미만으로 나온다.
	 *
	 * NEW  — 만든 적 없는 레시피. 취향 유사도 + 즐겨찾기 가산점 순.
	 * AGAIN — 만족했거나 즐겨찾기인데 최근에 안 만든 레시피. 평점 + 즐겨찾기 가산점 순.
	 *
	 * 취향 근거가 없으면(유사도 전부 0) NEW 는 동점이 되고 recipeId 순으로 결정된다.
	 * 빈 화면보다는 카탈로그를 보여주는 게 낫다는 판단이라, 이 경우 source 는 호출부가
	 * DEFAULT 로 라벨링한다.
	 */
	static List<Pick> pick(List<RecipeCandidate> candidates, Instant now) {
		List<RecipeCandidate> fresh = candidates.stream()
				.filter(candidate -> !candidate.cooked())
				.sorted(Comparator
						.comparingDouble(HomeRecommendationRuleEngine::newScore)
						.<RecipeCandidate>reversed()
						.thenComparing(RecipeCandidate::recipeId))
				.toList();
		List<RecipeCandidate> revisit = candidates.stream()
				.filter(candidate -> isRevisitable(candidate, now))
				.sorted(Comparator
						.comparingDouble(HomeRecommendationRuleEngine::againScore).reversed()
						.thenComparing(RecipeCandidate::recipeId))
				.toList();

		int newCount = Math.min(NEW_SLOTS, fresh.size());
		int againCount = Math.min(SLOT_COUNT - newCount, revisit.size());
		// 한쪽이 모자라면 남은 칸을 다른 쪽으로 채운다. 카탈로그가 작아 자주 발생한다.
		newCount = Math.min(fresh.size(), newCount + (SLOT_COUNT - newCount - againCount));

		List<Pick> picks = new ArrayList<>(SLOT_COUNT);
		fresh.stream().limit(newCount)
				.forEach(candidate -> picks.add(
						new Pick(candidate.recipeId(), "NEW", candidate.similarTo())));
		revisit.stream().limit(againCount)
				.forEach(candidate -> picks.add(
						new Pick(candidate.recipeId(), "AGAIN", null)));
		return picks;
	}

	private static boolean isRevisitable(RecipeCandidate candidate, Instant now) {
		return candidate.cooked()
				&& candidate.lastCookedAt().isBefore(now.minus(RECENTLY_COOKED))
				&& (candidate.bestRating() >= LIKED_RATING || candidate.favorite());
	}

	private static double newScore(RecipeCandidate candidate) {
		return candidate.tasteSimilarity() + favoriteBonus(candidate);
	}

	private static double againScore(RecipeCandidate candidate) {
		return candidate.bestRating() / 5.0 + favoriteBonus(candidate);
	}

	private static double favoriteBonus(RecipeCandidate candidate) {
		return candidate.favorite() ? FAVORITE_BONUS : 0;
	}
}
