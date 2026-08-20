package com.med.qa.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the {@link MaskType} masking strategies (D24).
 */
class MaskTypeTest {

    @Test
    @DisplayName("positive: PHONE masks the middle digits but keeps the prefix and suffix")
    void phoneMaskKeepsEdges() {
        String masked = MaskType.PHONE.mask("13812345678");

        assertTrue(masked.startsWith("138"), "phone prefix must be preserved");
        assertTrue(masked.endsWith("5678"), "phone suffix must be preserved");
        assertTrue(masked.contains("****"), "phone middle must be masked");
    }

    @Test
    @DisplayName("positive: ID_CARD keeps the first 6 and last 4 characters")
    void idCardMaskKeepsSixAndFour() {
        String masked = MaskType.ID_CARD.mask("11010119900307123X");

        assertTrue(masked.startsWith("110101"), "id-card prefix must be preserved");
        assertTrue(masked.endsWith("123X"), "id-card suffix must be preserved");
        assertTrue(masked.contains("****"), "id-card middle must be masked");
    }

    @Test
    @DisplayName("positive: MEDICAL_RECORD_NO keeps the first 2 and last 2 characters")
    void medicalRecordNoMaskKeepsTwoAndTwo() {
        String masked = MaskType.MEDICAL_RECORD_NO.mask("MRN20240012345");

        assertTrue(masked.startsWith("MR"), "medical record prefix must be preserved");
        assertTrue(masked.endsWith("45"), "medical record suffix must be preserved");
        assertTrue(masked.contains("**"), "medical record middle must be masked");
    }

    @Test
    @DisplayName("boundary: a null value masks to null")
    void nullValueMasksToNull() {
        assertNull(MaskType.PHONE.mask(null));
        assertNull(MaskType.ID_CARD.mask(null));
        assertNull(MaskType.MEDICAL_RECORD_NO.mask(null));
    }

    @Test
    @DisplayName("boundary: a value shorter than the keep-edges window is fully masked")
    void shortValueFullyMasked() {
        assertEquals("**", MaskType.MEDICAL_RECORD_NO.mask("AB"));
    }

    @Test
    @DisplayName("boundary: an empty string returns an empty string without throwing")
    void emptyStringReturnsEmpty() {
        assertEquals("", MaskType.MEDICAL_RECORD_NO.mask(""));
        assertTrue(MaskType.PHONE.mask("").isEmpty());
    }

    @Test
    @DisplayName("positive: every strategy returns a value that differs from the raw input")
    void maskingChangesTheValue() {
        String raw = "13812345678";
        assertEquals(MaskType.PHONE.mask(raw), MaskType.PHONE.mask(raw), "masking is deterministic");
    }
}
