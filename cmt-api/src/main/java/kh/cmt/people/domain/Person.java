package kh.cmt.people.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;

/**
 * A person of the school: an app user (staff, teacher or admin). Every query on it is limited to the caller's record
 * scope (department / team / self), also loading one by id. Inactive people stay for their history and cannot log in.
 * Codes are stored lower case; see V10__people.sql.
 */
@Entity
@Table(name = "person")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON, deduceAliasInjectionPoints = false)
public class Person extends SoftDeletable {

    @Column(name = "staff_id")
    private String staffId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "name_km")),
            @AttributeOverride(name = "en", column = @Column(name = "name_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "name_ar"))
    })
    private LocalizedText name;

    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String nationality;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "marital_status")
    private String maritalStatus;

    @Column(name = "blood_type")
    private String bloodType;

    private String phone;

    @Column(name = "phone_alt")
    private String phoneAlt;

    private String email;

    @Column(name = "address_province")
    private String addressProvince;

    @Column(name = "address_district")
    private String addressDistrict;

    @Column(name = "address_commune")
    private String addressCommune;

    @Column(name = "address_village")
    private String addressVillage;

    @Column(name = "address_street")
    private String addressStreet;

    @Column(name = "emergency_name")
    private String emergencyName;

    @Column(name = "emergency_relation")
    private String emergencyRelation;

    @Column(name = "emergency_phone")
    private String emergencyPhone;

    private String role;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "position_id")
    private UUID positionId;

    @Column(name = "employment_type")
    private String employmentType;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    @Column(name = "probation_end_date")
    private LocalDate probationEndDate;

    @Column(name = "supervisor_id")
    private UUID supervisorId;

    @Column(name = "last_working_day")
    private LocalDate lastWorkingDay;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    public UUID getPhotoFileId() {
        return photoFileId;
    }

    public void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    /** Moves the person to inactive: {@code reason} is one of the reason codes, {@code lastWorkingDay} their last day. */
    public void inactivate(UUID by, Instant at, String reason, LocalDate lastWorkingDay) {
        super.inactivate(by, at, reason);
        this.lastWorkingDay = lastWorkingDay;
    }

    @Override
    public void restore() {
        super.restore();
        this.lastWorkingDay = null;
    }

    // ------------------------------------------------------------------ accessors

    public String getStaffId() {
        return staffId;
    }

    public void setStaffId(String staffId) {
        this.staffId = staffId;
    }

    public LocalizedText getName() {
        return name;
    }

    public void setName(LocalizedText name) {
        this.name = name;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getMaritalStatus() {
        return maritalStatus;
    }

    public void setMaritalStatus(String maritalStatus) {
        this.maritalStatus = maritalStatus;
    }

    public String getBloodType() {
        return bloodType;
    }

    public void setBloodType(String bloodType) {
        this.bloodType = bloodType;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPhoneAlt() {
        return phoneAlt;
    }

    public void setPhoneAlt(String phoneAlt) {
        this.phoneAlt = phoneAlt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddressProvince() {
        return addressProvince;
    }

    public String getAddressDistrict() {
        return addressDistrict;
    }

    public String getAddressCommune() {
        return addressCommune;
    }

    public String getAddressVillage() {
        return addressVillage;
    }

    public String getAddressStreet() {
        return addressStreet;
    }

    public void setAddress(String province, String district, String commune, String village, String street) {
        this.addressProvince = province;
        this.addressDistrict = district;
        this.addressCommune = commune;
        this.addressVillage = village;
        this.addressStreet = street;
    }

    public String getEmergencyName() {
        return emergencyName;
    }

    public String getEmergencyRelation() {
        return emergencyRelation;
    }

    public String getEmergencyPhone() {
        return emergencyPhone;
    }

    public void setEmergencyContact(String name, String relation, String phone) {
        this.emergencyName = name;
        this.emergencyRelation = relation;
        this.emergencyPhone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public UUID getPositionId() {
        return positionId;
    }

    public void setPositionId(UUID positionId) {
        this.positionId = positionId;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(String employmentType) {
        this.employmentType = employmentType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getContractEndDate() {
        return contractEndDate;
    }

    public void setContractEndDate(LocalDate contractEndDate) {
        this.contractEndDate = contractEndDate;
    }

    public LocalDate getProbationEndDate() {
        return probationEndDate;
    }

    public void setProbationEndDate(LocalDate probationEndDate) {
        this.probationEndDate = probationEndDate;
    }

    public UUID getSupervisorId() {
        return supervisorId;
    }

    public void setSupervisorId(UUID supervisorId) {
        this.supervisorId = supervisorId;
    }

    public LocalDate getLastWorkingDay() {
        return lastWorkingDay;
    }

}
