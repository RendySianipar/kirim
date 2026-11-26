package dev.rendy.kirim.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

@Configuration
class AppConfig : WebMvcConfigurer {

    /** Injecting a Clock (instead of calling Instant.now()) keeps time-based logic testable. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Kirim Transfer API")
                .version("v1")
                .description("Peer-to-peer transfers with idempotency keys, a transactional outbox and Kafka events."),
        )
        // Swagger UI gets an "Authorize" button: paste a Keycloak access token.
        .components(Components().addSecuritySchemes("bearer", SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
        .addSecurityItem(SecurityRequirement().addList("bearer"))

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173")
            .allowedMethods("GET", "POST")
            .exposedHeaders("Idempotent-Replayed")
    }
}
