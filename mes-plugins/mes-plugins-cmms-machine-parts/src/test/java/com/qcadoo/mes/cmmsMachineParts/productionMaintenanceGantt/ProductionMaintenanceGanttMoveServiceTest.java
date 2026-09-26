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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService.MoveRejectedException;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
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
import com.qcadoo.model.api.search.SearchQueryBuilder;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.model.api.validators.ErrorMessage;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of {@link ProductionMaintenanceGanttMoveService}.
 * <p>
 * The service runs against a spy of a real {@link ProductionMaintenanceGanttMoveValidator} whose collaborators are
 * mocks stubbed so that every check passes: the order's technology allows both production lines, no planned event
 * requires a shutdown, the nearest working date of the target line is the slot start itself, and the stored position
 * equals the item as rendered. The basic parameter data definition counts one parameter, and {@link ParameterService}
 * returns a parameter whose {@code canChangeProdLineForAcceptedOrders} is false. The production line data definition
 * answers {@code find()} with a builder that records its criteria, orders and maximum result count, and selects the fixture
 * entities satisfying every recorded criterion. Unknown criteria, unknown orders and unknown builder methods fail the test.
 * The planned event data definition answers {@code find(String)} of
 * {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY} with projection rows of the fixture planned
 * events the query selects, and fails on {@code find()}, on any other query and on a missing or unexpected parameter.
 * <p>
 * Tests that depart from this passing setup change it in four ways:
 * <ul>
 * <li>routing, shutdown-window and working-hours tests change the answer of the collaborator of their own check: the
 * candidate production lines of the order's technology, the planned events, or the nearest working date;</li>
 * <li>stale-position, schedule-state, schedule-membership, context, unknown-position, target-row and item-id tests change a
 * field of the stored position or schedule, the position returned for the item's id, or an input of the move request;</li>
 * <li>basic parameter tests make the basic parameter data definition count no parameter, or return no basic parameter data
 * definition;</li>
 * <li>invalid-save tests make the save of the position return null or an invalid entity, and recompute tests make the
 * recompute service throw.</li>
 * </ul>
 * The recompute service is a mock, so the production line schedule executor services behind it are not reached. A move
 * rejected before the save is asserted to leave the position unchanged and unsaved and the recompute service without any
 * interaction; a move rejected by an invalid save is asserted to leave the position without a fast save and the recompute
 * service without any interaction.
 * <p>
 * Fixture: draft schedule 7; production lines L1 (id 1, origin), L2 (id 2, target) and L3 (id 3); position 11 of order ORD-1 on
 * L1 from 08:00 to 10:00; move request onto row L2 from 10:00 to 12:00, rendered as row L1, name ORD-1, 08:00 to 10:00.
 */
public class ProductionMaintenanceGanttMoveServiceTest {

    private static final Long SCHEDULE_ID = 7L;

    private static final Long OTHER_SCHEDULE_ID = 8L;

    private static final Long POSITION_ID = 11L;

    private static final String ORDER_NUMBER = "ORD-1";

    private static final String OTHER_ORDER_NUMBER = "ORD-2";

    private static final String ORIGIN_LINE_NUMBER = "L1";

    private static final String TARGET_LINE_NUMBER = "L2";

    private static final String OTHER_LINE_NUMBER = "L3";

    private static final String UNKNOWN_LINE_NUMBER = "L9";

    private static final String POSITION_START = "2026-10-01 08:00:00";

    private static final String POSITION_END = "2026-10-01 10:00:00";

    private static final String SLOT_FROM = "2026-10-01 10:00:00";

    private static final String SLOT_TO = "2026-10-01 12:00:00";

    private static final String SHUTDOWN_EVENT_NUMBER = "EV-7";

    private ProductionMaintenanceGanttMoveService moveService;

    private ProductionMaintenanceGanttMoveValidator validatorSpy;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private ProductionLineSchedulePositionValidators productionLineSchedulePositionValidators;

    @Mock
    private ParameterService parameterService;

    @Mock
    private ShiftsService shiftsService;

    @Mock
    private ProductionMaintenanceGanttRecomputeService recomputeService;

    @Mock
    private DataDefinition positionDD, productionLineDD, plannedEventDD;

    @Mock
    private DataDefinition parameterDD;

    @Mock
    private GanttChartItem item;

    private Entity schedule, parameter, order, position, savedPosition;

    private Entity lineL1, lineL2, lineL3;

    private Date positionStart, positionEnd, slotFrom, slotTo;

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<FixtureQuery> productionLineQueries = new ArrayList<FixtureQuery>();

