package com.cookpilot.backend.ai;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cookpilot.backend.recipe.Recipe;
import com.cookpilot.backend.recipe.RecipeIngredient;
import com.cookpilot.backend.recipe.RecipeStep;

import static org.assertj.core.api.Assertions.assertThat;

class AiLiveSessionServiceTest {

	@Test
	void systemInstruction은_안전_원칙과_재료_단계_전문을_담는다() {
		Recipe recipe = new Recipe(
				UUID.randomUUID(), "간장계란밥", "설명", 1.0, null,
				List.of(
						new RecipeIngredient(UUID.randomUUID(), "계란", 2.0, "개", true),
						new RecipeIngredient(UUID.randomUUID(), "참기름", null, null, false)),
				List.of(
						new RecipeStep(UUID.randomUUID(), 0, "밥을 준비한다", null, null, null),
						new RecipeStep(UUID.randomUUID(), 1, "계란을 부친다", 90, "기름 튐 주의", null)));

		String instruction = AiLiveSessionService.systemInstruction(recipe);

		assertThat(instruction)
				.contains("안전 원칙")             // CookingCoachClient 공통 규칙 포함
				.contains("간장계란밥")
				.contains("- 계란 2.0개")
				.contains("- 참기름 (선택)")       // 양 없는 재료는 이름만
				.contains("0. 밥을 준비한다")
				.contains("1. 계란을 부친다 (타이머 90초) (주의: 기름 튐 주의)");
	}
}
