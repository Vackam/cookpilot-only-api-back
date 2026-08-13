# feat-home-recipe-recommendation — 메인 화면 레시피 추천

## 무엇을, 왜

메인 화면에는 지금까지 `GET /api/v1/home/recent-recipes`(최근 조리 이력)밖에 없었다. 이력은
추천이 아니다 — 이미 만든 것만 다시 보여주므로 "오늘 뭐 만들지"에 답하지 못한다.

추천이 나올 수 있는 표면은 애초에 두 개뿐이다:

| 표면 | 엔드포인트 | 상태 |
|---|---|---|
| 메인 — 어떤 레시피를 만들까 | `GET /api/v1/home/recommendations` | **이번 작업** |
| 조리 전 — 재료를 얼마나 바꿀까 | `GET /api/v1/recipes/{id}/next-cook-recommendations` | 기존(#27, #28) |

`POST /ai-feedback` 은 조리 중 질문-답변이라 추천이 아니고, 리뷰의 `next_time_note` 는
사용자가 직접 쓰는 값이라 추천이 아니다. 이번 작업은 위 표의 첫 줄을 채운다.

## 핵심 설계 결정

### 1. 콘텐츠 기반. 협업 필터링·ML 은 쓰지 않는다

쓸 수 없어서다. 현재 카탈로그는 **레시피 8건**이고 사용자는 `UserService.getCurrentUser()`
가 돌려주는 **하드코딩 mock 1명**이다. 사용자 간 상호작용 행렬이 성립하지 않으므로 협업
필터링은 계산 자체가 불가능하고, 학습형 랭커는 라벨 데이터가 0건이라 인기순 베이스라인도 못
이긴다. 이 규모에서는 사람이 가중치를 정한 콘텐츠 기반 규칙이 정답이다.

ML 로 넘어갈 조건은 명확하다: 사용자 수천 명 + 조리 기록 1만 건 규모. 그때
`recommendation_feedback` 테이블이 그대로 학습 데이터가 된다.

### 2. 유사도 함수는 재료 추천 것을 그대로 재사용한다

`RecommendationRuleEngine.profileSimilarity(target, source, sameRecipe)` 는 DB 를 모르는
순수 static 함수라 그대로 쓸 수 있다. 이번 작업에서 이 클래스·`FlavorProfile` 레코드·해당
메서드의 가시성만 public 으로 올렸다(3 키워드). 로직은 손대지 않았다.

**단, 임계값은 공유하지 않는다.** 재료 추천의 `MIN_PROFILE_SIMILARITY = 0.60` 은 "다른
레시피의 기록을 근거로 끌어와도 되는가"의 기준이다. 메인 추천은 근거를 끌어오는 게 아니라
단순 랭킹이므로 컷오프 없이 점수 순으로만 세운다.

### 3. 슬롯을 NEW 2 + AGAIN 1 로 나눈다

카탈로그가 8건이라 "안 만들어본 것"만 채우면 4~5번 요리한 뒤 후보가 마르고 화면이 빈다.
그래서 칸을 나눴다:

- **NEW** — 만든 적 없는 레시피. 점수 = 취향 유사도 + 즐겨찾기 가산점
- **AGAIN** — 만족했거나(4점 이상) 즐겨찾기인데 최근 14일 안에 안 만든 레시피.
  점수 = 평점/5 + 즐겨찾기 가산점

한쪽 후보가 모자라면 남은 칸을 다른 쪽으로 채운다. 카탈로그가 작아 이 경로가 자주 탄다.

즐겨찾기 가산점은 0.10 이다. 유사도 최소 축(0.15)보다 작게 둬서 **취향 순위를 뒤집지
못하게** 했다 — 즐겨찾기는 동점 깨기 용도지 랭킹 신호가 아니다.

### 4. 근거 없음을 라벨로 노출한다

응답의 `source` 가 `TASTE`(내 취향 프로파일 근거) / `HISTORY`(내 조리 이력 근거) /
`DEFAULT`(근거 없음, 카탈로그 기본 노출) 중 하나다.

신규 사용자는 4점 이상 리뷰가 없어 유사도가 전부 0이 되고, NEW 슬롯이 동점 → recipeId 순
으로 결정된다. 빈 화면보다 낫다는 판단인데, **이 경우를 `TASTE` 와 섞으면 피드백 데이터가
오염된다.** `explanationSource` 가 GEMINI/FALLBACK 을 구분하는 것과 같은 이유다.

### 5. 문구는 LLM 이 쓰고, 실패하면 규칙 문구로 조용히 대체된다

`reason` 만 Gemini 가 쓴다. **무엇을 몇 번 칸에 넣을지는 규칙 엔진이 이미 정한 뒤**라
LLM 이 죽어도 추천 자체는 온전하다. 재료 추천(F-11)이 세운 계약을 그대로 승계했다.

```
규칙 엔진이 추천 3건 확정
        │
        ▼ 프롬프트 1개에 3건 배치 (요청당 1콜)
     Gemini ──→ { "reasons": ["...", "...", "..."] }
        │
        ▼ 기계 검증: 개수 일치 / 180자 이하 / 줄바꿈 없음 / 빈 문자열 없음
   통과 → explanationSource="GEMINI" + model + promptVersion
   실패 → explanationSource="FALLBACK" + 규칙 문구
```

**부분 채택은 하지 않는다.** 개수가 어긋난 응답은 어느 문구가 어느 추천 것인지 알 수 없어서,
엉뚱한 레시피에 남의 설명을 다는 것보다 전부 버리는 쪽이 안전하다.

프롬프트가 지키게 하는 것 두 가지:

- **순서·선정을 못 건드리게 한다.** 모델이 레시피를 바꿔치기하면 응답의 `recipeId` 와 문구가
  어긋난다. "순서를 바꾸거나 목록에 없는 레시피를 언급하지 마세요."
- **`DEFAULT` 를 개인화된 척 쓰지 못하게 한다.** 근거가 없는데 "취향에 맞을 거예요" 라고
  쓰면 라벨을 분리해 둔 의미가 사라진다. 프롬프트에 명시적으로 금지한다.

경과 기간은 `"3주"` / `"2개월"` 처럼 **문자열로 굳혀서** 넘긴다. 날짜를 그대로 주면 모델이
"지난 7월에" 같은 말을 지어낸다. 평점도 마찬가지로 서버 값만 쓰게 한다.

`PROMPT_VERSION = "home-recipe-reason-v1"`. 프롬프트를 고치면 v2 로 올린다.

### 6. 트랜잭션 밖에서 부른다 (3층 분리)

```
HomeRecommendationService          조합만. @Transactional 없음 ← 의도적
  ├─ HomeRecommendationLoader      @Transactional(readOnly) — DB 조회 전부
  │    └─ HomeRecommendationRuleEngine   순수 함수 — 슬롯·점수
  └─ HomeExplanationService        트랜잭션 밖 — Gemini HTTP
```

로더가 끝나면 커넥션이 반납되고 **그 뒤에** LLM 을 부른다. 응답을 최대 4초(readTimeout)
기다리는 동안 커넥션을 붙잡으면 동시 요청 몇 건으로 풀이 마른다. 재료 추천의
`RecommendationDraftLoader` 와 같은 이유, 같은 모양이다.

### 7. Gemini 배선을 복제하지 않고 공유로 뺐다

`GeminiRecommendationExplanationClient` 188줄 중 ~110줄이 벤더 배선(RestClient, JSON
스키마, thinking 끄기, 코드펜스 제거, 형식 검증)이고 프롬프트는 30줄뿐이었다. 호출자가
둘이 됐으므로 배선을 `com.cookpilot.backend.ai.gemini` 로 옮겼다 —
`@ConfigurationProperties("cookpilot.ai.gemini")` 라 프리픽스가 이미 그 위치를 가리키고 있었다.

```
ai/gemini/GeminiReasonsClient      "프롬프트 → 검증된 문구 N개". 절대 예외를 던지지 않는다
ai/gemini/GeminiApi                벤더 와이어 DTO. 이 패키지 밖으로 안 나간다
ai/gemini/GeminiProperties         설정 (cookpilot.ai.gemini.*)
```

두 기능은 프롬프트 문자열과 문구 목록만 주고받는다. 벤더 타입을 보지 않는다.

## API 변경

`GET /api/v1/home/recommendations` → `RecommendedRecipeResponse[]` (최대 3건)

```json
[
  {
    "recipeId": "...", "title": "닭갈비", "description": "...", "imageUrl": null,
    "slot": "NEW", "source": "TASTE",
    "reason": "제육볶음이 입에 맞으셨다면 닭갈비도 좋아요",
    "explanationSource": "GEMINI",
    "model": "gemini-3.5-flash",
    "promptVersion": "home-recipe-reason-v1",
    "favorite": false, "lastCookedAt": null, "bestRating": null
  },
  {
    "recipeId": "...", "title": "된장찌개", "...": "...",
    "slot": "AGAIN", "source": "HISTORY",
    "reason": "3주 전에 5점을 주셨어요",
    "explanationSource": "FALLBACK",
    "model": null,
    "promptVersion": "home-recipe-reason-v1",
    "favorite": true, "lastCookedAt": "2026-07-21T10:00:00Z", "bestRating": 5
  }
]
```

출처가 **두 축**이다. `source` 는 *왜 이 레시피가 뽑혔나*(추천 근거), `explanationSource` 는
*이 문장을 누가 썼나*(문구 출처)다. 둘은 독립이라 근거 없는 추천에 LLM 문구가 붙을 수도,
취향 추천에 규칙 문구가 붙을 수도 있다.

설정은 재료 추천과 같은 키를 공유한다 — `GEMINI_ENABLED`, `GEMINI_API_KEY`.
끄면 전 추천이 `FALLBACK` 으로 나가고 그 외에는 아무것도 달라지지 않는다.

**스키마 변경 없음.** 마이그레이션을 추가하지 않았다 — 필요한 테이블(`recipes`,
`recipe_flavor_profiles`, `post_cook_reviews`, `recipe_favorites`)이 전부 이미 있다.

## 파일

```
home/HomeRecommendationService.java      조회 + 문구 조립 (@Transactional(readOnly))
home/HomeRecommendationRuleEngine.java   슬롯 배정·점수 (순수 함수, 임계값 전부 여기)
home/RecommendedRecipeResponse.java      응답 DTO
home/HomeRecipeController.java           엔드포인트 추가
recommendation/RecommendationRuleEngine.java   가시성 3곳 public (로직 무변경)
```

## 검증

`HomeRecommendationRuleEngineTest` — DB·Spring 없이 순수 함수만. 슬롯 배정(정상/한쪽
고갈/후보 부족), 다시-만들기 자격(14일 이내 제외, 저평점 제외, 즐겨찾기 예외), 순위(가산점이
유사도를 못 뒤집음, 취향 없으면 id 순).

## 알려진 약점·후속

- **flavor profile 이 시드 8건 수동 입력**(`source='MANUAL'`)이다. 카탈로그가 커지는 순간
  프로파일 없는 레시피는 유사도 0이 되어 DEFAULT 로 떨어진다. `recommendation-system.md`
  의 D3(LLM 배치 분류)를 풀어야 이 기능이 실제로 산다. **이게 가장 큰 제약이다.**
- 추천을 저장하지 않는다. 재료 추천과 같은 구멍(D4) — 무엇을 추천했는지 서버가 모르므로
  클릭률·수락률 평가가 불가능하다. 피드백 엔드포인트도 아직 없다.
- 유사도 가중치(0.45/0.25/0.15/0.15)는 수동 8건 기준으로 잡힌 값이라 카탈로그가 넓어지면
  재조정이 필요하다.
- 14일·0.10·2:1 슬롯 비율은 전부 근거 없는 초기값이다. 실사용 로그가 쌓이기 전까지는
  조정할 기준이 없다.
- `AGAIN` 은 최고 평점(`bestRating`)으로 판정하는데 마지막 조리가 실패였어도 다시 올라온다.
  최신 평점으로 바꿀지는 데이터 보고 정한다.
