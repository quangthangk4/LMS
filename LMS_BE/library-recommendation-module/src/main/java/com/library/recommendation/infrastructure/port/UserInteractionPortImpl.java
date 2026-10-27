package com.library.recommendation.infrastructure.port;

import com.library.recommendation.infrastructure.persistence.entity.UserInteractionEntity;
import com.library.recommendation.infrastructure.persistence.repository.UserInteractionJpaRepository;
import com.library.shared.port.UserInteractionPort;
import com.library.shared.util.TsIdGenerator;
import com.library.user.domain.enums.InteractionType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserInteractionPortImpl implements UserInteractionPort {

    private final UserInteractionJpaRepository interactionRepository;

    @Override
    @Async("interactionExecutor")
    @Transactional
    public void record(Long userId, Long publicationId, String interactionType) {
        try {
            InteractionType type = InteractionType.valueOf(interactionType);

            // Dedup WATCH: chỉ lưu 1 record mỗi 24h cho cùng user + publication
            if (type == InteractionType.WATCH) {
                Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
                boolean alreadyViewed = interactionRepository
                    .existsByUserIdAndPublicationIdAndTypeAndCreatedAtAfter(userId, publicationId, type, since);
                if (alreadyViewed) {
                    log.debug("Skip duplicate WATCH: userId={}, pubId={}", userId, publicationId);
                    return;
                }
            }

            UserInteractionEntity entity = UserInteractionEntity.builder()
                .userId(userId)
                .publicationId(publicationId)
                .type(type)
                .build();
            entity.setId(TsIdGenerator.next());
            interactionRepository.save(entity);
            // Keep the 8-hour recommendation snapshot. Readers can explicitly refresh
            // personalization; recording a view must not trigger another AI calculation.
            log.debug("Recorded interaction: userId={}, pubId={}, type={}", userId, publicationId, interactionType);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown interaction type: {}", interactionType);
        } catch (Exception e) {
            log.warn(
                "Failed to record interaction: userId={}, pubId={}, type={}, error={}",
                userId,
                publicationId,
                interactionType,
                e.getMessage());
        }
    }
}
