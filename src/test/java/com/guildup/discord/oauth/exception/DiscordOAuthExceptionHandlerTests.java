package com.guildup.discord.oauth.exception;

import com.guildup.discord.oauth.controller.DiscordOAuthController;
import com.guildup.discord.oauth.service.DiscordOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiscordOAuthExceptionHandlerTests {

    @Test
    void returnsServiceUnavailableJsonForMissingOAuthConfiguration() throws Exception {
        DiscordOAuthService oauthService = mock(DiscordOAuthService.class);
        when(oauthService.createAuthorizationUrl(1L))
                .thenThrow(new DiscordOAuthConfigurationException("DISCORD_CLIENT_ID"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new DiscordOAuthController(oauthService, mock(com.guildup.community.service.CommunityAccessService.class)))
                .setControllerAdvice(new DiscordOAuthExceptionHandler(
                        mock(com.guildup.community.repository.DiscordCommunityConnectionRepository.class)
                ))
                .build();

        mockMvc.perform(get("/api/communities/1/discord/oauth/authorize").sessionAttr("LOGIN_USER_ID", 10L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(
                        "Discord 인증을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
                ));
    }
}
