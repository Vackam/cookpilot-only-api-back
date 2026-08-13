# only-api — Gemini Live 세션 토큰 발급

## 무엇을, 왜

음성 조리 코치를 Gemini Live API(WebSocket 양방향 오디오)로 붙이기로 했다. STT→LLM→TTS
조립으로는 Live 급 지연(첫 오디오 ~1초 미만)이 안 나오기 때문이다. 조리 세션은 프론트 소유라는
기존 전제(AGENTS.md)를 유지하려면 Flutter 가 Live WebSocket 에 직접 붙어야 하고, 그러려면
클라이언트에 자격증명이 필요하다. 진짜 API 키를 클라에 내려보낼 수는 없으므로, 서버가
**1회용 ephemeral token** 을 발급해 주는 엔드포인트 하나를 추가했다. 서버는 오디오 스트림에
관여하지 않는다 — 토큰 발급이 서버 역할의 전부다.

## 핵심 설계 결정

- **클라이언트 직결 + 서버는 토큰 발급만.** 서버 프록시 경유는 홉 지연과 오디오 중계 코드만
  늘린다. 세션 결과 저장은 기존 리뷰 POST 흐름 그대로다.
- **모델·systemInstruction 을 토큰에 잠근다**(`bidiGenerateContentSetup`). 레시피 전문(재료·
  단계·타이머·주의사항)과 CookingCoachClient 의 안전 원칙(SYSTEM_PROMPT 공유)을 서버가 박아
  보내므로, 클라이언트는 오디오만 흘리면 되고 탈취된 토큰은 다른 프롬프트·모델로 못 쓴다.
- **토큰 수명: uses=1, 발급 후 1분 내 연결 시작, 전체 30분.** 클라이언트는 요리 시작 직전에
  요청하고 즉시 연결한다. 토큰 만료는 연결 인증에만 걸리므로 세션이 30분을 넘어도 무방하다.
- **키 미설정이면 409.** 추천 설명(F-11)처럼 조용한 fallback 을 두지 않았다 — 토큰 없이는
  클라이언트가 Live 연결 자체를 못 하므로 실패를 숨기면 안 된다. (`IllegalStateException` →
  GlobalExceptionHandler 409 관례를 그대로 탄다.)
- **REST 직접 호출.** v1alpha `auth_tokens` 는 SDK 표면이 불안정해서(문서상 필드명
  `liveConnectConstraints` 는 REST 에선 `bidiGenerateContentSetup` — 실측으로 확인) 기존
  GeminiRecommendationExplanationClient 패턴대로 RestClient 를 쓴다. 키는 F-11 과 같은
  `GEMINI_API_KEY` 를 공유한다.

## API 변경

- `POST /api/v1/ai-sessions` 추가. 요청 `{recipeId}` → 응답 `{token, model}`.
  - `token`: `auth_tokens/...` — 클라이언트가 Live WebSocket
    (`.../v1alpha.GenerativeService.BidiGenerateContent`) 연결 시 API 키 자리에 그대로 사용.
  - `model`: 토큰에 잠긴 모델(기본 `gemini-2.5-flash-native-audio-preview-09-2025`,
    `GEMINI_LIVE_MODEL` 로 교체 가능).
  - 400(recipeId 누락) / 404(레시피 없음) / 409(서버에 키 미설정).
- 스키마 변경 없음(DB 무관). 설정은 `cookpilot.ai.live.*` 블록 추가.

## 검증

- `AiLiveSessionApiTest`: 400/404/409 경로 (테스트 환경엔 키가 없어 200 경로는 못 돈다).
- `AiLiveSessionServiceTest`: systemInstruction 에 안전 원칙·재료·단계 전문 포함.
- 200 경로는 로컬에서 실키로 E2E 실측: db 프로파일 기동 → 익명 유저 생성 → 레시피 조회 →
  `POST /ai-sessions` → 실토큰 발급 확인(HTTP 200).

## 알려진 약점·후속

- 백엔드 인증이 아직 익명 베타 세션뿐이라, 서버 주소를 아는 누구나 토큰 발급을 두들겨 쿼터를
  태울 수 있다. 실키 배포 전에 rate limit 또는 진짜 auth 필요.
- Live 세션 자체 시간 제한(오디오 세션 상한, context window)은 클라이언트가 resume 토큰으로
  대응해야 한다 — 서버는 관여하지 않는다.
- native audio 모델은 preview 라 이름이 바뀔 수 있다. `GEMINI_LIVE_MODEL` 로 덮어쓴다.
- 세션 결과(피드백 요약 등)를 리뷰에 싣는 계약은 아직 프론트와 미확정.
