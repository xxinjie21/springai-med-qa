package com.med.qa.privacy.annotation;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.med.qa.privacy.DesensitizeSerializer;
import com.med.qa.privacy.MaskType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} bean property for automatic masking during JSON serialization (D24).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class PatientContact {
 *     @Desensitize(MaskType.PHONE)
 *     private String phone;
 * }
 * }</pre>
 *
 * <p>The masking strategy is delegated to {@link MaskType} and ultimately to Hutool's
 * {@code DesensitizedUtil}; no hand-written masking logic lives in this project. Place the
 * annotation on a bean property (field or getter) of type {@code String}. A {@code null} value is
 * serialized as JSON {@code null} and left untouched.</p>
 *
 * <p>This annotation carries {@link JsonSerialize} through {@link JacksonAnnotationsInside}, so no
 * explicit serializer registration is required — Jackson picks up {@link DesensitizeSerializer}
 * automatically whenever it serializes an annotated property.</p>
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@JacksonAnnotationsInside
@JsonSerialize(using = DesensitizeSerializer.class)
public @interface Desensitize {

    /**
     * Masking strategy applied to the annotated value.
     *
     * @return the mask type, never {@code null}
     */
    MaskType value();
}
