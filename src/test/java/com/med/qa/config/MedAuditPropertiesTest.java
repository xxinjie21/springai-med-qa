package com.med.qa.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MedAuditProperties}: safe defaults, the master switch, and the fail-fast
 * truncation limits that must stay within the physical column widths.
 */
class MedAuditPropertiesTest {

    @Test
    @DisplayName("defaults enable auditing and match the column ceilings")
    void defaults() {
        MedAuditProperties properties = new MedAuditProperties();
        assertTrue(properties.isEnabled());
        assertEquals(100, properties.getOrder());
        assertEquals(MedAuditProperties.ACTION_COLUMN_LENGTH, properties.getMaxActionLength());
        assertEquals(MedAuditProperties.RESOURCE_TYPE_COLUMN_LENGTH, properties.getMaxResourceTypeLength());
        assertEquals(MedAuditProperties.RESOURCE_ID_COLUMN_LENGTH, properties.getMaxResourceIdLength());
        assertEquals(MedAuditProperties.MESSAGE_COLUMN_LENGTH, properties.getMaxMessageLength());
    }

    @Test
    @DisplayName("master switch toggles")
    void masterSwitch() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setEnabled(false);
        assertFalse(properties.isEnabled());
        properties.setEnabled(true);
        assertTrue(properties.isEnabled());
    }

    @Test
    @DisplayName("advice order is freely settable")
    void orderIsSettable() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setOrder(-5);
        assertEquals(-5, properties.getOrder());
        properties.setOrder(0);
        assertEquals(0, properties.getOrder());
    }

    @Test
    @DisplayName("maxActionLength accepts a positive value within the column and rejects 0 / overflow")
    void maxActionLengthBoundaries() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setMaxActionLength(10);
        assertEquals(10, properties.getMaxActionLength());
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxActionLength(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxActionLength(MedAuditProperties.ACTION_COLUMN_LENGTH + 1));
    }

    @Test
    @DisplayName("maxResourceTypeLength accepts a positive value within the column and rejects 0 / overflow")
    void maxResourceTypeLengthBoundaries() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setMaxResourceTypeLength(10);
        assertEquals(10, properties.getMaxResourceTypeLength());
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxResourceTypeLength(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxResourceTypeLength(MedAuditProperties.RESOURCE_TYPE_COLUMN_LENGTH + 1));
    }

    @Test
    @DisplayName("maxResourceIdLength accepts a positive value within the column and rejects 0 / overflow")
    void maxResourceIdLengthBoundaries() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setMaxResourceIdLength(10);
        assertEquals(10, properties.getMaxResourceIdLength());
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxResourceIdLength(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxResourceIdLength(MedAuditProperties.RESOURCE_ID_COLUMN_LENGTH + 1));
    }

    @Test
    @DisplayName("maxMessageLength accepts a positive value within the column and rejects 0 / overflow")
    void maxMessageLengthBoundaries() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setMaxMessageLength(10);
        assertEquals(10, properties.getMaxMessageLength());
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxMessageLength(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxMessageLength(MedAuditProperties.MESSAGE_COLUMN_LENGTH + 1));
    }
}
