package com.costlink.auth.config;

import com.costlink.common.dto.UserContext;
import com.costlink.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtUtil jwtUtil) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private OncePerRequestFilter jwtAuthFilter(JwtUtil jwtUtil) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                    HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && header.startsWith("Bearer ")) {
                    try {
                        Claims claims = jwtUtil.parseToken(header.substring(7));
                        UserContext.UserInfo user = new UserContext.UserInfo();
                        user.setUserId(toLong(claims.get("userId")));
                        user.setRole((String) claims.get("role"));
                        user.setDepartmentId(toLong(claims.get("departmentId")));
                        UserContext.set(user);
                    } catch (JwtException e) {
                        // Token 无效，跳过
                    }
                }
                try {
                    filterChain.doFilter(request, response);
                } finally {
                    UserContext.clear();
                }
            }

            private Long toLong(Object val) {
                if (val instanceof Number n) return n.longValue();
                return null;
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtUtil jwtUtil(
            @Value("${costlink.jwt.secret}") String secret,
            @Value("${costlink.jwt.access-token-ttl:30}") long accessTokenTtl,
            @Value("${costlink.jwt.refresh-token-ttl:7}") long refreshTokenTtl) {
        return new JwtUtil(secret, accessTokenTtl, refreshTokenTtl);
    }
}
