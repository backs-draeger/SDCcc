/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package com.draeger.medical.sdccc.manipulation.precondition.impl;

import com.draeger.medical.sdccc.manipulation.Manipulations;
import com.draeger.medical.sdccc.manipulation.precondition.PreconditionException;
import com.draeger.medical.sdccc.manipulation.precondition.SimplePrecondition;
import com.draeger.medical.sdccc.messages.MessageStorage;
import com.draeger.medical.sdccc.messages.mapping.MessageContent;
import com.draeger.medical.sdccc.sdcri.testclient.TestClient;
import com.draeger.medical.sdccc.tests.util.ImpliedValueUtil;
import com.draeger.medical.sdccc.util.Constants;
import com.draeger.medical.sdccc.util.TestRunObserver;
import com.draeger.medical.t2iapi.ResponseTypes;
import com.google.inject.Injector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.somda.sdc.biceps.common.MdibEntity;
import org.somda.sdc.biceps.common.access.MdibAccess;
import org.somda.sdc.biceps.model.message.AbstractContextReport;
import org.somda.sdc.biceps.model.message.DescriptionModificationReport;
import org.somda.sdc.biceps.model.message.DescriptionModificationType;
import org.somda.sdc.biceps.model.message.EpisodicContextReport;
import org.somda.sdc.biceps.model.participant.AbstractContextDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractContextState;
import org.somda.sdc.biceps.model.participant.AbstractDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractMultiState;
import org.somda.sdc.biceps.model.participant.ContextAssociation;
import org.somda.sdc.biceps.model.participant.EnsembleContextDescriptor;
import org.somda.sdc.biceps.model.participant.EnsembleContextState;
import org.somda.sdc.biceps.model.participant.LocationContextDescriptor;
import org.somda.sdc.biceps.model.participant.LocationContextState;
import org.somda.sdc.biceps.model.participant.MdsDescriptor;
import org.somda.sdc.biceps.model.participant.MeansContextDescriptor;
import org.somda.sdc.biceps.model.participant.MeansContextState;
import org.somda.sdc.biceps.model.participant.OperatorContextDescriptor;
import org.somda.sdc.biceps.model.participant.OperatorContextState;
import org.somda.sdc.biceps.model.participant.PatientContextDescriptor;
import org.somda.sdc.biceps.model.participant.PatientContextState;
import org.somda.sdc.biceps.model.participant.WorkflowContextDescriptor;
import org.somda.sdc.biceps.model.participant.WorkflowContextState;
import org.somda.sdc.dpws.soap.MarshallingService;
import org.somda.sdc.dpws.soap.SoapUtil;
import org.somda.sdc.dpws.soap.exception.MarshallingException;
import org.somda.sdc.glue.consumer.SdcRemoteDevice;

/**
 * A collection of conditional preconditions.
 */
public class ConditionalPreconditions {

