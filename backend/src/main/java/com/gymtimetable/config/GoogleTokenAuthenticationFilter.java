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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><strong>Note:</strong> the frontend uses Google's implicit OAuth2 flow, so the bearer
 * token is an opaque access token, not a JWT — it cannot be verified locally (e.g. via
 * JWKS) and must be checked against Google. To avoid a network round-trip to Google on
 * every single request, successful introspections are cached in-memory for a short TTL
 * (capped by the token's own remaining lifetime), so repeated requests with the same
 * token reuse the cached result instead of re-calling Google each time.</p>
 */
@Component
public class GoogleTokenAuthenticationFilter extends OncePerRequestFilter {

    /** Synchronous HTTP client used to call Google's token-introspection endpoint. */
    private final RestTemplate restTemplate = new RestTemplate();

    /** Upper bound on how long a successful introspection result is cached for. */
    private static final long MAX_CACHE_SECONDS = 300;

    /** In-memory cache of previously-introspected tokens, keyed by the raw token string. */
    private final Map<String, CachedIntrospection> introspectionCache = new ConcurrentHashMap<>();

    private record CachedIntrospection(String userId, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Intercepts each request exactly once, validates the Bearer token against Google
     * (or a cached prior result), and sets the authentication context if the token is valid.
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

            CachedIntrospection cached = introspectionCache.get(token);
            if (cached != null && !cached.isExpired()) {
                authenticate(cached.userId(), token);
            } else {
                if (cached != null) {
                    introspectionCache.remove(token);
                }
                introspectViaGoogle(token);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Calls Google's token-introspection endpoint, caches a successful result, and
     * authenticates the request. Any failure leaves the security context unauthenticated.
     */
    @SuppressWarnings("unchecked")
    private void introspectViaGoogle(String token) {
        try {
            // Introspect the token at Google's endpoint; the response body contains
            // the user_id (Google sub claim) and expires_in if the token is valid.
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=" + token, Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String userId = (String) resp.getBody().get("user_id");
                if (userId != null) {
                    long expiresInSeconds = MAX_CACHE_SECONDS;
                    Object expiresIn = resp.getBody().get("expires_in");
                    if (expiresIn instanceof Number number) {
                        expiresInSeconds = Math.min(MAX_CACHE_SECONDS, number.longValue());
                    }
                    introspectionCache.put(token,
                        new CachedIntrospection(userId, Instant.now().plusSeconds(Math.max(0, expiresInSeconds))));
                    authenticate(userId, token);
                }
            }
        } catch (Exception ignored) {
            // Any exception (network error, 4xx from Google) is silently swallowed;
            // the security context remains unauthenticated and Spring Security will
            // reject the request if the endpoint requires authentication.
        }
    }

    /**
     * Stores the Google ID as the principal so controllers can retrieve it
     * with {@code @AuthenticationPrincipal String googleId}.
     */
    private void authenticate(String userId, String token) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            userId, token, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
