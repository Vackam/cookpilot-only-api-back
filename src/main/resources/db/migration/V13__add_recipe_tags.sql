-- 레시피 분류 태그. 문화권·음식형태·조리법·용도를 한 구조로 담는다.
--
-- 화면에는 '한식 · 반찬 · 안주용' 이 전부 같은 모양의 칩 한 줄로 나가고, 팀이 이 테이블을
-- 열어도 한 목록으로 보인다. 축(axis_code)은 앱에 내려보내지 않고 두 가지에만 쓴다 —
-- 배타성 강제(한식이면서 일식일 수 없음)와 필터 의미(축 안은 OR, 축 사이는 AND).
--
-- 자유 문자열 배열(JSONB)로 두지 않은 이유. 웹(web/lib/recipe-types.ts)이 이미 tags 를
-- string[] 로 갖고 있는데, 시드 8건에서 벌써 갈라졌다 — '볶음밥'/'볶음요리' 가 따로 있고
-- '한그릇'/'초간단'/'15분요리' 가 같은 뜻으로 셋이다. 유튜브 추출(web/lib/gemini.ts)이
-- 자유 문자열 태그를 계속 만들어내므로, 사전에서 고르게 하지 않으면 오염이 누적된다.
-- 같은 문제를 recipe_ingredients.ingredient_group 에서 이미 한 번 겪었다.

-- 태그 사전. 이 테이블의 행 하나가 화면의 칩 하나다.
--
-- code 를 PK 로 쓴다. code 는 API 파라미터(?tags=CUISINE_KOREAN)로 그대로 나가는 값이라
-- 어차피 안정적이어야 하고, 대리키를 두면 recipe_tags 를 눈으로 읽을 수 없게 된다.
-- 표시명(label_ko)은 code 와 분리해 자유롭게 바꾼다 — 노출 뒤에는 URL·SEO 때문에
-- code 를 되돌리기 어렵다.
CREATE TABLE tags (
  code       TEXT PRIMARY KEY,
  axis_code  TEXT NOT NULL CHECK (axis_code IN (
               'CUISINE',     -- 문화권 (배타)
               'DISH',        -- 음식형태 (배타)
               'METHOD',      -- 조리법 (배타)
               'OCCASION'     -- 용도 (다중)
             )),
  label_ko   TEXT NOT NULL,
  sort_order INT  NOT NULL DEFAULT 0,
  is_active  BOOLEAN NOT NULL DEFAULT TRUE,

  -- 비어 있으면 recipe_tags 에 행으로 붙는 태그(사람·임포트·LLM 이 부여).
  -- 채워져 있으면 조회 시점에 계산하는 파생 태그이고 recipe_tags 에 행을 만들지 않는다.
  --
  -- '두부'와 '쉬움'이 여기 들어갈 자리다. 두부는 recipe_ingredients 에 이미 행이 있어서
  -- 연결을 또 만들면 재료를 고쳤을 때 태그가 어긋나고, 난이도는 수치에서 유도해야 정렬이
  -- 된다. 지금은 파생 태그를 하나도 넣지 않는다 — 난이도 규칙(재료 수·단계 수 경계)과
  -- 재료 칩 후보는 운영 데이터에서 분포를 재보고 정해야 하며, 근거 없이 심으면
  -- 되돌리기 어렵다. 컬럼을 미리 두는 것은 '두부를 저장형 태그로 넣지 말라'는 경계를
  -- 스키마에 드러내기 위해서다.
  match_rule JSONB,

  -- 복합 FK 대상. recipe_tags 가 axis_code 를 비정규화해 갖되 사전과 어긋나지 못하게 한다.
  UNIQUE (code, axis_code)
);

-- 레시피에 붙은 태그.
--
-- 부여 출처를 행마다 남긴다. 이게 없으면 분류기 v2 배치가 사람 검수를 조용히 덮어쓴다
-- (재료 행 삭제가 개인화 diff 를 CASCADE 로 지우는 것과 같은 종류의 사고다).
CREATE TABLE recipe_tags (
  recipe_id      UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  tag_code       TEXT NOT NULL,
  axis_code      TEXT NOT NULL,

  assigned_by    TEXT NOT NULL CHECK (assigned_by IN ('MANUAL', 'IMPORT', 'RULE', 'LLM')),
  confidence     NUMERIC(3, 2) CHECK (confidence >= 0 AND confidence <= 1),
  model          TEXT,
  prompt_version TEXT,
  assigned_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  PRIMARY KEY (recipe_id, tag_code),
  FOREIGN KEY (tag_code, axis_code) REFERENCES tags(code, axis_code),

  -- LLM 이 붙인 태그는 어느 모델·어느 프롬프트였는지 없으면 재현도 롤백도 못 한다.
  CONSTRAINT recipe_tags_llm_provenance_check CHECK (
    assigned_by <> 'LLM' OR (model IS NOT NULL AND prompt_version IS NOT NULL)
  )
);

