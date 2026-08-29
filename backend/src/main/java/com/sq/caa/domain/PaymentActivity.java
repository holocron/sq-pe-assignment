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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Payment-specific detail of a transaction. Maps the assignment table {@code payment_activity}.
 * The primary key is shared with {@link Transaction} through {@link MapsId}.
 */
@Entity
@Table(name = "payment_activity")
@Getter
@Setter
@NoArgsConstructor
public class PaymentActivity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** ACH / Wire / SWIFT / P2P. */
    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "sender_account", nullable = false, length = 40)
    private String senderAccount;

    @Column(name = "receiver_account", nullable = false, length = 40)
    private String receiverAccount;

    /** ISO 3166-1 alpha-2 beneficiary bank country. Stored as {@code CHAR(2)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "receiver_bank_country", nullable = false, length = 2)
    private String receiverBankCountry;

    /**
     * All-args constructor backing {@link #builder()}. It routes the owning transaction through
     * {@link #setTransaction(Transaction)} so the shared primary key stays consistent no matter how
     * the row is built. An explicitly supplied {@code transaction} therefore wins over an explicitly
     * supplied {@code transactionId}, which is what {@link MapsId} does at flush time anyway.
     */
    @Builder
    public PaymentActivity(UUID transactionId,
            Transaction transaction,
            String paymentMethod,
            String senderAccount,
            String receiverAccount,
            String receiverBankCountry) {
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
        this.senderAccount = senderAccount;
        this.receiverAccount = receiverAccount;
        this.receiverBankCountry = receiverBankCountry;
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
