package kr.ac.pusan.pickle.mail.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record MailDeliveryDetailResponse(MailDeliveryView delivery,
        List<MailDeliveryAttemptView> attemptHistory, @Nullable RequestMailSelectionView selection) {
}
