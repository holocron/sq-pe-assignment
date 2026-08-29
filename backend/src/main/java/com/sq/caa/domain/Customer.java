package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A bank customer. Maps the assignment table {@code customers}. */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "last_name", nullable = false, length = 120)
    private String lastName;

    @Column(name = "first_name", nullable = false, length = 120)
    private String firstName;

    @Column(name = "dob", nullable = false)
    private LocalDate dob;

    /** ISO 3166-1 alpha-2. Stored as {@code CHAR(2)}, hence the explicit JDBC type. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    /** {@code firstName lastName}, for display and search. */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    /** Age in whole years at the current UTC date, or {@code null} when the date of birth is unknown. */
    public Integer getAge() {
        return dob == null ? null : Period.between(dob, LocalDate.now(ZoneOffset.UTC)).getYears();
    }
}
