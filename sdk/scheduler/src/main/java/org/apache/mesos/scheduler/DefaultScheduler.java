package org.apache.mesos.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.TextFormat;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.config.*;
import org.apache.mesos.config.validate.ConfigurationValidator;
import org.apache.mesos.config.validate.TaskSetsCannotShrink;
import org.apache.mesos.config.validate.TaskVolumesCannotChange;
import org.apache.mesos.curator.CuratorConfigStore;
import org.apache.mesos.curator.CuratorStateStore;
import org.apache.mesos.dcos.DCOSCertInstaller;
import org.apache.mesos.dcos.DcosConstants;
import org.apache.mesos.offer.*;
import org.apache.mesos.reconciliation.DefaultReconciler;
import org.apache.mesos.reconciliation.Reconciler;
import org.apache.mesos.scheduler.api.TaskResource;
import org.apache.mesos.scheduler.plan.*;
import org.apache.mesos.scheduler.plan.api.PlansResource;
import org.apache.mesos.scheduler.recovery.DefaultRecoveryPlanManager;
import org.apache.mesos.scheduler.recovery.DefaultRecoveryRequirementProvider;
import org.apache.mesos.scheduler.recovery.DefaultTaskFailureListener;
import org.apache.mesos.scheduler.recovery.TaskFailureListener;
import org.apache.mesos.scheduler.recovery.constrain.TimedLaunchConstrainer;
import org.apache.mesos.scheduler.recovery.monitor.NeverFailureMonitor;
import org.apache.mesos.scheduler.recovery.monitor.TimedFailureMonitor;
import org.apache.mesos.specification.DefaultServiceSpecification;
import org.apache.mesos.specification.DefaultTaskSpecificationProvider;
import org.apache.mesos.specification.ServiceSpecification;
import org.apache.mesos.specification.TaskSpecificationProvider;
import org.apache.mesos.state.PersistentOperationRecorder;
import org.apache.mesos.state.StateStore;
import org.apache.mesos.state.StateStoreCache;
import org.apache.mesos.state.api.JsonPropertyDeserializer;
import org.apache.mesos.state.api.StateResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * This scheduler when provided with a ServiceSpecification will deploy the service and recover from encountered faults
 * when possible.  Changes to the ServiceSpecification will result in rolling configuration updates, or the creation of
 * new Tasks where applicable.
 */
public class DefaultScheduler implements Scheduler, Observer {
    protected static final String UNINSTALL_INCOMPLETE_ERROR_MESSAGE = "Framework has been removed";
    protected static final String UNINSTALL_INSTRUCTIONS_URI =
            "https://docs.mesosphere.com/latest/usage/managing-services/uninstall/";

