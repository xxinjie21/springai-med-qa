package com.med.qa.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCoordinateTest {

    @Test
    void ofBuildsAndExposesSegments() {
        SessionCoordinate coord = SessionCoordinate.of("tenant-1", "dept-7", "session-9");

        assertThat(coord.tenantId()).isEqualTo("tenant-1");
        assertThat(coord.deptId()).isEqualTo("dept-7");
        assertThat(coord.sessionId()).isEqualTo("session-9");
    }

    @Test
    void roundTripParseAndToConversationId() {
        String conversationId = "tenant-1:dept-7:session-9";
        SessionCoordinate coord = SessionCoordinate.parse(conversationId);

        assertThat(coord.tenantId()).isEqualTo("tenant-1");
        assertThat(coord.deptId()).isEqualTo("dept-7");
        assertThat(coord.sessionId()).isEqualTo("session-9");
        assertThat(coord.toConversationId()).isEqualTo(conversationId);
    }

    @Test
    void equalityAndHashCodeUseAllSegments() {
        SessionCoordinate a = SessionCoordinate.of("t", "d", "s");
        SessionCoordinate b = SessionCoordinate.of("t", "d", "s");
        SessionCoordinate c = SessionCoordinate.of("t", "d", "other");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void parseRejectsWrongSegmentCount() {
        assertThatThrownBy(() -> SessionCoordinate.parse("tenant:dept"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant:dept:sessionId");
    }

    @Test
    void parseRejectsBlankSegment() {
        assertThatThrownBy(() -> SessionCoordinate.parse("tenant::session"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void parseRejectsBlankInput() {
        assertThatThrownBy(() -> SessionCoordinate.parse("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void ofRejectsBlankSegment() {
        assertThatThrownBy(() -> SessionCoordinate.of("t", "", "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deptId");
    }
}
