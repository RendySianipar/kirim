package dev.rendy.kirim.support

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Request post-processors that put an already-validated JWT on a MockMvc
 * request, so tests don't need a running Keycloak. The subject is the
 * Keycloak user id that account ownership is checked against.
 */
object Auth {
    fun customer(subject: String): RequestPostProcessor =
        jwt().jwt { it.subject(subject).claim("preferred_username", subject) }
            .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

    fun ops(subject: String = "ops-user"): RequestPostProcessor =
        jwt().jwt { it.subject(subject).claim("preferred_username", subject) }
            .authorities(SimpleGrantedAuthority("ROLE_OPS"))

    /** A valid token whose user has no Kirim role at all. */
    fun noRole(subject: String = "stranger"): RequestPostProcessor =
        jwt().jwt { it.subject(subject) }.authorities(emptyList<GrantedAuthority>())
}
