package org.apache.mesos.scheduler.plan;

import com.google.inject.Inject;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.offer.OfferAccepter;
import org.apache.mesos.offer.OfferEvaluator;
import org.apache.mesos.offer.OfferRecommendation;
import org.apache.mesos.offer.OfferRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Default scheduler. See docs in {@link StageScheduler} interface.
 */
public class DefaultStageScheduler implements StageScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStageScheduler.class);

    private final OfferAccepter offerAccepter;
    private final OfferEvaluator offerEvaluator;

    @Inject
    public DefaultStageScheduler(OfferAccepter offerAccepter) {
        this(offerAccepter, new OfferEvaluator());
    }

    public DefaultStageScheduler(OfferAccepter offerAccepter, OfferEvaluator offerEvaluator) {
        this.offerAccepter = offerAccepter;
        this.offerEvaluator = offerEvaluator;
    }

    @Override
    public List<Protos.OfferID> resourceOffers(
            SchedulerDriver driver, List<Protos.Offer> offers, Block block) {
        List<Protos.OfferID> acceptedOffers = new ArrayList<>();

        if (driver == null || offers == null) {
            logger.error("Unexpected null argument encountered: driver='{}' offers='{}'",
                    driver, offers);
            return acceptedOffers;
        }

        if (block == null) {
            logger.info("Ignoring resource offers for null block.");
            return acceptedOffers;
        }

        if (!block.isPending()) {
            logger.info("Ignoring resource offers for block: {} status: {}",
                    block.getName(), Block.getStatus(block));
            return acceptedOffers;
        }

        logger.info("Processing resource offers for block: {}", block.getName());
        OfferRequirement offerReq = block.start();
        if (offerReq == null) {
            logger.info("No OfferRequirement for block: {}", block.getName());
            block.updateOfferStatus(Optional.empty());
            return acceptedOffers;
        }

        // Block has returned an OfferRequirement to process. Find offers which match the
        // requirement and accept them, if any are found:
        List<OfferRecommendation> recommendations = offerEvaluator.evaluate(offerReq, offers);
        if (recommendations.isEmpty()) {
            // complain that we're not finding suitable offers. out of space on the cluster?:
            logger.warn(
                    "Unable to find any offers which fulfill requirement provided by block {}: {}",
                    block.getName(), offerReq);
            block.updateOfferStatus(Optional.empty());
            return acceptedOffers;
        }

        acceptedOffers = offerAccepter.accept(driver, recommendations);

        // notify block of offer outcome:
        if (acceptedOffers.size() > 0) {
            Set<Protos.TaskID> taskIDs = getTaskIds(recommendations);
            if (taskIDs.size() > 0) {
                block.updateOfferStatus(Optional.of(taskIDs));
            } else {
                block.updateOfferStatus(Optional.empty());
            }
        } else {
            block.updateOfferStatus(Optional.empty());
        }

        return acceptedOffers;
    }

    private Set<Protos.TaskID> getTaskIds(List<OfferRecommendation> recommendations) {
        Set<Protos.TaskID> taskIds = new HashSet<>();
        for (OfferRecommendation recommendation : recommendations) {
            Protos.Offer.Operation operation = recommendation.getOperation();
            if (operation.hasType() &&
                operation.getType().equals(Protos.Offer.Operation.Type.LAUNCH)) {
                for (Protos.TaskInfo taskInfo : operation.getLaunch().getTaskInfosList()) {
                    taskIds.add(taskInfo.getTaskId());
                }
            }
        }

        return taskIds;
    }
}
