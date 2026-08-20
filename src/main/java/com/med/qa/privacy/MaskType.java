package com.med.qa.privacy;

import cn.hutool.core.util.DesensitizedUtil;

/**
 * Masking strategies for sensitive patient / contact fields (D24).
 *
 * <p>Each constant knows how to redact a single {@code String} value. Mobile phone numbers and
 * national ID cards delegate to the maintained Hutool {@link DesensitizedUtil} implementation; the
 * medical record number has no dedicated Hutool strategy, so a simple keep-edges mask is applied.
 * No mask pattern is hand-written in this project.</p>
 *
 * <p>Masking is presentation-only: the raw value is never altered or persisted by this enum.</p>
 */
public enum MaskType {

    /** Mobile phone number, e.g. {@code 138****5678}. */
    PHONE,

    /** National ID card number, keeps the first 6 and last 4 characters. */
    ID_CARD,

    /** Hospital medical record number, keeps the first 2 and last 2 characters. */
    MEDICAL_RECORD_NO;

    /**
     * Redacts the given value according to this masking strategy.
     *
     * @param value raw value, may be {@code null}
     * @return masked value, or {@code null} when the input was {@code null}
     */
    public String mask(String value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case PHONE -> DesensitizedUtil.mobilePhone(value);
            case ID_CARD -> DesensitizedUtil.idCardNum(value, 6, 4);
            case MEDICAL_RECORD_NO -> maskKeepEdges(value, 2, 2);
        };
    }

    /**
     * Keeps the leading and trailing characters of a value, replacing everything in between with
     * asterisks. Used for identifiers (medical record numbers) that Hutool does not mask natively.
     *
     * @param value raw value, never {@code null}
     * @param front number of leading characters to keep (must be {@code >= 0})
     * @param end   number of trailing characters to keep (must be {@code >= 0})
     * @return masked value, never {@code null}
     */
    private static String maskKeepEdges(String value, int front, int end) {
        int length = value.length();
        if (length <= front + end) {
            // Too short to reveal anything useful: mask entirely.
            return "*".repeat(length);
        }
        return value.substring(0, front)
                + "*".repeat(length - front - end)
                + value.substring(length - end);
    }
}
