package com.cookpilot.backend.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 다음 조리 추천의 판정 규칙(순수 함수, DB 무관).
 *
 * 입력은 엔티티가 아니라 값 타입이라 DB 없이 단위 테스트할 수 있다.
 * 조회·트랜잭션은 {@link RecommendationDraftLoader} 가 맡고, 이 클래스는 "어떤 근거를
 * 채택하고 얼마를 제안할지"만 결정한다. 추천 임계값은 전부 여기 모여 있다.
 */
public final class RecommendationRuleEngine {

	/** 추천 1건에 필요한 최소 근거 조리 횟수. 1회는 우연일 수 있어 2회부터 본다. */
	private static final int MIN_EVIDENCE_COUNT = 2;

	/** 한 레시피에 동시에 노출할 최대 추천 개수. 정렬·자르기는 호출부가 한다. */
	static final int MAX_RECOMMENDATIONS = 3;

	/** 추천 1건에 함께 내려보낼 최대 근거 개수(응답 크기 제한). */
	private static final int MAX_EVIDENCE_PER_RECOMMENDATION = 5;

	/**
	 * 다른 레시피의 조리 기록을 근거로 인정할 최소 맛 프로파일 유사도.
	 *
	 * {@link #profileSimilarity} 의 가중치와 맞물린 값이다. 0.60 은 사실상
	 * "소스 베이스(0.45) + 같은 국가 요리(0.25) = 0.70" 조합만 통과시킨다.
	 * cuisine + dishType + cookingMethod 를 다 맞춰도 0.55 라 탈락한다.
	 * 즉 "소스 계열이 같은 같은 국가 요리"만 교차 근거로 쓰겠다는 뜻.
	 */
	private static final double MIN_PROFILE_SIMILARITY = 0.60;

	/** 이 정도 미만의 양 변화는 사용자가 체감하지 못한다고 보고 추천하지 않는다. */
	private static final double MIN_MEANINGFUL_RATIO_DIFFERENCE = 0.05;

	/** 양 조정 비율의 허용 범위. 밖으로 나가면 오타/실험으로 보고 근거에서 제외한다. */
	private static final double MIN_USABLE_RATIO = 0.25;
	private static final double MAX_USABLE_RATIO = 2.0;

	private RecommendationRuleEngine() {
	}

	/** 판정 입력용 재료(엔티티/인메모리 어느 쪽에서든 만들 수 있는 값 타입). */
	record IngredientSnapshot(UUID id, String name, BigDecimal amount, String unit) {
	}

	/** 판정 입력용 맛 프로파일. 유사도 계산에 쓰는 4개 축만 담는다. */
	public record FlavorProfile(
			String cuisine,
			String dishType,
			List<String> cookingMethods,
			List<String> sauceBases
	) {
	}

	/** 근거 1건: 그 조리에서 관측된 양 조정 비율 + 그 기록의 프로파일 유사도. */
	record EvidenceSample(
			BigDecimal ratio,
			double profileSimilarity,
			RecommendationEvidence evidence
	) {
	}

	/** 판정 결과. 설명 문구가 붙기 전 단계. */
	record Draft(
			IngredientSnapshot targetIngredient,
			BigDecimal suggestedAmount,
			int changePercent,
			BigDecimal confidence,
			List<RecommendationEvidence> evidence
	) {
	}

	/**
	 * 대상 레시피와 근거 레시피의 맛 프로파일 유사도(0~1).
	 *
	 * 같은 레시피면 taxonomy 를 보지 않고 1 이다. 즉 프로파일 값은 "다른 레시피의
	 * 기록을 끌어올 때"만 실제로 영향을 준다.
	 */
	public static double profileSimilarity(
			FlavorProfile target, FlavorProfile source, boolean sameRecipe) {
		if (source == null) {
			return 0;
		}
		if (sameRecipe) {
			return 1;
		}
		double score = 0;
		if (sameText(target.cuisine(), source.cuisine())) {
			score += 0.25;
		}
		if (sameText(target.dishType(), source.dishType())) {
			score += 0.15;
		}
		if (intersects(target.cookingMethods(), source.cookingMethods())) {
			score += 0.15;
		}
		if (intersects(target.sauceBases(), source.sauceBases())) {
			score += 0.45;
		}
		return Math.min(1, score);
	}

	/** 근거로 인정할 만큼 프로파일이 가까운가. */
	static boolean passesSimilarity(double similarity) {
		return similarity >= MIN_PROFILE_SIMILARITY;
	}

