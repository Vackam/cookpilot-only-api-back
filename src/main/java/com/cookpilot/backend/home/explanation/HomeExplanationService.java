package com.cookpilot.backend.home.explanation;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 메인 화면 추천 문구를 붙인다. LLM 이 죽어도 규칙 문구로 조용히 대체하고, 문구가 어디서
 * 나왔는지는 응답에 남긴다.
 *
 * <p>추천의 <em>선정</em>은 이 서비스가 손대지 않는다. 몇 번째 칸에 무엇이 오는지는 이미
 * 정해진 뒤이고 여기는 문구만 만든다 — LLM 이 실패해도 추천 자체는 온전하다.
 */
@Service
public class HomeExplanationService {

	/**
	 * 프롬프트를 고치면 v2, v3 로 올린다. 응답에 실려 나가므로 "이 문구가 어느 프롬프트에서
	 * 나왔는지" 를 나중에 역추적할 수 있다. 기능 번호에 묶지 않는 이유는 재료 추천 쪽
	 * PROMPT_VERSION 과 같다 — 명세 개정마다 번호가 밀린다.
	 */
	public static final String PROMPT_VERSION = "home-recipe-reason-v1";

	private final HomeExplanationClient explanationClient;

	public HomeExplanationService(HomeExplanationClient explanationClient) {
		this.explanationClient = explanationClient;
	}

	public List<Explanation> explainAll(List<HomeExplanationContext> contexts) {
		if (contexts.isEmpty()) {
			return List.of();
		}
		List<String> generated = explanationClient.explainAll(contexts)
				.filter(reasons -> reasons.size() == contexts.size())
				.orElse(null);
		if (generated == null) {
			return contexts.stream()
					.map(context -> new Explanation(
							fallback(context), "FALLBACK", null, PROMPT_VERSION))
					.toList();
		}
		String model = explanationClient.model();
		return generated.stream()
				.map(reason -> new Explanation(reason, "GEMINI", model, PROMPT_VERSION))
				.toList();
	}

	/** LLM 없이도 성립하는 문구. 근거로 쓰는 값이 전부 서버 계산이라 틀릴 수가 없다. */
	String fallback(HomeExplanationContext context) {
		if ("AGAIN".equals(context.slot())) {
			if (context.bestRating() == null || context.bestRating() < 4) {
				return "즐겨찾기에 담아두신 레시피예요";
			}
			return "%s 전에 %d점을 주셨어요".formatted(
					GeminiHomeExplanationClient.elapsed(context.daysSinceCooked()),
					context.bestRating());
		}
		if (context.similarToTitle() != null) {
			return "%s%s 비슷한 요리예요".formatted(
					context.similarToTitle(), particle(context.similarToTitle()));
		}
		if (context.favorite()) {
			return "즐겨찾기에 담아두셨지만 아직 안 만들어보셨어요";
		}
		return "아직 만들어보지 않은 레시피예요";
	}

	/** 한글 받침 유무에 따른 조사. 받침이 있으면 "과", 없으면 "와". */
	private String particle(String title) {
		if (title.isEmpty()) {
			return "와";
		}
		char last = title.charAt(title.length() - 1);
		boolean hangul = last >= 0xAC00 && last <= 0xD7A3;
		return hangul && (last - 0xAC00) % 28 != 0 ? "과" : "와";
	}

	public record Explanation(
			String reason,
			String source,
			String model,
			String promptVersion
	) {
	}
}
