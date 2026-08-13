package com.cookpilot.backend.home.explanation;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문구 파이프라인 단위 테스트. HTTP 없이 클라이언트 대역만 갈아끼워 검증한다.
 */
class HomeExplanationServiceTest {

	private static HomeExplanationContext taste(String similarTo) {
		return new HomeExplanationContext("닭갈비", "매콤한 한 끼", "NEW", "TASTE",
				similarTo, null, null, false);
	}

	private static HomeExplanationContext history(int days, int rating) {
		return new HomeExplanationContext("된장찌개", "기본 된장찌개", "AGAIN", "HISTORY",
				null, days, rating, false);
	}

	private static HomeExplanationContext plain(boolean favorite) {
		return new HomeExplanationContext("라면", "간단한 한 끼", "NEW", "DEFAULT",
				null, null, null, favorite);
	}

	/** 항상 실패하는 대역 — 키가 없거나 Gemini 가 죽은 상황과 같다. */
	private static HomeExplanationService withFailingClient() {
		return new HomeExplanationService(new HomeExplanationClient() {
			@Override
			public Optional<List<String>> explainAll(List<HomeExplanationContext> contexts) {
				return Optional.empty();
			}

			@Override
			public String model() {
				return "test-model";
			}
		});
	}

	private static HomeExplanationService withClientReturning(List<String> reasons) {
		return new HomeExplanationService(new HomeExplanationClient() {
			@Override
			public Optional<List<String>> explainAll(List<HomeExplanationContext> contexts) {
				return Optional.of(reasons);
			}

			@Override
			public String model() {
				return "test-model";
			}
		});
	}

	@Nested
	class LLM이_살아있을_때 {

		@Test
		void 생성된_문구와_출처_모델_프롬프트버전이_실린다() {
			HomeExplanationService.Explanation result =
					withClientReturning(List.of("제육볶음이 입에 맞으셨다면 닭갈비도 좋아요"))
							.explainAll(List.of(taste("제육볶음"))).getFirst();

			assertThat(result.reason()).isEqualTo("제육볶음이 입에 맞으셨다면 닭갈비도 좋아요");
			assertThat(result.source()).isEqualTo("GEMINI");
			assertThat(result.model()).isEqualTo("test-model");
			assertThat(result.promptVersion())
					.isEqualTo(HomeExplanationService.PROMPT_VERSION);
		}

		@Test
		void 개수가_어긋나면_전부_규칙_문구로_대체된다() {
			// 2건을 요청했는데 1건만 왔다 — 어느 문구가 어느 추천 것인지 알 수 없다.
			List<HomeExplanationService.Explanation> results =
					withClientReturning(List.of("한 줄만"))
							.explainAll(List.of(taste("제육볶음"), plain(false)));

			assertThat(results).hasSize(2);
			assertThat(results).allSatisfy(result ->
					assertThat(result.source()).isEqualTo("FALLBACK"));
			assertThat(results.getFirst().reason()).isEqualTo("제육볶음과 비슷한 요리예요");
		}
	}

	@Nested
	class LLM이_죽었을_때 {

		@Test
		void 취향_근거는_근거_레시피_이름을_넣은_규칙_문구가_된다() {
			HomeExplanationService.Explanation result =
					withFailingClient().explainAll(List.of(taste("제육볶음"))).getFirst();

			assertThat(result.reason()).isEqualTo("제육볶음과 비슷한 요리예요");
			assertThat(result.source()).isEqualTo("FALLBACK");
			assertThat(result.model()).isNull();
			assertThat(result.promptVersion())
					.isEqualTo(HomeExplanationService.PROMPT_VERSION);
		}

		@Test
		void 받침_유무에_따라_조사를_고른다() {
			HomeExplanationService service = withFailingClient();

			assertThat(service.fallback(taste("제육볶음")).startsWith("제육볶음과")).isTrue();
			assertThat(service.fallback(taste("김치찌개")).startsWith("김치찌개와")).isTrue();
		}

		@Test
		void 이력_근거는_경과_기간과_평점을_쓴다() {
			HomeExplanationService service = withFailingClient();

			assertThat(service.fallback(history(21, 5))).isEqualTo("3주 전에 5점을 주셨어요");
			assertThat(service.fallback(history(65, 4))).isEqualTo("2개월 전에 4점을 주셨어요");
		}

		@Test
		void 평점이_낮은_이력은_즐겨찾기_문구가_된다() {
			assertThat(withFailingClient().fallback(history(30, 2)))
					.isEqualTo("즐겨찾기에 담아두신 레시피예요");
		}

		@Test
		void 근거가_없으면_개인화된_말을_쓰지_않는다() {
			HomeExplanationService service = withFailingClient();

			assertThat(service.fallback(plain(false)))
					.isEqualTo("아직 만들어보지 않은 레시피예요");
			assertThat(service.fallback(plain(true)))
					.isEqualTo("즐겨찾기에 담아두셨지만 아직 안 만들어보셨어요");
		}
	}

	@Nested
	class 프롬프트 {

		private final GeminiHomeExplanationClient client =
				new GeminiHomeExplanationClient(null);

		@Test
		void 취향_근거는_근거_레시피_이름을_모델에게_넘긴다() {
			assertThat(client.prompt(List.of(taste("제육볶음"))))
					.contains("근거=취향(만족했던 요리: 제육볶음)")
					.contains("레시피=닭갈비");
		}

		@Test
		void 이력_근거는_경과_기간을_굳혀_넘긴다() {
			// 날짜를 그대로 주면 모델이 "지난 7월에" 같은 말을 지어낸다.
			assertThat(client.prompt(List.of(history(21, 5))))
					.contains("근거=이력(3주 전에 만들어 5점)");
		}

		@Test
		void 근거_없음에는_개인화_금지를_명시한다() {
			String prompt = client.prompt(List.of(plain(false)));

			assertThat(prompt).contains("근거=없음(만든 적 없음)");
			assertThat(prompt).contains("개인 취향에 맞는다는 식으로 쓰면 안 됩니다");
		}

		@Test
		void 순서를_바꾸지_말라고_못_박는다() {
			assertThat(client.prompt(List.of(taste("제육볶음"), history(21, 5))))
					.contains("순서를 바꾸거나")
					.contains("입력 순서와 같은 순서의 reasons 배열")
					.contains("1. ")
					.contains("2. ");
		}
	}
}
