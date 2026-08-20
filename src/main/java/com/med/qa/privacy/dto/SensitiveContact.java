package com.med.qa.privacy.dto;

import com.med.qa.privacy.MaskType;
import com.med.qa.privacy.annotation.Desensitize;

/**
 * Reusable view object carrying sensitive patient-contact fields that must be masked before they
 * leave the service as JSON (D24).
 *
 * <p>The {@link Desensitize} annotation is the only thing required on a property: the
 * {@code DesensitizeSerializer} masks the value automatically through Jackson. Any response DTO in
 * the project can adopt the same three annotations without further wiring. The raw values are only
 * ever held in memory and are never persisted masked.</p>
 */
public class SensitiveContact {

    /** Patient mobile phone number; masked to {@code 138****5678} on serialization. */
    @Desensitize(MaskType.PHONE)
    private String phone;

    /** Patient national ID card number; masked to keep first 6 and last 4 digits. */
    @Desensitize(MaskType.ID_CARD)
    private String idCard;

    /** Hospital medical record number; masked to keep first 2 and last 2 characters. */
    @Desensitize(MaskType.MEDICAL_RECORD_NO)
    private String medicalRecordNo;

    public SensitiveContact() {
    }

    public SensitiveContact(String phone, String idCard, String medicalRecordNo) {
        this.phone = phone;
        this.idCard = idCard;
        this.medicalRecordNo = medicalRecordNo;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getMedicalRecordNo() {
        return medicalRecordNo;
    }

    public void setMedicalRecordNo(String medicalRecordNo) {
        this.medicalRecordNo = medicalRecordNo;
    }
}
