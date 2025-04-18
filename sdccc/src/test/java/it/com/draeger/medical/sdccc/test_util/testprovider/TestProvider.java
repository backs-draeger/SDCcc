/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package it.com.draeger.medical.sdccc.test_util.testprovider;

import com.google.inject.Injector;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.somda.sdc.biceps.common.storage.PreprocessingException;
import org.somda.sdc.biceps.model.participant.Mdib;
import org.somda.sdc.dpws.soap.wseventing.SubscriptionManager;
import org.somda.sdc.glue.provider.SdcDevice;

/**
 * An SDC provider used for testing.
 */
public interface TestProvider {

    /**
     * Start the SDC provider.
     *
     * @param waitTime time to wait for startup
     * @throws TimeoutException       in case startup did not finish on time
     * @throws PreprocessingException in case startup failed
     */
    void startService(Duration waitTime) throws TimeoutException, PreprocessingException;

    /**
     * Stop the SDC provider.
     *
     * @param waitTime time to wait for shutdown
     * @throws TimeoutException in case shutdown did not finish on time
     */
    void stopService(Duration waitTime) throws TimeoutException;

    Injector getInjector();

    SdcDevice getSdcDevice();

    Mdib getMdib();

    /**
     * Gets all currently active subscriptions for the Provider.
     *
     * @return currently active subscriptions
     */
    Map<String, SubscriptionManager> getActiveSubscriptions();
}
