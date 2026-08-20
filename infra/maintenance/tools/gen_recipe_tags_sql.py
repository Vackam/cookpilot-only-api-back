"""recipe_tags 백필 SQL 생성기 (V13 태그 사전 기준).

입력:
  1) db_recipes.tsv  — 대상 DB 의 `select id, title, status from recipes` 결과 (TSV)
  2) cookrcp_class.json — COOKRCP01 원문에서 뽑은 [{title, pat2, way2}] (get-open-api 로 수집)

부여 규칙 (마이그레이션 V13 의 '애매하면 미부여' 원칙을 따른다):
  * DISH/METHOD — 원문 RCP_PAT2/RCP_WAY2 를 제목 조인으로 그대로 옮긴다(assigned_by=IMPORT).
    원문 '기타' 는 태그로 만들지 않고 미부여. 제목이 원문에서 중복되고 분류가 서로 다르면 제외.
  * 원문에 없는 제목(V2 시드 등 13건) — 이 스크립트 하단의 수기 판정표(LLM)로 채운다.
  * CUISINE — 제목의 요리형 키워드로만 판정한다(LLM). 외래 요리형 + 한식 마커 동시면 퓨전,
    외래 요리형만이면 해당 문화권, 한식 마커만이면 한식, 그 외 미부여.
    후식·음료·빵류는 문화권 판정이 무의미해서 전부 미부여.
  * OCCASION — 제목에 근거가 뚜렷한 것만(LLM). 근거 약하면 미부여.

LLM 행은 model/prompt_version 을 남긴다(재현·롤백용, V13 CHECK 가 강제).
"""

import json
import sys

MODEL = "claude-fable-5"
PROMPT_VERSION = "tag-v1"

PAT2 = {
    "반찬": "DISH_SIDE",
    "일품": "DISH_MAIN",
    "후식": "DISH_DESSERT",
    "밥": "DISH_RICE",
    "국&찌개": "DISH_SOUP_STEW",
}
WAY2 = {
    "끓이기": "METHOD_BOIL",
    "굽기": "METHOD_GRILL",
    "볶기": "METHOD_STIR_FRY",
    "찌기": "METHOD_STEAM",
    "튀기기": "METHOD_DEEP_FRY",
}

# 외래 요리형. 매치 우선순위: 퓨전 명시 > 중식 > 일식 > 아시안 > 양식.
FUSION_EXPLICIT = ["퓨전", "한식풍", "K-"]
CHINESE = ["탕수", "깐풍", "유린기", "고추잡채", "딤섬", "짜장", "짬뽕", "마파",
           "양장피", "빠스", "꿔바로우", "멘보샤", "중국식", "라조"]
JAPANESE = ["초밥", "스시", "소바", "가라아게", "낫토", "다마고도후", "일본식",
            "샤브샤브", "규동", "우동", "미소"]
ASIAN = ["월남쌈", "쌀국수", "팟타야", "팟타이", "나시고랭", "니고랭", "그린커리",
         "탄두리", "인도식", "태국식", "사모사", "아시아식", "버터치킨",
         "라이스페이퍼"]
WESTERN = ["파스타", "스파게티", "뇨끼", "뇨키", "리조또", "리소토", "라자냐",
           "라비올리", "피자", "그라탕", "스테이크", "스튜", "오므라이스", "프리타타",
           "카프레제", "카프리제", "라따뚜이", "라타투이", "아란치니", "굴라쉬",
           "오소부코", "폭찹", "폭립", "함박", "미트볼", "파피요트", "파필로테",
           "커틀렛", "돈가스", "까스", "브리또", "샌드위치", "버거", "빠에야",
           "밀라노 스타일", "케이준", "서양식", "웰링턴", "가스파초"]

# 한식 마커. 요리형이 아니라 재료/양념 이름이 많아 '외래 요리형과 결합 = 퓨전' 판정에도 쓴다.
KOREAN = ["김치", "깍두기", "동치미", "겉절이", "장아찌", "나물", "무침", "찌개",
          "된장", "청국장", "고추장", "쌈장", "불고기", "비빔밥", "비빔국수", "국밥",
          "떡국", "떡볶이", "떡갈비", "잡채", "전골", "수제비", "칼국수", "미역국",
          "해장국", "육개장", "설렁탕", "곰탕", "삼계탕", "갈비탕", "쌈밥", "김밥",
          "인삼", "수삼", "곤드레", "시래기", "묵은지", "장떡", "화전", "송편",
          "약식", "약밥", "식혜", "수정과", "부각", "장조림", "탕평채", "효종갱",
          "보쌈", "족발", "수육", "편육", "초계탕", "물김치", "섞박지", "소박이",
          "짱아지", "옹심이", "인절미", "빙떡", "주악", "제육", "닭갈비", "닭볶음탕",
          "냉국", "무국", "배춧국", "막국수", "구절판", "산적",
          # 검증에서 드러난 퓨전 판정 누락 보강분
          "주먹밥", "누룽지", "황태", "김말이", "콩국수", "함초", "묵말랭이", "감태",
          "산채", "도토리묵", "들깨탕", "들깨국", "호박잎", "만두탕", "두부국",
          "밥버거", "깻잎", "라면"]

