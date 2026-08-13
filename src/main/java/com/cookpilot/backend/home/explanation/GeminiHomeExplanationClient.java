package com.cookpilot.backend.home.explanation;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cookpilot.backend.ai.gemini.GeminiReasonsClient;

/**
 * 메인 화면 추천 문구의 Gemini 프롬프트.
 *
 * HTTP·응답 검증·실패 흡수는 전부 {@link GeminiReasonsClient} 가 한다. 이 클래스에 남은
 * 것은 프롬프트뿐이다.
 *
 * <p>프롬프트가 지키게 하는 것 두 가지:
 * <ul>
 *   <li><strong>순서·선정을 건드리지 못하게 한다.</strong> 무엇을 몇 번째로 보여줄지는
 *       규칙 엔진이 이미 정했다. 모델이 레시피를 바꿔치기하면 응답의 recipeId 와 문구가
 *       어긋난다.</li>
 *   <li><strong>근거=DEFAULT 를 개인화된 척 쓰지 못하게 한다.</strong> 근거가 없는데
 *       "취향에 맞을 거예요" 라고 쓰면 라벨을 분리해 둔 의미가 사라진다.</li>
 * </ul>
 */
@Component
public class GeminiHomeExplanationClient implements HomeExplanationClient {

	private final GeminiReasonsClient reasonsClient;

	public GeminiHomeExplanationClient(GeminiReasonsClient reasonsClient) {
		this.reasonsClient = reasonsClient;
	}

	@Override
	public Optional<List<String>> explainAll(List<HomeExplanationContext> contexts) {
		if (contexts.isEmpty()) {
			return Optional.of(List.of());
		}
		return reasonsClient.reasons(prompt(contexts), contexts.size());
	}

	@Override
	public String model() {
		return reasonsClient.model();
	}

	String prompt(List<HomeExplanationContext> contexts) {
		StringBuilder items = new StringBuilder();
		for (int index = 0; index < contexts.size(); index++) {
			items.append("%d. %s\n".formatted(index + 1, describe(contexts.get(index))));
		}
		return """
				당신은 CookPilot 메인 화면의 레시피 추천 문구 작성기입니다.
				어떤 레시피를 몇 번째로 보여줄지는 서버가 이미 정했습니다. 순서를 바꾸거나
				목록에 없는 레시피를 언급하지 마세요.

				각 항목마다 한국어 한 문장(40자 이내)으로, 사용자가 이 레시피를 지금 보고 있는
				이유를 설명하세요. 근거 종류에 따라 쓸 수 있는 말이 다릅니다.
				- 취향: 근거가 된 요리 이름을 반드시 넣어 왜 비슷한지 한 가지만 짚으세요.
				- 이력: 주어진 경과 기간과 평점만 쓰세요. 날짜나 점수를 지어내지 마세요.
				- 없음: 아직 만들어보지 않았다는 사실만 담백하게 쓰세요.
				  개인 취향에 맞는다는 식으로 쓰면 안 됩니다. 근거가 없습니다.

				과장, 광고 문구, 이모지, 사용자 이름, 내부 점수는 쓰지 마세요.
				입력 순서와 같은 순서의 reasons 배열로 답하세요.

				%s
				""".formatted(items);
	}

	private String describe(HomeExplanationContext context) {
		StringBuilder line = new StringBuilder();
		line.append("레시피=").append(context.recipeTitle());
		if (context.recipeDescription() != null && !context.recipeDescription().isBlank()) {
			line.append(", 설명=").append(context.recipeDescription());
		}
		line.append(", 칸=")
				.append("AGAIN".equals(context.slot()) ? "다시 만들기" : "새로 도전");

		switch (context.source()) {
			case "TASTE" -> line.append(", 근거=취향(만족했던 요리: ")
					.append(context.similarToTitle()).append(")");
			case "HISTORY" -> line.append(", 근거=이력(")
					.append(elapsed(context.daysSinceCooked()))
					.append(" 전에 만들어 ").append(context.bestRating()).append("점)");
			default -> line.append(", 근거=없음(만든 적 없음)");
		}
		if (context.favorite()) {
			line.append(", 즐겨찾기=예");
		}
		return line.toString();
	}

	/** 모델이 날짜를 지어내지 않도록 경과 기간을 문자열로 굳혀 넘긴다. */
	static String elapsed(Integer days) {
		if (days == null) {
			return "얼마 전";
		}
		return days >= 30 ? "%d개월".formatted(days / 30) : "%d주".formatted(days / 7);
	}
}
