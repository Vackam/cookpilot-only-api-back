package com.cookpilot.backend.ai;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.cookpilot.backend.recipe.Recipe;
import com.cookpilot.backend.recipe.RecipeIngredient;
import com.cookpilot.backend.recipe.RecipeService;
import com.cookpilot.backend.recipe.RecipeStep;

/**
 * Gemini Live 세션 오픈. 서버는 토큰만 발급하고 오디오 스트림에는 관여하지 않는다 —
 * 클라이언트가 이 토큰으로 Live WebSocket 에 직접 붙는다(조리 세션은 프론트 소유라는
 * 기존 전제 그대로). 레시피 전체를 systemInstruction 으로 토큰에 잠가 보내므로
 * 클라이언트는 오디오만 흘리면 된다.
 */
@Service
public class AiLiveSessionService {

	private final RecipeService recipeService;
	private final GeminiLiveTokenClient tokenClient;

	public AiLiveSessionService(RecipeService recipeService, GeminiLiveTokenClient tokenClient) {
		this.recipeService = recipeService;
		this.tokenClient = tokenClient;
	}

	public AiLiveSessionResponse open(UUID recipeId) {
		Recipe recipe = recipeService.findById(recipeId);
		String token = tokenClient.createToken(systemInstruction(recipe));
		return new AiLiveSessionResponse(token, tokenClient.model());
	}

	/**
	 * 조리 코치 공통 규칙(CookingCoachClient.SYSTEM_PROMPT) 뒤에 이번 요리의 재료·단계
	 * 전체를 붙인다. Live 세션은 텍스트 피드백과 달리 단계를 요청마다 받을 수 없으므로
	 * 레시피 전문을 컨텍스트로 넣는다.
	 */
	static String systemInstruction(Recipe recipe) {
		StringBuilder text = new StringBuilder(CookingCoachClient.SYSTEM_PROMPT);
		text.append("\n지금 진행하는 요리: ").append(recipe.title()).append('\n');
		text.append("재료:\n");
		for (RecipeIngredient ingredient : recipe.ingredients()) {
			text.append("- ").append(ingredient.name());
			if (ingredient.amount() != null) {
				text.append(' ').append(ingredient.amount());
				if (ingredient.unit() != null) {
					text.append(ingredient.unit());
				}
			}
			if (!ingredient.required()) {
				text.append(" (선택)");
			}
			text.append('\n');
		}
		text.append("단계:\n");
		for (RecipeStep step : recipe.steps()) {
			text.append(step.stepIndex()).append(". ").append(step.instruction());
			if (step.timerSeconds() != null) {
				text.append(" (타이머 ").append(step.timerSeconds()).append("초)");
			}
			if (step.cautionNote() != null && !step.cautionNote().isBlank()) {
				text.append(" (주의: ").append(step.cautionNote()).append(')');
			}
			text.append('\n');
		}
		text.append("사용자가 지금 몇 번째 단계인지 말하면 그 단계 기준으로 안내하세요.\n");
		return text.toString();
	}
}