# 후식·음료·빵류는 문화권 판정이 무의미하다 — CUISINE 미부여 (사전 주석의 정책).
CUISINE_EXCLUDE = ["빙수", "케이크", "쿠키", "마들렌", "머핀", "스콘", "타르트", "빵",
                   "베이글", "도넛", "라떼", "에이드", "스무디", "쉐이크", "셰이크",
                   "주스", "젤리", "푸딩", "양갱", "화채", "정과", "약식", "호떡",
                   "씨리얼", "뮤즐리", "파르페", "다쿠아즈", "깜빠뉴", "무스",
                   "몽블랑", "티라미수", "판나코타", "파나코타", "샤벳", "소르베",
                   "아이스크림"]

OCCASION_RULES = [
    # (태그, confidence, [키워드])
    ("OCCASION_KIDS", 0.90, ["어린이", "영유아", "키즈"]),
    ("OCCASION_DIET", 0.90, ["다이어트"]),
    ("OCCASION_QUICK", 0.70, ["간편", "초스피드", "즉석"]),
    ("OCCASION_LUNCHBOX", 0.65, ["주먹밥", "김밥", "샌드위치", "유부초밥", "도시락"]),
    ("OCCASION_GUEST", 0.70, ["카나페", "까나페", "에피타이저", "테린"]),
    ("OCCASION_LATE_NIGHT", 0.60, ["라면", "떡볶이"]),
    ("OCCASION_DRINKING_SNACK", 0.70, ["골뱅이무침", "족발", "과매기"]),
    ("OCCASION_HOLIDAY", 0.65, ["화전", "송편", "원소병", "수정과"]),
]

# 원문에 제목이 없는 13건(V2 시드 + 제목 표기 변형)의 수기 판정. None = 미부여.
MANUAL_DISH_METHOD = {
    "LA 갈비구이":   ("DISH_SIDE", 0.80, "METHOD_GRILL", 0.95),
    "계란볶음밥":    ("DISH_RICE", 0.95, "METHOD_STIR_FRY", 0.95),
    "김치볶음밥":    ("DISH_RICE", 0.95, "METHOD_STIR_FRY", 0.95),
    "깻잎장아찌 롤": ("DISH_SIDE", 0.70, None, None),
    "닭갈비":        ("DISH_MAIN", 0.70, "METHOD_STIR_FRY", 0.80),
    "된장찌개":      ("DISH_SOUP_STEW", 0.95, "METHOD_BOIL", 0.95),
    "두부조림":      ("DISH_SIDE", 0.90, "METHOD_BOIL", 0.75),
    "둥지 튀김":     (None, None, "METHOD_DEEP_FRY", 0.90),
    "라면":          ("DISH_MAIN", 0.70, "METHOD_BOIL", 0.95),
    "봄 주먹밥":     ("DISH_RICE", 0.90, None, None),
    "양배추 롤":     (None, None, None, None),  # 원문에서 같은 제목의 분류가 갈려 미부여
    "오일파스타":    ("DISH_MAIN", 0.80, None, None),
    "제육볶음":      ("DISH_SIDE", 0.85, "METHOD_STIR_FRY", 0.95),
}


