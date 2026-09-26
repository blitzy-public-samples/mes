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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Field;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
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
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService.MoveRejectedException;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePPSExecutorService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePSExecutorService;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchCriterion;
import com.qcadoo.model.api.search.SearchOrder;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.model.api.validators.ErrorMessage;
import com.qcadoo.model.internal.api.DataAccessService;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of {@link ProductionMaintenanceGanttMoveService}.
 * <p>
 * The service runs against a spy of a real {@link ProductionMaintenanceGanttMoveValidator} whose collaborators are mocks
 * stubbed so that every check passes: the order's technology allows both production lines, no planned event requires a
 * shutdown, the nearest working date of the target line is the slot start itself, and the stored position equals the item as
 * rendered. Each rejection test changes only the collaborator of its own check. The production line and planned event data
 * definitions answer {@code find()} with a builder that records its criteria, orders and maximum result count, and selects the
 * fixture entities satisfying every recorded criterion. Unknown criteria, unknown orders and unknown builder methods fail the
 * test.
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
    private ProductionLineScheduleServicePSExecutorService psExecutor;

    @Mock
    private ProductionLineScheduleServicePPSExecutorService ppsExecutor;

    @Mock
    private DataAccessService dataAccessService;

    @Mock
    private DataDefinition positionDD, productionLineDD, plannedEventDD;

    @Mock
    private GanttChartItem item;

    private DataAccessService previousDataAccessService;

    private Entity schedule, parameter, order, position, savedPosition;

    private Entity lineL1, lineL2, lineL3;

    private Date positionStart, positionEnd, slotFrom, slotTo;

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<FixtureQuery> productionLineQueries = new ArrayList<FixtureQuery>();

    private final List<FixtureQuery> plannedEventQueries = new ArrayList<FixtureQuery>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(
                AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

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

        given(productionLineDD.find()).willAnswer(
                new FixtureFindAnswer(productionLines, productionLineConditions(), Collections
                        .<SearchOrder, Comparator<Entity>> emptyMap(), productionLineQueries));
        given(plannedEventDD.find()).willAnswer(
                new FixtureFindAnswer(plannedEvents, plannedEventConditions(), plannedEventOrders(), plannedEventQueries));

        lineL1 = productionLine(1L, ORIGIN_LINE_NUMBER);
        lineL2 = productionLine(2L, TARGET_LINE_NUMBER);
        lineL3 = productionLine(3L, OTHER_LINE_NUMBER);

        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));

        schedule = schedule(SCHEDULE_ID, ScheduleStateStringValues.DRAFT);

        parameter = mockEntity(1L);
        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, false);

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
        setField(validatorSpy, "productionMaintenanceGanttChartItemResolver", new ProductionMaintenanceGanttChartItemResolver());

        moveService = new ProductionMaintenanceGanttMoveService();

        setField(moveService, "dataDefinitionService", dataDefinitionService);
        setField(moveService, "productionMaintenanceGanttMoveValidator", validatorSpy);
        setField(moveService, "productionMaintenanceGanttRecomputeService", recomputeService);
    }

    @After
    public void restoreSearchRestrictions() throws Exception {
        swapSearchRestrictionsDataAccessService(previousDataAccessService);
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
        verifyZeroInteractions(psExecutor, ppsExecutor);
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
        assertEquals(2, productionLineQueries.size());
        assertEquals(Collections.singletonList(SearchRestrictions.eq(ProductionLineFields.NUMBER, TARGET_LINE_NUMBER)),
                productionLineQueries.get(0).getCriteria());
        assertEquals(Integer.valueOf(1), productionLineQueries.get(0).getMaxResults());
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
        assertEquals(1, plannedEventQueries.size());
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
        // when
        try {
            moveService.move(null);
            fail("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // then
            assertEquals("move request is required", e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, recomputeService, psExecutor, ppsExecutor);
        assertValidatorNeverCalled();
    }

    @Test
    public final void shouldRunInSerializableTransaction() throws NoSuchMethodException {
        // when
        Method move = ProductionMaintenanceGanttMoveService.class.getMethod("move", GanttChartMoveRequest.class);
        Transactional transactional = move.getAnnotation(Transactional.class);

        // then
        assertNotNull(transactional);
        assertEquals(Isolation.SERIALIZABLE, transactional.isolation());
        assertFalse(transactional.readOnly());
    }

    @Test
    public final void shouldUseMessageKeysOfRejectionReasons() {
        // then
        assertEquals("qcadooView.validate.global.optimisticLock", ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertEquals(ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY,
                ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
        assertEquals("cmmsMachineParts.productionMaintenanceGantt.move.error.saveFailed",
                ProductionMaintenanceGanttMoveService.SAVE_FAILED_KEY);
        assertEquals("cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed",
                ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY);
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
        // then
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new ConcurrencyFailureException("x")));
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new OptimisticLockingFailureException("x")));
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new CannotSerializeTransactionException("x")));
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(
                new OptimisticLockingFailureException("x"))));
    }

    @Test
    public final void shouldRecogniseSerializationFailureSqlStateInCauseChain() {
        // given
        SQLException serializationFailure = new SQLException("x", "40001");

        // then
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(serializationFailure));
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(new IllegalStateException(
                serializationFailure))));
    }

    @Test
    public final void shouldRecogniseSerializationFailureInNextExceptionChain() {
        // given
        SQLException batchFailure = new SQLException("batch", "08000");

        batchFailure.setNextException(new SQLException("y", "23505"));
        batchFailure.setNextException(new SQLException("z", "40001"));

        // then
        assertTrue(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(batchFailure)));
    }

    @Test
    public final void shouldNotRecogniseOtherExceptionsAsConflicts() {
        // given
        SQLException uniqueViolationWithNext = new SQLException("x", "23505");

        uniqueViolationWithNext.setNextException(new SQLException("y", "23503"));

        // then
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new SQLException("x", "23505")));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException(uniqueViolationWithNext)));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new SQLException("x")));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new RuntimeException("x")));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(new MoveRejectedException(
                ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY)));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(null));
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

        // then
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(first));
        assertFalse(ProductionMaintenanceGanttMoveService.isConcurrencyConflict(firstSqlException));
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
        verifyZeroInteractions(recomputeService, psExecutor, ppsExecutor);
    }

    private void assertNothingRecomputed() {
        verify(positionDD, never()).fastSave(Matchers.any(Entity.class));
        verifyZeroInteractions(recomputeService, psExecutor, ppsExecutor);
    }

    private void assertValidatorNeverCalled() {
        verify(validatorSpy, never()).validate(Matchers.any(Entity.class), Matchers.any(Entity.class),
                Matchers.any(GanttChartMoveRequest.class));
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

    private static Entity productionLine(final Long id, final String number) {
        Entity productionLine = mockEntity(id);

        stubStringField(productionLine, ProductionLineFields.NUMBER, number);
        stubBooleanField(productionLine, ProductionLineFields.PRODUCTION, true);
        stubBooleanField(productionLine, ProductionLineFields.ACTIVE, true);

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
        conditions.put(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true), new TrueFieldCondition(
                ProductionLineFields.ACTIVE));

        for (String number : Arrays.asList(ORIGIN_LINE_NUMBER, TARGET_LINE_NUMBER, OTHER_LINE_NUMBER, UNKNOWN_LINE_NUMBER)) {
            conditions.put(SearchRestrictions.eq(ProductionLineFields.NUMBER, number), new StringFieldCondition(
                    ProductionLineFields.NUMBER, number));
        }

        return conditions;
    }

    private Map<SearchCriterion, EntityCondition> plannedEventConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(PlannedEventFields.REQUIRES_SHUTDOWN, true), new TrueFieldCondition(
                PlannedEventFields.REQUIRES_SHUTDOWN));
        conditions.put(SearchRestrictions.lt(PlannedEventFields.START_DATE, slotTo), new DateFieldCondition(
                PlannedEventFields.START_DATE, slotTo, false));
        conditions.put(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, slotFrom), new DateFieldCondition(
                PlannedEventFields.FINISH_DATE, slotFrom, true));

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
     * {@code list()} with the selected entities and {@code uniqueResult()} with the first selected entity or null. The
     * selected entities are those satisfying every recorded criterion, sorted by the recorded orders and limited to the
     * recorded maximum result count. Fails on unknown criteria, unknown orders and every other builder method.
     */
    private static final class FixtureCriteriaBuilderAnswer implements Answer<Object> {

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

                if (selected.isEmpty()) {
                    return null;
                }

                return selected.get(0);
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

}

