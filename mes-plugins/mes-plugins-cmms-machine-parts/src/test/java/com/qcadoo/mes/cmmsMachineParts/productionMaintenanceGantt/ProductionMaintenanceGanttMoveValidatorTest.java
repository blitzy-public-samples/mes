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
import static com.qcadoo.testing.model.EntityTestUtils.stubHasManyField;
import static com.qcadoo.testing.model.EntityTestUtils.stubStringField;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.basic.shift.Shift;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveValidator.MoveRejection;
import com.qcadoo.mes.cmmsMachineParts.states.constants.PlannedEventStateStringValues;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.OrderStateStringValues;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.DivisionFieldsPL;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.mes.technologies.constants.TechnologyFields;
import com.qcadoo.mes.technologies.constants.TechnologyProductionLineFields;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchCriterion;
import com.qcadoo.model.api.search.SearchOrder;
import com.qcadoo.model.api.search.SearchQueryBuilder;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.model.internal.api.DataAccessService;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of {@link ProductionMaintenanceGanttMoveValidator}.
 * <p>
 * The production line data definition answers every {@code find()} with a builder that records the criteria and orders
 * added to it. Its {@code list()} returns the fixture production lines that satisfy every recorded criterion, sorted by the
 * recorded orders. Every criterion and order the test does not know fails the test.
 * <p>
 * The planned event data definition answers {@code find(String)} of
 * {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY} with a builder that records the query and its
 * bound parameters. Its {@code list()} returns a projection row, holding only the aliases the query selects, of every
 * fixture planned event the query selects: overlapping {@code [dateFrom, dateTo)}, with the bound requires-shutdown flag,
 * and whose own production line, workstation production line or division production lines include the bound production
 * line, in ascending start date and id order. Any other query, a missing or unexpected parameter, and {@code find()} fail
 * the test. The validator uses a real {@link ProductionMaintenanceGanttChartItemResolver}, wired with the mocked data
 * definition service, for shutdown events and position labels. The basic parameter data definition holds one
 * parameter, and the fixture order is pending, has a technology and has no production line.
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

    private static final String DATE_FROM_PARAMETER = "dateFrom";

    private static final String DATE_TO_PARAMETER = "dateTo";

    private static final String REQUIRES_SHUTDOWN_PARAMETER = "requiresShutdown";

    private static final String PRODUCTION_LINE_ID_PARAMETER = "productionLineId";

    private static final String EVENT_NUMBER_ALIAS = "eventNumber";

    private static final String EVENT_TYPE_ALIAS = "eventType";

    private static final String EVENT_STATE_ALIAS = "eventState";

    private static final String REQUIRES_SHUTDOWN_ALIAS = "requiresShutdown";

    private static final String START_DATE_ALIAS = "startDate";

    private static final String FINISH_DATE_ALIAS = "finishDate";

    private static final String EVENT_LINE_ID_ALIAS = "eventLineId";

    private static final String EVENT_LINE_NUMBER_ALIAS = "eventLineNumber";

    private static final String WORKSTATION_LINE_ID_ALIAS = "workstationLineId";

    private static final String WORKSTATION_LINE_NUMBER_ALIAS = "workstationLineNumber";

    private static final String DIVISION_ID_ALIAS = "divisionId";

    private static final String[] SHUTDOWN_EVENT_ALIASES = { EVENT_NUMBER_ALIAS, EVENT_TYPE_ALIAS, EVENT_STATE_ALIAS,
            REQUIRES_SHUTDOWN_ALIAS, START_DATE_ALIAS, FINISH_DATE_ALIAS, EVENT_LINE_ID_ALIAS, EVENT_LINE_NUMBER_ALIAS,
            WORKSTATION_LINE_ID_ALIAS, WORKSTATION_LINE_NUMBER_ALIAS, DIVISION_ID_ALIAS };

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
    private DataDefinition productionLineDD, plannedEventDD, positionDD, parameterDD;

    @Mock
    private GanttChartItem item;

    private DataAccessService previousDataAccessService;

    private Entity schedule, parameter, position;

    private Entity lineL1, lineL2, lineL3;

    private Date slotFrom, slotTo;

    private long nextEventId, nextDivisionId;

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<List<Object>> productionLineQueries = new ArrayList<List<Object>>();

    private final List<ShutdownEventsQuery> plannedEventQueries = new ArrayList<ShutdownEventsQuery>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        validator = new ProductionMaintenanceGanttMoveValidator();

        setField(validator, "dataDefinitionService", dataDefinitionService);
        setField(validator, "productionLineSchedulePositionValidators", productionLineSchedulePositionValidators);
        setField(validator, "parameterService", parameterService);
        setField(validator, "shiftsService", shiftsService);

        ProductionMaintenanceGanttChartItemResolver resolver = new ProductionMaintenanceGanttChartItemResolver();

        setField(resolver, "dataDefinitionService", dataDefinitionService);
        setField(validator, "productionMaintenanceGanttChartItemResolver", resolver);

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(
                AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

        slotFrom = date(SLOT_FROM);
        slotTo = date(SLOT_TO);

        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE)).willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT)).willReturn(plannedEventDD);
        given(dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER)).willReturn(
                parameterDD);
        given(parameterDD.count()).willReturn(1L);

        given(productionLineDD.find()).willAnswer(
                new DatabaseFindAnswer(productionLines, productionLineConditions(), Collections
                        .<SearchOrder, Comparator<Entity>> emptyMap(), productionLineQueries));
        given(plannedEventDD.find()).willAnswer(new FailingAnswer("criteria find() of the planned event data definition"));
        given(plannedEventDD.find(Matchers.anyString())).willAnswer(
                new ShutdownEventsFindAnswer(plannedEvents, plannedEventQueries));

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
        nextDivisionId = 300L;
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
                Collections.singletonList(lineL2), true, false);
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
                Collections.singletonList(lineL2), false, true);
        verify(parameterService).getParameter();
    }

    @Test
    public final void shouldCheckRoutingWithoutProductionLineQuery() {
        // given
        givenCandidateLines(lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
        assertTrue(productionLineQueries.isEmpty());
        verifyZeroInteractions(productionLineDD);
        verify(dataDefinitionService, never()).get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE);
    }

    @Test
    public final void shouldReadLineChangeFlagOfAcceptedOrdersAsFalseWhenNoBasicParameterExists() {
        // given
        given(parameterDD.count()).willReturn(0L);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, true);
        givenCandidateLines(lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL2), true, false);
        verify(parameterService, never()).getParameter();
        verify(parameterDD, never()).save(Matchers.any(Entity.class));
    }

    @Test
    public final void shouldReadLineChangeFlagOfAcceptedOrdersAsFalseWhenParameterModelIsMissing() {
        // given
        given(dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER)).willReturn(null);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, true);
        givenCandidateLines(lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertFalse(rejection.isPresent());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL2), true, false);
        verify(parameterService, never()).getParameter();
        verifyZeroInteractions(parameterDD);
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
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL2), true, false);
    }

    @Test
    public final void shouldRejectWhenNoCandidateLineIsEligible() {
        // given
        given(productionLineSchedulePositionValidators.getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.eq(Collections.singletonList(lineL3)), Matchers.anyBoolean(), Matchers.anyBoolean())).willReturn(
                new ArrayList<Entity>(Collections.singletonList(lineL3)));
        given(productionLineSchedulePositionValidators.getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.eq(Collections.singletonList(lineL2)), Matchers.anyBoolean(), Matchers.anyBoolean())).willReturn(
                new ArrayList<Entity>());

        // when
        Optional<MoveRejection> rejectionForInactiveL3 = validator.checkRouting(position, lineL3);
        Optional<MoveRejection> rejectionForL2WithoutCandidates = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejectionForInactiveL3, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        assertRejection(rejectionForL2WithoutCandidates, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(productionLineSchedulePositionValidators, never()).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL3), true, false);
        verify(productionLineSchedulePositionValidators, times(1)).getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.anyListOf(Entity.class), Matchers.anyBoolean(), Matchers.anyBoolean());
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL2), true, false);
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
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
    }

    @Test
    public final void shouldReadActivityOfTargetLineFromEntityStateAndNotFromAField() {
        // given: an active production line as the model loads it, holding no field named active
        Entity loadedLine = mockEntity(2L);
        stubStringField(loadedLine, ProductionLineFields.NUMBER, "L2");
        stubBooleanField(loadedLine, ProductionLineFields.PRODUCTION, true);
        given(loadedLine.isActive()).willReturn(true);
        givenCandidateLines(lineL1, loadedLine);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, loadedLine);

        // then
        assertFalse(rejection.isPresent());
        verify(loadedLine, never()).getBooleanField(ProductionLineFields.ACTIVE);
    }

    @Test
    public final void shouldRejectNonProductionTargetLine() {
        // given
        lineL2 = productionLine(2L, "L2", false, true);
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
    }

    @Test
    public final void shouldRejectPositionWithoutOrder() {
        // given
        Entity positionWithoutOrder = position(lineL1, null, POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(positionWithoutOrder, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
        verifyZeroInteractions(parameterService);
    }

    @Test
    public final void shouldRejectOrderWithoutTechnologyAndProductionLine() {
        // given
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, null, OrderStateStringValues.PENDING, null),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(positionOfOrder, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
    }

    @Test
    public final void shouldRejectOrderWithProductionLineWithoutTechnologyWhenLineChangeIsAllowed() {
        // given
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, lineL1, OrderStateStringValues.PENDING, null),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(positionOfOrder, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
    }

    @Test
    public final void shouldRejectOrderWithProductionLineWithoutStateWhenLineChangeIsAllowed() {
        // given
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, lineL1, null, mockEntity(31L)), POSITION_START,
                POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(positionOfOrder, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
    }

    @Test
    public final void shouldRejectAcceptedOrderWithoutTechnologyWhenAcceptedOrdersMayChangeLine() {
        // given
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, true);
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, lineL1, OrderStateStringValues.ACCEPTED, null),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(positionOfOrder, lineL2);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verifyZeroInteractions(productionLineSchedulePositionValidators);
        verify(parameterService).getParameter();
    }

    @Test
    public final void shouldKeepProductionLineOfOrderWithoutTechnologyWhenLineChangeIsNotAllowed() {
        // given
        setField(validator, "productionLineSchedulePositionValidators", new ProductionLineSchedulePositionValidators());
        stubBooleanField(schedule, ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE, false);
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, lineL1, OrderStateStringValues.PENDING, null),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejectionForOrderLine = validator.checkRouting(positionOfOrder, lineL1);
        Optional<MoveRejection> rejectionForOtherLine = validator.checkRouting(positionOfOrder, lineL2);

        // then
        assertFalse(rejectionForOrderLine.isPresent());
        assertRejection(rejectionForOtherLine, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldKeepProductionLineOfAcceptedOrderWithoutTechnologyWhenAcceptedOrdersMayNotChangeLine() {
        // given
        setField(validator, "productionLineSchedulePositionValidators", new ProductionLineSchedulePositionValidators());
        Entity positionOfOrder = position(lineL2, order(ORDER_NUMBER, lineL2, OrderStateStringValues.ACCEPTED, null),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejectionForOrderLine = validator.checkRouting(positionOfOrder, lineL2);
        Optional<MoveRejection> rejectionForOtherLine = validator.checkRouting(positionOfOrder, lineL1);

        // then
        assertFalse(rejectionForOrderLine.isPresent());
        assertRejection(rejectionForOtherLine, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(parameterService, times(2)).getParameter();
    }

    @Test
    public final void shouldAcceptOnlyTechnologyProductionLinesThroughTechnologyLineLookup() {
        // given
        setField(validator, "productionLineSchedulePositionValidators", new ProductionLineSchedulePositionValidators());
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, null, OrderStateStringValues.PENDING,
                technology(technologyProductionLine(lineL2))), POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejectionForTechnologyLine = validator.checkRouting(positionOfOrder, lineL2);
        Optional<MoveRejection> rejectionForOtherLine = validator.checkRouting(positionOfOrder, lineL1);

        // then
        assertFalse(rejectionForTechnologyLine.isPresent());
        assertRejection(rejectionForOtherLine, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
    }

    @Test
    public final void shouldAcceptEligibleTargetThroughTechnologyLineLookupWhenTechnologyHasNoProductionLines() {
        // given
        setField(validator, "productionLineSchedulePositionValidators", new ProductionLineSchedulePositionValidators());
        Entity positionOfOrder = position(lineL1, order(ORDER_NUMBER, null, OrderStateStringValues.PENDING, technology()),
                POSITION_START, POSITION_END);

        // when
        Optional<MoveRejection> rejectionForEligibleLine = validator.checkRouting(positionOfOrder, lineL2);
        Optional<MoveRejection> rejectionForInactiveLine = validator.checkRouting(positionOfOrder, lineL3);

        // then
        assertFalse(rejectionForEligibleLine.isPresent());
        assertRejection(rejectionForInactiveLine, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
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
        stubBooleanField(unsavedLine, ProductionLineFields.PRODUCTION, true);
        given(unsavedLine.isActive()).willReturn(true);
        givenCandidateLines(lineL1, lineL2);

        // when
        Optional<MoveRejection> rejection = validator.checkRouting(position, unsavedLine);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(unsavedLine), true, false);
    }

    @Test
    public final void shouldQueryShutdownEventsOverlappingSlotInStartDateOrder() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL1, null, null));

        // when
        validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        String query = ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY;

        assertEquals(1, plannedEventQueries.size());
        assertEquals(query, plannedEventQueries.get(0).getQueryString());
        assertTrue(query.contains(" from #cmmsMachineParts_plannedEvent ev left join ev.productionLine evLine"
                + " left join ev.workstation ws left join ws.productionLine wsLine left join ev.division dv "));
        assertTrue(query.contains(" where ev.startDate < :dateTo and ev.finishDate > :dateFrom"
                + " and ev.requiresShutdown = :requiresShutdown and (evLine.id = :productionLineId"
                + " or wsLine.id = :productionLineId or dv.id in (select lineDivision.id from #basic_division lineDivision"
                + " join lineDivision.productionLines divisionLine where divisionLine.id = :productionLineId))"));
        assertTrue(query.endsWith(" order by ev.startDate asc, ev.id asc"));

        for (String alias : SHUTDOWN_EVENT_ALIASES) {
            assertTrue(alias, query.contains(" as " + alias + ",") || query.contains(" as " + alias + " from "));
        }
    }

    @Test
    public final void shouldBindSlotShutdownFlagAndTargetLineIdToShutdownEventsQuery() {
        // given
        Map<String, String> expectedSetters = new LinkedHashMap<String, String>();

        expectedSetters.put(DATE_FROM_PARAMETER, "setTimestamp");
        expectedSetters.put(DATE_TO_PARAMETER, "setTimestamp");
        expectedSetters.put(REQUIRES_SHUTDOWN_PARAMETER, "setBoolean");
        expectedSetters.put(PRODUCTION_LINE_ID_PARAMETER, "setLong");

        // when
        validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        ShutdownEventsQuery query = plannedEventQueries.get(0);

        assertEquals(expectedSetters, query.getSetters());
        assertEquals(slotFrom, query.getValue(DATE_FROM_PARAMETER));
        assertEquals(slotTo, query.getValue(DATE_TO_PARAMETER));
        assertEquals(Boolean.TRUE, query.getValue(REQUIRES_SHUTDOWN_PARAMETER));
        assertEquals(lineL2.getId(), query.getValue(PRODUCTION_LINE_ID_PARAMETER));
    }

    @Test
    public final void shouldReadShutdownEventsOfEveryLineReferenceInOneQueryWithoutCriteriaOrDivisionQuery() {
        // given
        plannedEvents.add(plannedEvent("EV-LINE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 10:30:00",
                "2026-10-01 13:00:00", lineL2, null, division(lineL1)));
        plannedEvents.add(plannedEvent("EV-WORKSTATION", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 10:45:00",
                "2026-10-01 13:00:00", null, workstation(lineL2), division(lineL1)));
        plannedEvents.add(plannedEvent("EV-DIVISION", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, null, division(lineL1, lineL2)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-LINE");
        assertEquals(1, plannedEventQueries.size());
        assertEquals(3, plannedEventQueries.get(0).getRows().size());
        verify(plannedEventDD).find(ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY);
        verify(plannedEventDD, never()).find();
        verifyNoMoreInteractions(plannedEventDD);
        verify(dataDefinitionService).get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT);
        verify(dataDefinitionService, never()).get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_DIVISION);
        verifyNoMoreInteractions(dataDefinitionService);
        verifyZeroInteractions(productionLineDD, positionDD);
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
        // given
        plannedEvents.clear();

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        verify(plannedEventDD).find(ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY);
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

    @Test
    public final void shouldAcceptShutdownEventOfAnotherLineWhoseWorkstationIsOnTargetLine() {
        // given
        plannedEvents.add(plannedEvent("EV-L1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL1, workstation(lineL2), division(lineL1)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        assertEquals(1, plannedEventQueries.get(0).getRows().size());
    }

    @Test
    public final void shouldAcceptShutdownEventWhoseWorkstationIsOnAnotherLineAndWhoseDivisionHoldsTargetLine() {
        // given
        plannedEvents.add(plannedEvent("EV-W-L1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", null, workstation(lineL1), division(lineL1, lineL2)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        assertEquals(1, plannedEventQueries.get(0).getRows().size());
    }

    @Test
    public final void shouldRejectShutdownEventOfTargetLineWhoseDivisionDoesNotHoldTargetLine() {
        // given
        plannedEvents.add(plannedEvent("EV-L2", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, division(lineL1)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-L2");
        assertEquals(1, plannedEventQueries.get(0).getRows().size());
    }

    @Test
    public final void shouldNameEarliestShutdownEventPlacedOnTargetLineWhenSeveralOverlap() {
        // given
        plannedEvents.add(plannedEvent("EV-LINE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 10:45:00",
                "2026-10-01 13:00:00", lineL2, null, division(lineL1)));
        plannedEvents.add(plannedEvent("EV-OTHER-LINE", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 10:05:00",
                "2026-10-01 13:00:00", lineL1, workstation(lineL2), division(lineL2)));
        plannedEvents.add(plannedEvent("EV-WORKSTATION", PlannedEventStateStringValues.CANCELED, true,
                "2026-10-01 10:30:00", "2026-10-01 13:00:00", null, workstation(lineL2), division(lineL1)));
        plannedEvents.add(plannedEvent("EV-DIVISION", PlannedEventStateStringValues.IN_REALIZATION, true,
                "2026-10-01 10:15:00", "2026-10-01 13:00:00", null, null, division(lineL2)));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-DIVISION");
        assertEquals(4, plannedEventQueries.get(0).getRows().size());
    }

    @Test
    public final void shouldNameShutdownEventWithLowerIdWhenOverlappingEventsStartTogether() {
        // given
        Entity firstEvent = plannedEvent("EV-FIRST", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null);
        Entity secondEvent = plannedEvent("EV-SECOND", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 12:00:00", null, workstation(lineL2), null);

        plannedEvents.add(secondEvent);
        plannedEvents.add(firstEvent);

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(lineL2, slotFrom, slotTo);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, "EV-FIRST");
    }

    @Test
    public final void shouldAcceptNullTargetLineWithoutQueryingShutdownEvents() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(null, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        assertTrue(plannedEventQueries.isEmpty());
        verifyZeroInteractions(plannedEventDD, dataDefinitionService);
    }

    @Test
    public final void shouldAcceptTargetLineWithoutIdWithoutQueryingShutdownEvents() {
        // given
        Entity unsavedLine = mockEntity();
        given(unsavedLine.getId()).willReturn(null);
        stubStringField(unsavedLine, ProductionLineFields.NUMBER, "L2");
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));

        // when
        Optional<MoveRejection> rejection = validator.checkShutdownWindow(unsavedLine, slotFrom, slotTo);

        // then
        assertFalse(rejection.isPresent());
        assertTrue(plannedEventQueries.isEmpty());
        verifyZeroInteractions(plannedEventDD, dataDefinitionService);
    }

    @Test
    public final void shouldRequireShutdownWindowStart() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));
        NullPointerException exception = null;

        // when
        try {
            validator.checkShutdownWindow(lineL2, null, slotTo);
            fail("checkShutdownWindow accepted a null slot start");
        } catch (NullPointerException e) {
            exception = e;
        }

        // then
        assertEquals("dateFrom", exception.getMessage());
        verifyZeroInteractions(plannedEventDD);
    }

    @Test
    public final void shouldRequireShutdownWindowEnd() {
        // given
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, true, "2026-10-01 11:00:00",
                "2026-10-01 13:00:00", lineL2, null, null));
        NullPointerException exception = null;

        // when
        try {
            validator.checkShutdownWindow(lineL2, slotFrom, null);
            fail("checkShutdownWindow accepted a null slot end");
        } catch (NullPointerException e) {
            exception = e;
        }

        // then
        assertEquals("dateTo", exception.getMessage());
        verifyZeroInteractions(plannedEventDD);
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

    @Test
    public final void shouldRequireWorkingHoursStart() {
        // given
        given(shiftsService.getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.any(Entity.class))).willReturn(
                Optional.of(new DateTime(slotFrom)));
        given(shiftsService.findAll(Matchers.any(Entity.class))).willReturn(Collections.<Shift> emptyList());
        NullPointerException exception = null;

        // when
        try {
            validator.checkWorkingHours(lineL2, null);
            fail("checkWorkingHours accepted a null slot start");
        } catch (NullPointerException e) {
            exception = e;
        }

        // then
        assertEquals("dateFrom", exception.getMessage());
        verifyZeroInteractions(shiftsService);
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
                Collections.singletonList(lineL2), true, false);
        verify(plannedEventDD).find(ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY);
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
        assertEquals(slotFrom, plannedEventQueries.get(0).getValue(DATE_FROM_PARAMETER));
        assertEquals(slotTo, plannedEventQueries.get(0).getValue(DATE_TO_PARAMETER));

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
        // given
        String[] noArgs = null;

        // when
        MoveRejection rejection = new MoveRejection(ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY, noArgs);
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
        given(productionLine.isActive()).willReturn(active);

        return productionLine;
    }

    /**
     * Returns a pending order with the given number, a technology and no production line.
     */
    private static Entity order(final String number) {
        return order(number, null, OrderStateStringValues.PENDING, mockEntity(31L));
    }

    private static Entity order(final String number, final Entity productionLine, final String state, final Entity technology) {
        Entity order = mockEntity(21L);

        stubStringField(order, OrderFields.NUMBER, number);
        stubStringField(order, OrderFields.STATE, state);
        stubBelongsToField(order, OrderFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(order, OrderFields.TECHNOLOGY, technology);

        return order;
    }

    /**
     * Returns a technology whose {@code productionLines} has-many field holds the given technology production lines.
     */
    private static Entity technology(final Entity... technologyProductionLines) {
        Entity technology = mockEntity(31L);

        stubHasManyField(technology, TechnologyFields.PRODUCTION_LINES, Arrays.asList(technologyProductionLines));

        return technology;
    }

    private static Entity technologyProductionLine(final Entity productionLine) {
        Entity technologyProductionLine = mockEntity();

        stubBelongsToField(technologyProductionLine, TechnologyProductionLineFields.PRODUCTION_LINE, productionLine);

        return technologyProductionLine;
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
     * Returns a division with the next division id whose {@code getManyToManyField(productionLines)} answers a {@link List}
     * of the given production lines, the return type of {@link Entity#getManyToManyField(String)}.
     */
    private Entity division(final Entity... productionLines) {
        Entity division = mockEntity(nextDivisionId++);

        given(division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)).willReturn(
                new ArrayList<Entity>(Arrays.asList(productionLines)));

        return division;
    }

    private static Map<SearchCriterion, EntityCondition> productionLineConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true), new TrueFieldCondition(
                ProductionLineFields.PRODUCTION));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true), new ActiveCondition());

        return conditions;
    }

    /**
     * Returns the parameters of {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY}, each with the name
     * of the {@link SearchQueryBuilder} setter that binds it.
     */
    private static Map<String, String> shutdownEventsQueryParameters() {
        Map<String, String> parameters = new LinkedHashMap<String, String>();

        parameters.put(DATE_FROM_PARAMETER, "setTimestamp");
        parameters.put(DATE_TO_PARAMETER, "setTimestamp");
        parameters.put(REQUIRES_SHUTDOWN_PARAMETER, "setBoolean");
        parameters.put(PRODUCTION_LINE_ID_PARAMETER, "setLong");

        return parameters;
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
     * Satisfied when the entity is active.
     */
    private static final class ActiveCondition implements EntityCondition {

        @Override
        public boolean matches(final Entity entity) {
            return entity.isActive();
        }

    }

    /**
     * Orders planned events ascending by their non-null start date, then by their non-null id.
     */
    private static final class StartDateAndIdComparator implements Comparator<Entity> {

        @Override
        public int compare(final Entity first, final Entity second) {
            int result = first.getDateField(PlannedEventFields.START_DATE).compareTo(
                    second.getDateField(PlannedEventFields.START_DATE));

            if (result != 0) {
                return result;
            }

            return first.getId().compareTo(second.getId());
        }

    }

    /**
     * Query text, bound parameters with their setters, and returned projection rows of one shutdown events query.
     */
    private static final class ShutdownEventsQuery {

        private final String queryString;

        private final Map<String, String> setters = new LinkedHashMap<String, String>();

        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        private final List<Entity> rows = new ArrayList<Entity>();

        private ShutdownEventsQuery(final String queryString) {
            this.queryString = queryString;
        }

        private String getQueryString() {
            return queryString;
        }

        private Map<String, String> getSetters() {
            return setters;
        }

        private Object getValue(final String name) {
            return values.get(name);
        }

        private List<Entity> getRows() {
            return rows;
        }

    }

    /**
     * Answers {@code find(String)} of {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY} with a new
     * {@link ShutdownEventsQueryBuilderAnswer} builder over the given planned events, recorded as a new query of the given
     * query list. Fails on every other query.
     */
    private static final class ShutdownEventsFindAnswer implements Answer<SearchQueryBuilder> {

        private final List<Entity> plannedEvents;

        private final List<ShutdownEventsQuery> queries;

        private ShutdownEventsFindAnswer(final List<Entity> plannedEvents, final List<ShutdownEventsQuery> queries) {
            this.plannedEvents = plannedEvents;
            this.queries = queries;
        }

        @Override
        public SearchQueryBuilder answer(final InvocationOnMock invocation) {
            String queryString = (String) invocation.getArguments()[0];

            if (!ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY.equals(queryString)) {
                throw new AssertionError("Unexpected planned event query: " + queryString);
            }

            ShutdownEventsQuery query = new ShutdownEventsQuery(queryString);

            queries.add(query);

            return mock(SearchQueryBuilder.class, new ShutdownEventsQueryBuilderAnswer(plannedEvents, query));
        }

    }

    /**
     * Records each parameter of {@link #shutdownEventsQueryParameters()} bound once with its own setter, and answers the
     * setter with the builder itself. Answers {@code list()}, once every parameter is bound, with a projection row of each
     * planned event that starts before {@code dateTo}, finishes after {@code dateFrom} and has the bound requires-shutdown
     * flag, and whose own production line or workstation production line has the bound id or whose division holds a
     * production line with that id, in ascending start date and id order. Fails on an unknown or repeated parameter, a
     * parameter bound with another setter, a missing parameter and every other builder method.
     */
    private static final class ShutdownEventsQueryBuilderAnswer implements Answer<Object> {

        private final List<Entity> plannedEvents;

        private final ShutdownEventsQuery query;

        private final Map<String, String> parameters = shutdownEventsQueryParameters();

        private ShutdownEventsQueryBuilderAnswer(final List<Entity> plannedEvents, final ShutdownEventsQuery query) {
            this.plannedEvents = plannedEvents;
            this.query = query;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            String methodName = method.getName();
            Object[] arguments = invocation.getArguments();

            if (Object.class.equals(method.getDeclaringClass())) {
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
            if (parameters.containsValue(methodName)) {
                bind(methodName, (String) arguments[0], arguments[1]);

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                for (String name : parameters.keySet()) {
                    if (!query.setters.containsKey(name)) {
                        throw new AssertionError("Missing shutdown events query parameter: " + name);
                    }
                }

                query.rows.addAll(select());

                return mock(SearchResult.class, new SearchResultAnswer(new ArrayList<Entity>(query.rows)));
            }

            throw new AssertionError("Unexpected shutdown events query builder method: " + methodName);
        }

        private void bind(final String setter, final String name, final Object value) {
            if (!setter.equals(parameters.get(name))) {
                throw new AssertionError("Unexpected shutdown events query parameter " + name + " bound with " + setter);
            }
            if (query.setters.containsKey(name)) {
                throw new AssertionError("Shutdown events query parameter bound twice: " + name);
            }

            query.setters.put(name, setter);
            query.values.put(name, value);
        }

        private List<Entity> select() {
            Date dateFrom = (Date) query.getValue(DATE_FROM_PARAMETER);
            Date dateTo = (Date) query.getValue(DATE_TO_PARAMETER);
            boolean requiresShutdown = (Boolean) query.getValue(REQUIRES_SHUTDOWN_PARAMETER);
            Long productionLineId = (Long) query.getValue(PRODUCTION_LINE_ID_PARAMETER);

            List<Entity> selected = new ArrayList<Entity>();

            for (Entity plannedEvent : plannedEvents) {
                if (overlaps(plannedEvent, dateFrom, dateTo)
                        && plannedEvent.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN) == requiresShutdown
                        && concernsLine(plannedEvent, productionLineId)) {
                    selected.add(plannedEvent);
                }
            }

            Collections.sort(selected, new StartDateAndIdComparator());

            List<Entity> rows = new ArrayList<Entity>();

            for (Entity plannedEvent : selected) {
                rows.add(projectionRow(plannedEvent));
            }

            return rows;
        }

        private static boolean overlaps(final Entity plannedEvent, final Date dateFrom, final Date dateTo) {
            Date startDate = plannedEvent.getDateField(PlannedEventFields.START_DATE);
            Date finishDate = plannedEvent.getDateField(PlannedEventFields.FINISH_DATE);

            return startDate != null && finishDate != null && startDate.before(dateTo) && finishDate.after(dateFrom);
        }

        private static boolean concernsLine(final Entity plannedEvent, final Long productionLineId) {
            if (hasId(plannedEvent.getBelongsToField(PlannedEventFields.PRODUCTION_LINE), productionLineId)
                    || hasId(workstationLine(plannedEvent), productionLineId)) {
                return true;
            }

            Entity division = plannedEvent.getBelongsToField(PlannedEventFields.DIVISION);

            if (division == null || division.getId() == null) {
                return false;
            }

            for (Entity divisionLine : division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)) {
                if (hasId(divisionLine, productionLineId)) {
                    return true;
                }
            }

            return false;
        }

        private static Entity projectionRow(final Entity plannedEvent) {
            Entity eventLine = plannedEvent.getBelongsToField(PlannedEventFields.PRODUCTION_LINE);
            Entity workstationLine = workstationLine(plannedEvent);
            Entity division = plannedEvent.getBelongsToField(PlannedEventFields.DIVISION);
            Map<String, Object> fields = new LinkedHashMap<String, Object>();

            fields.put(EVENT_NUMBER_ALIAS, plannedEvent.getStringField(PlannedEventFields.NUMBER));
            fields.put(EVENT_TYPE_ALIAS, plannedEvent.getStringField(PlannedEventFields.TYPE));
            fields.put(EVENT_STATE_ALIAS, plannedEvent.getStringField(PlannedEventFields.STATE));
            fields.put(REQUIRES_SHUTDOWN_ALIAS, plannedEvent.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN));
            fields.put(START_DATE_ALIAS, plannedEvent.getDateField(PlannedEventFields.START_DATE));
            fields.put(FINISH_DATE_ALIAS, plannedEvent.getDateField(PlannedEventFields.FINISH_DATE));
            fields.put(EVENT_LINE_ID_ALIAS, idOf(eventLine));
            fields.put(EVENT_LINE_NUMBER_ALIAS, numberOf(eventLine));
            fields.put(WORKSTATION_LINE_ID_ALIAS, idOf(workstationLine));
            fields.put(WORKSTATION_LINE_NUMBER_ALIAS, numberOf(workstationLine));
            fields.put(DIVISION_ID_ALIAS, idOf(division));

            return mock(Entity.class, new ProjectionRowAnswer(fields));
        }

        private static Entity workstationLine(final Entity plannedEvent) {
            Entity workstation = plannedEvent.getBelongsToField(PlannedEventFields.WORKSTATION);

            if (workstation == null) {
                return null;
            }

            return workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
        }

        private static boolean hasId(final Entity entity, final Long id) {
            return entity != null && id.equals(entity.getId());
        }

        private static Long idOf(final Entity entity) {
            if (entity == null) {
                return null;
            }

            return entity.getId();
        }

        private static String numberOf(final Entity productionLine) {
            if (productionLine == null) {
                return null;
            }

            return productionLine.getStringField(ProductionLineFields.NUMBER);
        }

    }

    /**
     * Answers {@code getField}, {@code getStringField}, {@code getLongField}, {@code getBooleanField} and
     * {@code getDateField} of a projection row with the value of the alias, false for a null flag and a copy for a date.
     * Fails on an alias the row does not hold and on every other entity method.
     */
    private static final class ProjectionRowAnswer implements Answer<Object> {

        private static final List<String> FIELD_GETTERS = Arrays.asList("getField", "getStringField", "getLongField",
                "getBooleanField", "getDateField");

        private final Map<String, Object> fields;

        private ProjectionRowAnswer(final Map<String, Object> fields) {
            this.fields = fields;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            String methodName = method.getName();

            if (Object.class.equals(method.getDeclaringClass())) {
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
            if (!FIELD_GETTERS.contains(methodName)) {
                throw new AssertionError("Unexpected projection row method: " + methodName);
            }

            String alias = (String) invocation.getArguments()[0];

            if (!fields.containsKey(alias)) {
                throw new AssertionError("Unknown projection row alias: " + alias);
            }

            Object value = fields.get(alias);

            if ("getBooleanField".equals(methodName)) {
                return Boolean.TRUE.equals(value);
            }
            if (value instanceof Date) {
                return new Date(((Date) value).getTime());
            }

            return value;
        }

    }

    /**
     * Fails the test on every call it answers, naming the unexpected call.
     */
    private static final class FailingAnswer implements Answer<Object> {

        private final String call;

        private FailingAnswer(final String call) {
            this.call = call;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) {
            throw new AssertionError("Unexpected " + call);
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
     * Answers {@code list()} with the entities satisfying every recorded criterion, sorted by the recorded orders, and the
     * methods declared by {@link Object} with Mockito defaults. Fails on unknown criteria, unknown orders and every other
     * builder method, fluent or not, including {@code uniqueResult()}, {@code existsAliasForAssociation} and
     * {@code getAliasForAssociation}.
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

            if (Object.class.equals(method.getDeclaringClass())) {
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
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

            throw new AssertionError("Unexpected builder method: " + methodName);
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
