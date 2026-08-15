package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the {@link MedSecurityContext} thread-local holder.
 */
class MedSecurityContextTest {

    @Test
    @DisplayName("set and get round-trip a principal")
    void setAndGet() {
        MedPrincipal principal = new MedPrincipal("hosp-1", "card", MedRole.STAFF, null);
        try {
            MedSecurityContext.setPrincipal(principal);
            assertThat(MedSecurityContext.getPrincipal()).isSameAs(principal);
        } finally {
            MedSecurityContext.clear();
        }
    }

    @Test
    @DisplayName("clear removes the principal")
    void clearRemoves() {
        MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "card", MedRole.STAFF, null));
        MedSecurityContext.clear();
        assertThat(MedSecurityContext.getPrincipal()).isNull();
    }

    @Test
    @DisplayName("setting a null principal clears the context")
    void nullPrincipalClears() {
        MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "card", MedRole.STAFF, null));
        MedSecurityContext.setPrincipal(null);
        assertThat(MedSecurityContext.getPrincipal()).isNull();
    }

    @Test
    @DisplayName("an untouched context has no principal")
    void untouchedIsEmpty() {
        MedSecurityContext.clear();
        assertThat(MedSecurityContext.getPrincipal()).isNull();
    }
}
