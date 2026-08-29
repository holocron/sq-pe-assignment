package com.sq.caa.web.dto;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read models for transactions.
 *
 * <p>{@link TransactionView} always carries the three type-specific detail slots, exactly one of
 * which is non-null (the one matching {@code activityType}). Inlining the detail lets the operator
 * dashboard render a Card / Payment / Crypto tab from a single page request instead of following up
 * with one call per row.
 */
public final class TransactionDtos {

    private TransactionDtos() {
    }

    /** CARD specifics. {@code declineReason} is null for authorisations that were not declined. */
    public record CardDetail(String cardPan,
            String cardType,
            String merchantName,
            String mccCode,
            boolean cardPresent,
            String authorizationCode,
            String declineReason) {

        public static CardDetail from(CardActivity activity) {
            return activity == null ? null : new CardDetail(activity.getCardPan(), activity.getCardType(),
                    activity.getMerchantName(), activity.getMccCode(), activity.isCardPresent(),
                    activity.getAuthorizationCode(), activity.getDeclineReason());
        }
    }

    /** PAYMENT specifics. {@code receiverBankCountry} is an ISO-3166 alpha-2 code. */
    public record PaymentDetail(String paymentMethod,
            String senderAccount,
            String receiverAccount,
            String receiverBankCountry) {

        public static PaymentDetail from(PaymentActivity activity) {
            return activity == null ? null : new PaymentDetail(activity.getPaymentMethod(),
                    activity.getSenderAccount(), activity.getReceiverAccount(),
                    activity.getReceiverBankCountry());
        }
    }

    /** CRYPTO specifics. {@code exchangeName} is null when the transfer is not exchange-attributed. */
    public record CryptoDetail(String blockchain,
            String walletAddressFrom,
            String walletAddressTo,
            String txHash,
            String exchangeName) {

        public static CryptoDetail from(CryptoActivity activity) {
            return activity == null ? null : new CryptoDetail(activity.getBlockchain(),
                    activity.getWalletAddressFrom(), activity.getWalletAddressTo(), activity.getTxHash(),
                    activity.getExchangeName());
        }
    }

    /**
     * One transaction with its detail row inlined.
     *
     * <p>Must be built from a transaction whose customer and detail associations were fetched - the
     * repository {@code ...WithDetails} queries do that in a single statement.
     */
    public record TransactionView(UUID transactionId,
            UUID customerId,
            String customerName,
            ActivityType activityType,
            BigDecimal amount,
            String currency,
            String status,
            Instant createdAt,
            CardDetail card,
            PaymentDetail payment,
            CryptoDetail crypto) {

        public static TransactionView from(Transaction transaction) {
            return new TransactionView(
                    transaction.getTransactionId(),
                    transaction.getCustomer().getCustomerId(),
                    transaction.getCustomer().getFullName(),
                    transaction.getActivityType(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getStatus(),
                    transaction.getCreatedAt(),
                    CardDetail.from(transaction.getCardActivity()),
                    PaymentDetail.from(transaction.getPaymentActivity()),
                    CryptoDetail.from(transaction.getCryptoActivity()));
        }
    }
}
