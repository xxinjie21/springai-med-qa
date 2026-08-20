package com.med.qa.privacy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.med.qa.privacy.annotation.Desensitize;

import java.io.IOException;

/**
 * Jackson serializer that masks {@code String} values according to {@link Desensitize} (D24).
 *
 * <p>Implemented as a {@link ContextualSerializer} so Jackson hands this serializer the
 * {@link BeanProperty} it is about to serialize; the masking strategy is then read from the
 * field/getter annotation. When the property carries no {@link Desensitize} annotation the plain
 * value is written (this pass-through branch is only reached when the serializer is referenced
 * directly rather than through the annotation).</p>
 *
 * <p>The actual masking delegates to {@link MaskType}, which in turn uses Hutool's
 * {@code DesensitizedUtil} — no mask pattern is computed in this class.</p>
 */
public class DesensitizeSerializer extends JsonSerializer<String> implements ContextualSerializer {

    /** Masking strategy resolved from the property annotation, or {@code null} for pass-through. */
    private final MaskType maskType;

    /** Default constructor for Jackson; yields a pass-through serializer. */
    public DesensitizeSerializer() {
        this.maskType = null;
    }

    DesensitizeSerializer(MaskType maskType) {
        this.maskType = maskType;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (maskType == null) {
            gen.writeString(value);
            return;
        }
        gen.writeString(maskType.mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        Desensitize annotation = property == null ? null : property.getAnnotation(Desensitize.class);
        if (annotation != null) {
            return new DesensitizeSerializer(annotation.value());
        }
        return this;
    }
}
