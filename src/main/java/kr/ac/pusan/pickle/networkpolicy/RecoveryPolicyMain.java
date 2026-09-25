package kr.ac.pusan.pickle.networkpolicy;

import java.nio.file.Path;
import java.util.UUID;
import kr.ac.pusan.recoverypolicy.RecoveryPolicyApplication;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyAdvisoryLock.OutcomeUnknownException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Dedicated operator entry point; command-line arguments are never passed to Spring. */
public final class RecoveryPolicyMain {

    private RecoveryPolicyMain() {}

    public static void main(String[] args) {
        RecoveryPolicyCommand.Pin pin;
        try {
            pin = parse(args);
        } catch (RuntimeException invalid) {
            System.err.println("Usage: RecoveryPolicyMain MANIFEST_JSON MANIFEST_SHA256"
                    + " POLICY_PROOF_JSON POLICY_PROOF_SHA256 DB_IDENTITY_JSON ATTEMPT_UUID");
            System.exit(2);
            return;
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RecoveryPolicyApplication.class).web(WebApplicationType.NONE).run()) {
            RecoveryPolicyCommand.Result result = context.getBean(RecoveryPolicyCommand.class)
                    .execute(pin);
            System.out.println("Recovery policy result: " + result);
        } catch (OutcomeUnknownException uncertain) {
            System.err.println("Recovery outcome uncertain; keep both guests stopped and isolated."
                    + " Read-only result: " + outcomeLabel(uncertain));
            System.exit(1);
        } catch (RuntimeException failure) {
            System.err.println("Recovery policy apply failed (" + failure.getClass().getSimpleName()
                    + "); keep both guests stopped and isolated. Inspect the policy and audit rows.");
            System.exit(1);
        }
    }

    static RecoveryPolicyCommand.Pin parse(String[] args) {
        if (args.length != 6) {
            throw new IllegalArgumentException("Six recovery arguments are required.");
        }
        return new RecoveryPolicyCommand.Pin(Path.of(args[0]), args[1], Path.of(args[2]),
                args[3], Path.of(args[4]), UUID.fromString(args[5]));
    }

    static String outcomeLabel(OutcomeUnknownException uncertain) {
        String message = uncertain.getMessage();
        if (message != null && message.endsWith("APPLIED_WITH_AUDIT")) {
            return "APPLIED_WITH_AUDIT";
        }
        if (message != null && message.endsWith("NOT_CONFIRMED")) {
            return "NOT_CONFIRMED";
        }
        return "UNAVAILABLE";
    }
}
