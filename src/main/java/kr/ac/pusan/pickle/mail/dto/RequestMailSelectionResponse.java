package kr.ac.pusan.pickle.mail.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RequestMailSelectionResponse(UUID requestId, @Nullable RequestMailSelectionView selection) {
}
