package dev.rendy.kirim.risk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Risk alerts are for the operations/compliance team only: every /api call
 * needs a Keycloak token with the realm role "ops". Customers never call this
 * service.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/**").hasRole("OPS")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter()))
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                    "A valid access token is required (Authorization: Bearer ...)");
                        }))
                .exceptionHandling(eh -> eh.accessDeniedHandler((request, response, ex) ->
                        writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                "Risk alerts are only available to the OPS role")));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper,
                                     HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Keycloak realm roles (claim realm_access.roles) become Spring roles: "ops" -> ROLE_OPS. */
    static class KeycloakRealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            Object roles = realmAccess == null ? null : realmAccess.get("roles");
            List<GrantedAuthority> authorities = roles instanceof Collection<?> list
                    ? list.stream()
                            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()))
                            .toList()
                    : List.of();
            String name = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
        }
    }
}