	/** 근거로 쓸 수 있는 양 조정 비율인가(범위 밖이거나 사실상 변화 없음이면 false). */
	static boolean isUsableRatio(double ratio) {
		return ratio >= MIN_USABLE_RATIO
				&& ratio <= MAX_USABLE_RATIO
				&& Math.abs(ratio - 1.0) >= MIN_MEANINGFUL_RATIO_DIFFERENCE;
	}

	/**
	 * 근거들을 합쳐 추천 1건을 만든다. 조건 미달이면 {@code null}.
	 *
	 * 같은 리뷰가 여러 번 들어오면 최신 것 하나만 남기고, 유사도를 가중치로 쓴
	 * 가중평균 비율로 추천 양을 낸다.
	 */
	static Draft draft(IngredientSnapshot target, List<EvidenceSample> samples) {
		Map<UUID, EvidenceSample> uniqueByReview = new LinkedHashMap<>();
		samples.stream()
				.sorted(Comparator.comparing(
						(EvidenceSample sample) -> sample.evidence().cookedAt()).reversed())
				.forEach(sample -> uniqueByReview.putIfAbsent(
						sample.evidence().reviewId(), sample));
		List<EvidenceSample> uniqueSamples = List.copyOf(uniqueByReview.values());
		if (uniqueSamples.size() < MIN_EVIDENCE_COUNT) {
			return null;
		}

		double totalWeight = uniqueSamples.stream()
				.mapToDouble(EvidenceSample::profileSimilarity)
				.sum();
		// 유사도가 전부 0이면 가중평균이 정의되지 않는다(0으로 나눠 NaN). 호출자가
		// passesSimilarity 로 걸러 주는지와 무관하게 여기서 방어한다.
		if (totalWeight <= 0) {
			return null;
		}
		double weightedRatio = uniqueSamples.stream()
				.mapToDouble(sample ->
						sample.ratio().doubleValue() * sample.profileSimilarity())
				.sum() / totalWeight;
		if (Math.abs(weightedRatio - 1.0) < MIN_MEANINGFUL_RATIO_DIFFERENCE) {
			return null;
		}

		BigDecimal suggestedAmount = target.amount()
				.multiply(BigDecimal.valueOf(weightedRatio))
				.setScale(2, RoundingMode.HALF_UP)
				.stripTrailingZeros();
		int changePercent = (int) Math.round((weightedRatio - 1.0) * 100);
		double averageSimilarity = totalWeight / uniqueSamples.size();
		BigDecimal confidence = BigDecimal.valueOf(Math.min(
						0.95,
						0.45 + uniqueSamples.size() * 0.10 + averageSimilarity * 0.20))
				.setScale(2, RoundingMode.HALF_UP);
		List<RecommendationEvidence> evidence = uniqueSamples.stream()
				.map(EvidenceSample::evidence)
				.limit(MAX_EVIDENCE_PER_RECOMMENDATION)
				.toList();
		return new Draft(target, suggestedAmount, changePercent, confidence, evidence);
	}

	/**
	 * 이미 거절한 추천을 다시 올릴지 판정한다.
	 *
	 * 거절 이후 새로 쌓인 조리 기록이 있으면 다시 제안한다. 호출자는 최신 피드백이
	 * 거절일 때만 그 시각을 넘기고, 아니면 {@code null} 을 넘긴다.
	 */
	static boolean isRejectedWithoutNewEvidence(
			Instant latestRejectedAt, List<RecommendationEvidence> evidence) {
		if (latestRejectedAt == null) {
			return false;
		}
		Instant latestEvidenceAt = evidence.stream()
				.map(RecommendationEvidence::cookedAt)
				.max(Comparator.naturalOrder())
				.orElse(Instant.MIN);
		return !latestRejectedAt.isBefore(latestEvidenceAt);
	}

	/** 재료명 대조용 정규화(공백 제거 + 소문자). */
	static String normalizeName(String value) {
		return value == null
				? ""
				: value.replaceAll("\\s+", "").toLowerCase(Locale.KOREAN);
	}

	/** null 안전 대소문자 무시 비교. */
	static boolean sameText(String left, String right) {
		return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
	}

	private static boolean intersects(Collection<String> left, Collection<String> right) {
		Set<String> normalized = new HashSet<>();
		left.forEach(value -> normalized.add(value.toUpperCase(Locale.ROOT)));
		return right.stream()
				.map(value -> value.toUpperCase(Locale.ROOT))
				.anyMatch(normalized::contains);
	}
}
