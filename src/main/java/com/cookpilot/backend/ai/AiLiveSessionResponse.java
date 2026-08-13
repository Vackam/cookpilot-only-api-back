package com.cookpilot.backend.ai;

/**
 * Gemini Live 세션 토큰 발급 응답.
 *
 * token 은 1회용 ephemeral token("auth_tokens/...")으로, 클라이언트가 Live WebSocket 연결 시
 * API 키 자리에 그대로 쓴다. 발급 후 1분 안에 연결을 시작해야 하고 세션 하나만 열 수 있다.
 * model 은 토큰에 잠긴 모델 이름 — 클라이언트 setup 메시지가 참조한다.
 */
public record AiLiveSessionResponse(
		String token,
		String model
) {
}
