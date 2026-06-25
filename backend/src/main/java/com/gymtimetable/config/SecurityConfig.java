package com.gymtimetable.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the Work Timetable Manager API.
 *
 * <p>Configures a stateless, token-based security model where every request must carry
 * a valid Google OAuth 2.0 access token in the {@code Authorization: Bearer <token>} header.
 * Token validation is delegated to {@link GoogleTokenAuthenticationFilter}, which contacts
 * Google's introspection endpoint and populates the security context with the user's
 * Google ID on success.</p>
 *
 * <p>CSRF protection is disabled because the application is a stateless REST API with no
 * server-managed session cookies — all state is held client-side via localStorage tokens.</p>
 *
 * <p>CORS is configured from the {@code cors.allowed-origins} application property, which
 * supports a comma-separated list of origins. This allows the same Docker image to serve
 * both staging and production deployments without code changes.</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Comma-separated list of allowed CORS origins, injected from the
     * {@code cors.allowed-origins} application property.
     */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    /** Custom filter that validates Google access tokens on every incoming request. */
    private final GoogleTokenAuthenticationFilter googleTokenFilter;

    /**
     * Defines the HTTP security filter chain for all API endpoints.
     *
     * <p>Public endpoints:</p>
     * <ul>
     *   <li>{@code OPTIONS /**} — preflight CORS requests must not require authentication.</li>
     *   <li>{@code POST /api/auth/login} — the login endpoint itself carries the token in the
     *       body and must be accessible before the security context is fully established.</li>
     *   <li>{@code GET /actuator/health} — health check for deployment platforms (Railway).</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring
     * @return the fully configured {@link SecurityFilterChain}
     * @throws Exception if any Spring Security configuration step fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
            )
            .addFilterBefore(googleTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Builds the CORS configuration applied globally to all API routes.
     *
     * <p>Allowed origins are read from the {@code cors.allowed-origins} property and split
     * on commas, enabling multiple origins (e.g., a Vercel preview URL and the production
     * domain) to be listed in a single environment variable.</p>
     *
     * <p>Credentials ({@code withCredentials: true} in Axios) are permitted so that the
     * browser can attach cookies alongside the Authorization header if needed in the future.</p>
     *
     * @return a {@link CorsConfigurationSource} with the fully populated CORS rules
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
