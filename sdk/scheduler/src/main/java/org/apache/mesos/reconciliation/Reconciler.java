package org.apache.mesos.reconciliation;

import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.SchedulerDriver;

import java.util.Set;

/**
 * Interface for a task Reconciler, which synchronizes the Framework's task state with what Mesos
 * reports (with Mesos treated as source of truth).
 */
public interface Reconciler {

    /**
     * Starts reconciliation against the provided tasks, which should represent what the Scheduler
     * currently knows about task status.
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     */
    void start();

    /**
     * Triggers any needed reconciliation against the provided {@code driver}. This call is expected
     * to occur multiple times throughout the lifespan of the scheduler. It SHOULD be invoked each
     * time that the scheduler receives a TaskStatus update or an Offer.
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     *
     * @param driver The SchedulerDriver instance that the Reconciler should
     *               use to query the Mesos master for TaskStatus.
     */
    void reconcile(final SchedulerDriver driver);

    /**
     * Used to update the Reconciler with current task status. This is effectively an asynchronous
     * callback which is triggered by a call to reconcile().
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     *
     * @param status The TaskStatus used to update the Reconciler.
     */
    void update(final TaskStatus status);

    /**
     * Returns whether reconciliation is complete.
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     */
    boolean isReconciled();

    /**
     * Returns the set of known unreconciled task ids as {@code String}s. Note that an empty list
     * does NOT mean that reconciliation is complete. Use {@link #isReconciled()} to determine
     * completeness.
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     *
     * @see #isReconciled()
     */
    Set<String> remaining();

    /**
     * Forces reconciliation into a complete state. This may result in inconsistent task state
     * between Mesos and the Framework Scheduler, so calling it is not recommended.
     * <p>
     * NOTE: THIS CALL MUST BE THREAD-SAFE AGAINST OTHER RECONCILER CALLS
     */
    void forceComplete();
}
