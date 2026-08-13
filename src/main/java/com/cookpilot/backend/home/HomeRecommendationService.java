package com.cookpilot.backend.home;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.cookpilot.backend.home.HomeRecommendationLoader.RecommendationDraft;
import com.cookpilot.backend.home.explanation.HomeExplanationContext;
import com.cookpilot.backend.home.explanation.HomeExplanationService;
import com.cookpilot.backend.user.UserService;

/**
 * 메인 화면 레시피 추천 조합.
 *
 * 조회는 {@link HomeRecommendationLoader}(트랜잭션), 판정은
 * {@link HomeRecommendationRuleEngine}(순수 함수), 문구는
 * {@link HomeExplanationService}(외부 호출) 가 맡는다. 재료 추천이 세운 3층 분리를 그대로 따른다.
 *
 * <p>협업 필터링은 쓰지 않는다 — 쓸 수 없다. 사용자 간 신호가 모이기 전까지는 내 조리 이력과
 * 레시피의 맛 프로파일만으로 판정하는 콘텐츠 기반이 유일한 선택지다.
 *
 * <p><strong>이 클래스에 {@code @Transactional} 이 없는 것은 실수가 아니다.</strong>
 * 문구 생성은 트랜잭션 밖에서 불러야 한다.
 */
@Service
public class HomeRecommendationService {

	private final HomeRecommendationLoader loader;
	private final HomeExplanationService explanationService;
	private final UserService userService;

	public HomeRecommendationService(HomeRecommendationLoader loader,
			HomeExplanationService explanationService,
			UserService userService) {
		this.loader = loader;
		this.explanationService = explanationService;
		this.userService = userService;
	}

	public List<RecommendedRecipeResponse> findRecommendations() {
		UUID userId = userService.getCurrentUser().id();
		Instant now = Instant.now();

		List<RecommendationDraft> drafts = loader.loadDrafts(userId, now);
		if (drafts.isEmpty()) {
			return List.of();
		}

		List<HomeExplanationContext> contexts = drafts.stream()
				.map(draft -> toContext(draft, now))
				.toList();
		List<HomeExplanationService.Explanation> explanations =
				explanationService.explainAll(contexts);

		return IntStream.range(0, drafts.size())
				.mapToObj(index -> toResponse(drafts.get(index), explanations.get(index)))
				.toList();
	}

	private HomeExplanationContext toContext(RecommendationDraft draft, Instant now) {
		Integer daysSinceCooked = draft.lastCookedAt() == null
				? null
				: (int) Duration.between(draft.lastCookedAt(), now).toDays();
		return new HomeExplanationContext(
				draft.title(),
				draft.description(),
				draft.slot(),
				draft.source(),
				draft.similarToTitle(),
				daysSinceCooked,
				draft.bestRating(),
				draft.favorite());
	}

	private RecommendedRecipeResponse toResponse(RecommendationDraft draft,
			HomeExplanationService.Explanation explanation) {
		return new RecommendedRecipeResponse(
				draft.recipeId(),
				draft.title(),
				draft.description(),
				draft.imageUrl(),
				draft.slot(),
				draft.source(),
				explanation.reason(),
				explanation.source(),
				explanation.model(),
				explanation.promptVersion(),
				draft.favorite(),
				draft.lastCookedAt(),
				draft.bestRating());
	}
}
