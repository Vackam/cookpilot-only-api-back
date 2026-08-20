package com.cookpilot.backend.recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 레시피에 붙은 분류(조리방법·요리종류 라벨)와 해시태그 일괄 조회.
 *
 * recipe_tags/tags/recipe_hashtags 는 아직 JPA 엔티티가 없어(스키마 단계) JDBC 로 읽는다.
 * 목록 응답에서 N+1 이 나지 않게 recipeIds 를 한 번에 받는다 — RecipeController 가
 * favorites/개인버전을 묶어 조회하는 것과 같은 방식.
 */
@Component
public class RecipeTagLookup {

	/** 태그가 하나도 없는 레시피의 기본값. */
	public static final RecipeTagSummary EMPTY = new RecipeTagSummary(null, null, List.of());

	public record RecipeTagSummary(String cookingMethod, String dishType, List<String> hashtags) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public RecipeTagLookup(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<UUID, RecipeTagSummary> findByRecipes(List<UUID> recipeIds) {
		if (recipeIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> params = Map.of("ids", recipeIds);

		Map<UUID, String> methods = new HashMap<>();
		Map<UUID, String> dishes = new HashMap<>();
		jdbc.query("""
				SELECT rt.recipe_id, t.axis_code, t.label_ko
				FROM recipe_tags rt
				JOIN tags t ON t.code = rt.tag_code
				WHERE rt.recipe_id IN (:ids) AND t.axis_code IN ('METHOD', 'DISH')
				""", params, rs -> {
			UUID recipeId = rs.getObject("recipe_id", UUID.class);
			if ("METHOD".equals(rs.getString("axis_code"))) {
				methods.put(recipeId, rs.getString("label_ko"));
			} else {
				dishes.put(recipeId, rs.getString("label_ko"));
			}
		});

		Map<UUID, List<String>> hashtags = new HashMap<>();
		jdbc.query("""
				SELECT recipe_id, tag FROM recipe_hashtags
				WHERE recipe_id IN (:ids)
				ORDER BY tag
				""", params, rs -> {
			hashtags.computeIfAbsent(rs.getObject("recipe_id", UUID.class), k -> new java.util.ArrayList<>())
					.add(rs.getString("tag"));
		});

		Map<UUID, RecipeTagSummary> result = new HashMap<>();
		for (UUID id : recipeIds) {
			String method = methods.get(id);
			String dish = dishes.get(id);
			List<String> tags = hashtags.getOrDefault(id, List.of());
			if (method != null || dish != null || !tags.isEmpty()) {
				result.put(id, new RecipeTagSummary(method, dish, List.copyOf(tags)));
			}
		}
		return result;
	}
}
