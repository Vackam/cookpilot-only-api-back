package com.cookpilot.backend.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.cookpilot.backend.ai.gemini.GeminiProperties;
import com.cookpilot.backend.recommendation.explanation.GeminiRecommendationExplanationClient;
import com.cookpilot.backend.recommendation.explanation.RecommendationExplanationService;
import com.cookpilot.backend.recommendation.feedback.RecommendationFeedbackRepository;
import com.cookpilot.backend.recommendation.profile.RecipeFlavorProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하위 패키지(explanation/feedback/profile)로 나눈 뒤에도 컴포넌트·리포지토리·
 * {@code @ConfigurationProperties} 스캔이 전부 닿는지 확인한다.
 *
 * 컴파일러가 못 잡는 런타임 배선만 보는 스모크 테스트라 Docker 없이 돈다. 기본 프로파일이
 * 이미 flyway off / ddl-auto none 이고 테스트 런타임 h2 가 임베디드 datasource 를 잡아주므로
 * 별도 프로퍼티를 주지 않는다(프로퍼티를 주면 컨텍스트 캐시가 따로 생긴다).
 */
@SpringBootTest
class RecommendationWiringTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void 하위_패키지의_빈이_모두_등록된다() {
		assertThat(context.getBean(RecommendationController.class)).isNotNull();
		assertThat(context.getBean(RecommendationService.class)).isNotNull();
		assertThat(context.getBean(RecommendationExplanationService.class)).isNotNull();
		assertThat(context.getBean(GeminiRecommendationExplanationClient.class)).isNotNull();
		assertThat(context.getBean(RecommendationFeedbackRepository.class)).isNotNull();
		assertThat(context.getBean(RecipeFlavorProfileRepository.class)).isNotNull();
	}

	@Test
	void 하위_패키지의_설정_프로퍼티가_바인딩된다() {
		GeminiProperties properties = context.getBean(GeminiProperties.class);

		// 구체 값은 단언하지 않는다 — application.yml 이 GEMINI_* 환경변수로 값을
		// 덮으므로, 값 단언은 Gemini 를 켜 둔 머신에서 배선과 무관하게 깨진다.
		// (기본값과 같은 값 단언은 prefix 오타 시 레코드 기본값 폴백으로 통과해
		// 바인딩 회귀도 못 잡는다.) 여기서는 스캔이 닿아 채워진 빈이 나오는지만 본다.
		assertThat(properties.model()).isNotBlank();
		assertThat(properties.baseUrl()).isNotBlank();
		assertThat(properties.connectTimeout()).isPositive();
		assertThat(properties.readTimeout()).isPositive();
	}
}
