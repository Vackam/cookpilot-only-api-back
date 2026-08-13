package com.cookpilot.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gemini Live 세션 토큰 발급 설정. F-11 추천 설명·조리 피드백과 같은 Google AI Studio 키를
 * 공유한다. 키가 없으면 토큰 발급 요청은 409 로 거절된다(추천처럼 조용한 fallback 이 없다 —
 * 토큰 없이는 클라이언트가 Live 연결 자체를 못 하므로 실패를 숨기면 안 된다).
 */
@ConfigurationProperties("cookpilot.ai.live")
public record AiLiveProperties(
		@DefaultValue("") String apiKey,
		@DefaultValue("gemini-3.1-flash-live-preview") String model,
		@DefaultValue("https://generativelanguage.googleapis.com") String baseUrl
) {

	/** 호출 가능한 설정인지. 키가 비어 있으면 발급하지 않는다. */
	public boolean callable() {
		return apiKey != null && !apiKey.isBlank();
	}
}
