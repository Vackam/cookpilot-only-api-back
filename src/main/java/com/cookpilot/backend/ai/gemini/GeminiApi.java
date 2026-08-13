package com.cookpilot.backend.ai.gemini;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Gemini generateContent 요청·응답 와이어 포맷. 이 패키지 밖으로 나가지 않는 벤더 전용 DTO 다
 * ({@link GeminiReasonsClient} 만 만지고, 기능 패키지는 프롬프트 문자열과 문구 목록만 주고받는다).
 *
 * Gemini 는 usageMetadata·finishReason·modelVersion 처럼 우리가 쓰지 않는 필드를 계속
 * 늘려 보내지만, Jackson 3 은 모르는 필드를 기본으로 무시하므로 별도 애너테이션이 필요 없다
 * (GeminiApiTest 의 "모르는 필드가 섞여도 응답을 읽는다" 가 이 전제를 지킨다).
 */
final class GeminiApi {

	private GeminiApi() {
	}

	record GenerateContentRequest(
			List<Content> contents,
			GenerationConfig generationConfig
	) {
		static GenerateContentRequest ofUserText(String text, GenerationConfig config) {
			return new GenerateContentRequest(
					List.of(new Content("user", List.of(new Part(text)))),
					config);
		}
	}

	record Content(String role, List<Part> parts) {
	}

	record Part(String text) {
	}

	record GenerationConfig(
			double temperature,
			int maxOutputTokens,
			String responseMimeType,
			JsonNode responseJsonSchema,
			ThinkingConfig thinkingConfig
	) {
	}

	/** thinking 토큰 예산. flash 계열은 thinking 이 기본 활성이라 0 으로 꺼야 출력 예산이 온전히 본문에 쓰인다. */
	record ThinkingConfig(int thinkingBudget) {
	}

	record GenerateContentResponse(List<Candidate> candidates) {

		/** 첫 후보의 첫 파트 텍스트. 후보가 없거나 파트가 비거나 배열에 null 원소가 섞여도 empty. */
		Optional<String> firstText() {
			if (candidates == null || candidates.isEmpty()) {
				return Optional.empty();
			}
			Candidate first = candidates.get(0);
			if (first == null || first.content() == null) {
				return Optional.empty();
			}
			List<Part> parts = first.content().parts();
			if (parts == null || parts.isEmpty() || parts.get(0) == null) {
				return Optional.empty();
			}
			return Optional.ofNullable(parts.get(0).text())
					.filter(text -> !text.isBlank());
		}
	}

	record Candidate(Content content) {
	}

	/** responseJsonSchema 로 강제한 응답 본문. 모델이 실제로 지켰는지는 호출부가 검증한다. */
	record ReasonsPayload(List<String> reasons) {
	}
}
