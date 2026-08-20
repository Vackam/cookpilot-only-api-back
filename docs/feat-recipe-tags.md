# feat/recipe-tags — 레시피 분류 태그 + 검색 포팅, 1,150건 태그 백필

## 무엇을 왜

원본 레포(Cook-Pilot/backend)에서 두 가지를 가져왔다.

1. **레시피 분류 태그 스키마와 사전** (원본 #68) — 문화권·음식형태·조리법·용도 4축.
   유저 데이터가 없는 콜드스타트 구간에서 태그가 비개인화 발견축(칩 필터, 추천 fallback)
   역할을 한다. 재료 기반 개인화(`user_ingredient_stats`)는 조리 이력이 쌓여야 성립하는
   별개 단계라 이번 범위가 아니다.
2. **레시피 검색** (원본 #60) — `GET /api/v1/recipes/search?title=&ingredient=`.

그리고 로컬 개발 DB(1,150건 카탈로그)에 **태그를 실제로 백필**했다. 사전만 있고
부여가 0건이면 축이 살아있는지 검증할 수 없기 때문.

## 핵심 설계 결정

- **마이그레이션 번호**: 원본 V14 → 여기서는 **V13**. 두 레포의 번호는 V12부터 갈라져
  있다(원본 V12=social identity, only-api V12=ingredient master). 내용은 주석 한 줄
  (V14→V13) 외에 원본과 동일하게 유지.
- **검색은 only-api 관례에 맞춤**: 원본은 1-based page + 전용 응답 record지만, 여기는
  기존 목록과 같은 **0-based page + `PagedResponse`** + `IllegalArgumentException`(400).
  범위 밖 page 는 원본처럼 마지막 페이지로 보정하지 않고 빈 결과를 준다(목록과 동일 규칙).
  재료 검색 JPQL 은 원본 그대로 — only-api V12 는 `recipe_ingredients.name` 을 유지한다
  (원본 V16 은 드롭했음).
- **백필 부여 출처를 축별로 분리**:
  - DISH/METHOD — 식약처 COOKRCP01 원문 `RCP_PAT2`/`RCP_WAY2` 를 제목 조인으로 그대로
    옮김(**IMPORT**, 1,930행). 원문 '기타'는 설계대로 미부여. 제목이 원문에서 중복되고
    분류가 갈리는 1건('양배추롤')은 제외.
  - CUISINE/OCCASION + 원문 미수록 13건(V2 시드 등) — **LLM**(claude-fable-5, tag-v1,
    confidence 포함) 판정, 619행. 요리형 키워드 기반: 외래 요리형+한식 마커 → 퓨전,
    외래 요리형 2문화권 결합도 퓨전, 후식·음료·빵류는 문화권 미부여.
    '애매하면 미부여' 원칙 유지 → 태그 0개 레시피(빵·베이커리류 등)가 정상 존재.
- **에이전트 5개 교차 검증 후 재적용**: ①DB 제약·무결성 ②IMPORT 조인 재계산(양방향
  diff 0) ③CUISINE 의미(303행) ④OCCASION+수기 13건(88행) ⑤포팅 코드리뷰+라이브 스모크.
  ③④가 잡은 오류(탕수육의 '수육' 부분문자열 오발동, 고추잡채, 퓨전 문턱 비일관,
  해독주스→다이어트, 이유식→아이반찬)를 생성기에 반영해 전량 재생성·재적용했다.
  최종: 2,549행, 1,150건 중 1,126건 커버.
- **부여 데이터는 마이그레이션에 넣지 않는다** — 사전(V13)은 스키마와 함께, 부여는
  운영 데이터라 `infra/maintenance/`(원본 #69 관례)로. 생성기는
  `infra/maintenance/tools/gen_recipe_tags_sql.py`, 산출물은
  `infra/maintenance/2026-08-20-recipe-tags-backfill.sql`.

## 스키마/API 변경

- V13: `tags`(사전, 24행 시드), `recipe_tags`(부여 + 출처/신뢰도, 배타축 유니크 인덱스,
  LLM provenance CHECK). 태그 조회 API 는 아직 없다 — 스키마 단계.
- `GET /api/v1/recipes/search?title=&ingredient=&page=0&size=10` → `PagedResponse`.
  둘 다 부분일치(대소문자 무시), LIKE 메타문자(`% _ \`)는 리터럴로 이스케이프.

## 로컬 DB(5432) 관련

로컬 5432 의 cookpilot DB 는 운영 덤프 복원본이라 flyway 이력이 **원본 레포 계보**
(V12=add social identity)였다. 스키마 자체는 only-api V12 결과와 호환이어서
`flyway_schema_history` 의 12행 checksum/description 을 only-api 계보로 맞춘 뒤
V13 을 적용했다(유저 승인 하에). `recipe_ingredients.ingredient_group` 은 덤프에서
딸려온 잉여 컬럼 — JPA validate 는 매핑 안 된 컬럼을 문제 삼지 않는다.

## 프론트 요청 반영 — description 분리와 태그 응답/필터 (V14)

프론트 스펙(태그를 description 문자열에서 컬럼으로 분리) 요청을 수용하되, 스키마는
스펙 초안과 다르게 갔다. **스펙이 제안한 `recipes.cooking_method`/`dish_type` 컬럼은
만들지 않는다** — V13 `recipe_tags`(METHOD/DISH 축)가 이미 같은 사실을 더 강한 제약
(사전 FK·배타축·부여 출처)으로 들고 있어서, 컬럼을 또 만들면 같은 사실이 두 곳에
갈라진다(스펙 스스로 기각한 2안과 같은 문제).

- **V14 `extract_recipe_meta`**: 영양 6컬럼(kcal·탄단지·나트륨·1인분 중량) 보존 추출,
  `recipe_hashtags`(자유 문자열 N:M, 사전 미등록) 신설, description 은 설명 본문만 남김
  (본문 없던 53건은 `''`). 영양 추출은 반드시 영양 라인만 잘라서 한다 — 본문에
  "두유 200ml에 125kcal" 같은 문구가 있는 레시피가 실제로 있었다(버섯 두유 소스 볶음,
  원문 대조로 285 확인·수정).
- **응답**: 목록/검색/상세에 `cookingMethod`·`dishType`(라벨, 미부여 null)·`hashtags`
  (없으면 `[]`) 추가. `RecipeTagLookup` 이 recipeIds 일괄 조회로 N+1 방지.
- **검색 필터**: `?cookingMethod=&dishType=&hashtag=` — 전부 AND, 빈 값은 조건 없음,
  사전에 없는 값은 400 이 아니라 0건. 분류 값은 **tags.label_ko 정확 일치**.
- **스펙과 다른 점 (프론트에 전달 필요)**:
  1. `국&찌개` 가 아니라 **`국·찌개`**(가운뎃점, V13 사전 라벨)다.
  2. **`기타` 는 필터 값이 아니다** — V13 사전이 '기타'를 의도적으로 제외했고(도망갈
     칸을 주면 축이 죽는다), 해당 레시피는 미부여(null)다. 조리방법 5종·요리종류 5종.
  3. 해시태그 구분자는 쉼표뿐이다 — 태그 자체에 공백이 있어("삼삼한 밥상") 공백 분리 금지.
  4. 수기 판정 13건 덕에 일부 집계가 스펙 실측과 ±1 다르다(예: 반찬 568→569).

## 알려진 약점·후속

- CUISINE/OCCASION 은 제목 키워드 판정이라 한계가 있다(제목에 신호가 없으면 미부여).
  잘못 부여된 행은 `model='claude-fable-5' and prompt_version='tag-v1'` 로 일괄
  식별·롤백 가능.
- OCCASION 커버리지가 낮다(68행). 사전 주석대로, 분포를 본 뒤 건수 적은 태그는
  `is_active=false` 로 내리는 후속이 필요.
- 태그 조회/필터 API(`?tags=`)는 미구현 — 스키마·데이터가 먼저다.
- 원문 조인은 제목 기반이다(RCP_SEQ 를 저장하지 않아서). 제목이 바뀌면 재조인 불가.
