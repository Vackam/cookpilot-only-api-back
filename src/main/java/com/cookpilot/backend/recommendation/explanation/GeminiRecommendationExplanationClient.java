package com.cookpilot.backend.recommendation.explanation;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cookpilot.backend.ai.gemini.GeminiReasonsClient;

/**
 * 조리 전 재료 양 추천의 설명 문구 생성기.
 *
 * HTTP·응답 검증·실패 흡수는 전부 {@link GeminiReasonsClient} 가 한다. 이 클래스에 남은
 * 것은 <strong>프롬프트뿐</strong>이다 — 추천 수치와 근거는 서버가 이미 계산했으므로
 * 모델에게는 그 값을 바꾸지 말라고 못 박고 문구만 받는다.
 */
@Component
public class GeminiRecommendationExplanationClient
		implements RecommendationExplanationClient {

	private final GeminiReasonsClient reasonsClient;

	public GeminiRecommendationExplanationClient(GeminiReasonsClient reasonsClient) {
		this.reasonsClient = reasonsClient;
	}

	@Override
	public Optional<List<String>> explainAll(
			List<RecommendationExplanationContext> contexts) {
		if (contexts.isEmpty()) {
			return Optional.of(List.of());
		}
		return reasonsClient.reasons(prompt(contexts), contexts.size());
	}

	@Override
	public String model() {
		return reasonsClient.model();
	}

	String prompt(List<RecommendationExplanationContext> contexts) {
		StringBuilder items = new StringBuilder();
		for (int index = 0; index < contexts.size(); index++) {
			RecommendationExplanationContext context = contexts.get(index);
			String evidence = context.evidence().stream()
					.map(item -> "%s(평점 %d)".formatted(
							item.recipeTitle(), item.rating()))
					.distinct()
					.limit(3)
					.reduce((left, right) -> left + ", " + right)
					.orElse("과거 조리 기록");
			items.append("""
					%d. 대상 요리=%s, 재료=%s, 기존 양=%s%s, 추천 양=%s%s, 변경률=%d%%, 근거=%s
					""".formatted(
					index + 1,
					context.targetRecipeTitle(),
					context.ingredientName(),
					context.originalAmount().toPlainString(),
					context.unit(),
					context.suggestedAmount().toPlainString(),
					context.unit(),
					context.changePercent(),
					evidence));
		}
		return """
				당신은 CookPilot의 조리 전 개인화 추천 설명기입니다.
				추천 수치와 근거는 서버가 이미 계산했으므로 절대 변경하지 마세요.
				각 항목마다 한국어 한 문장으로, 과장 없이 사용자가 선택할 수 있는 제안으로 설명하세요.
				사용자의 이름이나 내부 점수는 언급하지 마세요.
				입력 순서와 같은 순서의 reasons 배열로 답하세요.

				%s
				""".formatted(items);
	}
}
