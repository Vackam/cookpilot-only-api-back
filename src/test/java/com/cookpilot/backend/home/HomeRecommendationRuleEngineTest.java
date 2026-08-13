package com.cookpilot.backend.home;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cookpilot.backend.home.HomeRecommendationRuleEngine.Pick;
import com.cookpilot.backend.home.HomeRecommendationRuleEngine.RecipeCandidate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메인 화면 추천 슬롯 배정 단위 테스트. DB·Spring 컨텍스트 없이 순수 함수만 검증한다.
 */
class HomeRecommendationRuleEngineTest {

	private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

	/** id 순 정렬이 결과에 섞이지 않도록 오름차순이 보장된 id 를 쓴다. */
	private static UUID id(int seq) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(seq));
	}

	private static RecipeCandidate fresh(int seq, double similarity) {
		return new RecipeCandidate(id(seq), null, 0, false, similarity,
				similarity > 0 ? id(99) : null);
	}

	private static RecipeCandidate cooked(int seq, int daysAgo, int rating) {
		return new RecipeCandidate(id(seq), NOW.minus(java.time.Duration.ofDays(daysAgo)),
				rating, false, 0, null);
	}

	@Nested
	class 슬롯_배정 {

		@Test
		void 새것_2건과_다시_1건을_채운다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					fresh(1, 0.70), fresh(2, 0.45), fresh(3, 0.25),
					cooked(4, 30, 5), cooked(5, 40, 4)), NOW);

			assertThat(picks).extracting(Pick::slot)
					.containsExactly("NEW", "NEW", "AGAIN");
			assertThat(picks).extracting(Pick::recipeId)
					.containsExactly(id(1), id(2), id(4));
		}

		@Test
		void 다시_만들_후보가_없으면_새것으로_3칸을_채운다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					fresh(1, 0.70), fresh(2, 0.45), fresh(3, 0.25), fresh(4, 0.10)), NOW);

			assertThat(picks).extracting(Pick::slot)
					.containsExactly("NEW", "NEW", "NEW");
			assertThat(picks).extracting(Pick::recipeId)
					.containsExactly(id(1), id(2), id(3));
		}

		@Test
		void 새것이_없으면_다시_만들기로_3칸을_채운다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					cooked(1, 30, 5), cooked(2, 40, 5), cooked(3, 50, 4), cooked(4, 60, 4)), NOW);

			assertThat(picks).extracting(Pick::slot)
					.containsExactly("AGAIN", "AGAIN", "AGAIN");
			assertThat(picks).extracting(Pick::recipeId)
					.containsExactly(id(1), id(2), id(3));
		}

		@Test
		void 후보가_모자라면_3칸을_못_채운다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					fresh(1, 0.70), cooked(2, 30, 5)), NOW);

			assertThat(picks).hasSize(2);
		}
	}

	@Nested
	class 다시_만들기_자격 {

		@Test
		void 최근_2주_안에_만든_것은_다시_올리지_않는다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					cooked(1, 3, 5), cooked(2, 13, 5)), NOW);

			assertThat(picks).isEmpty();
		}

		@Test
		void 만족하지_않은_조리는_다시_올리지_않는다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					cooked(1, 30, 3), cooked(2, 30, 2)), NOW);

			assertThat(picks).isEmpty();
		}

		@Test
		void 평점이_낮아도_즐겨찾기면_다시_올린다() {
			RecipeCandidate favorite = new RecipeCandidate(
					id(1), NOW.minus(java.time.Duration.ofDays(30)), 2, true, 0, null);

			assertThat(HomeRecommendationRuleEngine.pick(List.of(favorite), NOW))
					.extracting(Pick::slot)
					.containsExactly("AGAIN");
		}
	}

	@Nested
	class 순위 {

		@Test
		void 즐겨찾기_가산점은_유사도_순위를_뒤집지_못한다() {
			// 0.45(소스 베이스) 차이는 가산점 0.10 으로 넘을 수 없다.
			RecipeCandidate similar = new RecipeCandidate(id(2), null, 0, false, 0.70, id(99));
			RecipeCandidate favoriteButDistant =
					new RecipeCandidate(id(1), null, 0, true, 0.25, id(99));

			assertThat(HomeRecommendationRuleEngine.pick(
							List.of(favoriteButDistant, similar), NOW))
					.extracting(Pick::recipeId)
					.containsExactly(id(2), id(1));
		}

		@Test
		void 취향_근거가_없으면_유사도가_전부_0이라_id_순으로_결정된다() {
			List<Pick> picks = HomeRecommendationRuleEngine.pick(List.of(
					fresh(3, 0), fresh(1, 0), fresh(2, 0)), NOW);

			assertThat(picks).extracting(Pick::recipeId)
					.containsExactly(id(1), id(2), id(3));
			assertThat(picks).allSatisfy(pick -> assertThat(pick.similarTo()).isNull());
		}
	}
}
