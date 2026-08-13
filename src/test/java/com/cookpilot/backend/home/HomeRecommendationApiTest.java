package com.cookpilot.backend.home;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.cookpilot.backend.PostgresApiTestBase;
import com.cookpilot.backend.TestRecipeIds;
import com.cookpilot.backend.favorite.RecipeFavoriteRepository;
import com.cookpilot.backend.home.explanation.HomeExplanationService;
import com.cookpilot.backend.review.PostCookReviewRepository;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 메인 화면 추천 API 테스트.
 *
 * 추천은 카탈로그 전체와 이 사용자의 조리 이력 전부를 보고 순위를 매기므로, 공유 DB 에 남은
 * 다른 테스트 클래스의 리뷰가 결과를 바꾼다. RecentRecipeApiTest 와 같은 방식으로 매 테스트
 * 앞에서 이력을 비운다.
 */
class HomeRecommendationApiTest extends PostgresApiTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostCookReviewRepository postCookReviewRepository;

	@Autowired
	private RecipeFavoriteRepository recipeFavoriteRepository;

	@BeforeEach
	void clearHistory() {
		recipeFavoriteRepository.deleteAll();
		postCookReviewRepository.deleteAll();
	}

	private void cook(UUID recipeId, int rating, String cookedAt) throws Exception {
		String body = cookedAt == null
				? """
				{"clientSessionId": "%s", "recipeId": "%s", "rating": %d}
				""".formatted(UUID.randomUUID(), recipeId, rating)
				: """
				{"clientSessionId": "%s", "recipeId": "%s", "rating": %d, "cookedAt": "%s"}
				""".formatted(UUID.randomUUID(), recipeId, rating, cookedAt);

		mockMvc.perform(post("/api/v1/reviews")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated());
	}

	@Test
	void 신규_사용자는_근거_없음으로_라벨된_기본_노출을_받는다() throws Exception {
		mockMvc.perform(get("/api/v1/home/recommendations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].slot", everyItem(is("NEW"))))
				.andExpect(jsonPath("$[*].source", everyItem(is("DEFAULT"))))
				.andExpect(jsonPath("$[0].lastCookedAt").doesNotExist())
				.andExpect(jsonPath("$[0].bestRating").doesNotExist());
	}

	@Test
	void 만족한_조리가_있으면_맛이_가장_가까운_레시피가_취향_근거로_1위다() throws Exception {
		// 제육볶음(KOREAN/MAIN_DISH/STIR_FRY/GOCHUJANG+SOY_SAUCE) 과
		// 닭갈비(KOREAN/MAIN_DISH/STIR_FRY/GOCHUJANG) 는 네 축이 전부 겹쳐 유사도 1.0 이다.
		cook(TestRecipeIds.SPICY_PORK_RECIPE_ID, 5, null);

		mockMvc.perform(get("/api/v1/home/recommendations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].recipeId")
						.value(TestRecipeIds.DAKGALBI_RECIPE_ID.toString()))
				.andExpect(jsonPath("$[0].slot").value("NEW"))
				.andExpect(jsonPath("$[0].source").value("TASTE"));
	}

	@Test
	void 모든_추천에_문구와_그_출처가_실린다() throws Exception {
		// 문구 출처는 이 머신의 GEMINI_* 환경변수에 따라 갈리므로 값을 고정하지 않는다.
		// 여기서 지키려는 것은 "둘 중 하나로 라벨되고 프롬프트 버전이 함께 실린다" 이다.
		mockMvc.perform(get("/api/v1/home/recommendations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].reason", everyItem(not(emptyOrNullString()))))
				.andExpect(jsonPath("$[*].explanationSource",
						everyItem(oneOf("GEMINI", "FALLBACK"))))
				.andExpect(jsonPath("$[*].promptVersion",
						everyItem(is(HomeExplanationService.PROMPT_VERSION))));
	}

	@Test
	void 만족했지만_오래_안_만든_레시피는_다시_만들기로_올라온다() throws Exception {
		cook(TestRecipeIds.DOENJANG_STEW_RECIPE_ID, 5, "2026-06-12T10:00:00Z");

		mockMvc.perform(get("/api/v1/home/recommendations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[2].recipeId")
						.value(TestRecipeIds.DOENJANG_STEW_RECIPE_ID.toString()))
				.andExpect(jsonPath("$[2].slot").value("AGAIN"))
				.andExpect(jsonPath("$[2].source").value("HISTORY"))
				.andExpect(jsonPath("$[2].bestRating").value(5))
				.andExpect(jsonPath("$[2].lastCookedAt").exists());
	}

	@Test
	void 방금_만든_레시피는_다시_만들기로_올라오지_않는다() throws Exception {
		cook(TestRecipeIds.DOENJANG_STEW_RECIPE_ID, 5, null);

		mockMvc.perform(get("/api/v1/home/recommendations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].slot", everyItem(is("NEW"))));
	}
}
