package com.cookpilot.backend.ai;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Gemini Live 세션 토큰 발급 요청. 어떤 레시피를 조리하는지만 받는다 —
 * 모델·systemInstruction 은 서버가 정해 토큰에 잠근다.
 */
public record AiLiveSessionRequest(
		@NotNull(message = "recipeId는 필수입니다.") UUID recipeId) {
}
