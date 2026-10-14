package com.Aryan.APIX.security;


import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.repository.UserRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${apix.oauth.frontend-success-url:https://apix-xray.vercel.app/oauth-success}")
    private String frontendSuccessUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        OAuth2User oAuth2User=(OAuth2User) authentication.getPrincipal();
        String email=oAuth2User.getAttribute("email");
        String name=oAuth2User.getAttribute("name");
        String providerId=oAuth2User.getAttribute("sub");


        Optional<User> existing=userRepository.findByEmail(email);

        User user=existing.orElseGet(()->{
            User newUser=new User(name,email,"GOOGLE",providerId);
            return userRepository.save(newUser);
        });

        String token= jwtUtil.generateToken(user.getEmail());

        response.sendRedirect(frontendSuccessUrl + "?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
    }
}
