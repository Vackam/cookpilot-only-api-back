-- 재료 마스터 사전. recipe_ingredients.name 은 레시피별 자유 텍스트라 레시피를
-- 가로지르는 "같은 재료" 축이 없었다 (김치찌개의 돼지고기와 제육볶음의 돼지고기가
-- 남남). 다음 조리 추천의 유저×재료 이력 집계는 이 축이 있어야 성립한다.
--
-- 뭉침 정책: 같은 재료의 표기·부위 차이만 하나로 묶는다 (대파=파, 닭다리살→닭고기).
-- 종이 다르면 별도 행이다 — 표고를 기피해도 팽이는 좋아할 수 있으므로, 기피/조정
-- 신호가 전이된다는 보장이 없는 것끼리는 묶지 않는다. 잘못 묶어도 name 원문이
-- recipe_ingredients 에 그대로 남아 있어 재매핑은 UPDATE 한 번이다(추천 행에 피처
-- 스냅샷이 쌓이기 전까지).
CREATE TABLE ingredients (
  id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL UNIQUE
);

ALTER TABLE recipe_ingredients
  ADD COLUMN ingredient_id UUID REFERENCES ingredients(id);

-- 표기 변형 → 정식명 매핑. 여기 없는 name 은 그 자체가 정식명이다.
CREATE TEMPORARY TABLE ingredient_alias (alias TEXT PRIMARY KEY, canonical TEXT NOT NULL);
INSERT INTO ingredient_alias (alias, canonical) VALUES
  ('대파', '파'),
  ('진간장', '간장'),
  ('돼지고기 앞다리살', '돼지고기'),
  ('닭다리살', '닭고기');

INSERT INTO ingredients (name)
SELECT DISTINCT COALESCE(a.canonical, ri.name)
FROM recipe_ingredients ri
LEFT JOIN ingredient_alias a ON a.alias = ri.name;

UPDATE recipe_ingredients ri
SET ingredient_id = i.id
FROM ingredients i
LEFT JOIN ingredient_alias a ON a.canonical = i.name
WHERE ri.name = i.name OR ri.name = a.alias;

-- 시드가 전부 매핑됐는지 마이그레이션 자체가 검증한다. 새 레시피 시드를 넣는
-- 마이그레이션은 마스터 행과 ingredient_id 도 함께 채워야 한다.
--
-- NOT NULL 로 조이지 않는 이유: 애플리케이션은 아직 이 컬럼을 매핑하지 않고
-- (ddl-auto: validate 는 매핑 안 된 컬럼을 문제 삼지 않는다), db 프로파일 테스트가
-- JPA 로 재료 픽스처를 넣는다. 집계가 이 컬럼을 매핑하는 시점에 조이는 것을 검토한다.
DO $$
DECLARE unmapped INT;
BEGIN
  SELECT COUNT(*) INTO unmapped FROM recipe_ingredients WHERE ingredient_id IS NULL;
  IF unmapped > 0 THEN
    RAISE EXCEPTION '마스터 미매핑 재료 %건', unmapped;
  END IF;
END $$;

DROP TABLE ingredient_alias;

CREATE INDEX idx_recipe_ingredients_ingredient ON recipe_ingredients(ingredient_id);
