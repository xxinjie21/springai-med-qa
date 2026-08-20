package com.med.qa.privacy.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that {@link SensitiveContact} is masked when serialized by Jackson (D24).
 * No Spring context or middleware involved — a plain {@link ObjectMapper} exercises the
 * {@code @Desensitize} annotation through {@code DesensitizeSerializer}.
 */
class SensitiveContactSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("positive: every annotated field is masked on serialization")
    void allAnnotatedFieldsAreMasked() throws Exception {
        SensitiveContact contact = new SensitiveContact("13812345678", "11010119900307123X", "MRN20240012345");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(contact));

        String phone = node.get("phone").asText();
        String idCard = node.get("idCard").asText();
        String mrn = node.get("medicalRecordNo").asText();

        assertTrue(phone.startsWith("138") && phone.contains("****"), "phone masked");
        assertTrue(idCard.startsWith("110101") && idCard.contains("****"), "idCard masked");
        assertTrue(mrn.startsWith("MR") && mrn.contains("**"), "medical record number masked");
        assertFalse(phone.contains("12345678"), "raw phone digits must not leak");
        assertFalse(idCard.contains("19900307"), "raw id-card digits must not leak");
    }

    @Test
    @DisplayName("boundary: a null field is serialized as JSON null, not as a masked string")
    void nullFieldSerializesAsNull() throws Exception {
        SensitiveContact contact = new SensitiveContact(null, "11010119900307123X", "MRN20240012345");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(contact));

        assertTrue(node.get("phone").isNull());
        assertEquals("MRN20240012345".length(), node.get("medicalRecordNo").asText().length());
    }

    @Test
    @DisplayName("positive: the raw values survive in memory before serialization")
    void rawValuesArePreservedInMemory() {
        SensitiveContact contact = new SensitiveContact("13812345678", "11010119900307123X", "MRN20240012345");

        assertEquals("13812345678", contact.getPhone());
        assertEquals("11010119900307123X", contact.getIdCard());
        assertEquals("MRN20240012345", contact.getMedicalRecordNo());
    }
}
