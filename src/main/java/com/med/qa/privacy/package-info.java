/**
 * Privacy / field-desensitization layer (D24).
 *
 * <p>{@link com.med.qa.privacy.MaskType} enumerates the supported masking strategies and
 * {@link com.med.qa.privacy.DesensitizeSerializer} applies them during Jackson serialization. Phone
 * numbers and national ID cards are masked by Hutool's {@code DesensitizedUtil}; the medical record
 * number uses the enum's own keep-edges mask, because Hutool ships no strategy for it. That single
 * exception is the whole of the masking logic computed here — an earlier version of this javadoc
 * denied it, and the claim was corrected in D46.</p>
 */
package com.med.qa.privacy;
