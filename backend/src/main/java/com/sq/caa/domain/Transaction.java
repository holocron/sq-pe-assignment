package com.sq.caa.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Base customer activity record. Maps the assignment table {@code transactions}.
 *
 * <p>Exactly one of {@link #cardActivity}, {@link #paymentActivity} and {@link #cryptoActivity} is
 * populated, matching {@link #activityType}. Each detail row shares this transaction's primary key.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    /** {@code status} values used throughout the application and the rule DSL. */
    public static final String STATUS_COMPLETED = "Completed";
    public static final String STATUS_PENDING = "Pending";
    public static final String STATUS_FAILED = "Failed";
    public static final String STATUS_REVERSED = "Reversed";

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Native PostgreSQL enum {@code activity_type}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "activity_type", nullable = false, columnDefinition = "activity_type")
    private ActivityType activityType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** ISO currency code or crypto ticker. */
    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    /** Completed / Pending / Failed / Reversed - see the {@code STATUS_*} constants. */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToOne(mappedBy = "transaction", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private CardActivity cardActivity;

    @OneToOne(mappedBy = "transaction", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private PaymentActivity paymentActivity;

    @OneToOne(mappedBy = "transaction", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private CryptoActivity cryptoActivity;

    /**
     * All-args constructor backing {@link #builder()}.
     *
     * <p>It deliberately routes the three detail rows through their setters. Lombok's generated
     * builder assigns fields directly, so building a transaction with a detail row attached would
     * otherwise leave that row's {@code transaction} back-reference {@code null} and {@link MapsId}
     * would fail at flush time with a null identifier.
     */
    @Builder
    public Transaction(UUID transactionId, Customer customer, ActivityType activityType, BigDecimal amount,
            String currency, String status, Instant createdAt, CardActivity cardActivity,
            PaymentActivity paymentActivity, CryptoActivity cryptoActivity) {
        this.transactionId = transactionId;
        this.customer = customer;
        this.activityType = activityType;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        setCardActivity(cardActivity);
        setPaymentActivity(paymentActivity);
        setCryptoActivity(cryptoActivity);
    }

    /**
     * Sets the identifier and keeps any already attached detail row in step, since the detail tables
     * share this primary key.
     */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
        if (cardActivity != null) {
            cardActivity.setTransactionId(transactionId);
        }
        if (paymentActivity != null) {
            paymentActivity.setTransactionId(transactionId);
        }
        if (cryptoActivity != null) {
            cryptoActivity.setTransactionId(transactionId);
        }
    }

    /** Attaches the card detail row and keeps both sides of the shared-key association consistent. */
    public void setCardActivity(CardActivity cardActivity) {
        this.cardActivity = cardActivity;
        if (cardActivity != null) {
            cardActivity.setTransaction(this);
        }
    }

    /** Attaches the payment detail row and keeps both sides of the shared-key association consistent. */
    public void setPaymentActivity(PaymentActivity paymentActivity) {
        this.paymentActivity = paymentActivity;
        if (paymentActivity != null) {
            paymentActivity.setTransaction(this);
        }
    }

    /** Attaches the crypto detail row and keeps both sides of the shared-key association consistent. */
    public void setCryptoActivity(CryptoActivity cryptoActivity) {
        this.cryptoActivity = cryptoActivity;
        if (cryptoActivity != null) {
            cryptoActivity.setTransaction(this);
        }
    }
}
