package com.cookpilot.backend.ai.gemini;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 다음 조리 추천 설명 생성기(Gemini) 설정. 키가 없거나 enabled=false 면 클라이언트는 호출하지 않고
 * 규칙 결과 + 추천 fallback 을 그대로 쓴다.
 */
@ConfigurationProperties("cookpilot.ai.gemini")
public record GeminiProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("") String apiKey,
		@DefaultValue("gemini-3.5-flash") String model,
		@DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
		@DefaultValue("2s") Duration connectTimeout,
		@DefaultValue("4s") Duration readTimeout
) {

	/** 호출 가능한 설정인지. 키가 비어 있으면 켜져 있어도 호출하지 않는다. */
	public boolean callable() {
		return enabled && apiKey != null && !apiKey.isBlank();
	}
}
