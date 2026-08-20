package com.cookpilot.backend.recipe;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.cookpilot.backend.PostgresApiTestBase;
import com.cookpilot.backend.TestRecipeIds;
import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 레시피 목록/상세 API. 원본 레시피와 개인 버전 배지를 모두 PostgreSQL/JPA에서 조회한다.
 */
class RecipeApiTest extends PostgresApiTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecipeRepository recipeRepository;

	@Autowired
	private RecipeIngredientRepository ingredientRepository;

	@Autowired
	private RecipeStepRepository stepRepository;

	@Autowired
	private RecipeService recipeService;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void 레시피_목록을_조회한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(greaterThanOrEqualTo(2))))
				.andExpect(jsonPath("$.items[0].id").exists())
				.andExpect(jsonPath("$.items[0].title").exists())
				.andExpect(jsonPath("$.items[0].hasPersonalVersion").exists())
				.andExpect(jsonPath("$.items[0].favorite").exists())
				.andExpect(jsonPath("$.items[0].ingredients").doesNotExist())
				.andExpect(jsonPath("$.items[0].steps").doesNotExist());
	}

	@Test
	void 목록은_기본_10건까지만_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.items", hasSize(lessThanOrEqualTo(10))))
				.andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(8)));
	}

	@Test
	void 다음_페이지는_앞_페이지와_겹치지_않는다() throws Exception {
		String first = firstItemId(0);
		String second = firstItemId(1);

		mockMvc.perform(get("/api/v1/recipes?page=0&size=1"))
				.andExpect(jsonPath("$.hasNext").value(true));
		org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
	}

	@Test
	void 잘못된_페이지_인자는_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes?size=0"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/recipes?page=-1"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/recipes?size=101"))
				.andExpect(status().isBadRequest());
	}

	private String firstItemId(int page) throws Exception {
		return JsonPath.read(
				mockMvc.perform(get("/api/v1/recipes?page=" + page + "&size=1"))
						.andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString(),
				"$.items[0].id");
	}

	@Test
	void 요리명과_재료명으로_레시피를_페이지_검색한다() throws Exception {
		RecipeEntity basilPasta = recipeRepository.save(new RecipeEntity(
				"검색전용 바질 파스타", "요리명 검색 검증", BigDecimal.valueOf(2)));
		ingredientRepository.save(new RecipeIngredientEntity(
				basilPasta.getId(), "생바질", BigDecimal.valueOf(10), "g", true, 0));
		RecipeEntity tomatoSoup = recipeRepository.save(new RecipeEntity(
				"검색전용 토마토 수프", "재료명 검색 검증", BigDecimal.valueOf(2)));
		ingredientRepository.save(new RecipeIngredientEntity(
				tomatoSoup.getId(), "완숙 토마토", BigDecimal.valueOf(2), "개", true, 0));
		RecipeEntity inactiveBasil = new RecipeEntity(
				"검색전용 바질 폐기본", "중복 숨김 검증", BigDecimal.valueOf(2));
		inactiveBasil.setStatus("inactive");
		inactiveBasil = recipeRepository.save(inactiveBasil);
		ingredientRepository.save(new RecipeIngredientEntity(
				inactiveBasil.getId(), "생바질", BigDecimal.ONE, "g", true, 0));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "바질 파스타"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(basilPasta.getId().toString()))
				.andExpect(jsonPath("$.items[0].favorite").exists())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("ingredient", "토마토"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(tomatoSoup.getId().toString()));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "검색전용")
				.param("ingredient", "바질")
				.param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(basilPasta.getId().toString()))
				.andExpect(jsonPath("$.hasNext").value(false));

		// 범위를 벗어난 페이지는 목록과 같은 규칙 — 빈 결과를 그대로 준다(마지막 페이지로 보정하지 않음).
		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "검색전용")
				.param("page", "999"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(2));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "검색전용")
				.param("page", "-1"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 분류와_해시태그로_검색을_거르고_응답에_함께_싣는다() throws Exception {
		RecipeEntity grilledSide = recipeRepository.save(new RecipeEntity(
				"분류필터전용 더덕구이", "분류 필터 검증", BigDecimal.ONE));
		RecipeEntity boiledSide = recipeRepository.save(new RecipeEntity(
				"분류필터전용 시금치무침", "분류 AND 검증", BigDecimal.ONE));
		jdbc.update("""
				INSERT INTO recipe_tags (recipe_id, tag_code, axis_code, assigned_by) VALUES
				  (?::uuid, 'DISH_SIDE', 'DISH', 'IMPORT'),
				  (?::uuid, 'METHOD_GRILL', 'METHOD', 'IMPORT'),
				  (?::uuid, 'DISH_SIDE', 'DISH', 'IMPORT'),
				  (?::uuid, 'METHOD_BOIL', 'METHOD', 'IMPORT')
				""", grilledSide.getId(), grilledSide.getId(), boiledSide.getId(), boiledSide.getId());
		jdbc.update("INSERT INTO recipe_hashtags (recipe_id, tag) VALUES (?::uuid, '태그검색 더덕')",
				grilledSide.getId());

		// 분류 두 개는 AND — 반찬이면서 굽기인 것만.
		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "분류필터전용")
				.param("dishType", "반찬")
				.param("cookingMethod", "굽기"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(grilledSide.getId().toString()))
				.andExpect(jsonPath("$.items[0].cookingMethod").value("굽기"))
				.andExpect(jsonPath("$.items[0].dishType").value("반찬"))
				.andExpect(jsonPath("$.items[0].hashtags[0]").value("태그검색 더덕"));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("hashtag", "태그검색 더덕"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(grilledSide.getId().toString()));

		// 사전에 없는 분류 값은 400이 아니라 0건.
		mockMvc.perform(get("/api/v1/recipes/search")
				.param("dishType", "없는분류"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(0));

		// 태그가 없는 레시피는 null 분류 + 빈 해시태그 배열.
		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "분류필터전용 시금치무침")
				.param("dishType", "반찬"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].cookingMethod").value("끓이기"))
				.andExpect(jsonPath("$.items[0].hashtags", hasSize(0)));
	}

	@Test
	void 검색어의_LIKE_기호는_와일드카드가_아닌_문자로_검색한다() throws Exception {
		RecipeEntity literalSymbols = recipeRepository.save(new RecipeEntity(
				"검색기호 %_ 레시피", "LIKE 기호 검색 검증", BigDecimal.ONE));
		ingredientRepository.save(new RecipeIngredientEntity(
				literalSymbols.getId(), "100%_카카오", BigDecimal.ONE, "조각", true, 0));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("title", "%_"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(literalSymbols.getId().toString()));

		mockMvc.perform(get("/api/v1/recipes/search")
				.param("ingredient", "%_"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(literalSymbols.getId().toString()));
	}

	@Test
	void 제목이_같은_레시피는_ID_순서로_반환한다() {
		recipeRepository.save(new RecipeEntity(
				"동일 제목 정렬 검증", "첫 번째", BigDecimal.ONE));
		recipeRepository.save(new RecipeEntity(
				"동일 제목 정렬 검증", "두 번째", BigDecimal.ONE));

		List<String> actual = recipeService.findPage(0, RecipeService.MAX_PAGE_SIZE).getContent().stream()
				.filter(recipe -> recipe.title().equals("동일 제목 정렬 검증"))
				.map(RecipeOverview::id)
				.map(UUID::toString)
				.toList();
		List<String> expected = actual.stream().sorted().toList();

		org.assertj.core.api.Assertions.assertThat(actual)
				.containsExactlyElementsOf(expected);
	}

	@Test
	void 카탈로그의_필수_재료와_조리_안내가_일치한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes/10000000-0000-0000-0000-000000000003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[?(@.name == '식용유')].required")
						.value(contains(true)))
				.andExpect(jsonPath("$.steps[1].instruction")
						.value("팬에 식용유를 두르고 두부를 올려 앞뒤로 노릇하게 구우세요."));

		mockMvc.perform(get("/api/v1/recipes/10000000-0000-0000-0000-000000000008"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[?(@.name == '양배추')].required")
						.value(contains(true)))
				.andExpect(jsonPath("$.ingredients[?(@.name == '고구마')].required")
						.value(contains(true)));
	}

	@Test
	void 레시피_상세를_조회한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes/" + TestRecipeIds.RAMEN_RECIPE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("라면"))
				.andExpect(jsonPath("$.baseServings").value(1.0))
				.andExpect(jsonPath("$.steps", hasSize(3)))
				.andExpect(jsonPath("$.steps[0].instruction").value("물 500ml를 넣고 3분간 끓이세요."))
				.andExpect(jsonPath("$.steps[0].timerSeconds").value(180))
				.andExpect(jsonPath("$.ingredients", hasSize(4)));
	}

	@Test
	void DB에만_저장한_레시피를_목록과_상세에서_조회한다() throws Exception {
		RecipeEntity recipe = recipeRepository.save(new RecipeEntity(
				"DB 전용 된장국", "하드코딩 Map에는 없는 레시피", BigDecimal.valueOf(2),
				"https://cdn.cookpilot.app/recipes/doenjang.png"));
		ingredientRepository.save(new RecipeIngredientEntity(
				recipe.getId(), "물", BigDecimal.valueOf(500), "ml", true, 1));
		ingredientRepository.save(new RecipeIngredientEntity(
				recipe.getId(), "된장", BigDecimal.ONE, "큰술", true, 0));
		stepRepository.save(new RecipeStepEntity(
				recipe.getId(), 1, "두부를 넣고 마저 끓여요.", 180, "냄비가 뜨거워요"));
		stepRepository.save(new RecipeStepEntity(
				recipe.getId(), 0, "물을 끓이고 된장을 풀어요.", 120, null,
				"https://cdn.cookpilot.app/steps/doenjang-1.png"));

		// 기본 페이지(10건)에 들어온다는 보장이 없어 한 페이지에 몰아 담아 확인한다.
		mockMvc.perform(get("/api/v1/recipes?size=" + RecipeService.MAX_PAGE_SIZE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '" + recipe.getId() + "')]", hasSize(1)))
				.andExpect(jsonPath("$.items[?(@.id == '" + recipe.getId() + "')].imageUrl")
						.value(contains("https://cdn.cookpilot.app/recipes/doenjang.png")));

		mockMvc.perform(get("/api/v1/recipes/" + recipe.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("DB 전용 된장국"))
				.andExpect(jsonPath("$.description").value("하드코딩 Map에는 없는 레시피"))
				.andExpect(jsonPath("$.imageUrl").value("https://cdn.cookpilot.app/recipes/doenjang.png"))
				.andExpect(jsonPath("$.ingredients[0].name").value("된장"))
				.andExpect(jsonPath("$.ingredients[1].name").value("물"))
				.andExpect(jsonPath("$.steps[0].instruction").value("물을 끓이고 된장을 풀어요."))
				.andExpect(jsonPath("$.steps[0].timerSeconds").value(120))
				.andExpect(jsonPath("$.steps[0].imageUrl")
						.value("https://cdn.cookpilot.app/steps/doenjang-1.png"))
				.andExpect(jsonPath("$.steps[1].cautionNote").value("냄비가 뜨거워요"));
	}

	@Test
	void 없는_레시피는_404를_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/recipes/99999999-0000-0000-0000-000000000000"))
				.andExpect(status().isNotFound());
	}
}
