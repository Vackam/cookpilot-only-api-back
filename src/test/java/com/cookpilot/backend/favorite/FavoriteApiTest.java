package com.cookpilot.backend.favorite;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.cookpilot.backend.PostgresApiTestBase;
import com.cookpilot.backend.TestRecipeIds;
import com.cookpilot.backend.recipe.RecipeService;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FavoriteApiTest extends PostgresApiTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecipeFavoriteRepository recipeFavoriteRepository;

	@BeforeEach
	void clearFavorites() {
		recipeFavoriteRepository.deleteAll();
	}

	@Test
	void 신규_사용자의_즐겨찾기는_빈_목록이다() throws Exception {
		mockMvc.perform(get("/api/v1/favorites"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void 즐겨찾기를_추가하고_중복없이_조회하고_삭제한다() throws Exception {
		String path = "/api/v1/recipes/" + TestRecipeIds.RAMEN_RECIPE_ID + "/favorite";

		mockMvc.perform(put(path))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(TestRecipeIds.RAMEN_RECIPE_ID.toString()))
				.andExpect(jsonPath("$.favoritedAt").exists());
		mockMvc.perform(put(path))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/favorites"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(TestRecipeIds.RAMEN_RECIPE_ID.toString()));

		mockMvc.perform(get("/api/v1/recipes?size=" + RecipeService.MAX_PAGE_SIZE))
				.andExpect(status().isOk())
				.andExpect(jsonPath(
						"$.items[?(@.id == '" + TestRecipeIds.RAMEN_RECIPE_ID + "')].favorite")
						.value(contains(true)));

		mockMvc.perform(delete(path))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete(path))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/favorites"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void 없는_레시피_즐겨찾기는_404다() throws Exception {
		String path = "/api/v1/recipes/99999999-0000-0000-0000-000000000000/favorite";

		mockMvc.perform(put(path))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(path))
				.andExpect(status().isNotFound());
	}

	@Test
	void 동시에_즐겨찾기를_추가해도_한_건만_저장한다() throws Exception {
		String path = "/api/v1/recipes/" + TestRecipeIds.RAMEN_RECIPE_ID + "/favorite";
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<String> addFavorite = () -> {
			ready.countDown();
			start.await(5, TimeUnit.SECONDS);
			return mockMvc.perform(put(path))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();
		};

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			List<Future<String>> requests = List.of(
					executor.submit(addFavorite),
					executor.submit(addFavorite));
			org.assertj.core.api.Assertions.assertThat(
					ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			for (Future<String> request : requests) {
				org.assertj.core.api.Assertions.assertThat(
						request.get(10, TimeUnit.SECONDS))
						.contains(TestRecipeIds.RAMEN_RECIPE_ID.toString());
			}
		}

		org.assertj.core.api.Assertions.assertThat(
				recipeFavoriteRepository.count()).isEqualTo(1);
	}
}
