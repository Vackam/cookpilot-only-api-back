package com.cookpilot.backend.ai.gemini;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "프롬프트 하나 → 검증된 한국어 문구 N개" 만 하는 공용 Gemini 호출기.
 *
 * 추천 설명(재료 양)과 메인 화면 추천 문구가 같은 배선을 쓴다. 두 기능이 공유하는 것은
 * HTTP·스키마·검증뿐이고, 프롬프트와 fallback 문구는 각 기능이 소유한다.
 *
 * <p>계약은 하나다: <strong>이 클라이언트는 절대 예외를 던지지 않는다.</strong> 키가 없든
 * HTTP 가 죽든 응답 형식이 어긋나든 전부 {@link Optional#empty()} 로 흡수하고, 호출부는
 * 규칙 기반 fallback 문구를 쓴다. 문구가 실패해도 추천 자체(수치·순위)는 이미 서버가
 * 계산해 둔 값이라 온전히 살아남는다.
 */
@Component
public class GeminiReasonsClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiReasonsClient.class);

	private static final double TEMPERATURE = 0.2;

	/**
	 * 최대 유효 응답(3건 × 180자 한국어 + JSON 문법)이 잘리지 않을 크기.
	 * thinking 예산을 0 으로 꺼서(ThinkingConfig) 이 예산이 전부 본문에 쓰이게 한다.
	 */
	private static final int MAX_OUTPUT_TOKENS = 1024;

	/** 문구 한 줄의 상한. 넘거나 줄바꿈이 섞이면 모델이 형식을 벗어난 것으로 보고 버린다. */
	private static final int MAX_REASON_LENGTH = 180;

	/**
	 * 응답 형식 강제용 JSON 스키마. 도메인이 아니라 벤더에 넘기는 스키마 리터럴이라
	 * 레코드로 쪼개지 않고 JSON 그대로 둔다(생성자에서 한 번만 파싱).
	 */
	static final String REASONS_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "reasons": { "type": "array", "items": { "type": "string" } }
			  },
			  "required": ["reasons"]
			}
			""";

	private final GeminiProperties properties;
	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final GeminiApi.GenerationConfig generationConfig;

	public GeminiReasonsClient(GeminiProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
		this.objectMapper = objectMapper;
		JsonNode schema = objectMapper.readTree(REASONS_SCHEMA);
		this.generationConfig = new GeminiApi.GenerationConfig(
				TEMPERATURE, MAX_OUTPUT_TOKENS, "application/json", schema,
				new GeminiApi.ThinkingConfig(0));
	}

	/**
	 * 프롬프트를 보내고 문구 {@code expectedCount} 개를 받는다.
	 *
	 * @return 개수·길이·형식 검증을 전부 통과한 문구 목록. 하나라도 어긋나면 empty
	 */
	public Optional<List<String>> reasons(String prompt, int expectedCount) {
		if (!properties.callable()) {
			return Optional.empty();
		}
		if (expectedCount == 0) {
			return Optional.of(List.of());
		}

		try {
			GeminiApi.GenerateContentRequest request =
					GeminiApi.GenerateContentRequest.ofUserText(prompt, generationConfig);

			GeminiApi.GenerateContentResponse response = restClient.post()
					.uri("/v1beta/models/{model}:generateContent", properties.model())
					.header("x-goog-api-key", properties.apiKey())
					.body(request)
					.retrieve()
					.body(GeminiApi.GenerateContentResponse.class);
			return parseReasons(response, expectedCount);
		} catch (RuntimeException exception) {
			// 호출·역직렬화·파싱 어디서 무엇이 어긋나도 기능 자체는 살아야 하므로
			// 전부 fallback 으로 흡수한다(수치는 이미 서버가 계산한 값이라 안전).
			log.warn("Gemini 문구 생성에 실패해 규칙 fallback을 사용합니다 ({})",
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	public String model() {
		return properties.model();
	}

	/**
	 * 응답 본문에서 문구 목록을 꺼낸다. 형식이 조금이라도 어긋나면 empty 를 돌려주고
	 * 호출부가 규칙 기반 fallback 문구를 쓴다.
	 *
	 * <p>부분 채택은 하지 않는다 — 개수가 어긋난 응답은 어느 문구가 어느 항목에 붙는지
	 * 알 수 없어서, 엉뚱한 항목에 남의 설명을 다는 것보다 전부 버리는 쪽이 안전하다.
	 */
	Optional<List<String>> parseReasons(
			GeminiApi.GenerateContentResponse response, int expectedCount) {
		if (response == null) {
			return Optional.empty();
		}
		Optional<String> generated = response.firstText();
		if (generated.isEmpty()) {
			return Optional.empty();
		}
		try {
			GeminiApi.ReasonsPayload payload = objectMapper.readValue(
					stripCodeFence(generated.get()), GeminiApi.ReasonsPayload.class);
			if (payload.reasons() == null || payload.reasons().size() != expectedCount) {
				return Optional.empty();
			}
			List<String> reasons = new ArrayList<>(payload.reasons().size());
			for (String item : payload.reasons()) {
				String reason = item == null ? "" : item.trim();
				if (reason.isBlank()
						|| reason.length() > MAX_REASON_LENGTH
						|| reason.contains("\n")) {
					return Optional.empty();
				}
				reasons.add(reason);
			}
			return Optional.of(List.copyOf(reasons));
		} catch (RuntimeException exception) {
			log.warn("Gemini 문구 응답 검증에 실패해 규칙 fallback을 사용합니다 ({})",
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private String stripCodeFence(String value) {
		String trimmed = value.trim();
		if (!trimmed.startsWith("```")) {
			return trimmed;
		}
		int firstLineEnd = trimmed.indexOf('\n');
		int fenceEnd = trimmed.lastIndexOf("```");
		if (firstLineEnd < 0 || fenceEnd <= firstLineEnd) {
			return trimmed;
		}
		return trimmed.substring(firstLineEnd + 1, fenceEnd).trim();
	}
}
