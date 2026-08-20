package com.cookpilot.backend.recipe;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeRepository extends JpaRepository<RecipeEntity, UUID> {

	List<RecipeEntity> findByStatusOrderByTitleAscIdAsc(String status);

	// 메서드명이 정렬을 정의하므로 Pageable 에 Sort 를 넣지 않아도 title, id 순서가 유지된다.
	Page<RecipeEntity> findByStatusOrderByTitleAscIdAsc(String status, Pageable pageable);

	// recipe_tags/tags/recipe_hashtags 는 엔티티가 없어 JPQL 로 조인할 수 없다 — native 로 간다.
	// 분류 필터는 tags.label_ko 정확 일치: 사전에 없는 값은 매치가 없어 0건이 된다(400 아님).
	String SEARCH_WHERE = """
			WHERE r.status = :status
			  AND (:title = '' OR LOWER(r.title) LIKE LOWER('%' || :title || '%') ESCAPE '\\')
			  AND (:ingredient = '' OR EXISTS (
				SELECT 1 FROM recipe_ingredients ri
				WHERE ri.recipe_id = r.id
				  AND LOWER(ri.name) LIKE LOWER('%' || :ingredient || '%') ESCAPE '\\'
			  ))
			  AND (:cookingMethod = '' OR EXISTS (
				SELECT 1 FROM recipe_tags rt JOIN tags t ON t.code = rt.tag_code
				WHERE rt.recipe_id = r.id AND t.axis_code = 'METHOD' AND t.label_ko = :cookingMethod
			  ))
			  AND (:dishType = '' OR EXISTS (
				SELECT 1 FROM recipe_tags rt JOIN tags t ON t.code = rt.tag_code
				WHERE rt.recipe_id = r.id AND t.axis_code = 'DISH' AND t.label_ko = :dishType
			  ))
			  AND (:hashtag = '' OR EXISTS (
				SELECT 1 FROM recipe_hashtags rh
				WHERE rh.recipe_id = r.id AND rh.tag = :hashtag
			  ))
			""";

	@Query(value = "SELECT r.* FROM recipes r " + SEARCH_WHERE + " ORDER BY r.title ASC, r.id ASC",
			countQuery = "SELECT COUNT(*) FROM recipes r " + SEARCH_WHERE,
			nativeQuery = true)
	Page<RecipeEntity> search(
			@Param("status") String status,
			@Param("title") String title,
			@Param("ingredient") String ingredient,
			@Param("cookingMethod") String cookingMethod,
			@Param("dishType") String dishType,
			@Param("hashtag") String hashtag,
			Pageable pageable);
}
