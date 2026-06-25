package com.gymtimetable.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Servlet filter that validates incoming Google OAuth 2.0 access tokens and populates
 * the Spring Security context with the authenticated user's Google ID.
 *
 * <p>On every request that carries a {@code Bearer} token, this filter contacts Google's
 * token-introspection endpoint ({@code /oauth2/v1/tokeninfo}) to verify the token and
 * retrieve the {@code user_id} claim. A successful verification results in a
 * {@link UsernamePasswordAuthenticationToken} being placed in the
 * {@link SecurityContextHolder}, allowing downstream controllers to receive the Google ID
 * via {@code @AuthenticationPrincipal String googleId}.</p>
 *
 * <p>If the token is absent, invalid, or the introspection call fails for any reason,
 * no authentication object is set. The request then proceeds to the filter chain and will
 * be rejected by Spring Security for protected endpoints.</p>
 *
 * <p><strong>Note:</strong> Token validation is performed synchronously via a remote HTTP
 * call on every authenticated request. This introduces latency proportional to the
 * round-trip to Google's servers. A caching layer or local JWT verification would be
 * preferable for high-throughput environments.</p>
 */
@Component
public class GoogleTokenAuthenticationFilter extends OncePerRequestFilter {

    /** Synchronous HTTP client used to call Google's token-introspection endpoint. */
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Intercepts each request exactly once, validates the Bearer token against Google,
     * and sets the authentication context if the token is valid.
     *
     * @param request     the incoming HTTP request
     * @param response    the outgoing HTTP response
     * @param filterChain the remaining filter chain to delegate to after processing
     * @throws ServletException if a servlet-level error occurs
     * @throws IOException      if an I/O error occurs while reading or writing
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                // Introspect the token at Google's endpoint; the response body contains
                // the user_id (Google sub claim) if the token is valid and not expired.
                ResponseEntity<Map> resp = restTemplate.getForEntity(
                    "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=" + token, Map.class);

                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    String userId = (String) resp.getBody().get("user_id");
                    if (userId != null) {
                        // Store the Google ID as the principal so controllers can retrieve it
                        // with @AuthenticationPrincipal String googleId.
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            userId, token, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception ignored) {
                // Any exception (network error, 4xx from Google) is silently swallowed;
                // the security context remains unauthenticated and Spring Security will
                // reject the request if the endpoint requires authentication.
            }
        }
        filterChain.doFilter(request, response);
    }
}