    private final List<String> plannedEventQueries = new ArrayList<String>();

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);

        positionStart = date(POSITION_START);
        positionEnd = date(POSITION_END);
        slotFrom = date(SLOT_FROM);
        slotTo = date(SLOT_TO);

        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION)).willReturn(positionDD);
        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE)).willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT)).willReturn(plannedEventDD);
        given(dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER)).willReturn(
                parameterDD);

        given(productionLineDD.find()).willAnswer(
                new FixtureFindAnswer(productionLines, productionLineConditions(), Collections
                        .<SearchOrder, Comparator<Entity>> emptyMap(), productionLineQueries));
        given(plannedEventDD.find()).willAnswer(new FailingAnswer("criteria find() of the planned event data definition"));
        given(plannedEventDD.find(Matchers.anyString())).willAnswer(
                new ShutdownEventsFindAnswer(plannedEvents, plannedEventQueries));

        lineL1 = productionLine(1L, ORIGIN_LINE_NUMBER);
        lineL2 = productionLine(2L, TARGET_LINE_NUMBER);
        lineL3 = productionLine(3L, OTHER_LINE_NUMBER);

        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));

        schedule = schedule(SCHEDULE_ID, ScheduleStateStringValues.DRAFT);

        parameter = mockEntity(1L);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, false);

        given(parameterDD.count()).willReturn(1L);
        given(parameterService.getParameter()).willReturn(parameter);

        order = order(ORDER_NUMBER);

        position = mockEntity(POSITION_ID, positionDD);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineL1);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.ORDER, order);
        stubDateField(position, ProductionLineSchedulePositionFields.START_TIME, positionStart);
        stubDateField(position, ProductionLineSchedulePositionFields.END_TIME, positionEnd);

        savedPosition = mockEntity(POSITION_ID, positionDD);
        given(savedPosition.isValid()).willReturn(true);

        given(item.getEntityId()).willReturn(POSITION_ID);
        given(positionDD.get(POSITION_ID)).willReturn(position);
        given(positionDD.save(position)).willReturn(savedPosition);

        givenCandidateLines(lineL1, lineL2);
        given(shiftsService.getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL2))).willAnswer(
                new Answer<Optional<DateTime>>() {

                    @Override
                    public Optional<DateTime> answer(final InvocationOnMock invocation) {
                        return Optional.of((DateTime) invocation.getArguments()[0]);
                    }

                });

        validatorSpy = spy(new ProductionMaintenanceGanttMoveValidator());

        setField(validatorSpy, "dataDefinitionService", dataDefinitionService);
        setField(validatorSpy, "productionLineSchedulePositionValidators", productionLineSchedulePositionValidators);
        setField(validatorSpy, "parameterService", parameterService);
        setField(validatorSpy, "shiftsService", shiftsService);

        ProductionMaintenanceGanttChartItemResolver resolver = new ProductionMaintenanceGanttChartItemResolver();

        setField(resolver, "dataDefinitionService", dataDefinitionService);
        setField(validatorSpy, "productionMaintenanceGanttChartItemResolver", resolver);

        moveService = new ProductionMaintenanceGanttMoveService();

        setField(moveService, "dataDefinitionService", dataDefinitionService);
        setField(moveService, "productionMaintenanceGanttMoveValidator", validatorSpy);
        setField(moveService, "productionMaintenanceGanttRecomputeService", recomputeService);
    }

    @Test
    public final void shouldValidatePersistAndRecomputeInOrder() {
        // given
        GanttChartMoveRequest request = moveRequest();

        // when
        moveService.move(request);

        // then
        InOrder inOrder = inOrder(positionDD, validatorSpy, position, recomputeService);

        inOrder.verify(positionDD).get(POSITION_ID);
        inOrder.verify(validatorSpy).validate(position, lineL2, request);
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineL2);
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.START_TIME, slotFrom);
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.END_TIME, slotTo);
        inOrder.verify(positionDD).save(position);
        inOrder.verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL2, slotFrom);

        verify(positionDD, never()).fastSave(Matchers.any(Entity.class));
        verifyNoMoreInteractions(recomputeService);
    }

    @Test
    public final void shouldRunEveryCheckOfValidatorOnStoredPositionBeforeSave() {
        // given
        GanttChartMoveRequest request = moveRequest();

        // when
        moveService.move(request);

        // then
        InOrder inOrder = inOrder(validatorSpy, positionDD);

        inOrder.verify(validatorSpy).checkRouting(position, lineL2);
        inOrder.verify(validatorSpy).checkShutdownWindow(lineL2, slotFrom, slotTo);
        inOrder.verify(validatorSpy).checkWorkingHours(lineL2, slotFrom);
        inOrder.verify(validatorSpy).checkConcurrentEdit(position, request);
        inOrder.verify(positionDD).save(position);
    }

    @Test
    public final void shouldLoadTargetLineByNumberOfTargetRow() {
        // given
        GanttChartMoveRequest request = moveRequest();

        // when
        moveService.move(request);

        // then
        assertEquals(1, productionLineQueries.size());
        assertEquals(Collections.singletonList(SearchRestrictions.eq(ProductionLineFields.NUMBER, TARGET_LINE_NUMBER)),
                productionLineQueries.get(0).getCriteria());
        assertEquals(Integer.valueOf(2), productionLineQueries.get(0).getMaxResults());
        verify(validatorSpy).validate(position, lineL2, request);
    }

    @Test
    public final void shouldRejectTargetRowMatchingSeveralProductionLines() {
        // given
        productionLines.add(productionLine(4L, TARGET_LINE_NUMBER));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertEquals(1, productionLineQueries.size());
        assertEquals(Collections.singletonList(SearchRestrictions.eq(ProductionLineFields.NUMBER, TARGET_LINE_NUMBER)),
                productionLineQueries.get(0).getCriteria());
        assertEquals(Integer.valueOf(2), productionLineQueries.get(0).getMaxResults());
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldAcceptNumericScheduleIdInContext() {
        // given
        GanttChartMoveRequest request = moveRequest(TARGET_LINE_NUMBER,
                context(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, SCHEDULE_ID));

        // when
        moveService.move(request);

        // then
        verify(positionDD).save(position);
        verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL2, slotFrom);
    }

    @Test
    public final void shouldRecomputeOriginRowOfSameRowMove() {
        // given
        givenCandidateLines(lineL1);
        given(shiftsService.getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL1))).willReturn(
                Optional.of(new DateTime(slotFrom)));

        GanttChartMoveRequest request = moveRequest(ORIGIN_LINE_NUMBER, scheduleContext());

        // when
        moveService.move(request);

        // then
        verify(position).setField(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineL1);
        verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL1, slotFrom);
    }

    @Test
    public final void shouldRejectRoutingMismatchAndPersistNothing() {
        // given
        givenCandidateLines(lineL1);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, "orders.error.inappropriateProductionLineForPositionOrder");
        assertEquals(ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY, rejection.getMessageKey());
        verify(validatorSpy).checkRouting(position, lineL2);
        verify(validatorSpy, never()).checkShutdownWindow(Matchers.any(Entity.class), Matchers.any(Date.class),
                Matchers.any(Date.class));
        verifyZeroInteractions(plannedEventDD);
        assertTrue(plannedEventQueries.isEmpty());
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectShutdownWindowCollisionAndPersistNothing() {
        // given
        plannedEvents.add(shutdownEvent(SHUTDOWN_EVENT_NUMBER, "2026-10-01 11:00:00", "2026-10-01 13:00:00", lineL2));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, SHUTDOWN_EVENT_NUMBER);
        assertEquals(Collections.singletonList(ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY),
                plannedEventQueries);
        verify(plannedEventDD, never()).find();
        verify(validatorSpy).checkShutdownWindow(lineL2, slotFrom, slotTo);
        verify(validatorSpy, never()).checkWorkingHours(Matchers.any(Entity.class), Matchers.any(Date.class));
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectCalendarViolationAndPersistNothing() {
        // given
        given(shiftsService.getNearestWorkingDate(Matchers.any(DateTime.class), Matchers.eq(lineL2))).willReturn(
                Optional.of(new DateTime(slotFrom).plusHours(1)));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.OUTSIDE_WORKING_HOURS_KEY);
        verify(validatorSpy).checkWorkingHours(lineL2, slotFrom);
        verify(validatorSpy, never()).checkConcurrentEdit(Matchers.any(Entity.class),
                Matchers.any(GanttChartMoveRequest.class));
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectStalePositionAndPersistNothingWhenLineChanged() {
        // given
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineL3);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(validatorSpy).checkConcurrentEdit(position, request);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectStalePositionAndPersistNothingWhenStartChanged() {
        // given
        stubDateField(position, ProductionLineSchedulePositionFields.START_TIME, date("2026-10-01 07:30:00"));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(validatorSpy).checkConcurrentEdit(position, request);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectStalePositionAndPersistNothingWhenEndChanged() {
        // given
        stubDateField(position, ProductionLineSchedulePositionFields.END_TIME, date("2026-10-01 10:30:00"));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(validatorSpy).checkConcurrentEdit(position, request);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectStalePositionAndPersistNothingWhenOrderChanged() {
        // given
        stubBelongsToField(position, ProductionLineSchedulePositionFields.ORDER, order(OTHER_ORDER_NUMBER));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(validatorSpy).checkConcurrentEdit(position, request);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectWhenScheduleNoLongerDraft() {
        // given
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.APPROVED);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        verifyZeroInteractions(productionLineDD);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectWhenScheduleRejected() {
        // given
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.REJECTED);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectPositionOutsideContextSchedule() {
        // given
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE,
                schedule(OTHER_SCHEDULE_ID, ScheduleStateStringValues.DRAFT));

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        verifyZeroInteractions(productionLineDD);
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectPositionWithoutSchedule() {
        // given
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectContextWithoutScheduleId() {
        // given
        GanttChartMoveRequest request = moveRequest(TARGET_LINE_NUMBER, new JSONObject());

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectMissingContext() {
        // given
        GanttChartMoveRequest request = moveRequest(TARGET_LINE_NUMBER, null);

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectNonNumericScheduleIdInContext() {
        // given
        GanttChartMoveRequest request = moveRequest(TARGET_LINE_NUMBER,
                context(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, "seven"));

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectUnknownPosition() {
        // given
        given(positionDD.get(POSITION_ID)).willReturn(null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(positionDD).get(POSITION_ID);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectItemWithoutEntityId() {
        // given
        given(item.getEntityId()).willReturn(null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verify(positionDD, never()).get(Matchers.anyLong());
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectUnknownTargetLine() {
        // given
        GanttChartMoveRequest request = moveRequest(UNKNOWN_LINE_NUMBER, scheduleContext());

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertEquals(1, productionLineQueries.size());
        assertEquals(Collections.singletonList(SearchRestrictions.eq(ProductionLineFields.NUMBER, UNKNOWN_LINE_NUMBER)),
                productionLineQueries.get(0).getCriteria());
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldRejectMissingTargetRowName() {
        // given
        GanttChartMoveRequest request = moveRequest(null, scheduleContext());

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        verifyZeroInteractions(productionLineDD);
        assertValidatorNeverCalled();
        assertNothingPersisted();
    }

    @Test
    public final void shouldFailOnNullRequest() {
        // given
        GanttChartMoveRequest request = null;

        // when
        IllegalArgumentException failure = null;

        try {
            moveService.move(request);
            fail("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            failure = e;
        }

        // then
        assertNotNull(failure);
        assertEquals("move request is required", failure.getMessage());
        verifyZeroInteractions(dataDefinitionService, positionDD, recomputeService);
        assertValidatorNeverCalled();
    }

    @Test
    public final void shouldRunInSerializableTransaction() throws NoSuchMethodException {
        // given
        Method move = ProductionMaintenanceGanttMoveService.class.getMethod("move", GanttChartMoveRequest.class);

        // when
        Transactional transactional = move.getAnnotation(Transactional.class);

        // then
        assertNotNull(transactional);
        assertEquals(Isolation.SERIALIZABLE, transactional.isolation());
        assertFalse(transactional.readOnly());
    }

    @Test
    public final void shouldUseMessageKeysOfRejectionReasons() {
        // given
        String expectedOptimisticLockKey = "qcadooView.validate.global.optimisticLock";
        String expectedSaveFailedKey = "cmmsMachineParts.productionMaintenanceGantt.move.error.saveFailed";
        String expectedRecomputeFailedKey = "cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed";

        // when
        String optimisticLockKey = ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY;
        String validatorOptimisticLockKey = ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY;
        String saveFailedKey = ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY;
        String recomputeFailedKey = ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY;

        // then
        assertEquals(expectedOptimisticLockKey, optimisticLockKey);
        assertEquals(validatorOptimisticLockKey, optimisticLockKey);
        assertEquals(expectedSaveFailedKey, saveFailedKey);
        assertEquals(expectedRecomputeFailedKey, recomputeFailedKey);
    }


    @Test
    public final void shouldRejectWithGlobalErrorWhenSaveIsInvalid() {
        // given
        givenInvalidSave(Collections.singletonList(new ErrorMessage("qcadooView.validate.global.optimisticLock")),
                Collections.<String, ErrorMessage> emptyMap());

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, "qcadooView.validate.global.optimisticLock");
        verify(positionDD).save(position);
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithFirstGlobalErrorAndItsArgumentsBeforeFieldErrors() {
        // given
        Map<String, ErrorMessage> fieldErrors = new LinkedHashMap<String, ErrorMessage>();
        fieldErrors.put(ProductionLineSchedulePositionFields.START_TIME, new ErrorMessage("some.field.error"));

        givenInvalidSave(Arrays.asList(new ErrorMessage("first.global.error", "A", "B"), new ErrorMessage("second.global.error")),
                fieldErrors);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, "first.global.error", "A", "B");
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithFieldErrorWhenNoGlobalError() {
        // given
        Map<String, ErrorMessage> fieldErrors = new LinkedHashMap<String, ErrorMessage>();
        fieldErrors.put(ProductionLineSchedulePositionFields.START_TIME, new ErrorMessage("some.field.error", "08:00"));
        fieldErrors.put(ProductionLineSchedulePositionFields.END_TIME, new ErrorMessage("other.field.error", "10:00"));

        givenInvalidSave(Collections.<ErrorMessage> emptyList(), fieldErrors);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, "some.field.error", "08:00");
        verify(positionDD).save(position);
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithSaveFailedWhenInvalidSaveHasNoErrors() {
        // given
        givenInvalidSave(Collections.<ErrorMessage> emptyList(), Collections.<String, ErrorMessage> emptyMap());

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithSaveFailedWhenInvalidSaveHasNullErrorCollections() {
        // given
        givenInvalidSave(null, null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithSaveFailedWhenSaveReturnsNull() {
        // given
        given(positionDD.save(position)).willReturn(null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertNothingRecomputed();
    }

    @Test
    public final void shouldRejectWithSaveFailedAfterEveryCheckWhenNoBasicParameterExists() {
        // given
        given(parameterDD.count()).willReturn(0L);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertEveryCheckRanBeforeBasicParameterCheck(request);
        assertNothingPersisted();
        verify(parameterService, never()).getParameter();
        verify(parameterDD, times(2)).count();
        verifyNoMoreInteractions(parameterDD);
    }

    @Test
    public final void shouldRejectWithSaveFailedAfterEveryCheckWhenBasicParameterModelIsMissing() {
        // given
        given(dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER)).willReturn(null);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertEveryCheckRanBeforeBasicParameterCheck(request);
        assertNothingPersisted();
        verify(parameterService, never()).getParameter();
        verifyZeroInteractions(parameterDD);
    }

    @Test
    public final void shouldRejectRoutingMismatchBeforeMissingBasicParameter() {
        // given
        givenCandidateLines(lineL1);
        given(parameterDD.count()).willReturn(0L);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveValidator.ROUTING_MISMATCH_KEY);
        verify(validatorSpy).checkRouting(position, lineL2);
        verify(productionLineSchedulePositionValidators).getProductionLinesFromTechnology(position,
                Collections.singletonList(lineL2), true, false);
        verify(validatorSpy, never()).checkShutdownWindow(Matchers.any(Entity.class), Matchers.any(Date.class),
                Matchers.any(Date.class));
        verify(validatorSpy, times(1)).isBasicParameterPresent();
        verify(parameterService, never()).getParameter();
        assertNothingPersisted();
    }

    @Test
    public final void shouldCountBasicParameterWithoutCreatingItBeforeSaveOfAcceptedMove() {
        // given
        GanttChartMoveRequest request = moveRequest();

        // when
        moveService.move(request);

        // then
        InOrder inOrder = inOrder(validatorSpy, positionDD, recomputeService);

        inOrder.verify(validatorSpy).checkConcurrentEdit(position, request);
        inOrder.verify(validatorSpy).isBasicParameterPresent();
        inOrder.verify(positionDD).save(position);
        inOrder.verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL2, slotFrom);

        verify(parameterDD, times(2)).count();
        verifyNoMoreInteractions(parameterDD);
        verify(parameterService, times(1)).getParameter();
    }

    @Test
    public final void shouldWrapRecomputeExceptionAsRecomputeFailed() {
        // given
        IllegalStateException failure = new IllegalStateException("no finish date");

        givenRecomputeThrows(failure);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY);
        assertSame(failure, rejection.getCause());
        verify(positionDD).save(position);
        verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL2, slotFrom);
    }

    @Test
    public final void shouldRethrowConcurrencyConflictFromRecompute() {
        // given
        ConcurrencyFailureException conflict = new ConcurrencyFailureException("x");

        givenRecomputeThrows(conflict);

        GanttChartMoveRequest request = moveRequest();

        // when
        try {
            moveService.move(request);
            fail("Expected the concurrency conflict to propagate");
        } catch (ConcurrencyFailureException e) {
            // then
            assertSame(conflict, e);
        }
    }

    @Test
    public final void shouldRethrowSerializationFailureFromRecompute() {
        // given
        RuntimeException serializationFailure = new RuntimeException("flush failed", new IllegalStateException(
                new SQLException("x", "40001")));

        givenRecomputeThrows(serializationFailure);

        GanttChartMoveRequest request = moveRequest();

        // when
        try {
            moveService.move(request);
            fail("Expected the serialization failure to propagate");
        } catch (RuntimeException e) {
            // then
            assertSame(serializationFailure, e);
        }
    }

    @Test
    public final void shouldPropagateRecomputeRejectionUnchanged() {
        // given
        MoveRejectedException recomputeRejection = new MoveRejectedException(
                ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY);

        givenRecomputeThrows(recomputeRejection);

        GanttChartMoveRequest request = moveRequest();

        // when
        MoveRejectedException rejection = rejectionOf(request);

        // then
        assertSame(recomputeRejection, rejection);
        assertRejection(rejection, ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY);
        assertNull(rejection.getCause());
    }

    @Test
    public final void shouldRecogniseConcurrencyFailureExceptionsAsConflicts() {
        // given
        ConcurrencyFailureException concurrencyFailure = new ConcurrencyFailureException("x");
        OptimisticLockingFailureException optimisticLockingFailure = new OptimisticLockingFailureException("x");
        CannotSerializeTransactionException cannotSerializeTransaction = new CannotSerializeTransactionException("x");
        RuntimeException wrappedOptimisticLockingFailure = new RuntimeException(new OptimisticLockingFailureException("x"));

        // when
        boolean concurrencyFailureIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(concurrencyFailure);
        boolean optimisticLockingFailureIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(optimisticLockingFailure);
        boolean cannotSerializeTransactionIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(cannotSerializeTransaction);
        boolean wrappedOptimisticLockingFailureIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(wrappedOptimisticLockingFailure);

        // then
        assertTrue(concurrencyFailureIsConflict);
        assertTrue(optimisticLockingFailureIsConflict);
        assertTrue(cannotSerializeTransactionIsConflict);
        assertTrue(wrappedOptimisticLockingFailureIsConflict);
    }

    @Test
    public final void shouldRecogniseSerializationFailureSqlStateInCauseChain() {
        // given
        SQLException serializationFailure = new SQLException("x", "40001");
        RuntimeException wrappedSerializationFailure = new RuntimeException(new IllegalStateException(serializationFailure));

        // when
        boolean serializationFailureIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(serializationFailure);
        boolean wrappedSerializationFailureIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(wrappedSerializationFailure);

        // then
        assertTrue(serializationFailureIsConflict);
        assertTrue(wrappedSerializationFailureIsConflict);
    }

    @Test
    public final void shouldRecogniseSerializationFailureInNextExceptionChain() {
        // given
        SQLException batchFailure = new SQLException("batch", "08000");

        batchFailure.setNextException(new SQLException("y", "23505"));
        batchFailure.setNextException(new SQLException("z", "40001"));

        RuntimeException wrappedBatchFailure = new RuntimeException(batchFailure);

        // when
        boolean wrappedBatchFailureIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(wrappedBatchFailure);

        // then
        assertTrue(wrappedBatchFailureIsConflict);
    }

    @Test
    public final void shouldNotRecogniseOtherExceptionsAsConflicts() {
        // given
        SQLException uniqueViolation = new SQLException("x", "23505");
        SQLException uniqueViolationWithNext = new SQLException("x", "23505");

        uniqueViolationWithNext.setNextException(new SQLException("y", "23503"));

        RuntimeException wrappedUniqueViolationWithNext = new RuntimeException(uniqueViolationWithNext);
        SQLException sqlExceptionWithoutState = new SQLException("x");
        RuntimeException otherRuntimeException = new RuntimeException("x");
        MoveRejectedException moveRejection = new MoveRejectedException(
                ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        Throwable noThrowable = null;

        // when
        boolean uniqueViolationIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(uniqueViolation);
        boolean wrappedUniqueViolationWithNextIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(wrappedUniqueViolationWithNext);
        boolean sqlExceptionWithoutStateIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(sqlExceptionWithoutState);
        boolean otherRuntimeExceptionIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(otherRuntimeException);
        boolean moveRejectionIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(moveRejection);
        boolean noThrowableIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(noThrowable);

        // then
        assertFalse(uniqueViolationIsConflict);
        assertFalse(wrappedUniqueViolationWithNextIsConflict);
        assertFalse(sqlExceptionWithoutStateIsConflict);
        assertFalse(otherRuntimeExceptionIsConflict);
        assertFalse(moveRejectionIsConflict);
        assertFalse(noThrowableIsConflict);
    }

    @Test
    public final void shouldTerminateOnCyclicCauseAndNextExceptionChains() {
        // given
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);

        first.initCause(second);

        SQLException firstSqlException = new SQLException("a", "23505");
        SQLException secondSqlException = new SQLException("b", "23503");

        firstSqlException.setNextException(secondSqlException);
        secondSqlException.setNextException(firstSqlException);

        // when
        boolean cyclicCauseChainIsConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(first);
        boolean cyclicNextExceptionChainIsConflict = ProductionMaintenanceGanttMoveService
                .isConcurrencyConflict(firstSqlException);

        // then
        assertFalse(cyclicCauseChainIsConflict);
        assertFalse(cyclicNextExceptionChainIsConflict);
    }

    @Test
    public final void shouldRecogniseSerializationFailureAsCauseOfNextExceptionThatIsAlsoTheCause() {
        // given
        SQLException batchFailure = serializationFailureAsCauseOfNextException();

        // when
        boolean conflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(batchFailure);
        boolean wrappedConflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(
                "flush failed", batchFailure));

        // then
        assertTrue(conflict);
        assertTrue(wrappedConflict);
    }

    @Test
    public final void shouldRecogniseConcurrencyFailureExceptionAsCauseOfNextException() {
        // given
        SQLException batchFailure = new SQLException("batch", "08000");
        SQLException nextFailure = new SQLException("next", "08003");

        batchFailure.setNextException(nextFailure);
        nextFailure.initCause(new ConcurrencyFailureException("conflict"));

        // when
        boolean conflict = ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(batchFailure));

        // then
        assertTrue(conflict);
    }

    @Test
    public final void shouldRethrowSerializationFailureAsCauseOfNextExceptionFromRecompute() {
        // given
        RuntimeException serializationFailure = new RuntimeException("flush failed",
                serializationFailureAsCauseOfNextException());

        givenRecomputeThrows(serializationFailure);

        GanttChartMoveRequest request = moveRequest();

        RuntimeException thrown = null;

        // when
        try {
            moveService.move(request);
            fail("Expected the serialization failure to propagate");
        } catch (RuntimeException e) {
            thrown = e;
        }

        // then
        assertSame(serializationFailure, thrown);
        verify(positionDD).save(position);
        verify(recomputeService).recompute(schedule, savedPosition, lineL1, positionStart, lineL2, slotFrom);
    }

    @Test
    public final void shouldCarryMessageKeyAndCopiesOfArguments() {
        // given
        String[] args = { SHUTDOWN_EVENT_NUMBER };

        MoveRejectedException rejection = new MoveRejectedException(
                ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, args);

        // when
        args[0] = "changed";
        rejection.getArgs()[0] = "changed again";

        // then
        assertEquals(ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, rejection.getMessageKey());
        assertEquals(ProductionMaintenanceGanttMoveValidator.SHUTDOWN_WINDOW_KEY, rejection.getMessage());
        assertArrayEquals(new String[] { SHUTDOWN_EVENT_NUMBER }, rejection.getArgs());
        assertArrayEquals(new String[0],
                new MoveRejectedException(ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY).getArgs());
        assertArrayEquals(new String[0], new MoveRejectedException(ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY,
                (String[]) null).getArgs());
    }

    @Test
    public final void shouldEscapeLineBreaksAndControlCharactersOfLogValue() {
        // given
        String forgedRowName = "L2\r\n2026-10-01 12:00:00 INFO forged entry\t\u0007";

        // when
        String logValue = ProductionMaintenanceGanttMoveService.toLogValue(forgedRowName);

        // then
        assertFalse(logValue.contains("\r"));
        assertFalse(logValue.contains("\n"));
        assertFalse(logValue.contains("\t"));
        assertTrue(logValue.contains("\\r\\n"));
        assertEquals("L2\\r\\n2026-10-01 12:00:00 INFO forged entry\\t\\u0007", logValue);
    }

    @Test
    public final void shouldKeepIdentifiersAndNullReadableInLogValue() {
        // given
        Long positionId = POSITION_ID;

        // when
        String positionIdLogValue = ProductionMaintenanceGanttMoveService.toLogValue(positionId);
        String rowNameLogValue = ProductionMaintenanceGanttMoveService.toLogValue(TARGET_LINE_NUMBER);
        String nullLogValue = ProductionMaintenanceGanttMoveService.toLogValue(null);

        // then
        assertEquals("11", positionIdLogValue);
        assertEquals(TARGET_LINE_NUMBER, rowNameLogValue);
        assertEquals("null", nullLogValue);
    }

    @Test
    public final void shouldFailFixtureBuilderOnUnmodelledMethods() {
        // given
        SearchCriteriaBuilder builder = productionLineDD.find();

        AssertionError aliasFailure = null;
        AssertionError firstResultFailure = null;

        // when
        try {
            builder.existsAliasForAssociation(ProductionLineFields.NUMBER);
        } catch (AssertionError e) {
            aliasFailure = e;
        }
        try {
            builder.setFirstResult(0);
        } catch (AssertionError e) {
            firstResultFailure = e;
        }

        // then
        assertNotNull(aliasFailure);
        assertEquals("Unexpected builder method: existsAliasForAssociation", aliasFailure.getMessage());
        assertNotNull(firstResultFailure);
        assertEquals("Unexpected builder method: setFirstResult", firstResultFailure.getMessage());
        assertEquals("fixture SearchCriteriaBuilder", builder.toString());
    }

    @Test
    public final void shouldSelectProductionLinesByEntityActivityForActiveCriterionInFixtureBuilder() {
        // given
        Entity inactiveLine = productionLine(4L, UNKNOWN_LINE_NUMBER);

        given(inactiveLine.isActive()).willReturn(false);
        productionLines.add(inactiveLine);

        // when
        List<Entity> activeLines = productionLineDD.find().add(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true))
                .list().getEntities();

        // then
        assertEquals(Arrays.asList(lineL1, lineL2, lineL3), activeLines);
        assertFalse(lineL1.getBooleanField(ProductionLineFields.ACTIVE));
    }

    private MoveRejectedException rejectionOf(final GanttChartMoveRequest request) {
        MoveRejectedException rejection = null;

        try {
            moveService.move(request);
            fail("Expected the move to be rejected");
        } catch (MoveRejectedException e) {
            rejection = e;
        }

        return rejection;
    }

    private static void assertRejection(final MoveRejectedException rejection, final String messageKey, final String... args) {
        assertNotNull(rejection);
        assertEquals(messageKey, rejection.getMessageKey());
        assertArrayEquals(args, rejection.getArgs());
    }

    private void assertNothingPersisted() {
        verify(positionDD, never()).save(Matchers.any(Entity.class));
        verify(positionDD, never()).fastSave(Matchers.any(Entity.class));
        verify(position, never()).setField(Matchers.anyString(), Matchers.any());
        verifyZeroInteractions(recomputeService);
    }

    private void assertNothingRecomputed() {
        verify(positionDD, never()).fastSave(Matchers.any(Entity.class));
        verifyZeroInteractions(recomputeService);
    }

    private void assertValidatorNeverCalled() {
        verify(validatorSpy, never()).validate(Matchers.any(Entity.class), Matchers.any(Entity.class),
                Matchers.any(GanttChartMoveRequest.class));
    }

    /**
     * Asserts that the routing, shutdown window, working hours and concurrent edit checks ran in this order on the stored
     * position and the target line L2, and that the move service then checked the basic parameter once.
     */
    private void assertEveryCheckRanBeforeBasicParameterCheck(final GanttChartMoveRequest request) {
        InOrder inOrder = inOrder(validatorSpy);

        inOrder.verify(validatorSpy).checkRouting(position, lineL2);
        inOrder.verify(validatorSpy).checkShutdownWindow(lineL2, slotFrom, slotTo);
        inOrder.verify(validatorSpy).checkWorkingHours(lineL2, slotFrom);
        inOrder.verify(validatorSpy).checkConcurrentEdit(position, request);
        inOrder.verify(validatorSpy).isBasicParameterPresent();
    }

    private void givenCandidateLines(final Entity... candidateLines) {
        given(productionLineSchedulePositionValidators.getProductionLinesFromTechnology(Matchers.eq(position),
                Matchers.anyListOf(Entity.class), Matchers.anyBoolean(), Matchers.anyBoolean())).willReturn(
                new ArrayList<Entity>(Arrays.asList(candidateLines)));
    }

    private void givenInvalidSave(final List<ErrorMessage> globalErrors, final Map<String, ErrorMessage> fieldErrors) {
        given(savedPosition.isValid()).willReturn(false);
        given(savedPosition.getGlobalErrors()).willReturn(globalErrors);
        given(savedPosition.getErrors()).willReturn(fieldErrors);
    }

    private void givenRecomputeThrows(final RuntimeException exception) {
        doThrow(exception).when(recomputeService).recompute(Matchers.any(Entity.class), Matchers.any(Entity.class),
                Matchers.any(Entity.class), Matchers.any(Date.class), Matchers.any(Entity.class), Matchers.any(Date.class));
    }

    /**
     * Returns an SQL exception with SQLSTATE 08000 whose next exception, SQLSTATE 08003, is also its cause, and whose next
     * exception has a serialization failure (SQLSTATE 40001) as its own cause.
     */
    private static SQLException serializationFailureAsCauseOfNextException() {
        SQLException batchFailure = new SQLException("batch", "08000");
        SQLException nextFailure = new SQLException("next", "08003");

        batchFailure.setNextException(nextFailure);
        batchFailure.initCause(nextFailure);
        nextFailure.initCause(new SQLException("could not serialize access due to concurrent update", "40001"));

        return batchFailure;
    }

    private GanttChartMoveRequest moveRequest() {
        return moveRequest(TARGET_LINE_NUMBER, scheduleContext());
    }

    private GanttChartMoveRequest moveRequest(final String targetRowName, final JSONObject context) {
        return new GanttChartMoveRequest(item, targetRowName, ORIGIN_LINE_NUMBER, ORDER_NUMBER, POSITION_START, POSITION_END,
                slotFrom, slotTo, context);
    }

    private static JSONObject scheduleContext() {
        return context(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, String.valueOf(SCHEDULE_ID));
    }

    private static JSONObject context(final String key, final Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Date date(final String value) {
        try {
            return new SimpleDateFormat(DateUtils.L_DATE_TIME_FORMAT).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Entity productionLine(final Long id, final String number) {
        Entity productionLine = mockEntity(id);

        stubStringField(productionLine, ProductionLineFields.NUMBER, number);
        stubBooleanField(productionLine, ProductionLineFields.PRODUCTION, true);
        given(productionLine.isActive()).willReturn(true);

        return productionLine;
    }

    private static Entity schedule(final Long id, final String state) {
        Entity productionLineSchedule = mockEntity(id);

        stubStringField(productionLineSchedule, ProductionLineScheduleFields.STATE, state);
        stubBooleanField(productionLineSchedule, ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE, true);

        return productionLineSchedule;
    }

    private static Entity order(final String number) {
        Entity productionOrder = mockEntity(21L);

        stubStringField(productionOrder, OrderFields.NUMBER, number);
        stubBelongsToField(productionOrder, OrderFields.TECHNOLOGY, mockEntity(31L));

        return productionOrder;
    }

    private static Entity shutdownEvent(final String number, final String startDate, final String finishDate,
            final Entity productionLine) {
        Entity plannedEvent = mockEntity(100L);

        stubStringField(plannedEvent, PlannedEventFields.NUMBER, number);
        stubBooleanField(plannedEvent, PlannedEventFields.REQUIRES_SHUTDOWN, true);
        stubDateField(plannedEvent, PlannedEventFields.START_DATE, date(startDate));
        stubDateField(plannedEvent, PlannedEventFields.FINISH_DATE, date(finishDate));
        stubBelongsToField(plannedEvent, PlannedEventFields.PRODUCTION_LINE, productionLine);

        return plannedEvent;
    }

    private static Map<SearchCriterion, EntityCondition> productionLineConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true), new TrueFieldCondition(
                ProductionLineFields.PRODUCTION));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true), new ActiveCondition());

        for (String number : Arrays.asList(ORIGIN_LINE_NUMBER, TARGET_LINE_NUMBER, OTHER_LINE_NUMBER, UNKNOWN_LINE_NUMBER)) {
            conditions.put(SearchRestrictions.eq(ProductionLineFields.NUMBER, number), new StringFieldCondition(
                    ProductionLineFields.NUMBER, number));
        }

        return conditions;
    }

    /**
     * Returns the parameters of {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY}, each with the name
     * of the {@link SearchQueryBuilder} setter that binds it.
     */
    private static Map<String, String> shutdownEventsQueryParameters() {
        Map<String, String> parameters = new LinkedHashMap<String, String>();

        parameters.put("dateFrom", "setTimestamp");
        parameters.put("dateTo", "setTimestamp");
        parameters.put("requiresShutdown", "setBoolean");
        parameters.put("productionLineId", "setLong");

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
     * Satisfied when the string field equals the value.
     */
    private static final class StringFieldCondition implements EntityCondition {

        private final String fieldName;

        private final String value;

        private StringFieldCondition(final String fieldName, final String value) {
            this.fieldName = fieldName;
            this.value = value;
        }

        @Override
        public boolean matches(final Entity entity) {
            return value.equals(entity.getStringField(fieldName));
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
     * Criteria, orders and maximum result count recorded by one builder.
     */
    private static final class FixtureQuery {

        private final List<SearchCriterion> criteria = new ArrayList<SearchCriterion>();

        private final List<SearchOrder> orders = new ArrayList<SearchOrder>();

        private Integer maxResults;

        private List<SearchCriterion> getCriteria() {
            return criteria;
        }

        private Integer getMaxResults() {
            return maxResults;
        }

    }

    /**
     * Answers {@code find()} with a new builder that records into a new query of the given query list.
     */
    private static final class FixtureFindAnswer implements Answer<SearchCriteriaBuilder> {

        private final List<Entity> entities;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<FixtureQuery> queries;

        private FixtureFindAnswer(final List<Entity> entities, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders, final List<FixtureQuery> queries) {
            this.entities = entities;
            this.conditions = conditions;
            this.orders = orders;
            this.queries = queries;
        }

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            FixtureQuery query = new FixtureQuery();

            queries.add(query);

            return mock(SearchCriteriaBuilder.class, new FixtureCriteriaBuilderAnswer(entities, conditions, orders, query));
        }

    }

    /**
     * Answers {@code add}, {@code addOrder} and {@code setMaxResults} with the builder itself after recording their argument,
     * {@code list()} with a search result of the selected entities, and {@code uniqueResult()} with null for no selected
     * entity, the entity for one, and an {@link IllegalStateException} for several, as the framework's search criteria do. The
     * selected entities are those satisfying every recorded criterion, sorted by the recorded orders and limited to the
     * recorded maximum result count. Answers {@code toString()} with a fixed description, and {@code equals(Object)} and
     * {@code hashCode()} with identity semantics. Throws {@link AssertionError} on unknown criteria, unknown orders and every
     * other method, whatever its return type.
     */
    private static final class FixtureCriteriaBuilderAnswer implements Answer<Object> {

        private static final String BUILDER_DESCRIPTION = "fixture SearchCriteriaBuilder";

        private final List<Entity> entities;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final FixtureQuery query;

        private FixtureCriteriaBuilderAnswer(final List<Entity> entities, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders, final FixtureQuery query) {
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

                query.criteria.add(criterion);

                return invocation.getMock();
            }
            if ("addOrder".equals(methodName)) {
                SearchOrder order = (SearchOrder) invocation.getArguments()[0];

                if (!orders.containsKey(order)) {
                    throw new AssertionError("Unexpected order: " + order.getHibernateOrder());
                }

                query.orders.add(order);

                return invocation.getMock();
            }
            if ("setMaxResults".equals(methodName)) {
                query.maxResults = (Integer) invocation.getArguments()[0];

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                return mock(SearchResult.class, new SearchResultAnswer(select()));
            }
            if ("uniqueResult".equals(methodName)) {
                List<Entity> selected = select();

                if (selected.size() > 1) {
                    throw new IllegalStateException("Too many results, expected one, found " + selected.size());
                }
                if (selected.isEmpty()) {
                    return null;
                }

                return selected.get(0);
            }

            int parameterCount = method.getParameterTypes().length;

            if ("toString".equals(methodName) && parameterCount == 0) {
                return BUILDER_DESCRIPTION;
            }
            if ("equals".equals(methodName) && parameterCount == 1) {
                return invocation.getMock() == invocation.getArguments()[0];
            }
            if ("hashCode".equals(methodName) && parameterCount == 0) {
                return System.identityHashCode(invocation.getMock());
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

            for (int index = query.orders.size() - 1; index >= 0; index--) {
                Collections.sort(selected, orders.get(query.orders.get(index)));
            }

            if (query.maxResults != null && selected.size() > query.maxResults) {
                return new ArrayList<Entity>(selected.subList(0, query.maxResults));
            }

            return selected;
        }

        private boolean matchesEveryCriterion(final Entity entity) {
            for (SearchCriterion criterion : query.criteria) {
                if (!conditions.get(criterion).matches(entity)) {
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

    /**
     * Orders entities ascending by their non-null id.
     */
    private static final class IdComparator implements Comparator<Entity> {

        @Override
        public int compare(final Entity first, final Entity second) {
            return first.getId().compareTo(second.getId());
        }

    }

    /**
     * Answers {@code find(String)} of {@link ProductionMaintenanceGanttChartItemResolver#SHUTDOWN_EVENTS_QUERY} with a new
     * {@link ShutdownEventsQueryBuilderAnswer} builder over the given planned events after recording the query text in the
     * given query list. Fails on every other query.
     */
    private static final class ShutdownEventsFindAnswer implements Answer<SearchQueryBuilder> {

        private final List<Entity> plannedEvents;

        private final List<String> queries;

        private ShutdownEventsFindAnswer(final List<Entity> plannedEvents, final List<String> queries) {
            this.plannedEvents = plannedEvents;
            this.queries = queries;
        }

        @Override
        public SearchQueryBuilder answer(final InvocationOnMock invocation) {
            String queryString = (String) invocation.getArguments()[0];

            if (!ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY.equals(queryString)) {
                throw new AssertionError("Unexpected planned event query: " + queryString);
            }

            queries.add(queryString);

            return mock(SearchQueryBuilder.class, new ShutdownEventsQueryBuilderAnswer(plannedEvents));
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

        private final Map<String, String> parameters = shutdownEventsQueryParameters();

        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        private ShutdownEventsQueryBuilderAnswer(final List<Entity> plannedEvents) {
            this.plannedEvents = plannedEvents;
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
                String name = (String) arguments[0];

                if (!methodName.equals(parameters.get(name))) {
                    throw new AssertionError("Unexpected shutdown events query parameter " + name + " bound with "
                            + methodName);
                }
                if (values.containsKey(name)) {
                    throw new AssertionError("Shutdown events query parameter bound twice: " + name);
                }

                values.put(name, arguments[1]);

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                for (String name : parameters.keySet()) {
                    if (!values.containsKey(name)) {
                        throw new AssertionError("Missing shutdown events query parameter: " + name);
                    }
                }

                return mock(SearchResult.class, new SearchResultAnswer(select()));
            }

            throw new AssertionError("Unexpected shutdown events query builder method: " + methodName);
        }

        private List<Entity> select() {
            EntityCondition startsBeforeEnd = new DateFieldCondition(PlannedEventFields.START_DATE, (Date) values.get("dateTo"),
                    false);
            EntityCondition finishesAfterStart = new DateFieldCondition(PlannedEventFields.FINISH_DATE,
                    (Date) values.get("dateFrom"), true);
            boolean requiresShutdown = (Boolean) values.get("requiresShutdown");
            Long productionLineId = (Long) values.get("productionLineId");

            List<Entity> selected = new ArrayList<Entity>();

            for (Entity plannedEvent : plannedEvents) {
                if (startsBeforeEnd.matches(plannedEvent) && finishesAfterStart.matches(plannedEvent)
                        && plannedEvent.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN) == requiresShutdown
                        && concernsLine(plannedEvent, productionLineId)) {
                    selected.add(plannedEvent);
                }
            }

            Collections.sort(selected, new IdComparator());
            Collections.sort(selected, new DateFieldComparator(PlannedEventFields.START_DATE));

            List<Entity> rows = new ArrayList<Entity>();

            for (Entity plannedEvent : selected) {
                rows.add(projectionRow(plannedEvent));
            }

            return rows;
        }

        private static boolean concernsLine(final Entity plannedEvent, final Long productionLineId) {
            if (hasId(plannedEvent.getBelongsToField(PlannedEventFields.PRODUCTION_LINE), productionLineId)
                    || hasId(workstationLine(plannedEvent), productionLineId)) {
                return true;
            }

            Entity division = plannedEvent.getBelongsToField(PlannedEventFields.DIVISION);

            if (division == null) {
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

            fields.put("eventNumber", plannedEvent.getStringField(PlannedEventFields.NUMBER));
            fields.put("eventType", plannedEvent.getStringField(PlannedEventFields.TYPE));
            fields.put("eventState", plannedEvent.getStringField(PlannedEventFields.STATE));
            fields.put("requiresShutdown", plannedEvent.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN));
            fields.put("startDate", plannedEvent.getDateField(PlannedEventFields.START_DATE));
            fields.put("finishDate", plannedEvent.getDateField(PlannedEventFields.FINISH_DATE));
            fields.put("eventLineId", idOf(eventLine));
            fields.put("eventLineNumber", numberOf(eventLine));
            fields.put("workstationLineId", idOf(workstationLine));
            fields.put("workstationLineNumber", numberOf(workstationLine));
            fields.put("divisionId", idOf(division));

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

}
