package com.med.qa.privacy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.med.qa.privacy.annotation.Desensitize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link DesensitizeSerializer} behaviour (D24) — direct serialization and the
 * contextual resolution of the masking strategy from the property annotation.
 */
class DesensitizeSerializerTest {

    @Test
    @DisplayName("positive: a typed serializer masks the value through the resolved strategy")
    void typedSerializerMasksValue() throws Exception {
        JsonGenerator generator = mock(JsonGenerator.class);
        SerializerProvider provider = mock(SerializerProvider.class);

        new DesensitizeSerializer(MaskType.PHONE).serialize("13812345678", generator, provider);

        verify(generator).writeString(org.mockito.ArgumentMatchers.argThat(
                (String s) -> s.startsWith("138") && s.contains("****")));
    }

    @Test
    @DisplayName("boundary: a null value is written as JSON null, never as a masked string")
    void nullValueWrittenAsNull() throws Exception {
        JsonGenerator generator = mock(JsonGenerator.class);
        SerializerProvider provider = mock(SerializerProvider.class);

        new DesensitizeSerializer(MaskType.ID_CARD).serialize(null, generator, provider);

        verify(generator).writeNull();
    }

    @Test
    @DisplayName("pass-through: the default serializer writes the raw value unchanged")
    void passThroughWritesRawValue() throws Exception {
        JsonGenerator generator = mock(JsonGenerator.class);
        SerializerProvider provider = mock(SerializerProvider.class);

        new DesensitizeSerializer().serialize("plain-text", generator, provider);

        verify(generator).writeString("plain-text");
    }

    @Test
    @DisplayName("contextual: a null property falls back to the pass-through serializer")
    void contextualWithNullPropertyReturnsSelf() throws Exception {
        SerializerProvider provider = mock(SerializerProvider.class);
        DesensitizeSerializer base = new DesensitizeSerializer();

        JsonSerializer<?> result = base.createContextual(provider, null);

        assertSame(base, result, "a null property must return the same (pass-through) serializer");
        assertInstanceOf(DesensitizeSerializer.class, result);
    }

    @Test
    @DisplayName("contextual: a property without the annotation is left pass-through")
    void contextualWithoutAnnotationReturnsSelf() throws Exception {
        BeanProperty property = mock(BeanProperty.class);
        when(property.getAnnotation(Desensitize.class)).thenReturn(null);
        SerializerProvider provider = mock(SerializerProvider.class);

        JsonSerializer<?> result = new DesensitizeSerializer().createContextual(provider, property);

        assertInstanceOf(DesensitizeSerializer.class, result);
    }

    @Test
    @DisplayName("contextual: a property carrying the annotation yields a typed serializer")
    void contextualWithAnnotationResolvesMaskType() throws Exception {
        BeanProperty property = mock(BeanProperty.class);
        Desensitize annotation = mock(Desensitize.class);
        when(annotation.value()).thenReturn(MaskType.MEDICAL_RECORD_NO);
        when(property.getAnnotation(Desensitize.class)).thenReturn(annotation);
        SerializerProvider provider = mock(SerializerProvider.class);

        JsonSerializer<?> result = new DesensitizeSerializer().createContextual(provider, property);

        assertInstanceOf(DesensitizeSerializer.class, result);
    }
}
