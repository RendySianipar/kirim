package dev.rendy.kirim.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import jakarta.servlet.http.HttpServletResponse

/**
 * The API is an OAuth2 resource server: every request under /api carries a
 * Keycloak access token (`Authorization: Bearer ...`). Spring checks its
 * signature (keys from Keycloak's JWKS endpoint), expiry and issuer; this class
 * maps Keycloak's realm roles to Spring roles and decides who may call what.
 *
 * Coarse rules live here (by URL); rules that need data ("is this your
 * account?") live in [AccountAccess], next to the code that loads the data.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, objectMapper: ObjectMapper): SecurityFilterChain {
        http
            // Stateless bearer-token API: no cookies, no sessions, so no CSRF attack surface.
            .csrf { it.disable() }
            .cors(Customizer.withDefaults()) // uses the MVC CORS mapping in AppConfig
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                    "/error", // Spring Boot's error page; denying it would turn every 404/500 into 401
                ).permitAll()
                    .requestMatchers("/api/v1/reconciliations/**").hasRole(Roles.OPS)
                    .requestMatchers("/api/**").hasAnyRole(Roles.CUSTOMER, Roles.OPS)
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(KeycloakRealmRoleConverter()) }
                it.authenticationEntryPoint { _, response, _ ->
                    response.setHeader("WWW-Authenticate", "Bearer")
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "A valid access token is required (Authorization: Bearer ...)")
                }
            }
            .exceptionHandling {
                it.accessDeniedHandler { _, response, _ ->
                    writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Your role does not allow this request")
                }
            }
        return http.build()
    }

    /** 401/403 leave the API as Problem Details too, like every other error. */
    private fun writeProblem(response: HttpServletResponse, objectMapper: ObjectMapper, status: HttpStatus, code: String, detail: String) {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.outputStream, problem)
    }
}

object Roles {
    const val CUSTOMER = "CUSTOMER"
    const val OPS = "OPS"
}

/**
 * Keycloak puts realm roles in the `realm_access.roles` claim ("customer",
 * "ops"); Spring expects authorities named ROLE_CUSTOMER, ROLE_OPS.
 */
class KeycloakRealmRoleConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val realmAccess = jwt.getClaimAsMap("realm_access") ?: emptyMap<String, Any>()
        val roles = (realmAccess["roles"] as? Collection<*>).orEmpty()
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_" + it.toString().uppercase()) }
        return JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username") ?: jwt.subject)
    }
}
