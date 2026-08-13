package com.cookpilot.backend.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.cookpilot.backend.PostgresApiTestBase;
import com.cookpilot.backend.TestRecipeIds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 테스트 환경에는 GEMINI_API_KEY 가 없으므로 토큰 발급 자체는 409 로 끝난다.
 * 실제 발급(200)은 키가 있는 환경에서 수동 curl 로 검증했다(docs/only-api.md).
 */
class AiLiveSessionApiTest extends PostgresApiTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 키가_없으면_409() throws Exception {
		mockMvc.perform(post("/api/v1/ai-sessions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipeId\":\"" + TestRecipeIds.RAMEN_RECIPE_ID + "\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void recipeId_없으면_400() throws Exception {
		mockMvc.perform(post("/api/v1/ai-sessions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 없는_레시피면_404() throws Exception {
		mockMvc.perform(post("/api/v1/ai-sessions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipeId\":\"99999999-0000-0000-0000-000000000000\"}"))
				.andExpect(status().isNotFound());
	}
}