-- 배타축은 레시피당 하나. 여기 나열한 축이 tags.axis_code CHECK 의 배타축과 같아야 한다
-- (부분 인덱스가 다른 테이블 값을 참조할 수 없어 축 목록이 두 곳에 있다. 축을 늘리면 둘 다 고친다).
CREATE UNIQUE INDEX uq_recipe_tags_exclusive_axis
  ON recipe_tags(recipe_id, axis_code)
  WHERE axis_code IN ('CUISINE', 'DISH', 'METHOD');

-- 태그로 레시피를 찾는 방향(칩 필터). PK 는 반대 방향이라 따로 필요하다.
CREATE INDEX idx_recipe_tags_by_tag ON recipe_tags(tag_code, recipe_id);


-- ── 사전 시드 ─────────────────────────────────────────────────────────────────
--
-- 사전은 스키마와 같이 모든 환경에 있어야 하므로 마이그레이션에 둔다.
-- 반면 "어떤 레시피에 어떤 태그가 붙는가"는 운영 데이터라 여기 없다.
--
-- 음식형태·조리법은 원문(식품안전나라 COOKRCP01)의 RCP_PAT2 / RCP_WAY2 를 그대로 옮긴 값이다.
-- 원문에 결손이 0 이라 추론 없이 1,150 건을 채울 수 있지만, 그 백필은 원문과 DB 를
-- 제목으로 조인해야 해서(RCP_SEQ 를 저장하지 않았다) 마이그레이션으로 넣지 않는다.
--
-- 원문의 '기타'(요리종류 37건, 조리방법 310건)는 태그로 만들지 않는다. 만개의레시피
-- 상황축이 커버리지 95% 인데 그 중 57% 가 '일상' 한 값에 몰려 필터로서 죽어 있다 —
-- 고르기 애매하면 도망갈 칸을 주는 순간 축이 무의미해진다. 애매하면 미부여로 둔다.
-- 미부여는 나중에 채울 수 있지만 '기타'로 채워진 행은 되돌릴 근거가 없다.

INSERT INTO tags (code, axis_code, label_ko, sort_order) VALUES
  -- 문화권. 지금 카탈로그는 식약처 한식 데이터라 사실상 전량 한식이고, 이 축은 유튜브
  -- 레시피가 들어오기 시작해야 값을 한다. 그래도 지금 만들어 둔다 — 축이 없으면
  -- '양식'이 음식형태 축에 끼어들어 배타성을 깨뜨린다(만개의레시피가 그렇게 됐다).
  ('CUISINE_KOREAN',   'CUISINE', '한식',   10),
  ('CUISINE_JAPANESE', 'CUISINE', '일식',   20),
  ('CUISINE_CHINESE',  'CUISINE', '중식',   30),
  ('CUISINE_WESTERN',  'CUISINE', '양식',   40),
  ('CUISINE_ASIAN',    'CUISINE', '아시안', 50),
  ('CUISINE_FUSION',   'CUISINE', '퓨전',   60),

  -- 음식형태 = 원문 RCP_PAT2. 괄호는 원문 분포(1,156건 기준).
  ('DISH_SIDE',      'DISH', '반찬',    10),  -- 574
  ('DISH_MAIN',      'DISH', '일품',    20),  -- 181
  ('DISH_DESSERT',   'DISH', '후식',    30),  -- 142
  ('DISH_RICE',      'DISH', '밥',      40),  -- 119
  ('DISH_SOUP_STEW', 'DISH', '국·찌개', 50),  -- 103

  -- 조리법 = 원문 RCP_WAY2.
  ('METHOD_BOIL',      'METHOD', '끓이기', 10),  -- 326
  ('METHOD_GRILL',     'METHOD', '굽기',   20),  -- 259
  ('METHOD_STIR_FRY',  'METHOD', '볶기',   30),  -- 108
  ('METHOD_STEAM',     'METHOD', '찌기',   40),  -- 82
  ('METHOD_DEEP_FRY',  'METHOD', '튀기기', 50),  -- 71

  -- 용도. 원문에 없어 LLM 분류로만 얻는다. 여기 8개는 확정이 아니라 후보다 —
  -- 분류를 돌린 뒤 건수가 적은 태그는 is_active=false 로 내린다. 1,150 건에서는
  -- 태그 하나가 한 화면(약 20건)도 못 채우면 눌렀을 때 앱이 비어 보인다.
  ('OCCASION_DRINKING_SNACK', 'OCCASION', '안주',     10),
  ('OCCASION_LUNCHBOX',       'OCCASION', '도시락',   20),
  ('OCCASION_GUEST',          'OCCASION', '손님상',   30),
  ('OCCASION_KIDS',           'OCCASION', '아이반찬', 40),
  ('OCCASION_DIET',           'OCCASION', '다이어트', 50),
  ('OCCASION_QUICK',          'OCCASION', '초스피드', 60),
  ('OCCASION_LATE_NIGHT',     'OCCASION', '야식',     70),
  ('OCCASION_HOLIDAY',        'OCCASION', '명절',     80);
