# feat-recipe-pagination — 레시피 목록 페이지네이션

## 무엇을, 왜

`GET /api/v1/recipes` 가 활성 레시피를 **전부** 한 번에 반환했다. 카탈로그가 늘면서 메인
화면이 뜨지 않는 문제가 생겼다(프론트 홈은 받은 목록 전체를 eager 렌더한다). 목록을 서버에서
잘라 기본 10건만 내리도록 바꿔 초기 로드를 고정 비용으로 만든다.

범위는 `/api/v1/recipes` 하나다. `GET /api/v1/favorites`, `GET /api/v1/home/recent-recipes`,
`GET /api/v1/cooking-history` 는 각자 이미 상한이 있거나(최근 10건) 조회 조건이 좁아 이번에
건드리지 않았다.

## API 변경

```http
GET /api/v1/recipes?page=0&size=10
```

| 파라미터 | 기본값 | 제약 |
|---|---|---|
| `page` | `0` | `>= 0` |
| `size` | `10` | `1 ~ 100` |

응답은 배열에서 페이지 envelope 으로 바뀐다. **프론트 계약 파괴 변경이다.**

```json
{
  "items": [ { "id": "...", "title": "...", "hasPersonalVersion": false, "favorite": false } ],
  "page": 0,
  "size": 10,
  "totalElements": 42,
  "hasNext": true
}
```

스키마 변경 없음(순수 읽기 변경) — Flyway 마이그레이션 없다.

## 핵심 설계 결정

### 1. `Page<T>` 를 그대로 반환하지 않고 `PagedResponse<T>` record 로 감싼다

Spring 의 `PageImpl` 은 직렬화 형태가 안정적이지 않고(버전마다 필드가 흔들린다), 이 코드베이스는
공용 응답 wrapper 없이 평범한 record DTO 를 반환하는 관례다. `common/PagedResponse.java` 는
그 관례에 맞춘 최소 envelope 이며 `PagedResponse.of(page, items)` 로 만든다.

`totalPages` 는 넣지 않았다. 클라이언트가 필요로 하는 건 "더 있는가"뿐이라 `hasNext` 로 충분하다.

### 2. 정렬 계약은 메서드명으로 유지한다

`Page<RecipeEntity> findByStatusOrderByTitleAscIdAsc(String status, Pageable pageable)` —
`Pageable` 에 `Sort` 를 넣지 않고 메서드명 파생 정렬을 그대로 쓴다. `title ASC, id ASC` 는
#19 에서 정한 계약이고 테스트가 고정하고 있는데, 호출부에서 Sort 를 만들면 정렬 정의가 두 곳으로
쪼개진다.

List 버전 `findByStatusOrderByTitleAscIdAsc(String)` 은 **남겨뒀다**.
`home/HomeRecommendationLoader` 가 추천 점수를 매기려고 카탈로그 전체를 훑는 데 쓴다 —
추천은 페이징 대상이 아니다.

### 3. 페이지 크기 상한 100, 검증 실패는 `IllegalArgumentException`

상한이 없으면 `size=100000` 한 방으로 페이지네이션의 의미가 사라진다.
`GlobalExceptionHandler` 가 `IllegalArgumentException` → 400 `ProblemDetail` 로 이미 매핑하므로
새 핸들러도, `@Validated`(핸들러가 없어 500 으로 샌다) 도 쓰지 않았다.

### 4. N+1 회피 배치 조회는 그대로

`RecipeController` 는 페이지의 id 집합만 `PersonalRecipeService.findLatestByRecipes` /
`FavoriteService.findFavoriteRecipeIds` 에 넘긴다. 두 메서드 모두 `Collection<UUID>` 를 받으므로
시그니처 변경이 없고, 조회 대상이 오히려 10건으로 줄었다.

## 주요 변경 파일

- `common/PagedResponse.java` (신규)
- `recipe/RecipeRepository.java` — `Page` 오버로드 추가
- `recipe/RecipeService.java` — `findAll()` → `findPage(int, int)`, `MAX_PAGE_SIZE = 100`
- `recipe/RecipeController.java` — `@RequestParam page/size`, `PagedResponse` 반환
- `docs/openapi.json` — 재생성

## 검증

`./gradlew build` 통과. `RecipeApiTest` 에 추가·수정한 것:

- 기본 응답이 `page=0`, `size=10`, `items <= 10`
- `size=1` 로 `page=0` 과 `page=1` 의 첫 id 가 다르고 `hasNext=true`
- `size=0` / `page=-1` / `size=101` → 400
- 기존 `$[0].*` JSONPath → `$.items[0].*`
- `제목이_같은_레시피는_ID_순서로_반환한다` 는 `findPage(0, MAX_PAGE_SIZE)` 로 정렬 계약 유지 확인

`FavoriteApiTest` 의 목록 단언도 `$.items[...]` 로 바꾸고, 특정 레시피가 기본 10건 안에 든다는
보장이 없어 `size=100` 으로 조회하게 했다.

`PostgresApiTestBase` 는 테스트 클래스 간 데이터가 남으므로 `totalElements` 정확값은 단언하지
않고 `>=` 만 본다.

## 알려진 약점 · 후속

- **오프셋 페이징이다.** 카탈로그가 수천 건이 되면 뒤쪽 페이지의 `OFFSET` 비용과, 페이지를 넘기는
  중 레시피가 추가되면 항목이 밀리는 문제가 생긴다. 지금 규모(8건대)에선 과설계라 커서 페이징은
  하지 않았다.
- `recipes(status, title, id)` 인덱스는 아직 없다. 전체 정렬 스캔이 부담이 될 때 마이그레이션으로
  추가한다.
- 프론트 검색은 여전히 클라이언트 필터링이라 상위 100건만 대상으로 한다. 카탈로그가 100을 넘으면
  서버 검색 엔드포인트가 필요하다.
- 이번 재생성으로 `docs/openapi.json` 에 `/api/v1/home/recommendations`(`RecommendedRecipeResponse`)
  가 함께 들어왔다. 해당 엔드포인트 커밋에서 스펙을 재생성하지 않아 생긴 **기존 드리프트**이며,
  이번 변경과는 무관하다.
