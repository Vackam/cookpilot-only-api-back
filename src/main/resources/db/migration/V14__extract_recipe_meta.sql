-- 레시피 메타를 description 문자열에서 구조화 저장으로 분리.
--
-- 카탈로그 적재기가 조리방법·요리종류·영양·해시태그를 recipes.description 에 줄바꿈으로
-- 이어 붙여 넣었다. 표시용 문자열 안에 갇힌 데이터는 필터가 안 되고, 앱이 서버 포맷을
-- 정규식으로 추측해야 해서 포맷이 바뀌면 조용히 깨진다.
--
-- 조리방법·요리종류는 컬럼을 새로 만들지 않는다 — V13 의 recipe_tags(METHOD/DISH 축)가
-- 이미 그 자리다(부여는 운영 데이터라 infra/maintenance 백필로 들어간다).
-- 여기서는 description 에만 있던 나머지 둘을 꺼낸다:
--   * 영양 — 지금 노출 계획은 없지만, description 을 정리하면서 지우면 원본이 사라지므로
--     컬럼으로 보존한다.
--   * 해시태그 — 154종 중 101종이 1회성 재료명이라 어휘 사전(tags)에 넣지 않고
--     자유 문자열 N:M 테이블로 둔다. 사전 오염 없이 정확 일치 필터만 지원한다.
-- 마지막으로 description 에는 설명 본문만 남긴다.
--
-- 시드 8건(V2)은 이 라인들이 없어 전부 no-op — 새 환경에서도 안전하다.

ALTER TABLE recipes ADD COLUMN serving_weight_g NUMERIC(7,1);
ALTER TABLE recipes ADD COLUMN kcal             NUMERIC(7,1);
ALTER TABLE recipes ADD COLUMN carbohydrate_g   NUMERIC(7,1);
ALTER TABLE recipes ADD COLUMN protein_g        NUMERIC(7,1);
ALTER TABLE recipes ADD COLUMN fat_g            NUMERIC(7,1);
ALTER TABLE recipes ADD COLUMN sodium_mg        NUMERIC(8,1);

CREATE TABLE recipe_hashtags (
  recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  tag       TEXT NOT NULL,
  PRIMARY KEY (recipe_id, tag)
);
CREATE INDEX idx_recipe_hashtags_tag ON recipe_hashtags(tag);

-- ── 백필 ────────────────────────────────────────────────────────────────────

-- 영양 라인: '1인분 [중량g ·] Nkcal · 탄수화물 Ng · 단백질 Ng · 지방 Ng · 나트륨 Nmg'
-- 중량(g)이 붙는 변형(281건)과 안 붙는 변형(862건)이 섞여 있어 중량만 따로 뽑는다.
-- 반드시 영양 라인만 잘라낸 문자열에서 추출한다 — 설명 본문에도 '두유 200ml에 125kcal'
-- 같은 문구가 있어, description 전체에서 뽑으면 본문 값을 집는다.
UPDATE recipes r SET
  serving_weight_g = (substring(n.line from '^1인분 ([0-9.]+)g '))::numeric,
  kcal             = (substring(n.line from '([0-9.]+)kcal'))::numeric,
  carbohydrate_g   = (substring(n.line from '탄수화물 ([0-9.]+)g'))::numeric,
  protein_g        = (substring(n.line from '단백질 ([0-9.]+)g'))::numeric,
  fat_g            = (substring(n.line from '지방 ([0-9.]+)g'))::numeric,
  sodium_mg        = (substring(n.line from '나트륨 ([0-9.]+)mg'))::numeric
FROM (
  SELECT id, substring(description from '(?:^|\n)(1인분 [^\n]*kcal[^\n]*)') AS line
  FROM recipes
) n
WHERE n.id = r.id AND n.line IS NOT NULL;

-- 해시태그 라인: '해시태그: a, b, ...'. 구분자는 쉼표뿐이다 — 태그 자체에 공백이
-- 들어간다('삼삼한 밥상'). 공백으로 쪼개면 안 된다.
INSERT INTO recipe_hashtags (recipe_id, tag)
SELECT DISTINCT r.id, trim(t)
FROM recipes r,
     unnest(string_to_array(substring(r.description from '해시태그:\s*([^\n]+)'), ',')) AS t
WHERE r.description ~ '(^|\n)해시태그:' AND trim(t) <> '';

-- description 정리: 태그·영양·해시태그 줄을 지우고 설명 본문만 남긴다.
-- 본문이 원래 없던 레시피는 빈 문자열('')이 된다 — NULL 과 섞이지 않게 통일.
UPDATE recipes SET description = coalesce(
  trim(both E'\n' from regexp_replace(
    description,
    '(^|\n)(조리방법:[^\n]*|해시태그:[^\n]*|1인분[^\n]*kcal[^\n]*)', '', 'g')),
  '')
WHERE description ~ '(^|\n)(조리방법:|해시태그:|1인분[^\n]*kcal)';

UPDATE recipes SET description = '' WHERE description IS NULL;
