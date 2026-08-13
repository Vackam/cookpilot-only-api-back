package com.cookpilot.backend.ai;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/ai-sessions")
public class AiLiveSessionController {

	private final AiLiveSessionService aiLiveSessionService;

	public AiLiveSessionController(AiLiveSessionService aiLiveSessionService) {
		this.aiLiveSessionService = aiLiveSessionService;
	}

	@PostMapping
	public AiLiveSessionResponse open(@Valid @RequestBody AiLiveSessionRequest request) {
		return aiLiveSessionService.open(request.recipeId());
	}
}
