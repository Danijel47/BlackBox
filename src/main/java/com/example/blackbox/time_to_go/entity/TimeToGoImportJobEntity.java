package com.example.blackbox.time_to_go.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "time_to_go_import_job")
public class TimeToGoImportJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_key", nullable = false, length = 128)
    private String importKey;

    @Column(name = "tomtom_job_id", nullable = false, unique = true, length = 128)
    private String tomtomJobId;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "saved_at")
    private Instant savedAt;

    @Column(name = "message")
    private String message;

    protected TimeToGoImportJobEntity() {
    }

    public TimeToGoImportJobEntity(String importKey, String tomtomJobId, String status, LocalDate fromDate, LocalDate toDate, Instant now, String message) {
        this.importKey = importKey;
        this.tomtomJobId = tomtomJobId;
        this.status = status;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.createdAt = now;
        this.updatedAt = now;
        this.message = message;
    }

    public String getImportKey() {
        return importKey;
    }

    public String getTomtomJobId() {
        return tomtomJobId;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    public String getMessage() {
        return message;
    }

    public void updateStatus(String status, String message) {
        this.status = status;
        this.message = message;
        this.updatedAt = Instant.now();
    }

    public void markSaved(String message) {
        this.status = "SAVED";
        this.message = message;
        this.savedAt = Instant.now();
        this.updatedAt = this.savedAt;
    }
}
