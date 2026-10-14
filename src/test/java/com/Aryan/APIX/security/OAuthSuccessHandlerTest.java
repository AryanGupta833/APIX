package com.Aryan.APIX.security;

import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthSuccessHandlerTest {
    @Test
    void redirectsToConfiguredLocalFrontendAfterGoogleLogin() throws Exception {
        UserRepository users = mock(UserRepository.class);
        JwtUtil jwt = new JwtUtil();
        OAuthSuccessHandler handler = new OAuthSuccessHandler();
        ReflectionTestUtils.setField(handler, "userRepository", users);
        ReflectionTestUtils.setField(handler, "jwtUtil", jwt);
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "http://localhost:5174/oauth-success");

        User user = new User(); user.setEmail("dev@example.test"); user.setName("Local Developer");
        when(users.findByEmail("dev@example.test")).thenReturn(Optional.of(user));
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("dev@example.test");
        when(principal.getAttribute("name")).thenReturn("Local Developer");
        when(principal.getAttribute("sub")).thenReturn("google-subject");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(null, response, authentication);

        assertTrue(response.getRedirectedUrl().startsWith("http://localhost:5174/oauth-success?token="));
    }
}
