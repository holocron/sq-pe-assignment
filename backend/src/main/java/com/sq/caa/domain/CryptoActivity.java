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
 * Cryptocurrency-specific detail of a transaction. Maps the assignment table {@code crypto_activity}.
 * The primary key is shared with {@link Transaction} through {@link MapsId}.
 */
@Entity
@Table(name = "crypto_activity")
@Getter
@Setter
@NoArgsConstructor
public class CryptoActivity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** BTC / ETH / USDT / XMR / ... */
    @Column(name = "blockchain", nullable = false, length = 30)
    private String blockchain;

    @Column(name = "wallet_address_from", nullable = false, length = 120)
    private String walletAddressFrom;

    @Column(name = "wallet_address_to", nullable = false, length = 120)
    private String walletAddressTo;

    @Column(name = "tx_hash", nullable = false, length = 120)
    private String txHash;

    /** Counterparty exchange; {@code null} when the transfer is unattributed. */
    @Column(name = "exchange_name", length = 80)
    private String exchangeName;

    /**
     * All-args constructor backing {@link #builder()}. It routes the owning transaction through
     * {@link #setTransaction(Transaction)} so the shared primary key stays consistent no matter how
     * the row is built. An explicitly supplied {@code transaction} therefore wins over an explicitly
     * supplied {@code transactionId}, which is what {@link MapsId} does at flush time anyway.
     */
    @Builder
    public CryptoActivity(UUID transactionId,
            Transaction transaction,
            String blockchain,
            String walletAddressFrom,
            String walletAddressTo,
            String txHash,
            String exchangeName) {
        this.transactionId = transactionId;
        this.blockchain = blockchain;
        this.walletAddressFrom = walletAddressFrom;
        this.walletAddressTo = walletAddressTo;
        this.txHash = txHash;
        this.exchangeName = exchangeName;
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
