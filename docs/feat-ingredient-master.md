# feat/ingredient-master

재료 마스터 사전(`ingredients`) 신설 + `recipe_ingredients` 정규화 백필. 다음 조리 추천을 고전 ML 구조(유저×재료 이력 집계)로 재설계하기로 한 로드맵의 1단계이자 모든 판정기의 공통 선행 조건.

## 무엇을 왜

`recipe_ingredients.name` 은 레시피별 자유 텍스트라 레시피를 가로지르는 "같은 재료" 축이 없었다 — 김치찌개의 돼지고기와 제육볶음의 돼지고기가 시스템 눈엔 남남. `(user_id, ingredient)` 단위 이력 집계(양 조절 경향, 기피 점수)는 이 축이 있어야 성립한다.

이전 시도였던 Gemini 직접 판정(`feat/ai-next-cook-recommendation`, 아카이브 커밋 `7c275ea`로 폐기)은 재료를 이름 문자열로 프롬프트에 넘겨 이 문제를 우회했지만, ML 전환으로 축 정규화가 필수가 됐다.

## 핵심 설계 결정

1. **정체성만 분리, 표기는 유지.** `ingredients` 는 `id + name(UNIQUE)` 두 컬럼뿐. 표기명("새송이버섯")·양·단위는 `recipe_ingredients` 에 그대로 남고, `ingredient_id` FK 가 정체성만 가리킨다. 개인 버전 diff 계약(행 FK 참조)은 무변경.
2. **뭉침 정책: 표기·부위 차이만 묶는다.** 대파=파, 진간장→간장, 돼지고기 앞다리살→돼지고기, 닭다리살→닭고기 (4건). 종이 다르면 별도 행 — 표고를 기피해도 팽이는 좋아할 수 있으므로 신호 전이가 보장 안 되는 것끼리는 안 묶는다. 잘못 묶어도 `name` 원문이 남아 있어 재매핑은 UPDATE 한 번(추천 행에 피처 스냅샷이 쌓이기 전까지 무비용).
3. **백필은 데이터 주도.** 마스터 행을 하드코딩하지 않고 `SELECT DISTINCT COALESCE(alias.canonical, name)` 으로 생성 — 시드 어디에 재료가 추가돼 있어도 누락 없음. 마이그레이션 끝에서 미매핑 0건을 DO 블록으로 자체 검증한다.
4. **`ingredient_id` 는 일단 NULL 허용.** 애플리케이션이 아직 이 컬럼을 매핑하지 않고(원 레포 V15 `ingredient_group` 과 같은 패턴), db 프로파일 테스트가 JPA 로 재료 픽스처를 넣기 때문. 집계 구현이 컬럼을 매핑하는 시점에 NOT NULL 조이기를 검토.
5. **JPA 엔티티 없음.** 이 브랜치는 스키마+백필만. `ddl-auto: validate` 는 매핑 안 된 테이블·컬럼을 문제 삼지 않는다. 엔티티/집계는 로드맵 2단계(유저×재료 집계)에서.

## 스키마 변경

- **V12__ingredient_master.sql**: `ingredients(id, name UNIQUE)` 신설, `recipe_ingredients.ingredient_id` FK 추가, 별칭 매핑 기반 백필(시드 24종 → 마스터 23행), `idx_recipe_ingredients_ingredient` 인덱스. API 표면 변경 없음.

## 검증

- `CoreSchemaPersistenceTest`(Flyway 실행 경로) green.
- 일회용 postgres:16 에 V1→V12 psql 순차 적용으로 확인: 마스터 23행, 파=대파 병합(5행), 미매핑 0행.

## 알려진 약점 · 후속

- 별칭 사전이 마이그레이션 안에 박제된 4건뿐 — 새 레시피 시드를 넣는 마이그레이션은 마스터 행과 `ingredient_id` 를 함께 채워야 한다(안 채우면 NULL 로 남고, 집계에서 빠진다).
- NULL 허용이라 스키마 수준의 매핑 강제가 없다. NOT NULL 전환은 테스트 픽스처의 마스터 행 생성이 전제.
- 마이그레이션 번호 주의: 원 레포(Cook-Pilot/backend) main 은 V12~V14 를 이미 다른 용도로 쓴다. 역이식 시 리넘버 필요.
- 다음 단계(로드맵 2): 유저×재료 이력 집계. 아카이브 브랜치의 `RecommendationContextLoader` 수집 쿼리(긍정 리뷰→버전→MODIFY 조정)가 원재료.
