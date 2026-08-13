package com.cookpilot.backend.home;

import java.time.Instant;
import java.util.UUID;

/**
 * 메인 화면 레시피 추천 1건.
 *
 * 출처가 두 축으로 나뉜다. {@code source} 는 <em>왜 이 레시피가 뽑혔나</em>(추천 근거),
 * {@code explanationSource} 는 <em>이 문장을 누가 썼나</em>(문구 출처)다. 둘은 독립이라
 * 근거 없는 추천에 LLM 문구가 붙을 수도, 취향 추천에 규칙 문구가 붙을 수도 있다.
 */
public record RecommendedRecipeResponse(
		UUID recipeId,
		String title,
		String description,
		String imageUrl,
		/** NEW = 아직 안 만들어본 것, AGAIN = 예전에 만족했던 것 */
		String slot,
		/** TASTE = 내 취향 프로파일 근거, HISTORY = 내 조리 이력 근거, DEFAULT = 근거 없음 */
		String source,
		String reason,
		/** GEMINI = LLM 생성 문구, FALLBACK = 규칙 문구 */
		String explanationSource,
		/** GEMINI 일 때만 채워진다 */
		String model,
		String promptVersion,
		boolean favorite,
		/** 만든 적 없으면 null */
		Instant lastCookedAt,
		/** 그 레시피에 준 최고 평점. 만든 적 없으면 null */
		Integer bestRating
) {
}
