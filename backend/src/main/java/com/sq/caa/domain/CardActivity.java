package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Card-specific detail of a transaction. Maps the assignment table {@code card_activity}.
 *
 * <p>The primary key is shared with {@link Transaction} through {@link MapsId}, so
 * {@link #transactionId} is derived from {@link #transaction} on persist.
 */
@Entity
@Table(name = "card_activity")
@Getter
@Setter
@NoArgsConstructor
public class CardActivity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** Masked PAN, e.g. {@code ****1234}. */
    @Column(name = "card_pan", nullable = false, length = 25)
    private String cardPan;

    /** Debit / Credit / Prepaid. */
    @Column(name = "card_type", nullable = false, length = 20)
    private String cardType;

    @Column(name = "merchant_name", nullable = false, length = 160)
    private String merchantName;

    /** Merchant category code. */
    @Column(name = "mcc_code", nullable = false, length = 4)
    private String mccCode;

    /** {@code false} means card-not-present. */
    @Column(name = "card_present", nullable = false)
    private boolean cardPresent;

    @Column(name = "authorization_code", nullable = false, length = 20)
    private String authorizationCode;

    /** Populated only for declined authorisations. */
    @Column(name = "decline_reason", length = 120)
    private String declineReason;

    /**
     * All-args constructor backing {@link #builder()}. It routes the owning transaction through
     * {@link #setTransaction(Transaction)} so the shared primary key stays consistent no matter how
     * the row is built. An explicitly supplied {@code transaction} therefore wins over an explicitly
     * supplied {@code transactionId}, which is what {@link MapsId} does at flush time anyway.
     */
    @Builder
    public CardActivity(UUID transactionId,
            Transaction transaction,
            String cardPan,
            String cardType,
            String merchantName,
            String mccCode,
            boolean cardPresent,
            String authorizationCode,
            String declineReason) {
        this.transactionId = transactionId;
        this.cardPan = cardPan;
        this.cardType = cardType;
        this.merchantName = merchantName;
        this.mccCode = mccCode;
        this.cardPresent = cardPresent;
        this.authorizationCode = authorizationCode;
        this.declineReason = declineReason;
        setTransaction(transaction);
    }

    /** Attaches the owning transaction and adopts its identifier, which is also this row's key. */
    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
        if (transaction != null && transaction.getTransactionId() != null) {
            this.transactionId = transaction.getTransactionId();
        }
    }
}
