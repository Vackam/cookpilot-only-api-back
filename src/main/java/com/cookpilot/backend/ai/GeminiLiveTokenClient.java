package com.cookpilot.backend.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gemini Live 용 ephemeral token 발급기(v1alpha auth_tokens). 진짜 API 키는 서버에만 두고,
 * 클라이언트에는 1회용 단기 토큰만 내려보낸다. 토큰에 모델·systemInstruction 을
 * 잠가 두므로(bidiGenerateContentSetup) 탈취돼도 다른 용도로 못 쓴다.
 */
@Component
public class GeminiLiveTokenClient {

	/** 토큰 전체 수명. Live 연결 인증에만 쓰이므로 세션이 이보다 길어도 무방하다. */
	private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
	/** 이 시간 안에 세션을 시작해야 한다. 클라이언트는 발급 직후 바로 연결하므로 짧게 잡는다. */
	private static final Duration NEW_SESSION_TTL = Duration.ofMinutes(1);

	private final AiLiveProperties properties;
	private final RestClient restClient;

	public GeminiLiveTokenClient(AiLiveProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(2));
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	/**
	 * systemInstruction 이 잠긴 1회용 토큰을 발급한다. 반환값("auth_tokens/...")이
	 * 클라이언트가 Live WebSocket 연결에 API 키 대신 쓰는 문자열이다.
	 */
	public String createToken(String systemInstruction) {
		if (!properties.callable()) {
			throw new IllegalStateException(
					"GEMINI_API_KEY가 설정되지 않아 Live 세션 토큰을 발급할 수 없습니다.");
		}
		Instant now = Instant.now();
		CreateAuthTokenRequest request = new CreateAuthTokenRequest(
				1,
				now.plus(TOKEN_TTL).toString(),
				now.plus(NEW_SESSION_TTL).toString(),
				new BidiGenerateContentSetup(
						"models/" + properties.model(),
						new GenerationConfig(List.of("AUDIO")),
						new Content(List.of(new Part(systemInstruction)))));

		CreateAuthTokenResponse response = restClient.post()
				.uri("/v1alpha/auth_tokens")
				.header("x-goog-api-key", properties.apiKey())
				.body(request)
				.retrieve()
				.body(CreateAuthTokenResponse.class);
		if (response == null || response.name() == null || response.name().isBlank()) {
			throw new IllegalStateException("Live 세션 토큰 응답이 비어 있습니다.");
		}
		return response.name();
	}

	public String model() {
		return properties.model();
	}

	// v1alpha auth_tokens 와이어 포맷. SDK 문서의 liveConnectConstraints 는 REST 에선
	// bidiGenerateContentSetup 이다(실측 확인). 이 파일 밖으로 나가지 않는 벤더 전용 DTO.

	record CreateAuthTokenRequest(
			int uses,
			String expireTime,
			String newSessionExpireTime,
			BidiGenerateContentSetup bidiGenerateContentSetup) {
	}

	record BidiGenerateContentSetup(
			String model,
			GenerationConfig generationConfig,
			Content systemInstruction) {
	}

	record GenerationConfig(List<String> responseModalities) {
	}

	record Content(List<Part> parts) {
	}

	record Part(String text) {
	}

	record CreateAuthTokenResponse(String name) {
	}
}
