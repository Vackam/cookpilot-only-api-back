package com.cookpilot.backend.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 목록 응답의 페이지 envelope. Spring 의 {@code PageImpl} 은 직렬화 형태가 불안정해서
 * 그대로 반환하지 않고, 이 코드베이스 관례대로 평범한 record 로 감싼다.
 */
public record PagedResponse<T>(
		List<T> items,
		int page,
		int size,
		long totalElements,
		boolean hasNext
) {

	public static <T> PagedResponse<T> of(Page<?> page, List<T> items) {
		return new PagedResponse<>(
				items,
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.hasNext());
	}
}