    private static boolean descriptionModificationPreconditionCheck(
            final Injector injector, final DescriptionModificationType... modificationTypes)
            throws PreconditionException {
        final var modificationTypesList = List.of(modificationTypes);
        final var messageStorage = injector.getInstance(MessageStorage.class);
        final var testClient = injector.getInstance(TestClient.class);
        final var clientInjector = testClient.getInjector();
        final var marshalling = clientInjector.getInstance(MarshallingService.class);
        final var soapUtil = clientInjector.getInstance(SoapUtil.class);
        try (final var messages =
                messageStorage.getInboundMessagesByBodyType(Constants.MSG_DESCRIPTION_MODIFICATION_REPORT)) {
            // determine if there were any description insertions or deletions
            return messages.getStream()
                    .map(MessageContent::getBody)
                    .map(body -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                    .map(body -> {
                        try {
                            return marshalling.unmarshal(body);
                        } catch (MarshallingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(message -> soapUtil.getBody(message, DescriptionModificationReport.class)
                            .orElseThrow(() -> new RuntimeException(
                                    "Could not retrieve description modification report body from message")))
                    .anyMatch(message -> message.getReportPart().stream()
                            .map(ImpliedValueUtil::getModificationType)
                            .anyMatch(modificationTypesList::contains));
        } catch (final IOException e) {
            throw new PreconditionException(
                    "An error occurred while trying to retrieve description modification report messages from storage",
                    e);
            // there is no other way to retrieve the exception from the stream scope
        } catch (final RuntimeException e) {
            throw new PreconditionException(
                    "An error occurred while trying to process description modification report messages from storage",
                    e);
        }
    }

    private static boolean descriptionModificationManipulation(final Injector injector, final Logger logger) {
        final var manipulations = injector.getInstance(Manipulations.class);
        final var testClient = injector.getInstance(TestClient.class);

        final MdibAccess mdibAccess;
        final SdcRemoteDevice remoteDevice;

        remoteDevice = testClient.getSdcRemoteDevice();
        if (remoteDevice == null) {
            logger.error("remote device could not be accessed, likely due to a disconnect");
            return false;
        }
        mdibAccess = remoteDevice.getMdibAccess();

        final var modifiableDescriptors =
                manipulations.getRemovableDescriptorsOfClass().getResponse();
        logger.debug("Changing presence for descriptors {}", modifiableDescriptors);

        if (modifiableDescriptors == null) {
            logger.info("No modifiable descriptors available for manipulation");
            return false;
        }

        final var manipulationResults = new HashSet<ResponseTypes.Result>();
        for (String handle : modifiableDescriptors) {
            // determine if descriptor is currently present
            var descriptorEntity = mdibAccess.getEntity(handle);
            logger.debug("Descriptor {} presence: {}", handle, descriptorEntity.isPresent());

            // if the descriptor is not present, insert it first
            if (descriptorEntity.isEmpty()) {
                manipulationResults.add(manipulations.insertDescriptor(handle).getResult());
                descriptorEntity = mdibAccess.getEntity(handle);
                if (descriptorEntity.isEmpty()) {
                    manipulationResults.add(ResponseTypes.Result.RESULT_FAIL);
                    logger.error("Descriptor {} couldn't be inserted, manipulation failed", handle);
                }
                logger.debug("Descriptor {} presence: {}", handle, descriptorEntity.isPresent());
            }

            // remove descriptor
            manipulationResults.add(manipulations.removeDescriptor(handle).getResult());
            descriptorEntity = mdibAccess.getEntity(handle);
            if (descriptorEntity.isPresent()) {
                manipulationResults.add(ResponseTypes.Result.RESULT_FAIL);
                logger.error("Descriptor {} couldn't be removed, manipulation failed", handle);
            }
            logger.debug("Descriptor {} presence: {}", handle, descriptorEntity.isPresent());

            // reinsert descriptor
            manipulationResults.add(manipulations.insertDescriptor(handle).getResult());
            descriptorEntity = mdibAccess.getEntity(handle);
            if (descriptorEntity.isEmpty()) {
                manipulationResults.add(ResponseTypes.Result.RESULT_FAIL);
                logger.error("Descriptor {} couldn't be reinserted, manipulation failed", handle);
            }
            logger.debug("Descriptor {} presence: {}", handle, descriptorEntity.isPresent());

            if (manipulationResults.contains(ResponseTypes.Result.RESULT_FAIL)) {
                logger.info("Could not successfully modify descriptor {}, stopping the precondition", handle);
                break;
            }
        }

        return !manipulationResults.contains(ResponseTypes.Result.RESULT_FAIL)
                && !manipulationResults.contains(ResponseTypes.Result.RESULT_NOT_IMPLEMENTED)
                && manipulationResults.contains(ResponseTypes.Result.RESULT_SUCCESS);
    }

    private static boolean descriptionUpdateManipulation(final Injector injector) {
        final var manipulations = injector.getInstance(Manipulations.class);

        final ResponseTypes.Result manipulationResult =
                manipulations.triggerAnyDescriptorUpdate().getResult();

        return ResponseTypes.Result.RESULT_SUCCESS.equals(manipulationResult);
    }

    private static boolean triggerReportPreconditionCheck(
            final Injector injector, final Logger log, final QName... reportType) throws PreconditionException {
        final var messageStorage = injector.getInstance(MessageStorage.class);
        try (final var messages = messageStorage.getInboundMessagesByBodyType(reportType)) {
            // determine if there were any reports with the specified type
            final var areReportsPresent = messages.areObjectsPresent();
            log.info("Reports of types {} are present: {}", reportType, areReportsPresent);
            return areReportsPresent;
        } catch (IOException e) {
            throw new PreconditionException(
                    String.format(
                            "An error occurred while trying to retrieve %s report messages from storage",
                            Arrays.stream(reportType).map(QName::toString).collect(Collectors.joining(", "))),
                    e);
        }
    }

    private static boolean triggerReportManipulation(
            final Injector injector, final Logger log, final QName reportType) {
        final var manipulations = injector.getInstance(Manipulations.class);
        log.info("Executing triggerReport manipulation for {}", reportType);
        final var result = manipulations.triggerReport(reportType).getResult();
        return result == ResponseTypes.Result.RESULT_SUCCESS;
    }

    /**
     * Precondition which checks whether any Hello message has been received, triggering a Hello message otherwise.
     */
    public static class HelloMessagePrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(HelloMessagePrecondition.class);

        /**
         * Creates a hello message precondition check.
         */
        public HelloMessagePrecondition() {
            super(HelloMessagePrecondition::preconditionCheck, HelloMessagePrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            final var messageStorage = injector.getInstance(MessageStorage.class);
            try (final var messages = messageStorage.getInboundMessagesByBodyType(false, Constants.WSD_HELLO_BODY)) {
                return messages.areObjectsPresent();
            } catch (final IOException e) {
                throw new PreconditionException(
                        "An error occurred while trying to retrieve hello messages from storage", e);
            }
        }

        static boolean manipulation(final Injector injector) {
            final var manipulations = injector.getInstance(Manipulations.class);
            final var result = manipulations.sendHello().getResult();
            LOG.info("Manipulation to send Hello message was {}", result);
            return result == ResponseTypes.Result.RESULT_SUCCESS;
        }
    }

    /**
     * Precondition that checks whether DescriptionModificationReport messages containing an insertion have been
     * received, triggering description modifications otherwise.
     */
    public static class DescriptionModificationCrtPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(DescriptionModificationCrtPrecondition.class);

        /**
         * Creates a description modification crt precondition check.
         */
        public DescriptionModificationCrtPrecondition() {
            super(
                    DescriptionModificationCrtPrecondition::preconditionCheck,
                    DescriptionModificationCrtPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return descriptionModificationPreconditionCheck(injector, DescriptionModificationType.CRT);
        }

        /**
         * Performs the removal and reinsertion of descriptors in the mdib to trigger reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return descriptionModificationManipulation(injector, LOG);
        }
    }

    /**
     * Precondition that checks whether DescriptionModificationReport messages containing an update have been
     * received, triggering description modifications otherwise.
     */
    public static class DescriptionModificationUptPrecondition extends SimplePrecondition {

        /**
         * Creates a description modification upt precondition check.
         */
        public DescriptionModificationUptPrecondition() {
            super(
                    DescriptionModificationUptPrecondition::preconditionCheck,
                    DescriptionModificationUptPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return descriptionModificationPreconditionCheck(injector, DescriptionModificationType.UPT);
        }

        /**
         * Performs the update of a chosen descriptor in the mdib to trigger reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return descriptionUpdateManipulation(injector);
        }
    }

    /**
     * Precondition that checks whether DescriptionModificationReport messages containing a deletion have been
     * received, triggering description modifications otherwise.
     */
    public static class DescriptionModificationDelPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(DescriptionModificationDelPrecondition.class);

        /**
         * Creates a description modification del precondition check.
         */
        public DescriptionModificationDelPrecondition() {
            super(
                    DescriptionModificationDelPrecondition::preconditionCheck,
                    DescriptionModificationDelPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return descriptionModificationPreconditionCheck(injector, DescriptionModificationType.DEL);
        }

        /**
         * Performs the removal and reinsertion of descriptors in the mdib to trigger reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return descriptionModificationManipulation(injector, LOG);
        }
    }

    /**
     * Precondition which checks whether DescriptionModificationReport messages containing an insertion
     * update and deletion of an MdsDescriptor have been received, triggering description changes otherwise.
     */
    public static class DescriptionModificationMdsDescriptorPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(DescriptionModificationMdsDescriptorPrecondition.class);

        /**
         * Creates a description changed for mds descriptor precondition check.
         */
        public DescriptionModificationMdsDescriptorPrecondition() {
            super(
                    DescriptionModificationMdsDescriptorPrecondition::preconditionCheck,
                    DescriptionModificationMdsDescriptorPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            final var messageStorage = injector.getInstance(MessageStorage.class);
            final var testClient = injector.getInstance(TestClient.class);
            final var clientInjector = testClient.getInjector();
            final var marshalling = clientInjector.getInstance(MarshallingService.class);
            final var soapUtil = clientInjector.getInstance(SoapUtil.class);
            final var crtSeen = new AtomicBoolean(false);
            final var uptSeen = new AtomicBoolean(false);
            final var delSeen = new AtomicBoolean(false);
            try (final var messages =
                    messageStorage.getInboundMessagesByBodyType(Constants.MSG_DESCRIPTION_MODIFICATION_REPORT)) {
                // determine if there were a description insertion, update and deletion for an mds descriptor
                final var reportParts = messages.getStream()
                        .map(MessageContent::getBody)
                        .map(body -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                        .map(body -> {
                            try {
                                return marshalling.unmarshal(body);
                            } catch (MarshallingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .map(message -> soapUtil.getBody(message, DescriptionModificationReport.class)
                                .orElseThrow(() -> new RuntimeException(
                                        "Could not retrieve description modification report body from message")))
                        .map(DescriptionModificationReport::getReportPart)
                        .flatMap(Collection::stream)
                        .toList();
                for (var reportPart : reportParts) {
                    final var mdsDescriptorPresent = reportPart.getDescriptor().stream()
                            .filter(abstractDescriptor ->
                                    abstractDescriptor.getClass().equals(MdsDescriptor.class))
                            .findAny();
                    if (mdsDescriptorPresent.isPresent()) {
                        switch (ImpliedValueUtil.getModificationType(reportPart)) {
                            case CRT -> crtSeen.set(true);
                            case UPT -> uptSeen.set(true);
                            case DEL -> delSeen.set(true);
                            default -> {}
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw new PreconditionException(
                        "An error occurred while trying to process description modification report messages from storage",
                        e);
            }
            return crtSeen.get() && uptSeen.get() && delSeen.get();
        }

        static boolean manipulation(final Injector injector) {
            final var manipulations = injector.getInstance(Manipulations.class);
            final var testClient = injector.getInstance(TestClient.class);
            final TestRunObserver testRunObserver = injector.getInstance(TestRunObserver.class);

            final MdibAccess mdibAccess;
            final SdcRemoteDevice remoteDevice = testClient.getSdcRemoteDevice();
            if (remoteDevice == null) {
                LOG.error("remote device could not be accessed, likely due to a disconnect");
                return false;
            }
            mdibAccess = remoteDevice.getMdibAccess();

            final List<String> removableMdsDescriptors = manipulations
                    .getRemovableDescriptorsOfClass(MdsDescriptor.class)
                    .getResponse();
            if (removableMdsDescriptors == null) {
                LOG.error("No removable MdsDescriptors could be found via the GetRemovableDescriptorsOfType "
                        + "manipulation. Please check if the test case applying this precondition is applicable to "
                        + "your device and if the GetRemovableDescriptorsOfType manipulation has been implemented "
                        + "correctly.");
                return false;
            }

            // absent:        present:
            // 1. insert*
            // 2. update      1. update
            // 3. remove      2. remove*
            //                3. insert
            //
            // * = abort when this manipulation fails

            final EnumMap<DescriptionModificationType, Integer> numberOfSuccessfulTriggers =
                    new EnumMap<>(DescriptionModificationType.class);

            try {
                for (String removableMdsDescriptor : removableMdsDescriptors) {
                    final Optional<AbstractDescriptor> descOpt = mdibAccess.getDescriptor(removableMdsDescriptor);
                    if (descOpt.isEmpty()) {
                        // removable MdsDescriptor is absent
                        triggerAllDescriptorUpdatesForInitiallyAbsentMdsDescriptor(
                                removableMdsDescriptor, manipulations, numberOfSuccessfulTriggers, testRunObserver);
                    } else {
                        // removable MdsDescriptor is present
                        triggerAllDescriptorUpdatesForInitiallyPresentMdsDescriptor(
                                removableMdsDescriptor, manipulations, numberOfSuccessfulTriggers, testRunObserver);
                    }
                    if (isGoalReached(numberOfSuccessfulTriggers)) {
                        return true;
                    }
                }
            } catch (UnexpectedManipulationResultException e) {
                return false;
            }

            // all options exhausted and the goal is still not reached
            LOG.error("Unable to find any MdsDescriptors using the GetRemovableDescriptorsOfClass() manipulation "
                    + "that can be inserted, updated and removed (at least one for each is required for the test "
                    + "applying this precondition). "
                    + "Please check if the test case applying this precondition is applicable to your device and if the "
                    + "GetRemovableDescriptorsOfClass, InsertDescriptor, RemoveDescriptor, and TriggerDescriptorUpdate "
                    + "manipulations have been implemented correctly.");
            return false;
        }

        private static boolean isGoalReached(
                final EnumMap<DescriptionModificationType, Integer> numberOfSuccessfulTriggers) {
            final Integer crt = numberOfSuccessfulTriggers.get(DescriptionModificationType.CRT);
            final Integer upt = numberOfSuccessfulTriggers.get(DescriptionModificationType.UPT);
            final Integer del = numberOfSuccessfulTriggers.get(DescriptionModificationType.DEL);
            return crt != null && crt > 0 && upt != null && upt > 0 && del != null && del > 0;
        }

        private static void triggerAllDescriptorUpdatesForInitiallyAbsentMdsDescriptor(
                final String initiallyAbsentMdsDescriptor,
                final Manipulations manipulations,
                final EnumMap<DescriptionModificationType, Integer> numberOfSuccessfulTriggers,
                final TestRunObserver testRunObserver)
                throws UnexpectedManipulationResultException {

            // 1. insert
            ResponseTypes.Result result =
                    manipulations.insertDescriptor(initiallyAbsentMdsDescriptor).getResult();
            LOG.info("Manipulation insertDescriptor({}) returned result {}", initiallyAbsentMdsDescriptor, result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.CRT);
                    break;
                case RESULT_NOT_SUPPORTED:
                    return;
                default:
                    failDueToUnexpectedResult(
                            "insertDescriptor", initiallyAbsentMdsDescriptor, result, testRunObserver);
            }

            // 2. update
            result = manipulations
                    .triggerDescriptorUpdate(initiallyAbsentMdsDescriptor)
                    .getResult();
            LOG.info(
                    "Manipulation triggerDescriptorUpdate({}) returned result {}",
                    initiallyAbsentMdsDescriptor,
                    result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.UPT);
                    break;
                case RESULT_NOT_SUPPORTED:
                    break; // continue with this descriptor
                default:
                    failDueToUnexpectedResult(
                            "triggerDescriptorUpdate", initiallyAbsentMdsDescriptor, result, testRunObserver);
            }

            // 3. remove
            result =
                    manipulations.removeDescriptor(initiallyAbsentMdsDescriptor).getResult();
            LOG.info("Manipulation removeDescriptor({}) returned result {}", initiallyAbsentMdsDescriptor, result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.DEL);
                    break;
                case RESULT_NOT_SUPPORTED:
                    break; // do nothing
                default:
                    failDueToUnexpectedResult(
                            "removeDescriptor", initiallyAbsentMdsDescriptor, result, testRunObserver);
            }
        }

        private static void triggerAllDescriptorUpdatesForInitiallyPresentMdsDescriptor(
                final String initiallyPresentMdsDescriptor,
                final Manipulations manipulations,
                final EnumMap<DescriptionModificationType, Integer> numberOfSuccessfulTriggers,
                final TestRunObserver testRunObserver)
                throws UnexpectedManipulationResultException {

            // 1. update
            ResponseTypes.Result result = manipulations
                    .triggerDescriptorUpdate(initiallyPresentMdsDescriptor)
                    .getResult();
            LOG.info(
                    "Manipulation triggerDescriptorUpdate({}) returned result {}",
                    initiallyPresentMdsDescriptor,
                    result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.UPT);
                    break;
                case RESULT_NOT_SUPPORTED:
                    break; // continue with this descriptor
                default:
                    failDueToUnexpectedResult(
                            "triggerDescriptorUpdate", initiallyPresentMdsDescriptor, result, testRunObserver);
            }

            // 2. remove
            result = manipulations
                    .removeDescriptor(initiallyPresentMdsDescriptor)
                    .getResult();
            LOG.info("Manipulation removeDescriptor({}) returned result {}", initiallyPresentMdsDescriptor, result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.DEL);
                    break;
                case RESULT_NOT_SUPPORTED:
                    return;
                default:
                    failDueToUnexpectedResult(
                            "removeDescriptor", initiallyPresentMdsDescriptor, result, testRunObserver);
            }

            // 3. re-insert
            result = manipulations
                    .insertDescriptor(initiallyPresentMdsDescriptor)
                    .getResult();
            LOG.info("Manipulation insertDescriptor({}) returned result {}", initiallyPresentMdsDescriptor, result);
            switch (result) {
                case RESULT_SUCCESS:
                    increaseNumberInHash(numberOfSuccessfulTriggers, DescriptionModificationType.CRT);
                    break;
                case RESULT_NOT_SUPPORTED:
                    break; // do nothing
                default:
                    failDueToUnexpectedResult(
                            "insertDescriptor", initiallyPresentMdsDescriptor, result, testRunObserver);
            }
        }

        private static void failDueToUnexpectedResult(
                final String manipulationName,
                final String argument,
                final ResponseTypes.Result result,
                final TestRunObserver testRunObserver)
                throws UnexpectedManipulationResultException {
            testRunObserver.invalidateTestRun(String.format(
                    "Unexpected manipulation result: The %s(\"%s\") manipulation returned %s.",
                    manipulationName, argument, result));
            throw new UnexpectedManipulationResultException();
        }

        private static void increaseNumberInHash(
                final EnumMap<DescriptionModificationType, Integer> hash, final DescriptionModificationType key) {
            if (hash.containsKey(key)) {
                hash.put(key, hash.get(key) + 1);
            } else {
                hash.put(key, 1);
            }
        }

        static class UnexpectedManipulationResultException extends Exception {

            UnexpectedManipulationResultException() {}
        }
    }

    /**
     * Precondition which checks whether report messages containing state changes have been received,
     * triggering reports otherwise.
     */
    public static class StateChangedPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(StateChangedPrecondition.class);

        /**
         * Creates a state changed precondition check.
         */
        public StateChangedPrecondition() {
            super(StateChangedPrecondition::preconditionCheck, StateChangedPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(
                    injector,
                    LOG,
                    Constants.MSG_EPISODIC_ALERT_REPORT,
                    Constants.MSG_EPISODIC_COMPONENT_REPORT,
                    Constants.MSG_EPISODIC_METRIC_REPORT,
                    Constants.MSG_EPISODIC_OPERATIONAL_STATE_REPORT,
                    Constants.MSG_EPISODIC_CONTEXT_REPORT);
        }

        static boolean manipulation(final Injector injector) {
            final var reportTypes = List.of(
                    Constants.MSG_EPISODIC_ALERT_REPORT,
                    Constants.MSG_EPISODIC_COMPONENT_REPORT,
                    Constants.MSG_EPISODIC_METRIC_REPORT,
                    Constants.MSG_EPISODIC_OPERATIONAL_STATE_REPORT,
                    Constants.MSG_EPISODIC_CONTEXT_REPORT);
            final var manipulations = injector.getInstance(Manipulations.class);
            LOG.info("Executing triggerReport manipulation for {}", reportTypes);
            final var results = new HashSet<ResponseTypes.Result>();
            for (var reportType : reportTypes) {
                results.add(manipulations.triggerReport(reportType).getResult());
            }
            return results.contains(ResponseTypes.Result.RESULT_SUCCESS)
                    && !results.contains(ResponseTypes.Result.RESULT_NOT_IMPLEMENTED)
                    && !results.contains(ResponseTypes.Result.RESULT_FAIL);
        }
    }

    /**
     * Precondition which checks whether DescriptionModificationReport messages containing an insertion
     * or deletion have been received, triggering description changes otherwise.
     */
    public static class DescriptionModificationCrtOrDelPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(DescriptionModificationCrtOrDelPrecondition.class);

        /**
         * Creates a description modification crt or del precondition check.
         */
        public DescriptionModificationCrtOrDelPrecondition() {
            super(
                    DescriptionModificationCrtOrDelPrecondition::preconditionCheck,
                    DescriptionModificationCrtOrDelPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return descriptionModificationPreconditionCheck(
                    injector, DescriptionModificationType.CRT, DescriptionModificationType.DEL);
        }

        /**
         * Performs the removal and reinsertion of descriptors in the mdib to trigger reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return descriptionModificationManipulation(injector, LOG);
        }
    }

    /**
     * Precondition which checks whether all context descriptors  had at least two different context states
     * associated, triggering context state associations otherwise.
     */
    public static class AllKindsOfContextStatesAssociatedPrecondition extends SimplePrecondition {

        static final Map<Class<? extends AbstractContextState>, Set<String>> ALREADY_ASSOCIATED_CONTEXTS;

        static {
            ALREADY_ASSOCIATED_CONTEXTS = new HashMap<>();
            ALREADY_ASSOCIATED_CONTEXTS.put(PatientContextState.class, new HashSet<>());
            ALREADY_ASSOCIATED_CONTEXTS.put(LocationContextState.class, new HashSet<>());
            ALREADY_ASSOCIATED_CONTEXTS.put(EnsembleContextState.class, new HashSet<>());
            ALREADY_ASSOCIATED_CONTEXTS.put(MeansContextState.class, new HashSet<>());
            ALREADY_ASSOCIATED_CONTEXTS.put(OperatorContextState.class, new HashSet<>());
            ALREADY_ASSOCIATED_CONTEXTS.put(WorkflowContextState.class, new HashSet<>());
        }

        private static final Logger LOG = LogManager.getLogger(AllKindsOfContextStatesAssociatedPrecondition.class);

        /**
         * Creates a precondition check, if all kind of context where associated.
         */
        public AllKindsOfContextStatesAssociatedPrecondition() {
            super(
                    AllKindsOfContextStatesAssociatedPrecondition::preconditionCheck,
                    AllKindsOfContextStatesAssociatedPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            final var messageStorage = injector.getInstance(MessageStorage.class);
            final var testClient = injector.getInstance(TestClient.class);
            final var clientInjector = testClient.getInjector();
            final var marshalling = clientInjector.getInstance(MarshallingService.class);
            final var soapUtil = clientInjector.getInstance(SoapUtil.class);
            final var contextStates = new ArrayList<AbstractContextState>();
            ALREADY_ASSOCIATED_CONTEXTS.values().forEach(Set::clear);
            try (final var messages =
                    messageStorage.getInboundMessagesByBodyType(Constants.MSG_EPISODIC_CONTEXT_REPORT)) {
                // determine if there were any context state changes
                messages.getStream()
                        .map(MessageContent::getBody)
                        .map(body -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                        .map(body -> {
                            try {
                                return marshalling.unmarshal(body);
                            } catch (MarshallingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .map(message -> soapUtil.getBody(message, EpisodicContextReport.class)
                                .orElseThrow(() -> new RuntimeException(
                                        "Could not retrieve episodic context report body from message")))
                        .forEach(message -> message.getReportPart().stream()
                                .map(AbstractContextReport.ReportPart::getContextState)
                                .toList()
                                .forEach(contextStates::addAll));
                for (var state : contextStates) {
                    if (ImpliedValueUtil.getContextAssociation(state) == ContextAssociation.ASSOC) {
                        ALREADY_ASSOCIATED_CONTEXTS.get(state.getClass()).add(state.getHandle());
                    }
                }
            } catch (final IOException e) {
                throw new PreconditionException(
                        "An error occurred while trying to retrieve description modification report messages from storage",
                        e);
                // there is no other way to retrieve the exception from the stream scope
            } catch (final RuntimeException e) {
                throw new PreconditionException(
                        "An error occurred while trying to process description modification report messages from storage",
                        e);
            }
            return enoughContextStatesSeen();
        }

        private static boolean enoughContextStatesSeen() {
            for (var entry : ALREADY_ASSOCIATED_CONTEXTS.entrySet()) {
                if (entry.getValue().size() < 2) {
                    return false;
                }
            }
            return true;
        }

        static boolean manipulation(final Injector injector) throws PreconditionException {
            final var testClient = injector.getInstance(TestClient.class);
            final var manipulations = injector.getInstance(Manipulations.class);
            final var testRunObserver = injector.getInstance(TestRunObserver.class);

            final MdibAccess mdibAccess;
            final SdcRemoteDevice remoteDevice;

            remoteDevice = testClient.getSdcRemoteDevice();
            if (remoteDevice == null) {
                LOG.error("remote device could not be accessed, likely due to a disconnect");
                return false;
            }
            mdibAccess = remoteDevice.getMdibAccess();

            var manipulationSuccessful = true;
            var entitiesSeen = false;

            for (var entry : ALREADY_ASSOCIATED_CONTEXTS.entrySet()) {
                final var entities = mdibAccess.findEntitiesByType(getDescriptorClass(entry.getKey()));
                if (!entities.isEmpty()) {
                    entitiesSeen = true;
                    if (entry.getValue().size() < 2) {
                        manipulationSuccessful &= manipulateContextState(
                                entities, entry.getKey(), testClient, manipulations, testRunObserver);
                    }
                }
            }

            return manipulationSuccessful && entitiesSeen;
        }

        private static Class<? extends AbstractContextDescriptor> getDescriptorClass(
                final Class<? extends AbstractContextState> stateClass) throws PreconditionException {
            Class<? extends AbstractContextDescriptor> descriptorClass = null;
            if (stateClass.equals(PatientContextState.class)) {
                descriptorClass = PatientContextDescriptor.class;
            } else if (stateClass.equals(LocationContextState.class)) {
                descriptorClass = LocationContextDescriptor.class;
            } else if (stateClass.equals(EnsembleContextState.class)) {
                descriptorClass = EnsembleContextDescriptor.class;
            } else if (stateClass.equals(MeansContextState.class)) {
                descriptorClass = MeansContextDescriptor.class;
            } else if (stateClass.equals(OperatorContextState.class)) {
                descriptorClass = OperatorContextDescriptor.class;
            } else if (stateClass.equals(WorkflowContextState.class)) {
                descriptorClass = WorkflowContextDescriptor.class;
            }
            if (descriptorClass == null) {
                throw new PreconditionException(String.format("Unknown context state class %s", stateClass));
            } else {
                return descriptorClass;
            }
        }

        private static boolean manipulateContextState(
                final Collection<MdibEntity> contextEntities,
                final Class<? extends AbstractContextState> contextStateClass,
                final TestClient testClient,
                final Manipulations manipulations,
                final TestRunObserver testRunObserver) {
            LOG.info("Associating new context states for {} handle(s)", contextEntities.size());
            for (MdibEntity entity : contextEntities) {
                // store previously existing handles for comparison
                final var originalStates = entity.getStates(contextStateClass).stream()
                        .map(AbstractMultiState::getHandle)
                        .collect(Collectors.toSet());
                // associate first new context state
                var newStateHandle = associateNewContextForHandle(
                        testClient.getSdcRemoteDevice(),
                        manipulations,
                        entity.getHandle(),
                        originalStates,
                        contextStateClass);

                // associate another one if the first one worked out
                if (newStateHandle != null) {
                    // add new state to known states
                    originalStates.add(newStateHandle);
                    newStateHandle = associateNewContextForHandle(
                            testClient.getSdcRemoteDevice(),
                            manipulations,
                            entity.getHandle(),
                            originalStates,
                            contextStateClass);
                }

                if (newStateHandle == null) {
                    testRunObserver.invalidateTestRun(
                            String.format("Associating a new context state for handle %s failed", entity.getHandle()));
                    return false;
                }
            }
            return !contextEntities.isEmpty();
        }

        /**
         * Associates a new context state for a given descriptor handle.
         *
         * @param device               the context state will appear in, used for validation
         * @param manipulations        to call for insertion of state
         * @param handle               of the descriptor to insert a new state for
         * @param previousStateHandles previously present state handles, to ensure new state is actually new
         * @param contextStateClass    of the context state
         * @return handle of new valid state, or empty
         */
        static String associateNewContextForHandle(
                final SdcRemoteDevice device,
                final Manipulations manipulations,
                final String handle,
                final Collection<String> previousStateHandles,
                final Class<? extends AbstractContextState> contextStateClass) {
            LOG.debug("Associating new context state for handle {}", handle);
            var stateHandle = manipulations
                    .createContextStateWithAssociation(handle, ContextAssociation.ASSOC)
                    .getResponse();
            if (stateHandle == null) {
                LOG.error("Associating new context state failed for handle {}", handle);
                return null;
            }
            LOG.debug("New context created, state handle is {}", stateHandle);
            final var validState = verifyStatePresentAndAssociated(
                    device, handle, stateHandle, previousStateHandles, contextStateClass);
            if (!validState) {
                LOG.error("Validation for new context state {} failed", stateHandle);
                // remove state handle from return value in invalid cases
                stateHandle = null;
            }
            return stateHandle;
        }

        /**
         * Verifies that a context state with the given handle is present, associated and attached to the
         * correct descriptor. Additionally, verifies that the new state handle was not present before executing
         * the manipulation.
         *
         * @param device               to verify mdib for
         * @param descriptorHandle     the state must be attached to
         * @param stateHandle          of state to verify
         * @param previousStateHandles previously present state handles, to ensure new state is actually new
         * @param contextStateClass    of the context state
         * @return true if valid, false otherwise
         */
        static boolean verifyStatePresentAndAssociated(
                final SdcRemoteDevice device,
                final String descriptorHandle,
                final String stateHandle,
                final Collection<String> previousStateHandles,
                final Class<? extends AbstractContextState> contextStateClass) {
            final var contextStateOpt = device.getMdibAccess().getState(stateHandle, contextStateClass);

            if (contextStateOpt.isEmpty()) {
                return false;
            }
            final var contextState = contextStateOpt.orElseThrow();
            LOG.debug("verifyStatePresentAndAssociated: New context state for handle found. {}", contextState);

            var valid = ContextAssociation.ASSOC == ImpliedValueUtil.getContextAssociation(contextState);
            LOG.info("Validity for {} after association check: {}", stateHandle, valid);
            valid &= descriptorHandle.equals(contextState.getDescriptorHandle());
            LOG.info("Validity for {} after descriptor handle correctness check: {}", stateHandle, valid);
            valid &= !previousStateHandles.contains(stateHandle);
            LOG.info("Validity for {} after checking if state handle is already known: {}", stateHandle, valid);

            return valid;
        }
    }

    /**
     * Precondition which checks whether EpisodicAlertReport messages have been received, and that triggers such
     * messages otherwise.
     */
    public static class TriggerEpisodicAlertReportPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(TriggerEpisodicAlertReportPrecondition.class);

        /**
         * Creates a trigger episodic alert report precondition check.
         */
        public TriggerEpisodicAlertReportPrecondition() {
            super(
                    TriggerEpisodicAlertReportPrecondition::preconditionCheck,
                    TriggerEpisodicAlertReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_EPISODIC_ALERT_REPORT);
        }

        /**
         * Performs the manipulation to trigger episodic alert reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_EPISODIC_ALERT_REPORT);
        }
    }

    /**
     * Precondition which checks whether EpisodicComponentReport messages have been received, and that triggers such
     * messages otherwise.
     */
    public static class TriggerEpisodicComponentReportPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(TriggerEpisodicComponentReportPrecondition.class);

        /**
         * Creates a trigger episodic component report precondition check.
         */
        public TriggerEpisodicComponentReportPrecondition() {
            super(
                    TriggerEpisodicComponentReportPrecondition::preconditionCheck,
                    TriggerEpisodicComponentReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_EPISODIC_COMPONENT_REPORT);
        }

        /**
         * Performs the manipulation to trigger episodic component reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_EPISODIC_COMPONENT_REPORT);
        }
    }

    /**
     * Precondition which checks whether EpisodicContextReport messages have been received, and that triggers such
     * messages otherwise.
     */
    public static class TriggerEpisodicContextReportPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(TriggerEpisodicContextReportPrecondition.class);

        /**
         * Creates a trigger episodic context report precondition check.
         */
        public TriggerEpisodicContextReportPrecondition() {
            super(
                    TriggerEpisodicContextReportPrecondition::preconditionCheck,
                    TriggerEpisodicContextReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_EPISODIC_CONTEXT_REPORT);
        }

        /**
         * Performs the manipulation to trigger episodic context reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_EPISODIC_CONTEXT_REPORT);
        }
    }

    /**
     * Precondition which checks whether EpisodicMetricReport messages have been received, and that triggers such
     * messages otherwise.
     */
    public static class TriggerEpisodicMetricReportPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(TriggerEpisodicMetricReportPrecondition.class);

        /**
         * Creates a trigger episodic metric report precondition check.
         */
        public TriggerEpisodicMetricReportPrecondition() {
            super(
                    TriggerEpisodicMetricReportPrecondition::preconditionCheck,
                    TriggerEpisodicMetricReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_EPISODIC_METRIC_REPORT);
        }

        /**
         * Performs the manipulation to trigger episodic metric reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_EPISODIC_METRIC_REPORT);
        }
    }

    /**
     * Precondition which checks whether EpisodicOperationalStateReport messages have been received, and that triggers
     * such messages otherwise.
     */
    public static class TriggerEpisodicOperationalStateReportPrecondition extends SimplePrecondition {

        private static final Logger LOG = LogManager.getLogger(TriggerEpisodicOperationalStateReportPrecondition.class);

        /**
         * Creates a trigger episodic operational state report precondition check.
         */
        public TriggerEpisodicOperationalStateReportPrecondition() {
            super(
                    TriggerEpisodicOperationalStateReportPrecondition::preconditionCheck,
                    TriggerEpisodicOperationalStateReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_EPISODIC_OPERATIONAL_STATE_REPORT);
        }

        /**
         * Performs the manipulation to trigger episodic operational state reports.
         *
         * @param injector to analyze mdib on
         * @return true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_EPISODIC_OPERATIONAL_STATE_REPORT);
        }
    }

    /**
     * Precondition which checks whether a OperationInvokedReport messages have been received, and trigger such a
     * message otherwise.
     */
    public static class TriggerOperationInvokedReportPrecondition extends SimplePrecondition {
        private static final Logger LOG = LogManager.getLogger(TriggerOperationInvokedReportPrecondition.class);

        /**
         * Creates a trigger operation invoked report message precondition check.
         */
        public TriggerOperationInvokedReportPrecondition() {
            super(
                    TriggerOperationInvokedReportPrecondition::preconditionCheck,
                    TriggerOperationInvokedReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_OPERATION_INVOKED_REPORT);
        }

        /**
         * Performs the manipulation to trigger operation invoked reports.
         *
         * @param injector to analyze mdib on
         * @return  true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_OPERATION_INVOKED_REPORT);
        }
    }

    /**
     * Precondition which checks whether DescriptionModificationReport messages have been received, and trigger such a
     * message otherwise.
     */
    public static class TriggerDescriptionModificationReportPrecondition extends SimplePrecondition {
        private static final Logger LOG = LogManager.getLogger(TriggerDescriptionModificationReportPrecondition.class);

        /**
         * Creates a trigger description modification report message precondition check.
         */
        public TriggerDescriptionModificationReportPrecondition() {
            super(
                    TriggerDescriptionModificationReportPrecondition::preconditionCheck,
                    TriggerDescriptionModificationReportPrecondition::manipulation);
        }

        static boolean preconditionCheck(final Injector injector) throws PreconditionException {
            return triggerReportPreconditionCheck(injector, LOG, Constants.MSG_DESCRIPTION_MODIFICATION_REPORT);
        }

        /**
         * Performs the manipulation to trigger description modification reports.
         *
         * @param injector to analyze mdib on
         * @return  true if successful, false otherwise
         */
        static boolean manipulation(final Injector injector) {
            return triggerReportManipulation(injector, LOG, Constants.MSG_DESCRIPTION_MODIFICATION_REPORT);
        }
    }
}
