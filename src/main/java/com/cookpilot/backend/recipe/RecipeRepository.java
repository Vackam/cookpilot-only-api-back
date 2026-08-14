package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeRepository extends JpaRepository<RecipeEntity, UUID> {

	List<RecipeEntity> findByStatusOrderByTitleAscIdAsc(String status);

	// 메서드명이 정렬을 정의하므로 Pageable 에 Sort 를 넣지 않아도 title, id 순서가 유지된다.
	Page<RecipeEntity> findByStatusOrderByTitleAscIdAsc(String status, Pageable pageable);
}
