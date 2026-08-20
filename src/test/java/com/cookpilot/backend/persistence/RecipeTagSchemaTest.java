package com.cookpilot.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V13 태그 스키마가 지키기로 한 불변식의 적용 테스트.
 *
 * 태그는 아직 엔티티가 없어(스키마와 사전만 들어간 단계) JdbcTemplate 으로 직접 확인한다.
 * 여기서 검증하는 것은 "어떤 잘못된 데이터가 DB 에 못 들어가는가" 다 — 애플리케이션 코드가
 * 막아주기를 기대하지 않고 제약으로 막는 것이 이 설계의 전제이기 때문이다.
 *
 * 레시피는 Flyway 시드의 고정 id 를 쓴다(모든 환경에 동일하게 존재).
 *
 * 실행 요건: Docker 데몬 필요(Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
@Transactional
class RecipeTagSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	private static final String RECIPE = "10000000-0000-0000-0000-000000000001";
	private static final String OTHER_RECIPE = "10000000-0000-0000-0000-000000000002";

	@Autowired
	private JdbcTemplate jdbc;

	private void attach(String recipeId, String tagCode, String axisCode, String assignedBy) {
		jdbc.update("""
				INSERT INTO recipe_tags (recipe_id, tag_code, axis_code, assigned_by)
				VALUES (?::uuid, ?, ?, ?)
				""", recipeId, tagCode, axisCode, assignedBy);
	}

	@Test
	void 사전이_축별로_시드된다() {
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE axis_code = 'CUISINE'", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE axis_code = 'DISH'", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE axis_code = 'METHOD'", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE axis_code = 'OCCASION'", Integer.class)).isEqualTo(8);
	}

	@Test
	void 원문에_있던_기타는_태그로_만들지_않는다() {
		// '기타' 로 도망갈 칸을 주면 축이 죽는다. 애매하면 미부여로 둔다는 결정의 적용.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE label_ko = '기타'", Integer.class)).isZero();
	}

	@Test
	void 코드값과_표시명이_분리돼_있다() {
		assertThat(jdbc.queryForObject(
				"SELECT label_ko FROM tags WHERE code = 'OCCASION_DRINKING_SNACK'", String.class))
				.isEqualTo("안주");
	}

	@Test
	void 파생_태그는_아직_없다() {
		// 두부·쉬움은 match_rule 을 가진 파생 태그 자리이고, 근거를 재기 전에는 심지 않는다.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM tags WHERE match_rule IS NOT NULL", Integer.class)).isZero();
	}

	@Test
	void 다중축은_한_레시피에_여러_개_붙는다() {
		attach(RECIPE, "OCCASION_DRINKING_SNACK", "OCCASION", "MANUAL");
		attach(RECIPE, "OCCASION_LATE_NIGHT", "OCCASION", "MANUAL");

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM recipe_tags
				WHERE recipe_id = ?::uuid AND axis_code = 'OCCASION'
				""", Integer.class, RECIPE)).isEqualTo(2);
	}

	@Test
	void 배타축은_한_레시피에_하나만_붙는다() {
		attach(RECIPE, "CUISINE_KOREAN", "CUISINE", "IMPORT");

		// 같은 레시피가 한식이면서 일식일 수는 없다.
		assertThatThrownBy(() -> attach(RECIPE, "CUISINE_JAPANESE", "CUISINE", "IMPORT"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 배타축이어도_레시피가_다르면_같은_축을_쓸_수_있다() {
		attach(RECIPE, "CUISINE_KOREAN", "CUISINE", "IMPORT");
		attach(OTHER_RECIPE, "CUISINE_JAPANESE", "CUISINE", "IMPORT");

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM recipe_tags WHERE axis_code = 'CUISINE'", Integer.class))
				.isEqualTo(2);
	}

	@Test
	void 사전에_없는_태그는_붙일_수_없다() {
		assertThatThrownBy(() -> attach(RECIPE, "OCCASION_술안주", "OCCASION", "MANUAL"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 사전과_다른_축으로는_붙일_수_없다() {
		// 복합 FK (tag_code, axis_code) 가 비정규화된 axis_code 를 사전에 묶는다.
		assertThatThrownBy(() -> attach(RECIPE, "CUISINE_KOREAN", "OCCASION", "IMPORT"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void LLM이_붙인_태그는_모델과_프롬프트_버전이_있어야_한다() {
		assertThatThrownBy(() -> attach(RECIPE, "OCCASION_DIET", "OCCASION", "LLM"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void LLM_출처가_갖춰지면_붙는다() {
		jdbc.update("""
				INSERT INTO recipe_tags
					(recipe_id, tag_code, axis_code, assigned_by, confidence, model, prompt_version)
				VALUES (?::uuid, 'OCCASION_DIET', 'OCCASION', 'LLM', 0.82, 'gemini-2.5-flash', 'tag-v1')
				""", RECIPE);

		assertThat(jdbc.queryForObject("""
				SELECT model FROM recipe_tags WHERE recipe_id = ?::uuid AND tag_code = 'OCCASION_DIET'
				""", String.class, RECIPE)).isEqualTo("gemini-2.5-flash");
	}

	@Test
	void 확신도는_0과_1_사이여야_한다() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO recipe_tags
					(recipe_id, tag_code, axis_code, assigned_by, confidence, model, prompt_version)
				VALUES (?::uuid, 'OCCASION_KIDS', 'OCCASION', 'LLM', 1.5, 'gemini-2.5-flash', 'tag-v1')
				""", RECIPE)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 알_수_없는_부여_출처는_거부된다() {
		assertThatThrownBy(() -> attach(RECIPE, "OCCASION_GUEST", "OCCASION", "GUESS"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 레시피가_지워지면_태그도_함께_지워진다() {
		// 시드 레시피는 리뷰·프로파일 등이 매달려 있어 지우면 다른 제약에 걸린다.
		// 이 테스트가 보려는 것은 recipe_tags 의 CASCADE 뿐이므로 자기 레시피를 만든다.
		String throwaway = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO recipes (id, title, base_servings) VALUES (?::uuid, '태그 CASCADE 확인용', 1)
				""", throwaway);
		attach(throwaway, "DISH_SIDE", "DISH", "IMPORT");

		jdbc.update("DELETE FROM recipes WHERE id = ?::uuid", throwaway);

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM recipe_tags WHERE recipe_id = ?::uuid", Integer.class, throwaway))
				.isZero();
	}
}