    protected static final Integer DELAY_BETWEEN_DESTRUCTIVE_RECOVERIES_SEC = 10 * 60;
    protected static final Integer PERMANENT_FAILURE_DELAY_SEC = 20 * 60;
    protected static final Integer AWAIT_TERMINATION_TIMEOUT_MS = 10000;

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultScheduler.class);

    protected final ExecutorService executor = Executors.newFixedThreadPool(1);
    protected final BlockingQueue<Collection<Object>> resourcesQueue = new ArrayBlockingQueue<>(1);
    protected final ServiceSpecification serviceSpecification;
    protected final StateStore stateStore;
    protected final ConfigStore<ServiceSpecification> configStore;
    protected final Collection<ConfigurationValidator<ServiceSpecification>> configValidators;
    protected final Optional<Integer> permanentFailureTimeoutSec;
    protected final Integer destructiveRecoveryDelaySec;

    protected SchedulerDriver driver;
    protected OfferRequirementProvider offerRequirementProvider;
    protected TaskSpecificationProvider taskSpecificationProvider;
    protected Reconciler reconciler;
    protected TaskFailureListener taskFailureListener;
    protected TaskKiller taskKiller;
    protected OfferAccepter offerAccepter;
    protected PlanScheduler planScheduler;
    protected PlanManager deploymentPlanManager;
    protected PlanManager recoveryPlanManager;
    protected PlanCoordinator planCoordinator;
    protected Collection<Object> resources;

    /**
     * Returns a new {@link DefaultScheduler} instance using the provided service-defined
     * {@link ServiceSpecification} and the default ZK location for framework state.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *     unrecognized deserialization type
     * @see DcosConstants#MESOS_MASTER_ZK_CONNECTION_STRING
     */
    public static DefaultScheduler create(ServiceSpecification serviceSpecification)
            throws ConfigStoreException {
        return create(serviceSpecification, Collections.emptyList());
    }

    /**
     * Returns a new {@link DefaultScheduler} instance using the provided service-defined
     * {@link ServiceSpecification} and the default ZK location for framework state, and with the
     * provided custom {@link PlacementRule} classes which are used within the provided
     * {@link ServiceSpecification}. Custom implementations of {@link PlacementRule}s MUST be
     * serializable and deserializable by Jackson, and MUST be provided to support correctly
     * identifying them in the serialized {@link ServiceSpecification}.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @param customDeserializationSubtypes custom placement rule implementations which support
     *     Jackson deserialization
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *     unrecognized deserialization type
     * @see DcosConstants#MESOS_MASTER_ZK_CONNECTION_STRING
     */
    public static DefaultScheduler create(
            ServiceSpecification serviceSpecification,
            Collection<Class<?>> customDeserializationSubtypes) throws ConfigStoreException {
        return create(
                serviceSpecification,
                createStateStore(serviceSpecification),
                createConfigStore(
                        serviceSpecification,
                        customDeserializationSubtypes));
    }

    /**
     * Returns a new {@link DefaultScheduler} instance using the provided
     * {@link ServiceSpecification}, {@link ConfigStore}, and {@link StateStore}.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @param stateStore framework state storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @param configStore framework config storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @see #createStateStore(String, String)
     */
    public static DefaultScheduler create(
            ServiceSpecification serviceSpecification,
            StateStore stateStore,
            ConfigStore<ServiceSpecification> configStore) {
        return create(
                serviceSpecification,
                stateStore,
                configStore,
                defaultConfigValidators(),
                Optional.of(PERMANENT_FAILURE_DELAY_SEC),
                DELAY_BETWEEN_DESTRUCTIVE_RECOVERIES_SEC);
    }

    /**
     * Returns a new {@link DefaultScheduler} instance using the provided
     * {@code frameworkName} and {@link PlanManager} stack, and the default ZK location for
     * framework state.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @param permanentFailureTimeoutSec minimum duration to wait in seconds before deciding that a
     *      task has failed, or an empty {@link Optional} to disable this detection
     * @param destructiveRecoveryDelaySec minimum duration to wait in seconds between destructive
     *      recovery operations such as destroying a failed task
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *      unrecognized deserialization type
     * @see DcosConstants#MESOS_MASTER_ZK_CONNECTION_STRING
     */
    public static DefaultScheduler create(
            ServiceSpecification serviceSpecification,
            Optional<Integer> permanentFailureTimeoutSec,
            Integer destructiveRecoveryDelaySec) throws ConfigStoreException {
        return create(
                serviceSpecification,
                createStateStore(serviceSpecification),
                createConfigStore(serviceSpecification),
                defaultConfigValidators(),
                permanentFailureTimeoutSec,
                destructiveRecoveryDelaySec);
    }

    /**
     * Returns a new {@link DefaultScheduler} instance using the provided
     * {@code frameworkName}, {@link PlanManager} stack, and {@link StateStore}.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @param stateStore framework state storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @param configStore framework config storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @param configValidators configuration validators to be used when evaluating config changes
     * @param permanentFailureTimeoutSec minimum duration to wait in seconds before deciding that a
     *     task has failed, or an empty {@link Optional} to disable this detection
     * @param destructiveRecoveryDelaySec minimum duration to wait in seconds between destructive
     *     recovery operations such as destroying a failed task
     */
    public static DefaultScheduler create(
            ServiceSpecification serviceSpecification,
            StateStore stateStore,
            ConfigStore<ServiceSpecification> configStore,
            Collection<ConfigurationValidator<ServiceSpecification>> configValidators,
            Optional<Integer> permanentFailureTimeoutSec,
            Integer destructiveRecoveryDelaySec) {
        return new DefaultScheduler(
                serviceSpecification,
                stateStore,
                configStore,
                configValidators,
                permanentFailureTimeoutSec,
                destructiveRecoveryDelaySec);
    }

    /**
     * Creates and returns a new default {@link StateStore} suitable for passing to
     * {@link DefaultScheduler#create}. To avoid the risk of zookeeper consistency issues, the
     * returned storage MUST NOT be written to before the Scheduler has registered with Mesos, as
     * signified by a call to {@link DefaultScheduler#registered(SchedulerDriver,
     * org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)}
     *
     * @param zkConnectionString the zookeeper connection string to be passed to curator (host:port)
     */
    public static StateStore createStateStore(ServiceSpecification serviceSpecification, String zkConnectionString) {
        return StateStoreCache.getInstance(new CuratorStateStore(
                serviceSpecification.getName(),
                zkConnectionString));
    }

    /**
     * Calls {@link #createStateStore(String, String)} with the specification name as the
     * {@code frameworkName} and with a reasonable default for {@code zkConnectionString}.
     *
     * @see DcosConstants#MESOS_MASTER_ZK_CONNECTION_STRING
     */
    public static StateStore createStateStore(ServiceSpecification serviceSpecification) {
        return createStateStore(
                serviceSpecification,
                DcosConstants.MESOS_MASTER_ZK_CONNECTION_STRING);
    }

    /**
     * Creates and returns a new default {@link ConfigStore} suitable for passing to
     * {@link DefaultScheduler#create}. To avoid the risk of zookeeper consistency issues, the
     * returned storage MUST NOT be written to before the Scheduler has registered with Mesos, as
     * signified by a call to {@link DefaultScheduler#registered(SchedulerDriver,
     * org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)}.
     *
     * @param zkConnectionString the zookeeper connection string to be passed to curator (host:port)
     * @param customDeserializationSubtypes custom subtypes to register for deserialization of
     *     {@link DefaultServiceSpecification}, mainly useful for deserializing custom
     *     implementations of {@link PlacementRule}s.
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *     unrecognized deserialization type
     */
    public static ConfigStore<ServiceSpecification> createConfigStore(
            ServiceSpecification serviceSpecification,
            String zkConnectionString,
            Collection<Class<?>> customDeserializationSubtypes) throws ConfigStoreException {
        return new CuratorConfigStore<>(
                DefaultServiceSpecification.getFactory(serviceSpecification, customDeserializationSubtypes),
                serviceSpecification.getName(),
                zkConnectionString);
    }

    /**
     * Calls {@link #createConfigStore(String, String, Collection))} with the specification name as
     * the {@code frameworkName} and with a reasonable default for {@code zkConnectionString}.
     *
     * @param customDeserializationSubtypes custom subtypes to register for deserialization of
     *     {@link DefaultServiceSpecification}, mainly useful for deserializing custom
     *     implementations of {@link PlacementRule}s.
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *     unrecognized deserialization type
     * @see DcosConstants#MESOS_MASTER_ZK_CONNECTION_STRING
     */
    public static ConfigStore<ServiceSpecification> createConfigStore(
            ServiceSpecification serviceSpecification,
            Collection<Class<?>> customDeserializationSubtypes) throws ConfigStoreException {
        return createConfigStore(
                serviceSpecification,
                DcosConstants.MESOS_MASTER_ZK_CONNECTION_STRING,
                customDeserializationSubtypes);
    }

    /**
     * Calls {@link #createConfigStore(ServiceSpecification, Collection)} with an empty list of
     * custom deserialization types.
     *
     * @throws ConfigStoreException if validating serialization of the config fails, e.g. due to an
     *     unrecognized deserialization type
     */
    public static ConfigStore<ServiceSpecification> createConfigStore(
            ServiceSpecification serviceSpecification) throws ConfigStoreException {
        return createConfigStore(serviceSpecification, Collections.emptyList());
    }

    /**
     * Returns the default configuration validators:
     * - Task sets cannot shrink (each set's task count must stay the same or increase).
     * - Task volumes cannot be changed.
     *
     * This function may be used to get the default validators and add more to the list when
     * constructing the {@link DefaultScheduler}.
     */
    public static List<ConfigurationValidator<ServiceSpecification>> defaultConfigValidators() {
        // Return a list to allow direct append by the caller.
        return Arrays.asList(
                new TaskSetsCannotShrink(),
                new TaskVolumesCannotChange());
    }

    /**
     * Creates a new DefaultScheduler.
     *
     * @param serviceSpecification specification containing service name and tasks to be deployed
     * @param stateStore framework state storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @param configStore framework config storage, which must not be written to before the scheduler
     *     has been registered with mesos as indicated by a call to {@link DefaultScheduler#registered(
     *     SchedulerDriver, org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
     * @param configValidators custom validators to be used, instead of the default validators
     *     returned by {@link #defaultConfigValidators()}
     * @param permanentFailureTimeoutSec minimum duration to wait in seconds before deciding that a
     *     task has failed, or an empty {@link Optional} to disable this detection
     * @param destructiveRecoveryDelaySec minimum duration to wait in seconds between destructive
     *     recovery operations such as destroying a failed task
     */
    protected DefaultScheduler(
            ServiceSpecification serviceSpecification,
            StateStore stateStore,
            ConfigStore<ServiceSpecification> configStore,
            Collection<ConfigurationValidator<ServiceSpecification>> configValidators,
            Optional<Integer> permanentFailureTimeoutSec,
            Integer destructiveRecoveryDelaySec) {
        this.serviceSpecification = serviceSpecification;
        this.stateStore = stateStore;
        this.configStore = configStore;
        this.configValidators = configValidators;
        this.permanentFailureTimeoutSec = permanentFailureTimeoutSec;
        this.destructiveRecoveryDelaySec = destructiveRecoveryDelaySec;
    }

    public Collection<Object> getResources() throws InterruptedException {
        if (resources == null) {
            resources = resourcesQueue.take();
        }

        return resources;
    }

    @VisibleForTesting
    void awaitTermination() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(AWAIT_TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void initialize(SchedulerDriver driver) throws InterruptedException {
        LOGGER.info("Initializing...");
        // NOTE: We wait until this point to perform any work using configStore/stateStore.
        // We specifically avoid writing any data to ZK before registered() has been called.
        initializeGlobals(driver);
        initializeDeploymentPlanManager();
        initializeRecoveryPlanManager();
        initializeResources();
        DCOSCertInstaller.installCertificate(System.getenv("JAVA_HOME"));
        final List<PlanManager> planManagers = Arrays.asList(
                deploymentPlanManager,
                recoveryPlanManager);
        planCoordinator = new DefaultPlanCoordinator(planManagers, planScheduler);
        planCoordinator.subscribe(this);
        LOGGER.info("Done initializing.");
    }

    private void initializeGlobals(SchedulerDriver driver) {
        LOGGER.info("Updating config...");
        ConfigurationUpdater<ServiceSpecification> configurationUpdater =
                new DefaultConfigurationUpdater(
                        stateStore,
                        configStore,
                        DefaultServiceSpecification.getComparatorInstance(),
                        configValidators);
        final ConfigurationUpdater.UpdateResult configUpdateResult;
        try {
            configUpdateResult = configurationUpdater.updateConfiguration(serviceSpecification);
        } catch (ConfigStoreException e) {
            LOGGER.error("Fatal error when performing configuration update. Service exiting.", e);
            throw new IllegalStateException(e);
        }

        LOGGER.info("Initializing globals...");
        offerRequirementProvider = new DefaultOfferRequirementProvider(
                new DefaultTaskConfigRouter(), configUpdateResult.targetId);
        taskSpecificationProvider = new DefaultTaskSpecificationProvider(configStore);
        taskFailureListener = new DefaultTaskFailureListener(stateStore);
        taskKiller = new DefaultTaskKiller(stateStore, taskFailureListener, driver);
        reconciler = new DefaultReconciler(stateStore);
        offerAccepter = new OfferAccepter(Arrays.asList(new PersistentOperationRecorder(stateStore)));
        planScheduler = new DefaultPlanScheduler(offerAccepter, new OfferEvaluator(stateStore), taskKiller);
    }

    /**
     * Override this function to inject your own deployment plan manager.
     */
    protected void initializeDeploymentPlanManager() {
        LOGGER.info("Initializing deployment plan...");
        deploymentPlanManager = new DefaultPlanManager(
                new DefaultPlanFactory(new DefaultPhaseFactory(new DefaultStepFactory(
                        configStore,
                        stateStore,
                        offerRequirementProvider,
                        taskSpecificationProvider)))
                .getPlan(serviceSpecification));
    }

    /**
     * Override this function to inject your own recovery plan manager.
     */
    protected void initializeRecoveryPlanManager() {
        LOGGER.info("Initializing recovery plan...");
        recoveryPlanManager = new DefaultRecoveryPlanManager(
                stateStore,
                new DefaultRecoveryRequirementProvider(offerRequirementProvider, taskSpecificationProvider),
                new TimedLaunchConstrainer(Duration.ofSeconds(destructiveRecoveryDelaySec)),
                permanentFailureTimeoutSec.isPresent()
                        ? new TimedFailureMonitor(Duration.ofSeconds(permanentFailureTimeoutSec.get()))
                        : new NeverFailureMonitor());
    }

    private void initializeResources() throws InterruptedException {
        LOGGER.info("Initializing resources...");
        Collection<Object> resources = new ArrayList<>();
        resources.add(new PlansResource(ImmutableMap.of(
                "deploy", deploymentPlanManager,
                "recovery", recoveryPlanManager)));
        resources.add(new StateResource(stateStore, new JsonPropertyDeserializer()));
        resources.add(new TaskResource(stateStore, taskKiller, serviceSpecification.getName()));
        resourcesQueue.put(resources);
    }

    private void logOffers(List<Protos.Offer> offers) {
        if (offers == null) {
            return;
        }

        LOGGER.info(String.format("Received %d offers:", offers.size()));
        for (int i = 0; i < offers.size(); ++i) {
            // Offer protobuffers are very long. print each as a single line:
            LOGGER.info(String.format("- Offer %d: %s", i + 1, TextFormat.shortDebugString(offers.get(i))));
        }
    }

    private void declineOffers(SchedulerDriver driver, List<Protos.OfferID> acceptedOffers, List<Protos.Offer> offers) {
        final List<Protos.Offer> unusedOffers = OfferUtils.filterOutAcceptedOffers(offers, acceptedOffers);
        unusedOffers.stream().forEach(offer -> {
            final Protos.OfferID offerId = offer.getId();
            LOGGER.info("Declining offer: " + offerId.getValue());
            driver.declineOffer(offerId);
        });
    }

    private Optional<ResourceCleanerScheduler> getCleanerScheduler() {
        try {
            ResourceCleaner cleaner = new ResourceCleaner(stateStore);
            return Optional.of(new ResourceCleanerScheduler(cleaner, offerAccepter));
        } catch (Exception ex) {
            LOGGER.error("Failed to construct ResourceCleaner", ex);
            return Optional.empty();
        }
    }

    @SuppressWarnings({"DM_EXIT"})
    private void hardExit(SchedulerErrorCode errorCode) {
        System.exit(errorCode.ordinal());
    }

    @Override
    public void registered(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) {
        LOGGER.info("Registered framework with frameworkId: " + frameworkId.getValue());
        try {
            initialize(driver);
        } catch (InterruptedException e) {
            LOGGER.error("Initialization failed with exception: ", e);
            hardExit(SchedulerErrorCode.INITIALIZATION_FAILURE);
        }

        try {
            stateStore.storeFrameworkId(frameworkId);
        } catch (Exception e) {
            LOGGER.error(String.format(
                    "Unable to store registered framework ID '%s'", frameworkId.getValue()), e);
            hardExit(SchedulerErrorCode.REGISTRATION_FAILURE);
        }

        this.driver = driver;
        reconciler.reconcile(driver);
        suppressOrRevive();
    }

    @Override
    public void reregistered(SchedulerDriver driver, Protos.MasterInfo masterInfo) {
        LOGGER.error("Re-registration implies we were unregistered.");
        hardExit(SchedulerErrorCode.RE_REGISTRATION);
        suppressOrRevive();
    }

    @Override
    public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offersToProcess) {
        List<Protos.Offer> offers = new ArrayList<>(offersToProcess);
        executor.execute(() -> {
            logOffers(offers);

            // Task Reconciliation:
            // Task Reconciliation must complete before any Tasks may be launched.  It ensures that a Scheduler and
            // Mesos have agreed upon the state of all Tasks of interest to the scheduler.
            // http://mesos.apache.org/documentation/latest/reconciliation/
            reconciler.reconcile(driver);
            if (!reconciler.isReconciled()) {
                LOGGER.info("Reconciliation is still in progress.");
                return;
            }

            // Coordinate amongst all the plans via PlanCoordinator.
            final List<Protos.OfferID> acceptedOffers = new ArrayList<>();
            acceptedOffers.addAll(planCoordinator.processOffers(driver, offers));

            List<Protos.Offer> unusedOffers = OfferUtils.filterOutAcceptedOffers(offers, acceptedOffers);
            offers.clear();
            offers.addAll(unusedOffers);

            // Resource Cleaning:
            // A ResourceCleaner ensures that reserved Resources are not leaked.  It is possible that an Agent may
            // become inoperable for long enough that Tasks resident there were relocated.  However, this Agent may
            // return at a later point and begin offering reserved Resources again.  To ensure that these unexpected
            // reserved Resources are returned to the Mesos Cluster, the Resource Cleaner performs all necessary
            // UNRESERVE and DESTROY (in the case of persistent volumes) Operations.
            // Note: If there are unused reserved resources on a dirtied offer, then it will be cleaned in the next
            // offer cycle.
            final Optional<ResourceCleanerScheduler> cleanerScheduler = getCleanerScheduler();
            if (cleanerScheduler.isPresent()) {
                acceptedOffers.addAll(cleanerScheduler.get().resourceOffers(driver, offers));
            }

            unusedOffers = OfferUtils.filterOutAcceptedOffers(offers, acceptedOffers);
            offers.clear();
            offers.addAll(unusedOffers);

            // Decline remaining offers.
            declineOffers(driver, acceptedOffers, offers);
        });
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
        LOGGER.error("Rescinding offers is not supported.");
        hardExit(SchedulerErrorCode.OFFER_RESCINDED);
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                LOGGER.info(String.format(
                        "Received status update for taskId=%s state=%s message='%s'",
                        status.getTaskId().getValue(),
                        status.getState().toString(),
                        status.getMessage()));

                // Store status, then pass status to PlanManager => Plan => Steps
                try {
                    stateStore.storeStatus(status);
                    deploymentPlanManager.update(status);
                    recoveryPlanManager.update(status);
                    reconciler.update(status);

                    if (TaskUtils.needsRecovery(status)) {
                        revive();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to update TaskStatus received from Mesos. "
                            + "This may be expected if Mesos sent stale status information: " + status, e);
                }
            }
        });
    }

    @Override
    public void frameworkMessage(
            SchedulerDriver driver,
            Protos.ExecutorID executorId,
            Protos.SlaveID slaveId,
            byte[] data) {
        LOGGER.error("Received a Framework Message, but don't know how to process it");
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        LOGGER.error("Disconnected from Master.");
        hardExit(SchedulerErrorCode.DISCONNECTED);
    }

    @Override
    public void slaveLost(SchedulerDriver driver, Protos.SlaveID agentId) {
        // TODO: Add recovery optimizations relevant to loss of an Agent.  TaskStatus updates are sufficient now.
        LOGGER.warn("Agent lost: " + agentId);
    }

    @Override
    public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, int status) {
        // TODO: Add recovery optimizations relevant to loss of an Executor.  TaskStatus updates are sufficient now.
        LOGGER.warn(String.format("Lost Executor: %s on Agent: %s", executorId, slaveId));
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOGGER.error("SchedulerDriver failed with message: " + message);

        // Update or remove this when uninstall is solved:
        if (message.contains(UNINSTALL_INCOMPLETE_ERROR_MESSAGE)) {
            // Scenario:
            // - User installs service X
            // - X registers against a new framework ID, then stores that ID in ZK
            // - User uninstalls service X without wiping ZK and/or resources
            // - User reinstalls service X
            // - X sees previous framework ID in ZK and attempts to register against it
            // - Mesos returns this error because that framework ID is no longer available for use
            LOGGER.error("This error is usually the result of an incomplete cleanup of Zookeeper "
                    + "and/or reserved resources following a previous uninstall of the service.");
            LOGGER.error("Please uninstall this service, read and perform the steps described at "
                    + UNINSTALL_INSTRUCTIONS_URI + " to delete the reserved resources, and then "
                    + "install this service once more.");
        }

        hardExit(SchedulerErrorCode.ERROR);
    }

    private void suppressOrRevive() {
        if (planCoordinator.hasOperations()) {
            revive();
        } else {
            suppress();
        }
    }

    private void suppress() {
        LOGGER.info("Suppressing offers.");
        driver.suppressOffers();
        stateStore.setSuppressed(true);
    }

    private void revive() {
        LOGGER.info("Reviving offers.");
        driver.reviveOffers();
        stateStore.setSuppressed(false);
    }

    @Override
    public void update(Observable observable) {
        if (observable == planCoordinator) {
            suppressOrRevive();
        }
    }
}
