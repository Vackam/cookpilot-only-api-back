package com.cookpilot.backend.ai;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.cookpilot.backend.recipe.RecipeStep;

/**
 * 조리 중 사용자 발화에 대한 LLM 조언 생성기(Spring AI).
 * 현재는 string으로 온다고 가정
 */
@Component
public class CookingCoachClient {

	private static final Logger log = LoggerFactory.getLogger(CookingCoachClient.class);

	/** 안전 원칙은 모델 재량에 맡기지 않고 여기에 고정한다. Live 세션(AiLiveSessionService)도 공유한다. */
	static final String SYSTEM_PROMPT = """
			당신은 CookPilot의 조리 중 음성 어시스턴트입니다.
			사용자는 지금 불 앞에 서 있고, 손이 젖어 있으며, 답변을 귀로만 듣습니다.

			응답 규칙:
			- 답변은 그대로 TTS 로 읽힙니다. 한국어 두 문장 이내, 숫자는 소리 내어 읽기 쉽게 쓰세요.
			- 머리말·목록·마크다운 없이 문장만 쓰세요.
			- 되묻지 마세요. 발화가 불분명하면 현재 단계 기준으로 가장 안전한 안내를 하세요.

			안전 원칙(어길 수 없음):
			- 변질이 의심되는 재료를 먹어도 된다고 단정하지 마세요.
			- 덜 익은 육류·해산물은 추가 가열을 먼저 안내하세요.
			- 알레르기 질문은 보수적으로 답하고 확신하지 마세요.
			- 화상·화재 위험이 감지되면 다른 안내보다 안전 행동을 먼저 말하세요.
			""";

	/** AI 가 꺼져 있으면 null. 이 필드의 null 여부가 곧 "LLM 을 쓸 수 있는가" 다. */
	private final ChatClient chatClient;

	public CookingCoachClient(ObjectProvider<ChatModel> chatModel) {
		ChatModel model = chatModel.getIfAvailable();
		this.chatClient = model == null
				? null
				: ChatClient.builder(model)
							.defaultSystem(SYSTEM_PROMPT)
							.build();
	}

	/**
	 * TTS 로 읽을 문장 하나를 만든다.
	 * 설정이 꺼져 있거나, 호출이 실패하거나, 빈 답이 오면 empty 를 돌려준다.
	 */
	public Optional<String> advise(String recipeTitle, RecipeStep step, String userSpeech) {
		if (chatClient == null) {
			return Optional.empty();
		}
		try {
			String speech = chatClient.prompt()
					.user(userPrompt(recipeTitle, step, userSpeech))
					.call()
					.content();
			return speech == null || speech.isBlank() ? Optional.empty() : Optional.of(speech.trim());
		} catch (RuntimeException exception) {
			// 호출·역직렬화 어디서 무엇이 어긋나도 목 응답으로 흡수한다.
			// 타입만 찍으면 (RuntimeException) 만 남아 원인을 못 찾는다 — 스택까지 남긴다.
			// TODO: 재시도로 변경하거나 해야함.
			log.warn("AI 조리 피드백 생성에 실패해 목 응답을 사용합니다", exception);
			return Optional.empty();
		}
	}

	private String userPrompt(String recipeTitle, RecipeStep step, String userSpeech) {
		return """
				요리: %s
				현재 단계 %d: %s
				단계 타이머(초): %s
				단계 주의사항: %s
				사용자 발화(음성 인식 결과): %s
				""".formatted(
				recipeTitle,
				step.stepIndex(),
				step.instruction(),
				step.timerSeconds() == null ? "없음" : step.timerSeconds().toString(),
				step.cautionNote() == null || step.cautionNote().isBlank() ? "없음" : step.cautionNote(),
				userSpeech);
	}
}
