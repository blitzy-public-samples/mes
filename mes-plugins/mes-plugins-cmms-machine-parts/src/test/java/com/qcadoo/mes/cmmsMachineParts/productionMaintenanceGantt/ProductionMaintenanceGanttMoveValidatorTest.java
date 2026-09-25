/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt;

import static com.qcadoo.testing.model.EntityTestUtils.mockEntity;
import static com.qcadoo.testing.model.EntityTestUtils.stubBelongsToField;
import static com.qcadoo.testing.model.EntityTestUtils.stubBooleanField;
import static com.qcadoo.testing.model.EntityTestUtils.stubDateField;
import static com.qcadoo.testing.model.EntityTestUtils.stubStringField;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basic.shift.Shift;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveValidator.MoveRejection;
import com.qcadoo.mes.cmmsMachineParts.states.constants.PlannedEventStateStringValues;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.DivisionFieldsPL;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchCriterion;
import com.qcadoo.model.api.search.SearchOrder;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.model.internal.api.DataAccessService;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of {@link ProductionMaintenanceGanttMoveValidator}.
 * <p>
 * The production line and planned event data definitions answer every {@code find()} with a builder that records the
 * criteria and orders added to it. Its {@code list()} returns the fixture entities of the model that satisfy every recorded
 * criterion, sorted by the recorded orders. Every criterion and order the test does not know fails the test. The
 * validator uses a real {@link ProductionMaintenanceGanttChartItemResolver} for event lines and position labels.
 */
public class ProductionMaintenanceGanttMoveValidatorTest {

    private static final Long POSITION_ID = 11L;

    private static final String SCHEDULE_ID = "7";

    private static final String ORDER_NUMBER = "ORD-1";

    private static final String POSITION_START = "2026-10-01 06:00:00";

    private static final String POSITION_END = "2026-10-01 08:00:00";

    private static final String SLOT_FROM = "2026-10-01 10:00:00";

    private static final String SLOT_TO = "2026-10-01 12:00:00";

    private static final String[] ALL_PLANNED_EVENT_STATES = { PlannedEventStateStringValues.NEW,
            PlannedEventStateStringValues.IN_PLAN, PlannedEventStateStringValues.PLANNED,
            PlannedEventStateStringValues.IN_REALIZATION, PlannedEventStateStringValues.IN_EDITING,
            PlannedEventStateStringValues.REALIZED, PlannedEventStateStringValues.CANCELED,
            PlannedEventStateStringValues.ACCEPTED };

    private ProductionMaintenanceGanttMoveValidator validator;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private ProductionLineSchedulePositionValidators productionLineSchedulePositionValidators;

    @Mock
    private ParameterService parameterService;

    @Mock
    private ShiftsService shiftsService;

    @Mock
    private DataAccessService dataAccessService;

    @Mock
    private DataDefinition productionLineDD, plannedEventDD, positionDD;

    @Mock
    private GanttChartItem item;

    private DataAccessService previousDataAccessService;

    private Entity schedule, parameter, position;

    private Entity lineL1, lineL2, lineL3;

    private Date slotFrom, slotTo;

    private long nextEventId;

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<List<Object>> productionLineQueries = new ArrayList<List<Object>>();

    private final List<List<Object>> plannedEventQueries = new ArrayList<List<Object>>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        validator = new ProductionMaintenanceGanttMoveValidator();

