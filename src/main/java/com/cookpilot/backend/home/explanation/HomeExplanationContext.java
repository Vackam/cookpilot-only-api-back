package com.cookpilot.backend.home.explanation;

/**
 * 추천 1건에 문구를 붙이기 위해 필요한 전부.
 *
 * 어떤 레시피를 몇 번 칸에 넣을지는 이미 정해진 뒤다. 여기 담긴 값은 "왜 이게 여기 있는지"를
 * 설명하는 재료일 뿐이라 recipeId 도 점수도 들어 있지 않다 — 모델에게 내부 점수를 보여줄
 * 이유가 없고, 보여주면 문구에 새어 나온다.
 *
 * @param slot NEW(새로 도전) 또는 AGAIN(다시 만들기)
 * @param source TASTE(취향 근거) / HISTORY(조리 이력 근거) / DEFAULT(근거 없음)
 * @param similarToTitle TASTE 일 때 근거가 된 레시피 제목. 그 외 null
 * @param daysSinceCooked AGAIN 일 때 마지막 조리로부터 지난 일수. 그 외 null
 * @param bestRating AGAIN 일 때 그 레시피에 준 최고 평점. 그 외 null
 */
public record HomeExplanationContext(
		String recipeTitle,
		String recipeDescription,
		String slot,
		String source,
		String similarToTitle,
		Integer daysSinceCooked,
		Integer bestRating,
		boolean favorite
) {
}
