package dev.rendy.kirim.risk.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rendy.kirim.risk.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

class AlertSecurityIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void noTokenIsA401() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void customersCannotSeeAlerts() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void opsCanSeeAlerts() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk());
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
