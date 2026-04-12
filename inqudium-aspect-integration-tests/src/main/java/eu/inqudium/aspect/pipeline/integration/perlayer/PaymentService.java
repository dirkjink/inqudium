package eu.inqudium.aspect.pipeline.integration.perlayer;

/**
 * Payment service — demonstrates per-layer annotation combinations.
 *
 * <p>Each method uses a different set of layer annotations, producing
 * a different pipeline per method:</p>
 *
 * <pre>
 *   charge()     @Authorized @Logged @Timed  →  AUTH → LOG → TIME  (all three)
 *   refund()     @Authorized @Logged         →  AUTH → LOG         (no timing)
 *   lookup()     @Logged                     →  LOG                (log only)
 *   validate()   @Authorized @Timed          →  AUTH → TIME        (skip logging)
 *   internal()   (none)                      →  not intercepted
 * </pre>
 */
public class PaymentService {

    @Authorized
    @Logged
    @Timed
    public String charge(String account, int amount) {
        return "Charged " + amount + " from " + account;
    }

    @Authorized
    @Logged
    public String refund(String account, int amount) {
        return "Refunded " + amount + " to " + account;
    }

    @Logged
    public String lookup(String transactionId) {
        return "Transaction " + transactionId + ": completed";
    }

    @Authorized
    @Timed
    public String validate(String account) {
        return "Account " + account + " is valid";
    }

    /**
     * No layer annotations — the aspect pointcut does not match,
     * so this method is never intercepted.
     */
    public String internal(String note) {
        return "Internal: " + note;
    }
}