def classify_cuisine(title: str):
    """(tag_code, confidence) 또는 None."""
    # '해초밥'(해초+밥)이 '초밥'으로 오인되는 것을 막는다.
    probe = title.replace("해초밥", "")
    if any(m in probe for m in CUISINE_EXCLUDE):
        return None
    if any(m in probe for m in FUSION_EXPLICIT):
        return "CUISINE_FUSION", 0.85

    foreign_hits = []  # (code, 매치된 키워드들)
    for markers, code in ((CHINESE, "CUISINE_CHINESE"), (JAPANESE, "CUISINE_JAPANESE"),
                          (ASIAN, "CUISINE_ASIAN"), (WESTERN, "CUISINE_WESTERN")):
        matched = [m for m in markers if m in probe]
        if matched:
            foreign_hits.append((code, matched))

    # 한식 마커 검사는 매치된 외래 키워드를 지운 문자열로 한다 —
    # '탕수육'의 '수육', '고추잡채'의 '잡채' 같은 부분문자열 오발동 방지.
    # 단, 마커가 외래 키워드를 통째로 품는 경우('밥버거'⊃'버거')는 원문으로 검사한다.
    all_matched = [k for _code, matched in foreign_hits for k in matched]
    stripped = probe
    for keyword in all_matched:
        stripped = stripped.replace(keyword, "")
    korean_hit = any(
        (m in stripped) or (m in probe and any(k in m for k in all_matched))
        for m in KOREAN
        if not (m == "된장" and "미소" in stripped))

    if len(foreign_hits) >= 2:
        # 서로 다른 문화권의 요리형이 한 제목에 결합 (깐풍파스타, 멘보샤+뇨끼).
        return "CUISINE_FUSION", 0.70
    if foreign_hits:
        if korean_hit:
            return "CUISINE_FUSION", 0.70
        return foreign_hits[0][0], 0.80
    if korean_hit:
        return "CUISINE_KOREAN", 0.80
    return None


def classify_occasions(title: str):
    hits = {}
    for code, conf, markers in OCCASION_RULES:
        if code not in hits and any(m in title for m in markers):
            hits[code] = conf
    return sorted(hits.items())


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main(db_tsv: str, class_json: str, out_sql: str) -> None:
    src = json.load(open(class_json, encoding="utf-8"))
    by_title, conflict = {}, set()
    for row in src:
        key, value = row["title"], (row["pat2"], row["way2"])
        if key in by_title and by_title[key] != value:
            conflict.add(key)
        by_title[key] = value
    for key in conflict:
        del by_title[key]

    recipes = [line.rstrip("\n").split("\t")
               for line in open(db_tsv, encoding="utf-8") if line.strip()]

    lines = [
        "-- recipe_tags 백필. gen_recipe_tags_sql.py 가 생성 — 손으로 고치지 말 것.",
        "-- DISH/METHOD 는 COOKRCP01 원문(IMPORT), CUISINE/OCCASION 과 원문 미수록 13건은",
        f"-- LLM({MODEL}, {PROMPT_VERSION}) 판정. 근거가 약한 축은 미부여로 남긴다.",
        "BEGIN;",
    ]
    stats = {"IMPORT": 0, "LLM": 0}

    def add(recipe_id, tag, axis, by, conf=None):
        if by == "IMPORT":
            lines.append(
                f"INSERT INTO recipe_tags (recipe_id, tag_code, axis_code, assigned_by) "
                f"VALUES ('{recipe_id}', '{tag}', '{axis}', 'IMPORT');")
        else:
            lines.append(
                f"INSERT INTO recipe_tags (recipe_id, tag_code, axis_code, assigned_by, "
                f"confidence, model, prompt_version) "
                f"VALUES ('{recipe_id}', '{tag}', '{axis}', 'LLM', {conf:.2f}, "
                f"{sql_quote(MODEL)}, {sql_quote(PROMPT_VERSION)});")
        stats[by] += 1

    unmatched = []
    for recipe_id, title, _status in recipes:
        if title in by_title:
            pat2, way2 = by_title[title]
            if pat2 in PAT2:
                add(recipe_id, PAT2[pat2], "DISH", "IMPORT")
            if way2 in WAY2:
                add(recipe_id, WAY2[way2], "METHOD", "IMPORT")
        elif title in MANUAL_DISH_METHOD:
            dish, dish_conf, method, method_conf = MANUAL_DISH_METHOD[title]
            if dish:
                add(recipe_id, dish, "DISH", "LLM", dish_conf)
            if method:
                add(recipe_id, method, "METHOD", "LLM", method_conf)
        else:
            unmatched.append(title)

        cuisine = classify_cuisine(title)
        if cuisine:
            add(recipe_id, cuisine[0], "CUISINE", "LLM", cuisine[1])
        for code, conf in classify_occasions(title):
            add(recipe_id, code, "OCCASION", "LLM", conf)

    if unmatched:
        raise SystemExit(f"원문에도 수기 판정표에도 없는 제목 {len(unmatched)}건: {unmatched[:10]}")

    lines.append("COMMIT;")
    with open(out_sql, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"recipes={len(recipes)} IMPORT rows={stats['IMPORT']} LLM rows={stats['LLM']} -> {out_sql}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
