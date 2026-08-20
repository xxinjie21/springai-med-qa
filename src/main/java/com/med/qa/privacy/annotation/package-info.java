/**
 * Declarative field-desensitization annotation (D24).
 *
 * <p>{@link com.med.qa.privacy.annotation.Desensitize} marks a {@code String} property for automatic
 * masking on JSON serialization. It is pure metadata — the enforcement lives in
 * {@code com.med.qa.privacy.DesensitizeSerializer}.</p>
 */
package com.med.qa.privacy.annotation;
