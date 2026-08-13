package com.cookpilot.backend.home.explanation;

import java.util.List;
import java.util.Optional;

/**
 * 메인 화면 추천 문구 생성기. 구현이 실패하면 예외 대신 empty 를 돌려주고, 호출부가
 * 규칙 기반 fallback 문구를 쓴다.
 */
public interface HomeExplanationClient {

	Optional<List<String>> explainAll(List<HomeExplanationContext> contexts);

	String model();
}
