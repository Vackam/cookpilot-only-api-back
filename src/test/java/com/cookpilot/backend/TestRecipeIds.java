package com.cookpilot.backend;

import java.util.UUID;

/** Flyway V2 데모 seed를 테스트에서 참조하기 위한 고정 ID. */
public final class TestRecipeIds {

	public static final UUID RAMEN_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000001");
	public static final UUID FRIED_RICE_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000002");
	public static final UUID BRAISED_TOFU_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000003");
	public static final UUID DOENJANG_STEW_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000004");
	public static final UUID EGG_FRIED_RICE_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000005");
	public static final UUID SPICY_PORK_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000006");
	public static final UUID DAKGALBI_RECIPE_ID =
			UUID.fromString("10000000-0000-0000-0000-000000000008");

	private TestRecipeIds() {
	}
}
