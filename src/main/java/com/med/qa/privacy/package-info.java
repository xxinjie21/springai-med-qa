/**
 * Privacy / field-desensitization layer (D24).
 *
 * <p>{@link com.med.qa.privacy.MaskType} enumerates the supported masking strategies and
 * {@link com.med.qa.privacy.DesensitizeSerializer} applies them during Jackson serialization. The
 * masking itself is delegated to Hutool's {@code DesensitizedUtil}; no mask pattern is computed in
 * this project.</p>
 */
package com.med.qa.privacy;