        setField(validator, "dataDefinitionService", dataDefinitionService);
        setField(validator, "productionLineSchedulePositionValidators", productionLineSchedulePositionValidators);
        setField(validator, "parameterService", parameterService);
        setField(validator, "shiftsService", shiftsService);
        setField(validator, "productionMaintenanceGanttChartItemResolver", new ProductionMaintenanceGanttChartItemResolver());

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(
                AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

        slotFrom = date(SLOT_FROM);
        slotTo = date(SLOT_TO);

        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE)).willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT)).willReturn(plannedEventDD);

        given(productionLineDD.find()).willAnswer(
                new DatabaseFindAnswer(productionLines, productionLineConditions(), Collections
                        .<SearchOrder, Comparator<Entity>> emptyMap(), productionLineQueries));
        given(plannedEventDD.find()).willAnswer(
                new DatabaseFindAnswer(plannedEvents, plannedEventConditions(slotFrom, slotTo), plannedEventOrders(),
                        plannedEventQueries));

        lineL1 = productionLine(1L, "L1", true, true);
        lineL2 = productionLine(2L, "L2", true, true);
        lineL3 = productionLine(3L, "L3", true, false);

        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));

        schedule = mockEntity(Long.valueOf(SCHEDULE_ID));
        stubBooleanField(schedule, ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE, true);

        parameter = mockEntity(1L);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, false);

        given(parameterService.getParameter()).willReturn(parameter);

        position = position(lineL1, order(ORDER_NUMBER), POSITION_START, POSITION_END);

        given(item.getEntityId()).willReturn(POSITION_ID);

        nextEventId = 100L;
    }

    @After
    public void restoreSearchRestrictions() throws Exception {
        swapSearchRestrictionsDataAccessService(previousDataAccessService);
    }

    @Test
    public final void shouldAcceptCrossRowDropOntoLineAllowedByTechnology() {
        // given
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Arrays.asList(lineL1, lineL2), true, false);
    }

    @Test
    public final void shouldPassScheduleAndParameterFlagsToTechnologyLineLookup() {
        // given
        stubBooleanField(schedule, ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE, false);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, true);
        givenCandidateLines(lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Arrays.asList(lineL1, lineL2), false, true);
    }

    @Test
    public final void shouldQueryProductionAndActiveLinesAsEligibleLines() {
        // given
        givenCandidateLines(lineL2);

        // when
        validator.checkRouting(position, lineL2);

        // then
        assertEquals(1, productionLineQueries.size());
        assertEquals(
                new HashSet<Object>(Arrays.<Object> asList(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true),
                        SearchRestrictions.eq(ProductionLineFields.ACTIVE, true))),
                new HashSet<Object>(productionLineQueries.get(0)));
        assertEquals(2, productionLineQueries.get(0).size());
    }

    @Test
    public final void shouldRejectCrossRowDropOntoLineNotAllowedByTechnology() {
        // given
        givenCandidateLines(lineL1);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        assertEquals("orders.error.inappropriateProductionLineForPositionOrder",
                ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldRejectWhenNoCandidateLineIsEligible() {
        // given
        givenCandidateLines(lineL3);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL3);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Arrays.asList(lineL1, lineL2), true, false);
    }

    @Test
    public final void shouldRejectWhenTechnologyReturnsNoCandidateLine() {
        // given
        givenCandidateLines();

        // when
        Optional<MoveRejection> rejectionForL1 = validator.checkRouting(position, lineL1);
        Optional<MoveRejection> rejectionForL2 = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejectionForL1, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        assertRejection(rejectionForL2, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldRejectWhenTechnologyLineLookupReturnsNull() {
        // given
        given(productionLineSchedulePositionValidators.getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.anyListOf(Entity.class), Matchers.anyBoolean(), Matchers.anyBoolean())).willReturn(null);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldRejectInactiveTargetLine() {
        // given
        lineL2 = productionLine(2L, "L2", true, false);
        productionLines.clear();
        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL1), true, false);
    }

    @Test
    public final void shouldRejectNonProductionTargetLine() {
        // given
        lineL2 = productionLine(2L, "L2", false, true);
        productionLines.clear();
        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL1), true, false);
    }

    @Test
    public final void shouldAcceptCandidateLineMatchingTargetLineById() {
        // given
        Entity candidateL2 = productionLine(2L, "L2", true, true);
        Entity targetL2 = productionLine(2L, "L2", true, true);
        givenCandidateLines(candidateL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, targetL2);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldIgnoreNullEntriesOfCandidateLines() {
        // given
        givenCandidateLines(null, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldRejectTargetLineWithoutId() {
        // given
        Entity unsavedLine = mockEntity();
        stubStringField(unsavedLine, ProductionLineFields.NUMBER, "L2");
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, unsavedLine);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldQueryShutdownEventsOverlappingSlotInStartDateOrder() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL1, null, null));

        // when
        validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertEquals(1, plannedEventQueries.size());
        assertEquals(
                new HashSet<Object>(Arrays.<Object> asList(SearchRestrictions.eq(PlannedEventFields.REQUIRES_SHUTDOWN, true),
                        SearchRestrictions.lt(PlannedEventFields.START_DATE, slotTo),
                        SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, slotFrom),
                        SearchOrders.asc(PlannedEventFields.START_DATE))), new HashSet<Object>(plannedEventQueries.get(0)));
        assertEquals(4, plannedEventQueries.get(0).size());
    }

    @Test
    public final void shouldRejectSlotOverlappingShutdownEvent() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-1");
    }

    @Test
    public final void shouldRejectShutdownEventCoveringWholeSlot() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.IN_REALIZATION, true, "2026-10-01 09:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-1");
    }

    @Test
    public final void shouldRejectShutdownEventInsideSlot() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.NEW, true, "2026-10-01 10:30:00",
                "2026-10-01 11:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-1");
    }

    @Test
    public final void shouldRejectOverlapWithCancelledShutdownEvent() {
        // given
        plannedEvents.add(plannedEvent("EV-C", PlannedEventStateStringValues.CANCELED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-C");
        assertEquals("06canceled", PlannedEventStateStringValues.CANCELED);
    }

    @Test
    public final void shouldRejectOverlapWithShutdownEventInEveryState() {
        for (String state : ALL_PLANNED_EVENT_STATES) {
            // given
            plannedEvents.clear();
            plannedEvents.add(plannedEvent("EV-" + state, state, true, "2026-10-01 11:00:00", "2026-10-01 13:00:00", lineL2,
                    null, null));

            // when
            Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

            // then
            assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-" + state);
        }
    }

    @Test
    public final void shouldRejectOverlapWithWorkstationOnlyShutdownEvent() {
        // given
        plannedEvents.add(plannedEvent("EV-W", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, workstation(lineL2), null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-W");
    }

    @Test
    public final void shouldRejectOverlapWithDivisionOnlyShutdownEvent() {
        // given
        plannedEvents.add(plannedEvent("EV-D", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, null, division(lineL1, lineL2)));

        // when
        Optional<MoveRejection> rejectionForL1 = validator.checkShutdownWindow(lineL1, slotFrom, slotTo);
        Optional<MoveRejection> rejectionForL2 = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);
        Optional<MoveRejection> rejectionForL3 = validator.checkShutdownWindow(lineL3, slotFrom, slotTo);

        // then
        assertRejection(rejectionForL1, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-D");
        assertRejection(rejectionForL2, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-D");
        assertFalse(rejectionForL3.isPresent());
    }

    @Test
    public final void shouldRejectOverlapWithDivisionShutdownEventWhoseWorkstationHasNoLine() {
        // given
        plannedEvents.add(plannedEvent("EV-WD", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, workstation(null), division(lineL2)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-WD");
    }

    @Test
    public final void shouldMatchEventLineWithTargetLineById() {
        // given
        Entity eventL2 = productionLine(2L, "L2", true, true);
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", eventL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-1");
    }

    @Test
    public final void shouldNameEarliestOverlappingShutdownEventOfTargetLine() {
        // given
        plannedEvents.add(plannedEvent("EV-LATE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:30:00",
                "2026-10-01 13:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-OTHER-LINE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 09:00:00",
                "2026-10-01 13:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-EARLY", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 10:30:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-EARLY");
    }

    @Test
    public final void shouldAcceptSlotWithoutOverlappingEvents() {
        // given
        plannedEvents.add(plannedEvent("EV-BEFORE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 06:00:00",
                "2026-10-01 09:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-AFTER", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 13:00:00",
                "2026-10-01 15:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldAcceptSlotWhenNoPlannedEventExists() {
        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        verify(plannedEventDD).find();
    }

    @Test
    public final void shouldAcceptOverlappingShutdownEventOnAnotherLine() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-2", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, workstation(lineL1), null));
        plannedEvents.add(plannedEvent("EV-3", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, null, division(lineL1, lineL3)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldAcceptOverlappingEventNotRequiringShutdown() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, false, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldAcceptShutdownEventFinishingAtSlotStart() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 08:00:00",
                SLOT_FROM, lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldAcceptShutdownEventStartingAtSlotEnd() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, SLOT_TO, "2026-10-01 14:00:00",
                lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldAcceptOverlappingShutdownEventWithoutResolvableLine() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, null, division()));
        plannedEvents.add(plannedEvent("EV-2", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test(expected = NullPointerException.class)
    public final void shouldRequireShutdownWindowStart() {
        // when
        validator.checkShutdownWindow(lineL2, null, slotTo);
    }

    @Test(expected = NullPointerException.class)
    public final void shouldRequireShutdownWindowEnd() {
        // when
        validator.checkShutdownWindow(lineL2, slotFrom, null);
    }


    @Test
    public final void shouldAcceptStartInsideWorkingHours() {
        // given
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom)));

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertFalse(rejection.isPresent());

        ArgumentCaptor<DateTime> dateCaptor = ArgumentCaptor.forClass(DateTime.class);
        ArgumentCaptor<Entity> lineCaptor = ArgumentCaptor.forClass(Entity.class);

        verify(shiftsService).getNearestWorkingDate(dateCaptor.capture(), lineCaptor.capture());

        assertEquals(slotFrom.getTime(), dateCaptor.getValue().getMillis());
        assertSame(lineL2, lineCaptor.getValue());
        verify(shiftsService, never()).findAll(Matchers.any(Entity.class));
    }

    @Test
    public final void shouldAcceptNearestWorkingDateBeforeStart() {
        // given
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom).minusMinutes(30)));

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldRejectStartOutsideWorkingHours() {
        // given
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom).plusHours(2)));

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OUTSIDE_WORKING_HOURS_KEY);
        verify(shiftsService, never()).findAll(Matchers.any(Entity.class));
    }

    @Test
    public final void shouldRejectStartOneSecondBeforeWorkingHours() {
        // given
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom).plusSeconds(1)));

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OUTSIDE_WORKING_HOURS_KEY);
    }

    @Test
    public final void shouldRejectWhenNoShiftWorksAfterStart() {
        // given
        givenNearestWorkingDate(Optional.<DateTime> empty());
        given(shiftsService.findAll(lineL2)).willReturn(Collections.singletonList(mock(Shift.class)));

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OUTSIDE_WORKING_HOURS_KEY);
        verify(shiftsService).findAll(lineL2);
    }

    @Test
    public final void shouldAcceptAnyStartWhenNoShiftExists() {
        // given
        givenNearestWorkingDate(Optional.<DateTime> empty());
        given(shiftsService.findAll(lineL2)).willReturn(Collections.<Shift> emptyList());

        // when
        Optional<MoveRejection> rejection = validator.checkWorkingHours(lineL2, slotFrom);

        // then
        assertFalse(rejection.isPresent());
        verify(shiftsService).findAll(lineL2);
    }

    @Test(expected = NullPointerException.class)
    public final void shouldRequireWorkingHoursStart() {
        // when
        validator.checkWorkingHours(lineL2, null);
    }

    @Test
    public final void shouldAcceptUnchangedPosition() {
        // given
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(position, request);

        // then
        assertFalse(rejection.isPresent());
    }

    @Test
    public final void shouldRejectPositionMovedToAnotherLineSinceRendering() {
        // given
        Entity stalePosition = position(lineL2, order(ORDER_NUMBER), POSITION_START, POSITION_END);
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(stalePosition, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
        assertEquals("qcadooView.validate.global.optimisticLock", ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldRejectPositionWithChangedStartSinceRendering() {
        // given
        Entity stalePosition = position(lineL1, order(ORDER_NUMBER), "2026-10-01 06:30:00", POSITION_END);
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(stalePosition, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldRejectPositionWithChangedEndSinceRendering() {
        // given
        Entity stalePosition = position(lineL1, order(ORDER_NUMBER), POSITION_START, "2026-10-01 08:30:00");
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(stalePosition, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldRejectPositionWithChangedOrderSinceRendering() {
        // given
        Entity stalePosition = position(lineL1, order("ORD-2"), POSITION_START, POSITION_END);
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(stalePosition, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldRejectPositionWithoutProductionLine() {
        // given
        Entity stalePosition = position(null, order(ORDER_NUMBER), POSITION_START, POSITION_END);
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkConcurrentEdit(stalePosition, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldCompareEscapedOrderNumberWithOriginalName() {
        // given
        Entity markupPosition = position(lineL1, order("<b>ORD</b>"), POSITION_START, POSITION_END);
        GanttChartMoveRequest escapedRequest = moveRequest("L1", "&lt;b&gt;ORD&lt;/b&gt;", POSITION_START, POSITION_END);
        GanttChartMoveRequest rawRequest = moveRequest("L1", "<b>ORD</b>", POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> escapedRejection = validator.checkConcurrentEdit(markupPosition, escapedRequest);
        Optional<MoveRejection> rawRejection = validator.checkConcurrentEdit(markupPosition, rawRequest);

        // then
        assertFalse(escapedRejection.isPresent());
        assertRejection(rawRejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
    }

    @Test
    public final void shouldAcceptEveryCheckWhenMoveIsValid() {
        // given
        givenCandidateLines(lineL1, lineL2);
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom)));
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertFalse(rejection.isPresent());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Arrays.asList(lineL1, lineL2), true, false);
        verify(plannedEventDD).find();
        verify(shiftsService).getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL2));
        verify(position).getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    @Test
    public final void shouldReturnRoutingRejectionWhenEveryCheckFails() {
        // given
        givenEveryCheckFailing();
        GanttChartMoveRequest request = moveRequest("L1", "ORD-STALE", POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(shiftsService);
        verifyZeroInteractions(plannedEventDD);
        verify(dataDefinitionService, never()).get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT);
        verify(position, never()).getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    @Test
    public final void shouldReturnShutdownRejectionWhenRoutingPassesAndLaterChecksFail() {
        // given
        givenEveryCheckFailing();
        givenCandidateLines(lineL2);
        GanttChartMoveRequest request = moveRequest("L1", "ORD-STALE", POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-SHUTDOWN");
        verifyZeroInteractions(shiftsService);
        verify(position, never()).getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    @Test
    public final void shouldReturnCalendarRejectionWhenRoutingAndShutdownPassAndLaterChecksFail() {
        // given
        givenEveryCheckFailing();
        givenCandidateLines(lineL2);
        plannedEvents.clear();
        GanttChartMoveRequest request = moveRequest("L1", "ORD-STALE", POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OUTSIDE_WORKING_HOURS_KEY);
        verify(shiftsService).getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL2));
        verify(position, never()).getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    @Test
    public final void shouldReturnLockRejectionWhenOnlyConcurrentEditCheckFails() {
        // given
        givenEveryCheckFailing();
        givenCandidateLines(lineL2);
        plannedEvents.clear();
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom)));
        GanttChartMoveRequest request = moveRequest("L1", "ORD-STALE", POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY);
        verify(position, times(1)).getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    @Test
    public final void shouldValidateSlotOfMoveRequest() {
        // given
        givenCandidateLines(lineL2);
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom)));
        plannedEvents.add(plannedEvent("EV-FINISHED", PlannedEventStateStringValues.REALIZED, true, "2026-10-01 08:00:00",
                SLOT_FROM, lineL2, null, null));
        GanttChartMoveRequest request = moveRequest("L1", ORDER_NUMBER, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.validate(position, lineL2, request);

        // then
        assertFalse(rejection.isPresent());
        assertTrue(plannedEventQueries.get(0).contains(SearchRestrictions.lt(PlannedEventFields.START_DATE, slotTo)));
        assertTrue(plannedEventQueries.get(0).contains(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, slotFrom)));

        ArgumentCaptor<DateTime> dateCaptor = ArgumentCaptor.forClass(DateTime.class);

        verify(shiftsService).getNearestWorkingDate(dateCaptor.capture(), Matchers.eq(lineL2));

        assertEquals(slotFrom.getTime(), dateCaptor.getValue().getMillis());
    }

    @Test
    public final void shouldStoreMessageKeyAndArgumentsOfMoveRejection() {
        // given
        String[] args = { "EV-1", "EV-2" };

        // when
        MoveRejection rejection = new MoveRejection(ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, args);
        args[0] = "CHANGED";
        String[] returnedArgs = rejection.getArgs();
        returnedArgs[1] = "CHANGED";

        // then
        assertEquals(ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, rejection.getMessageKey());
        assertArrayEquals(new String[] { "EV-1", "EV-2" }, rejection.getArgs());
    }

    @Test
    public final void shouldStoreNullArgumentsOfMoveRejectionAsNoArguments() {
        // when
        MoveRejection rejection = new MoveRejection(ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY,
                (String[]) null);
        MoveRejection rejectionWithoutArgs = new MoveRejection(ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);

        // then
        assertArrayEquals(new String[0], rejection.getArgs());
        assertArrayEquals(new String[0], rejectionWithoutArgs.getArgs());
    }

    private void givenCandidateLines(final Entity... candidateLines) {
        given(productionLineSchedulePositionValidators.getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.anyListOf(Entity.class), Matchers.anyBoolean(), Matchers.anyBoolean())).willReturn(
                new ArrayList<Entity>(Arrays.asList(candidateLines)));
    }

    private void givenNearestWorkingDate(final Optional<DateTime> nearestWorkingDate) {
        given(shiftsService.getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL2))).willReturn(
                nearestWorkingDate);
    }

    private void givenEveryCheckFailing() {
        givenCandidateLines(lineL1);
        plannedEvents.add(plannedEvent("EV-SHUTDOWN", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));
        givenNearestWorkingDate(Optional.of(new DateTime(slotFrom).plusHours(2)));
        given(shiftsService.findAll(lineL2)).willReturn(Collections.singletonList(mock(Shift.class)));
    }

    private static void assertRejection(final Optional<MoveRejection> rejection, final String messageKey,
            final String... args) {
        assertTrue(rejection.isPresent());
        assertEquals(messageKey, rejection.get().getMessageKey());
        assertArrayEquals(args, rejection.get().getArgs());
    }

    private GanttChartMoveRequest moveRequest(final String originalRowName, final String originalName,
            final String originalDateFrom, final String originalDateTo) {
        return new GanttChartMoveRequest(item, "L2", originalRowName, originalName, originalDateFrom, originalDateTo, slotFrom,
                slotTo, scheduleContext());
    }

    private static JSONObject scheduleContext() {
        try {
            return new JSONObject().put(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, SCHEDULE_ID);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataAccessService swapSearchRestrictionsDataAccessService(final DataAccessService dataAccessService)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = SearchRestrictions.class.getDeclaredField("dataAccessService");

        field.setAccessible(true);

        DataAccessService previous = (DataAccessService) field.get(null);

        field.set(null, dataAccessService);

        return previous;
    }

    private static Date date(final String value) {
        try {
            return new SimpleDateFormat(DateUtils.L_DATE_TIME_FORMAT).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Entity productionLine(final Long id, final String number, final boolean production, final boolean active) {
        Entity productionLine = mockEntity(id);

        stubStringField(productionLine, ProductionLineFields.NUMBER, number);
        stubBooleanField(productionLine, ProductionLineFields.PRODUCTION, production);
        stubBooleanField(productionLine, ProductionLineFields.ACTIVE, active);

        return productionLine;
    }

    private static Entity order(final String number) {
        Entity order = mockEntity(21L);

        stubStringField(order, OrderFields.NUMBER, number);

        return order;
    }

    private Entity position(final Entity productionLine, final Entity order, final String startTime, final String endTime) {
        Entity schedulePosition = mockEntity(POSITION_ID, positionDD);

        stubBelongsToField(schedulePosition, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);
        stubBelongsToField(schedulePosition, ProductionLineSchedulePositionFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(schedulePosition, ProductionLineSchedulePositionFields.ORDER, order);
        stubDateField(schedulePosition, ProductionLineSchedulePositionFields.START_TIME, date(startTime));
        stubDateField(schedulePosition, ProductionLineSchedulePositionFields.END_TIME, date(endTime));

        return schedulePosition;
    }

    private Entity plannedEvent(final String number, final String state, final boolean requiresShutdown,
            final String startDate, final String finishDate, final Entity productionLine, final Entity workstation,
            final Entity division) {
        Entity plannedEvent = mockEntity(nextEventId++);

        stubStringField(plannedEvent, PlannedEventFields.NUMBER, number);
        stubStringField(plannedEvent, PlannedEventFields.STATE, state);
        stubBooleanField(plannedEvent, PlannedEventFields.REQUIRES_SHUTDOWN, requiresShutdown);
        stubDateField(plannedEvent, PlannedEventFields.START_DATE, date(startDate));
        stubDateField(plannedEvent, PlannedEventFields.FINISH_DATE, date(finishDate));
        stubBelongsToField(plannedEvent, PlannedEventFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(plannedEvent, PlannedEventFields.WORKSTATION, workstation);
        stubBelongsToField(plannedEvent, PlannedEventFields.DIVISION, division);

        return plannedEvent;
    }

    private static Entity workstation(final Entity productionLine) {
        Entity workstation = mockEntity();

        stubBelongsToField(workstation, WorkstationFieldsPL.PRODUCTION_LINE, productionLine);

        return workstation;
    }

    /**
     * Returns a division whose {@code getManyToManyField(productionLines)} answers a {@link List} of the given production
     * lines, the return type of {@link Entity#getManyToManyField(String)}.
     */
    private static Entity division(final Entity... productionLines) {
        Entity division = mockEntity();

        given(division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)).willReturn(
                new ArrayList<Entity>(Arrays.asList(productionLines)));

        return division;
    }


    private static Map<SearchCriterion, EntityCondition> productionLineConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true), new TrueFieldCondition(
                ProductionLineFields.PRODUCTION));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true), new TrueFieldCondition(
                ProductionLineFields.ACTIVE));

        return conditions;
    }

    private static Map<SearchCriterion, EntityCondition> plannedEventConditions(final Date dateFrom, final Date dateTo) {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(PlannedEventFields.REQUIRES_SHUTDOWN, true), new TrueFieldCondition(
                PlannedEventFields.REQUIRES_SHUTDOWN));
        conditions.put(SearchRestrictions.lt(PlannedEventFields.START_DATE, dateTo), new DateFieldCondition(
                PlannedEventFields.START_DATE, dateTo, false));
        conditions.put(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, dateFrom), new DateFieldCondition(
                PlannedEventFields.FINISH_DATE, dateFrom, true));

        return conditions;
    }

    private static Map<SearchOrder, Comparator<Entity>> plannedEventOrders() {
        Map<SearchOrder, Comparator<Entity>> orders = new LinkedHashMap<SearchOrder, Comparator<Entity>>();

        orders.put(SearchOrders.asc(PlannedEventFields.START_DATE), new DateFieldComparator(PlannedEventFields.START_DATE));

        return orders;
    }

    /**
     * Condition an entity satisfies for one database criterion.
     */
    private interface EntityCondition {

        boolean matches(Entity entity);

    }

    /**
     * Satisfied when the boolean field is true.
     */
    private static final class TrueFieldCondition implements EntityCondition {

        private final String fieldName;

        private TrueFieldCondition(final String fieldName) {
            this.fieldName = fieldName;
        }

        @Override
        public boolean matches(final Entity entity) {
            return entity.getBooleanField(fieldName);
        }

    }

    /**
     * Satisfied when the date field is strictly after the bound ({@code after = true}) or strictly before it
     * ({@code after = false}).
     */
    private static final class DateFieldCondition implements EntityCondition {

        private final String fieldName;

        private final Date bound;

        private final boolean after;

        private DateFieldCondition(final String fieldName, final Date bound, final boolean after) {
            this.fieldName = fieldName;
            this.bound = new Date(bound.getTime());
            this.after = after;
        }

        @Override
        public boolean matches(final Entity entity) {
            Date value = entity.getDateField(fieldName);

            if (value == null) {
                return false;
            }
            if (after) {
                return value.after(bound);
            }

            return value.before(bound);
        }

    }

    /**
     * Orders entities ascending by a non-null date field.
     */
    private static final class DateFieldComparator implements Comparator<Entity> {

        private final String fieldName;

        private DateFieldComparator(final String fieldName) {
            this.fieldName = fieldName;
        }

        @Override
        public int compare(final Entity first, final Entity second) {
            return first.getDateField(fieldName).compareTo(second.getDateField(fieldName));
        }

    }

    /**
     * Answers {@code find()} with a new builder that records its criteria and orders in a new query of the given query list.
     */
    private static final class DatabaseFindAnswer implements Answer<SearchCriteriaBuilder> {

        private final List<Entity> entities;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<List<Object>> queries;

        private DatabaseFindAnswer(final List<Entity> entities, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders, final List<List<Object>> queries) {
            this.entities = entities;
            this.conditions = conditions;
            this.orders = orders;
            this.queries = queries;
        }

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            List<Object> query = new ArrayList<Object>();

            queries.add(query);

            return mock(SearchCriteriaBuilder.class, new DatabaseCriteriaBuilderAnswer(entities, conditions, orders, query));
        }

    }

    /**
     * Records known criteria of {@code add} and known orders of {@code addOrder} and answers them with the builder itself.
     * Answers {@code list()} with the entities satisfying every recorded criterion, sorted by the recorded orders. Fails on
     * unknown criteria, unknown orders and every other builder method.
     */
    private static final class DatabaseCriteriaBuilderAnswer implements Answer<Object> {

        private final List<Entity> entities;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<Object> query;

        private DatabaseCriteriaBuilderAnswer(final List<Entity> entities,
                final Map<SearchCriterion, EntityCondition> conditions, final Map<SearchOrder, Comparator<Entity>> orders,
                final List<Object> query) {
            this.entities = entities;
            this.conditions = conditions;
            this.orders = orders;
            this.query = query;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            String methodName = method.getName();

            if ("add".equals(methodName)) {
                SearchCriterion criterion = (SearchCriterion) invocation.getArguments()[0];

                if (!conditions.containsKey(criterion)) {
                    throw new AssertionError("Unexpected criterion: " + criterion.getHibernateCriterion());
                }

                query.add(criterion);

                return invocation.getMock();
            }
            if ("addOrder".equals(methodName)) {
                SearchOrder order = (SearchOrder) invocation.getArguments()[0];

                if (!orders.containsKey(order)) {
                    throw new AssertionError("Unexpected order: " + order.getHibernateOrder());
                }

                query.add(order);

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                return mock(SearchResult.class, new SearchResultAnswer(select()));
            }
            if (SearchCriteriaBuilder.class.equals(method.getReturnType())) {
                throw new AssertionError("Unexpected builder method: " + methodName);
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        private List<Entity> select() {
            List<Entity> selected = new ArrayList<Entity>();

            for (Entity entity : entities) {
                if (matchesEveryCriterion(entity)) {
                    selected.add(entity);
                }
            }

            for (int index = query.size() - 1; index >= 0; index--) {
                Object element = query.get(index);

                if (element instanceof SearchOrder) {
                    Collections.sort(selected, orders.get(element));
                }
            }

            return selected;
        }

        private boolean matchesEveryCriterion(final Entity entity) {
            for (Object element : query) {
                if (element instanceof SearchCriterion && !conditions.get(element).matches(entity)) {
                    return false;
                }
            }

            return true;
        }

    }

    /**
     * Answers {@code getEntities()} and {@code getTotalNumberOfEntities()} of a search result with the given entities.
     */
    private static final class SearchResultAnswer implements Answer<Object> {

        private final List<Entity> entities;

        private SearchResultAnswer(final List<Entity> entities) {
            this.entities = entities;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();

            if ("getEntities".equals(methodName)) {
                return entities;
            }
            if ("getTotalNumberOfEntities".equals(methodName)) {
                return entities.size();
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

    }

}

