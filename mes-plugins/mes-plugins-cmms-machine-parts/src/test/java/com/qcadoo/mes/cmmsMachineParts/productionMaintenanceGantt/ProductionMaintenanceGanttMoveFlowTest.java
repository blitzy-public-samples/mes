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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.listeners.ProductionMaintenanceGanttListeners;
import com.qcadoo.mes.orders.DefaultProductionLineScheduleServicePPSImpl;
import com.qcadoo.mes.orders.DefaultProductionLineScheduleServicePSImpl;
import com.qcadoo.mes.orders.ProductionLineScheduleService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePPS;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePPSExecutorService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePS;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePSExecutorService;
import com.qcadoo.mes.orders.constants.DurationOfOrderCalculatedOnBasis;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.listeners.ProductionLinePositionNewData;
import com.qcadoo.mes.orders.states.constants.OrderStateStringValues;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.mes.technologies.constants.TechnologyFields;
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
import com.qcadoo.plugin.api.PluginManager;
import com.qcadoo.plugin.api.PluginStateResolver;
import com.qcadoo.plugin.api.RunIfEnabled;
import com.qcadoo.plugin.internal.PluginUtilsService;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartComponentState;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of the Gantt move flow, from {@link ProductionMaintenanceGanttListeners#moveItem} through
 * {@link ProductionMaintenanceGanttMoveService}, {@link ProductionMaintenanceGanttMoveValidator} and
 * {@link ProductionMaintenanceGanttRecomputeService} into the {@code orders} scheduling services.
 * <p>
 * Real instances: the listener, the move service, the validator, {@link ProductionMaintenanceGanttChartItemResolver}, the
 * recompute service, {@link ProductionLineScheduleService}, {@link ProductionLineSchedulePositionValidators},
 * {@link ProductionLineScheduleServicePSExecutorService} and {@link ProductionLineScheduleServicePPSExecutorService}. The
 * executor services hold the implementation lists each test sets: {@link RecordingPsImplementation},
 * {@link DisabledSchedulingPsImplementation}, or a Mockito spy of {@link DefaultProductionLineScheduleServicePSImpl} or
 * {@link DefaultProductionLineScheduleServicePPSImpl}. {@link PluginUtilsService} is initialised with a mocked
 * {@link PluginStateResolver}, and {@link SearchRestrictions} converts entities through a mocked {@link DataAccessService}
 * that returns them unchanged.
 * <p>
 * Mocked: the data definitions, {@link ShiftsService} (the nearest working date of a start is the start itself),
 * {@link ParameterService}, {@link PluginManager} (every plugin disabled), {@link TranslationService} and the
 * {@link GanttChartComponentState} whose {@code getMoveRequest()} returns the move request. The position data definition
 * answers {@code find()} with a builder that records its criteria, orders and maximum result count, and selects the fixture
 * positions satisfying every recorded criterion in their current state, sorted by the recorded orders. The production line and
 * planned event data definitions answer the same way. Unknown criteria, unknown orders and unknown builder methods fail the
 * test. The order data definition answers every query with an entity without finish date.
 * <p>
 * Fixture, on 2026-10-01: draft schedule 7 starting at 06:00, calculated on the time consuming technology basis, with
 * production line change allowed. Production lines A (id 1) and B (id 2), both production and active. Row A: a1 06:00-08:00
 * (order ORD-A1), the moved position m 08:00-10:00 (order ORD-M), a2 10:30-12:00 (order ORD-A2). Row B: b1 06:00-09:00 (order
 * ORD-B1), b2 12:00-13:00 (order ORD-B2). No planned event exists. The move request drops m on row B from 10:00 to 12:00, as
 * rendered on row A as ORD-M from 08:00 to 10:00. The recording implementation starts each recomputed position 15 minutes
 * after the finish date it receives and gives it a duration of 60 minutes.
 */
public class ProductionMaintenanceGanttMoveFlowTest {

    private static final Long SCHEDULE_ID = 7L;

    private static final Long LINE_A_ID = 1L;

    private static final Long LINE_B_ID = 2L;

    private static final Long POSITION_A1_ID = 11L;

    private static final Long MOVED_POSITION_ID = 12L;

    private static final Long POSITION_A2_ID = 13L;

    private static final Long POSITION_B1_ID = 21L;

    private static final Long POSITION_B2_ID = 22L;

    private static final String LINE_A_NUMBER = "A";

    private static final String LINE_B_NUMBER = "B";

    private static final String SCHEDULE_START = "2026-10-01 06:00:00";

    private static final String A1_START = "2026-10-01 06:00:00";

    private static final String A1_END = "2026-10-01 08:00:00";

    private static final String MOVED_START = "2026-10-01 08:00:00";

    private static final String MOVED_END = "2026-10-01 10:00:00";

    private static final String A2_START = "2026-10-01 10:30:00";

    private static final String A2_END = "2026-10-01 12:00:00";

    private static final String B1_START = "2026-10-01 06:00:00";

    private static final String B1_END = "2026-10-01 09:00:00";

    private static final String B2_START = "2026-10-01 12:00:00";

    private static final String B2_END = "2026-10-01 13:00:00";

    private static final String SLOT_FROM = "2026-10-01 10:00:00";

    private static final String SLOT_TO = "2026-10-01 12:00:00";

    private static final String RECOMPUTED_A2_START = "2026-10-01 08:15:00";

    private static final String RECOMPUTED_A2_END = "2026-10-01 09:15:00";

    private static final String RECOMPUTED_B2_START = "2026-10-01 12:15:00";

    private static final String RECOMPUTED_B2_END = "2026-10-01 13:15:00";

    private static final String PRODUCTION_SCHEDULING_PLUGIN = "productionScheduling";

    private static final int CHANGEOVER_MINUTES = 15;

    private static final int ORDER_DURATION_MINUTES = 60;

    private static final String CREATE_METHOD = "createProductionLinePositionNewData";

    private static final String SAVE_METHOD = "savePosition";

    private static final String COPY_METHOD = "copyPS";

    private static final String L_ID = "id";

    private static final String ORDER_A1_NUMBER = "ORD-A1";

    private static final String ORDER_MOVED_NUMBER = "ORD-M";

    private static final String ORDER_A2_NUMBER = "ORD-A2";

    private static final String ORDER_B1_NUMBER = "ORD-B1";

    private static final String ORDER_B2_NUMBER = "ORD-B2";

    private static final String ORDERS_FOR_SUBPRODUCTS_GENERATION_PLUGIN = "ordersForSubproductsGeneration";

    private static final Long CHANGEOVER_ID = 31L;

    private ProductionMaintenanceGanttListeners listeners;

    private ProductionLineScheduleServicePSExecutorService psExecutorService;

    private ProductionLineScheduleServicePPSExecutorService ppsExecutorService;

    private RecordingPsImplementation recordingPsImplementation;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private DataDefinition positionDD, productionLineDD, plannedEventDD, orderDD;

    @Mock
    private ShiftsService shiftsService;

    @Mock
    private ParameterService parameterService;

    @Mock
    private PluginManager pluginManager;

    @Mock
    private PluginStateResolver pluginStateResolver;

    @Mock
    private TranslationService translationService;

    @Mock
    private DataAccessService dataAccessService;

    @Mock
    private GanttChartComponentState gantt;

    @Mock
    private ViewDefinitionState view;

    @Mock
    private GanttChartItem item;

    private DataAccessService previousDataAccessService;

    private PluginUtilsService previousPluginUtilsService;

    private Entity schedule, parameter, changeover, orderAggregate;

    private Entity lineA, lineB;

    private Entity orderA1, orderMoved, orderA2, orderB1, orderB2;

    private Entity technologyMoved, technologyA2, technologyB2;

    private Entity positionA1, movedPosition, positionA2, positionB1, positionB2;

    private final List<Entity> positions = new ArrayList<Entity>();

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    /**
     * Builds the fixture, stubs the data definitions and the mocked services, and wires the real services into the listener.
     * The executor services start with {@link RecordingPsImplementation} as the only PS implementation and
     * {@link DefaultProductionLineScheduleServicePPSImpl} as the only PPS implementation.
     */
    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        given(dataAccessService.convertToDatabaseEntity(any(Entity.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);
        previousPluginUtilsService = getPluginUtilsServiceInstance();

        new PluginUtilsService(pluginStateResolver).init();

        buildFixture();
        stubDataDefinitions();
        stubServices();
        wireServices();
    }

    /**
     * Restores the data access service of {@link SearchRestrictions} and the {@link PluginUtilsService} instance that were set
     * before the test.
     */
    @After
    public void restoreStatics() throws Exception {
        swapSearchRestrictionsDataAccessService(previousDataAccessService);
        setPluginUtilsServiceInstance(previousPluginUtilsService);
    }

    private void buildFixture() {
        schedule = mockEntity(SCHEDULE_ID);

        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.DRAFT);
        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS,
                DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY.getStringValue());
        stubDateField(schedule, ProductionLineScheduleFields.START_TIME, date(SCHEDULE_START));
        stubBooleanField(schedule, ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE, true);

        parameter = mockEntity(1L);

        stubBooleanField(parameter, ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS, false);

        changeover = mockEntity(CHANGEOVER_ID);

        orderAggregate = mockEntity();

        stubDateField(orderAggregate, OrderFields.FINISH_DATE, null);

        lineA = productionLine(LINE_A_ID, LINE_A_NUMBER);
        lineB = productionLine(LINE_B_ID, LINE_B_NUMBER);

        productionLines.add(lineA);
        productionLines.add(lineB);

        technologyMoved = technology(41L);
        technologyA2 = technology(42L);
        technologyB2 = technology(43L);

        orderA1 = order(51L, ORDER_A1_NUMBER, null);
        orderMoved = order(52L, ORDER_MOVED_NUMBER, technologyMoved);
        orderA2 = order(53L, ORDER_A2_NUMBER, technologyA2);
        orderB1 = order(54L, ORDER_B1_NUMBER, null);
        orderB2 = order(55L, ORDER_B2_NUMBER, technologyB2);

        positionA1 = position("positionA1", POSITION_A1_ID, lineA, orderA1, A1_START, A1_END);
        movedPosition = position("movedPosition", MOVED_POSITION_ID, lineA, orderMoved, MOVED_START, MOVED_END);
        positionA2 = position("positionA2", POSITION_A2_ID, lineA, orderA2, A2_START, A2_END);
        positionB1 = position("positionB1", POSITION_B1_ID, lineB, orderB1, B1_START, B1_END);
        positionB2 = position("positionB2", POSITION_B2_ID, lineB, orderB2, B2_START, B2_END);

        positions.add(positionA1);
        positions.add(movedPosition);
        positions.add(positionA2);
        positions.add(positionB1);
        positions.add(positionB2);
    }

    private void stubDataDefinitions() {
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION))
                .willReturn(positionDD);
        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER, ProductionLinesConstants.MODEL_PRODUCTION_LINE))
                .willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER, CmmsMachinePartsConstants.MODEL_PLANNED_EVENT))
                .willReturn(plannedEventDD);
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER)).willReturn(orderDD);

        FixtureFindAnswer positionFindAnswer = new FixtureFindAnswer(positions, positionConditions(), positionOrders());
        FixtureFindAnswer productionLineFindAnswer = new FixtureFindAnswer(productionLines, productionLineConditions(),
                new LinkedHashMap<SearchOrder, Comparator<Entity>>());
        FixtureFindAnswer plannedEventFindAnswer = new FixtureFindAnswer(plannedEvents, plannedEventConditions(),
                plannedEventOrders());

        given(positionDD.get(MOVED_POSITION_ID)).willReturn(movedPosition);
        given(positionDD.save(movedPosition)).willReturn(movedPosition);
        given(positionDD.find()).willAnswer(positionFindAnswer);
        given(productionLineDD.find()).willAnswer(productionLineFindAnswer);
        given(plannedEventDD.find()).willAnswer(plannedEventFindAnswer);
        given(orderDD.find()).willAnswer(new Answer<SearchCriteriaBuilder>() {

            @Override
            public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
                return mock(SearchCriteriaBuilder.class, new AggregateCriteriaBuilderAnswer(orderAggregate));
            }

        });
    }

    private void stubServices() {
        given(parameterService.getParameter()).willReturn(parameter);
        given(pluginManager.isPluginEnabled(anyString())).willReturn(false);
        given(shiftsService.getNearestWorkingDate(any(DateTime.class), any(Entity.class))).willAnswer(
                new Answer<Optional<DateTime>>() {

                    @Override
                    public Optional<DateTime> answer(final InvocationOnMock invocation) {
                        return Optional.of((DateTime) invocation.getArguments()[0]);
                    }

                });
        given(item.getEntityId()).willReturn(MOVED_POSITION_ID);
    }

    private void wireServices() {
        ProductionMaintenanceGanttChartItemResolver resolver = new ProductionMaintenanceGanttChartItemResolver();

        setField(resolver, "dataDefinitionService", dataDefinitionService);
        setField(resolver, "translationService", translationService);

        ProductionLineSchedulePositionValidators productionLineSchedulePositionValidators = new ProductionLineSchedulePositionValidators();

        ProductionMaintenanceGanttMoveValidator validator = new ProductionMaintenanceGanttMoveValidator();

        setField(validator, "dataDefinitionService", dataDefinitionService);
        setField(validator, "productionLineSchedulePositionValidators", productionLineSchedulePositionValidators);
        setField(validator, "parameterService", parameterService);
        setField(validator, "shiftsService", shiftsService);
        setField(validator, "productionMaintenanceGanttChartItemResolver", resolver);

        ProductionLineScheduleService productionLineScheduleService = new ProductionLineScheduleService();

        setField(productionLineScheduleService, "dataDefinitionService", dataDefinitionService);
        setField(productionLineScheduleService, "pluginManager", pluginManager);
        setField(productionLineScheduleService, "productionLineSchedulePositionValidators", productionLineSchedulePositionValidators);

        psExecutorService = new ProductionLineScheduleServicePSExecutorService();
        ppsExecutorService = new ProductionLineScheduleServicePPSExecutorService();
        recordingPsImplementation = new RecordingPsImplementation(changeover);

        usePsImplementations(recordingPsImplementation);
        usePpsImplementations(new DefaultProductionLineScheduleServicePPSImpl());

        ProductionMaintenanceGanttRecomputeService recomputeService = new ProductionMaintenanceGanttRecomputeService();

        setField(recomputeService, "dataDefinitionService", dataDefinitionService);
        setField(recomputeService, "productionLineScheduleService", productionLineScheduleService);
        setField(recomputeService, "productionLineScheduleServicePSExecutorService", psExecutorService);
        setField(recomputeService, "productionLineScheduleServicePPSExecutorService", ppsExecutorService);

        ProductionMaintenanceGanttMoveService moveService = new ProductionMaintenanceGanttMoveService();

        setField(moveService, "dataDefinitionService", dataDefinitionService);
        setField(moveService, "productionMaintenanceGanttMoveValidator", validator);
        setField(moveService, "productionMaintenanceGanttRecomputeService", recomputeService);

        listeners = new ProductionMaintenanceGanttListeners();

        setField(listeners, "productionMaintenanceGanttMoveService", moveService);

        GanttChartMoveRequest moveRequest = new GanttChartMoveRequest(item, LINE_B_NUMBER, LINE_A_NUMBER,
                resolver.positionLabel(movedPosition), MOVED_START, MOVED_END, date(SLOT_FROM), date(SLOT_TO), context(
                        ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, String.valueOf(SCHEDULE_ID)));

        given(gantt.getMoveRequest()).willReturn(moveRequest);
    }


    @Test
    public final void shouldUpdateChangeoversOnOriginAndDestinationRows() {
        // given
        usePsImplementations(recordingPsImplementation);

        // when
        listeners.moveItem(view, gantt, new String[0]);

        // then
        verify(gantt).acceptMove();
        verify(gantt, never()).rejectMove(anyString(), Matchers.<String> anyVararg());

        List<RecordedCall> calls = recordingPsImplementation.getCalls();

        assertEquals(4, calls.size());
        assertCreateCall(calls.get(0), lineA, date(A1_END), positionA2, technologyA2, orderA1);
        assertSaveCall(calls.get(1), positionA2, date(RECOMPUTED_A2_START), date(RECOMPUTED_A2_END));
        assertCreateCall(calls.get(2), lineB, date(SLOT_TO), positionB2, technologyB2, orderMoved);
        assertSaveCall(calls.get(3), positionB2, date(RECOMPUTED_B2_START), date(RECOMPUTED_B2_END));
        assertNotRecorded(calls, positionA1);
        assertNotRecorded(calls, positionB1);
        assertNotRecorded(calls, movedPosition);

        assertPositionTimes(positionA2, RECOMPUTED_A2_START, RECOMPUTED_A2_END);
        assertPositionTimes(positionB2, RECOMPUTED_B2_START, RECOMPUTED_B2_END);
        assertNotRecomputed(positionA1, A1_START, A1_END);
        assertNotRecomputed(positionB1, B1_START, B1_END);

        assertSame(lineB, movedPosition.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE));
        assertPositionTimes(movedPosition, SLOT_FROM, SLOT_TO);

        verify(positionDD).save(movedPosition);
        verify(positionDD, times(3)).find();
        verify(positionDD, never()).fastSave(any(Entity.class));
        verify(plannedEventDD).find();
        verify(orderDD, never()).find();
        verify(shiftsService).getNearestWorkingDate(new DateTime(date(SLOT_FROM)), lineB);
        verify(pluginManager, times(2)).isPluginEnabled(ORDERS_FOR_SUBPRODUCTS_GENERATION_PLUGIN);
    }

    @Test
    public final void shouldFailWithRecomputeFailedWhenOnlyDefaultPsImplementationRuns() {
        // given
        DefaultProductionLineScheduleServicePSImpl defaultPsImplementation = Mockito
                .spy(new DefaultProductionLineScheduleServicePSImpl());

        usePsImplementations(defaultPsImplementation);

        // when
        listeners.moveItem(view, gantt, new String[0]);

        // then
        verifyRecomputeFailedRejection();

        verify(defaultPsImplementation).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), eq(lineA), eq(date(A1_END)), eq(positionA2),
                eq(technologyA2), eq(orderA1));
        verify(defaultPsImplementation, times(1)).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class), any(Entity.class),
                any(Entity.class), any(Entity.class));
        verify(defaultPsImplementation, never()).savePosition(any(Entity.class), any(ProductionLinePositionNewData.class));
        verify(defaultPsImplementation, never()).copyPS(any(Entity.class), any(Entity.class), any(Entity.class));

        assertNotRecomputed(positionA2, A2_START, A2_END);
        assertNotRecomputed(positionB2, B2_START, B2_END);
    }

    @Test
    public final void shouldFailWithRecomputeFailedWhenOnlyDefaultPpsImplementationRuns() {
        // given
        DefaultProductionLineScheduleServicePPSImpl defaultPpsImplementation = Mockito
                .spy(new DefaultProductionLineScheduleServicePPSImpl());

        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS,
                DurationOfOrderCalculatedOnBasis.PLAN_FOR_SHIFT.getStringValue());
        usePpsImplementations(defaultPpsImplementation);

        // when
        listeners.moveItem(view, gantt, new String[0]);

        // then
        verifyRecomputeFailedRejection();

        verify(defaultPpsImplementation).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), eq(lineA), eq(date(A1_END)), eq(positionA2),
                eq(technologyA2), eq(orderA1));
        verify(defaultPpsImplementation, times(1)).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class), any(Entity.class),
                any(Entity.class), any(Entity.class));
        verify(defaultPpsImplementation, never()).savePosition(any(Entity.class), any(ProductionLinePositionNewData.class));
        verify(defaultPpsImplementation, never()).copyPPS(any(Entity.class), any(Entity.class), any(Entity.class));
        assertTrue(recordingPsImplementation.getCalls().isEmpty());

        assertNotRecomputed(positionA2, A2_START, A2_END);
        assertNotRecomputed(positionB2, B2_START, B2_END);
    }

    @Test
    public final void shouldFailWithRecomputeFailedWhenSchedulingPluginDisabled() {
        // given
        DisabledSchedulingPsImplementation schedulingPsImplementation = new DisabledSchedulingPsImplementation(changeover);

        usePsImplementations(schedulingPsImplementation);
        given(pluginStateResolver.isEnabled(PRODUCTION_SCHEDULING_PLUGIN)).willReturn(false);

        // when
        listeners.moveItem(view, gantt, new String[0]);

        // then
        verifyRecomputeFailedRejection();

        assertTrue(schedulingPsImplementation.getCalls().isEmpty());
        verify(pluginStateResolver, times(1)).isEnabled(PRODUCTION_SCHEDULING_PLUGIN);

        assertNotRecomputed(positionA2, A2_START, A2_END);
        assertNotRecomputed(positionB2, B2_START, B2_END);
    }

    @Test
    public final void shouldRecomputeBothRowsWhenSchedulingPluginEnabled() {
        // given
        DisabledSchedulingPsImplementation schedulingPsImplementation = new DisabledSchedulingPsImplementation(changeover);

        usePsImplementations(schedulingPsImplementation);
        given(pluginStateResolver.isEnabled(PRODUCTION_SCHEDULING_PLUGIN)).willReturn(true);

        // when
        listeners.moveItem(view, gantt, new String[0]);

        // then
        verify(gantt).acceptMove();
        verify(gantt, never()).rejectMove(anyString(), Matchers.<String> anyVararg());
        verify(pluginStateResolver, times(4)).isEnabled(PRODUCTION_SCHEDULING_PLUGIN);

        List<RecordedCall> calls = schedulingPsImplementation.getCalls();

        assertEquals(4, calls.size());
        assertCreateCall(calls.get(0), lineA, date(A1_END), positionA2, technologyA2, orderA1);
        assertSaveCall(calls.get(1), positionA2, date(RECOMPUTED_A2_START), date(RECOMPUTED_A2_END));
        assertCreateCall(calls.get(2), lineB, date(SLOT_TO), positionB2, technologyB2, orderMoved);
        assertSaveCall(calls.get(3), positionB2, date(RECOMPUTED_B2_START), date(RECOMPUTED_B2_END));

        assertPositionTimes(positionA2, RECOMPUTED_A2_START, RECOMPUTED_A2_END);
        assertPositionTimes(positionB2, RECOMPUTED_B2_START, RECOMPUTED_B2_END);
        assertNotRecomputed(positionA1, A1_START, A1_END);
        assertNotRecomputed(positionB1, B1_START, B1_END);
    }


    /**
     * Verifies that the listener rejected the move with {@code RECOMPUTE_FAILED_KEY}, never accepted it, and that no position
     * was fast-saved and no order was queried.
     */
    private void verifyRecomputeFailedRejection() {
        verify(gantt).rejectMove(eq(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY), Matchers.<String> anyVararg());
        verify(gantt, never()).acceptMove();
        verify(positionDD, never()).fastSave(any(Entity.class));
        verify(orderDD, never()).find();
    }

    private static void assertCreateCall(final RecordedCall call, final Entity productionLine, final Date finishDate,
            final Entity position, final Entity technology, final Entity previousOrder) {
        assertEquals(CREATE_METHOD, call.getMethodName());
        assertSame(productionLine, call.getProductionLine());
        assertEquals(finishDate, call.getDate());
        assertSame(position, call.getPosition());
        assertSame(technology, call.getTechnology());
        assertSame(previousOrder, call.getOrder());
    }

    /**
     * Asserts a {@code savePosition} call for the position with new data from the start to the finish date carrying the fixture
     * changeover, made while the position already held that start and finish date.
     */
    private void assertSaveCall(final RecordedCall call, final Entity position, final Date startDate, final Date finishDate) {
        assertEquals(SAVE_METHOD, call.getMethodName());
        assertSame(position, call.getPosition());
        assertNotNull(call.getNewData());
        assertEquals(startDate, call.getNewData().getStartDate());
        assertEquals(finishDate, call.getNewData().getFinishDate());
        assertSame(changeover, call.getNewData().getChangeover());
        assertEquals(startDate, call.getDate());
        assertEquals(finishDate, call.getEndDate());
    }

    private static void assertNotRecorded(final List<RecordedCall> calls, final Entity position) {
        for (RecordedCall call : calls) {
            assertFalse("Unexpected " + call.getMethodName() + " call for " + position, call.getPosition() == position);
        }
    }

    private static void assertPositionTimes(final Entity position, final String startTime, final String endTime) {
        assertEquals(date(startTime), position.getDateField(ProductionLineSchedulePositionFields.START_TIME));
        assertEquals(date(endTime), position.getDateField(ProductionLineSchedulePositionFields.END_TIME));
    }

    /**
     * Asserts that the position still holds the given start and end time and that no field of it was ever set.
     */
    private static void assertNotRecomputed(final Entity position, final String startTime, final String endTime) {
        assertPositionTimes(position, startTime, endTime);
        verify(position, never()).setField(anyString(), any());
    }

    private void usePsImplementations(final ProductionLineScheduleServicePS... implementations) {
        setField(psExecutorService, "productionLineScheduleServicesPS",
                new ArrayList<ProductionLineScheduleServicePS>(Arrays.asList(implementations)));
    }

    private void usePpsImplementations(final ProductionLineScheduleServicePPS... implementations) {
        setField(ppsExecutorService, "productionLineScheduleServicesPPS",
                new ArrayList<ProductionLineScheduleServicePPS>(Arrays.asList(implementations)));
    }

    private static Entity productionLine(final Long id, final String number) {
        Entity productionLine = mockEntity(id);

        stubStringField(productionLine, ProductionLineFields.NUMBER, number);
        stubBooleanField(productionLine, ProductionLineFields.PRODUCTION, true);
        stubBooleanField(productionLine, ProductionLineFields.ACTIVE, true);

        return productionLine;
    }

    /**
     * Returns a technology without production lines of its own.
     */
    private static Entity technology(final Long id) {
        Entity technology = mockEntity(id);

        stubHasManyField(technology, TechnologyFields.PRODUCTION_LINES, Collections.<Entity> emptyList());

        return technology;
    }

    /**
     * Returns a pending order with the given number and technology and without a production line.
     */
    private static Entity order(final Long id, final String number, final Entity technology) {
        Entity order = mockEntity(id);

        stubStringField(order, OrderFields.NUMBER, number);
        stubStringField(order, OrderFields.STATE, OrderStateStringValues.PENDING);
        stubBelongsToField(order, OrderFields.PRODUCTION_LINE, null);
        stubBelongsToField(order, OrderFields.TECHNOLOGY, technology);

        return order;
    }

    /**
     * Returns a position of the fixture schedule whose getters read the fields its {@code setField} calls store.
     */
    private Entity position(final String name, final Long id, final Entity productionLine, final Entity order,
            final String startTime, final String endTime) {
        PositionFieldsAnswer answer = new PositionFieldsAnswer(id, positionDD);

        answer.putField(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);
        answer.putField(ProductionLineSchedulePositionFields.PRODUCTION_LINE, productionLine);
        answer.putField(ProductionLineSchedulePositionFields.ORDER, order);
        answer.putField(ProductionLineSchedulePositionFields.START_TIME, date(startTime));
        answer.putField(ProductionLineSchedulePositionFields.END_TIME, date(endTime));

        return mock(Entity.class, withSettings().name(name).defaultAnswer(answer));
    }

    /**
     * Returns the criteria the position queries may use, each with the condition a position satisfies for it: the fixture
     * schedule, rows A and B, the id of every position, and "after" and "at or before" the vacated start and the drop start.
     */
    private Map<SearchCriterion, EntityCondition> positionConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB));

        for (Entity position : positions) {
            conditions.put(SearchRestrictions.idNe(position.getId()), new IdNotEqualCondition(position.getId()));
        }

        for (Date bound : Arrays.asList(date(MOVED_START), date(SLOT_FROM))) {
            conditions.put(SearchRestrictions.gt(ProductionLineSchedulePositionFields.START_TIME, bound), new DateFieldCondition(
                    ProductionLineSchedulePositionFields.START_TIME, bound, DateComparison.AFTER));
            conditions.put(SearchRestrictions.le(ProductionLineSchedulePositionFields.START_TIME, bound), new DateFieldCondition(
                    ProductionLineSchedulePositionFields.START_TIME, bound, DateComparison.AT_OR_BEFORE));
        }

        return conditions;
    }

    /**
     * Returns the orders the position queries may use: start time ascending, id ascending and start time descending.
     */
    private static Map<SearchOrder, Comparator<Entity>> positionOrders() {
        Map<SearchOrder, Comparator<Entity>> orders = new LinkedHashMap<SearchOrder, Comparator<Entity>>();

        orders.put(SearchOrders.asc(ProductionLineSchedulePositionFields.START_TIME), new DateFieldComparator(
                ProductionLineSchedulePositionFields.START_TIME, true));
        orders.put(SearchOrders.asc(L_ID), new IdComparator());
        orders.put(SearchOrders.desc(ProductionLineSchedulePositionFields.START_TIME), new DateFieldComparator(
                ProductionLineSchedulePositionFields.START_TIME, false));

        return orders;
    }

    /**
     * Returns the criteria the production line queries may use: the numbers of rows A and B, production and active.
     */
    private static Map<SearchCriterion, EntityCondition> productionLineConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(ProductionLineFields.NUMBER, LINE_A_NUMBER), new StringFieldCondition(
                ProductionLineFields.NUMBER, LINE_A_NUMBER));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.NUMBER, LINE_B_NUMBER), new StringFieldCondition(
                ProductionLineFields.NUMBER, LINE_B_NUMBER));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true), new TrueFieldCondition(
                ProductionLineFields.PRODUCTION));
        conditions.put(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true), new TrueFieldCondition(ProductionLineFields.ACTIVE));

        return conditions;
    }

    /**
     * Returns the criteria the planned event query may use: requires shutdown, start before the slot end and finish after the
     * slot start.
     */
    private static Map<SearchCriterion, EntityCondition> plannedEventConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.eq(PlannedEventFields.REQUIRES_SHUTDOWN, true), new TrueFieldCondition(
                PlannedEventFields.REQUIRES_SHUTDOWN));
        conditions.put(SearchRestrictions.lt(PlannedEventFields.START_DATE, date(SLOT_TO)), new DateFieldCondition(
                PlannedEventFields.START_DATE, date(SLOT_TO), DateComparison.BEFORE));
        conditions.put(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, date(SLOT_FROM)), new DateFieldCondition(
                PlannedEventFields.FINISH_DATE, date(SLOT_FROM), DateComparison.AFTER));

        return conditions;
    }

    /**
     * Returns the order the planned event query may use: start date ascending.
     */
    private static Map<SearchOrder, Comparator<Entity>> plannedEventOrders() {
        Map<SearchOrder, Comparator<Entity>> orders = new LinkedHashMap<SearchOrder, Comparator<Entity>>();

        orders.put(SearchOrders.asc(PlannedEventFields.START_DATE), new DateFieldComparator(PlannedEventFields.START_DATE, true));

        return orders;
    }

    private static Date date(final String value) {
        try {
            return new SimpleDateFormat(DateUtils.L_DATE_TIME_FORMAT).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unparseable fixture date " + value, e);
        }
    }

    private static Date plusMinutes(final Date date, final int minutes) {
        return new Date(date.getTime() + minutes * 60000L);
    }

    private static JSONObject context(final String key, final String value) {
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

    private static PluginUtilsService getPluginUtilsServiceInstance() throws NoSuchFieldException, IllegalAccessException {
        return (PluginUtilsService) getPluginUtilsServiceInstanceField().get(null);
    }

    private static void setPluginUtilsServiceInstance(final PluginUtilsService pluginUtilsService) throws NoSuchFieldException,
            IllegalAccessException {
        getPluginUtilsServiceInstanceField().set(null, pluginUtilsService);
    }

    private static Field getPluginUtilsServiceInstanceField() throws NoSuchFieldException {
        Field field = PluginUtilsService.class.getDeclaredField("instance");

        field.setAccessible(true);

        return field;
    }


    /**
     * PS implementation that records every call. {@code createProductionLinePositionNewData} puts new data under the id of the
     * production line, starting {@code CHANGEOVER_MINUTES} after the received finish date, lasting
     * {@code ORDER_DURATION_MINUTES} and carrying the given changeover. {@code savePosition} records the position's start and
     * end time at the moment of the call.
     */
    private static class RecordingPsImplementation implements ProductionLineScheduleServicePS {

        private final Entity changeover;

        private final List<RecordedCall> calls = new ArrayList<RecordedCall>();

        RecordingPsImplementation(final Entity changeover) {
            this.changeover = changeover;
        }

        @Override
        public void createProductionLinePositionNewData(
                final Map<Long, ProductionLinePositionNewData> orderProductionLinesPositionNewData, final Entity productionLine,
                final Date finishDate, final Entity position, final Entity technology, final Entity previousOrder) {
            calls.add(new RecordedCall(CREATE_METHOD, productionLine, copyOf(finishDate), null, position, technology,
                    previousOrder, null));

            Date startDate = plusMinutes(finishDate, CHANGEOVER_MINUTES);

            orderProductionLinesPositionNewData.put(productionLine.getId(), new ProductionLinePositionNewData(startDate,
                    plusMinutes(startDate, ORDER_DURATION_MINUTES), changeover));
        }

        @Override
        public void savePosition(final Entity position, final ProductionLinePositionNewData productionLinePositionNewData) {
            calls.add(new RecordedCall(SAVE_METHOD, null, position.getDateField(ProductionLineSchedulePositionFields.START_TIME),
                    position.getDateField(ProductionLineSchedulePositionFields.END_TIME), position, null, null,
                    productionLinePositionNewData));
        }

        @Override
        public void copyPS(final Entity productionLineSchedule, final Entity order, final Entity productionLine) {
            calls.add(new RecordedCall(COPY_METHOD, productionLine, null, null, null, null, order, null));
        }

        List<RecordedCall> getCalls() {
            return new ArrayList<RecordedCall>(calls);
        }

        private static Date copyOf(final Date date) {
            if (date == null) {
                return null;
            }

            return new Date(date.getTime());
        }

    }

    /**
     * {@link RecordingPsImplementation} that runs only while the {@code productionScheduling} plugin is enabled.
     */
    @RunIfEnabled(PRODUCTION_SCHEDULING_PLUGIN)
    private static final class DisabledSchedulingPsImplementation extends RecordingPsImplementation {

        DisabledSchedulingPsImplementation(final Entity changeover) {
            super(changeover);
        }

    }

    /**
     * One call a {@link RecordingPsImplementation} received. For {@code createProductionLinePositionNewData}: the production
     * line, the finish date, the position, the technology and the previous order. For {@code savePosition}: the position, its
     * start time as {@code date}, its end time as {@code endDate}, and the new data. For {@code copyPS}: the production line and
     * the order.
     */
    private static final class RecordedCall {

        private final String methodName;

        private final Entity productionLine;

        private final Date date;

        private final Date endDate;

        private final Entity position;

        private final Entity technology;

        private final Entity order;

        private final ProductionLinePositionNewData newData;

        private RecordedCall(final String methodName, final Entity productionLine, final Date date, final Date endDate,
                final Entity position, final Entity technology, final Entity order, final ProductionLinePositionNewData newData) {
            this.methodName = methodName;
            this.productionLine = productionLine;
            this.date = date;
            this.endDate = endDate;
            this.position = position;
            this.technology = technology;
            this.order = order;
            this.newData = newData;
        }

        String getMethodName() {
            return methodName;
        }

        Entity getProductionLine() {
            return productionLine;
        }

        Date getDate() {
            return date;
        }

        Date getEndDate() {
            return endDate;
        }

        Entity getPosition() {
            return position;
        }

        Entity getTechnology() {
            return technology;
        }

        Entity getOrder() {
            return order;
        }

        ProductionLinePositionNewData getNewData() {
            return newData;
        }

    }

    /**
     * Answers a position mock from a field map: {@code setField} stores a value, {@code getField}, {@code getDateField},
     * {@code getBelongsToField} and {@code getStringField} read it, date values are copied on the way in and out,
     * {@code getId} returns the id, {@code getDataDefinition} the position data definition and {@code isValid} true. Every other
     * method returns the Mockito default.
     */
    private static final class PositionFieldsAnswer implements Answer<Object> {

        private final Long id;

        private final DataDefinition dataDefinition;

        private final Map<String, Object> fields = new HashMap<String, Object>();

        private PositionFieldsAnswer(final Long id, final DataDefinition dataDefinition) {
            this.id = id;
            this.dataDefinition = dataDefinition;

            fields.put(L_ID, id);
        }

        private void putField(final String fieldName, final Object value) {
            fields.put(fieldName, copyOfValue(value));
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();

            if ("getId".equals(methodName)) {
                return id;
            }
            if ("getDataDefinition".equals(methodName)) {
                return dataDefinition;
            }
            if ("isValid".equals(methodName)) {
                return true;
            }
            if ("setField".equals(methodName)) {
                putField((String) arguments[0], arguments[1]);

                return null;
            }
            if ("getField".equals(methodName) || "getBelongsToField".equals(methodName) || "getStringField".equals(methodName)) {
                return fields.get(arguments[0]);
            }
            if ("getDateField".equals(methodName)) {
                return copyOfValue(fields.get(arguments[0]));
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        private static Object copyOfValue(final Object value) {
            if (value instanceof Date) {
                return new Date(((Date) value).getTime());
            }

            return value;
        }

    }

    /**
     * Answers {@code find()} with a new {@link FixtureCriteriaBuilderAnswer} builder over the current rows of the table.
     */
    private static final class FixtureFindAnswer implements Answer<SearchCriteriaBuilder> {

        private final List<Entity> table;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private FixtureFindAnswer(final List<Entity> table, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders) {
            this.table = table;
            this.conditions = conditions;
            this.orders = orders;
        }

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            return mock(SearchCriteriaBuilder.class, new FixtureCriteriaBuilderAnswer(new ArrayList<Entity>(table), conditions,
                    orders));
        }

    }

    /**
     * Records known criteria of {@code add}, known orders of {@code addOrder} and the limit of {@code setMaxResults}, and
     * answers them with the builder itself. Answers {@code list()} and {@code uniqueResult()} with the rows that satisfy every
     * recorded criterion in their current state, sorted by the recorded orders and cut to the limit. Fails on unknown criteria,
     * unknown orders, a {@code uniqueResult()} matching more than one row and every other builder method.
     */
    private static final class FixtureCriteriaBuilderAnswer implements Answer<Object> {

        private final List<Entity> rows;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<Object> query = new ArrayList<Object>();

        private Integer maxResults;

        private FixtureCriteriaBuilderAnswer(final List<Entity> rows, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders) {
            this.rows = rows;
            this.conditions = conditions;
            this.orders = orders;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();

            if ("add".equals(methodName)) {
                SearchCriterion criterion = (SearchCriterion) arguments[0];

                if (!conditions.containsKey(criterion)) {
                    throw new AssertionError("Unexpected criterion: " + criterion.getHibernateCriterion());
                }

                query.add(criterion);

                return invocation.getMock();
            }
            if ("addOrder".equals(methodName)) {
                SearchOrder order = (SearchOrder) arguments[0];

                if (!orders.containsKey(order)) {
                    throw new AssertionError("Unexpected order: " + order.getHibernateOrder());
                }

                query.add(order);

                return invocation.getMock();
            }
            if ("setMaxResults".equals(methodName)) {
                maxResults = (Integer) arguments[0];

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                return new FixedSearchResult(select());
            }
            if ("uniqueResult".equals(methodName)) {
                List<Entity> selected = select();

                if (selected.size() > 1) {
                    throw new AssertionError("uniqueResult matched " + selected.size() + " rows");
                }
                if (selected.isEmpty()) {
                    return null;
                }

                return selected.get(0);
            }
            if (SearchCriteriaBuilder.class.equals(invocation.getMethod().getReturnType())) {
                throw new AssertionError("Unexpected builder method: " + methodName);
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        private List<Entity> select() {
            List<Entity> selected = new ArrayList<Entity>();

            for (Entity row : rows) {
                if (matchesEveryCriterion(row)) {
                    selected.add(row);
                }
            }

            for (int index = query.size() - 1; index >= 0; index--) {
                Object element = query.get(index);

                if (element instanceof SearchOrder) {
                    Collections.sort(selected, orders.get(element));
                }
            }

            if (maxResults != null && selected.size() > maxResults) {
                return new ArrayList<Entity>(selected.subList(0, maxResults));
            }

            return selected;
        }

        private boolean matchesEveryCriterion(final Entity row) {
            for (Object element : query) {
                if (element instanceof SearchCriterion && !conditions.get(element).matches(row)) {
                    return false;
                }
            }

            return true;
        }

    }

    /**
     * Answers every builder method with the builder itself, {@code uniqueResult()} with the given entity and {@code list()}
     * with a result holding only that entity.
     */
    private static final class AggregateCriteriaBuilderAnswer implements Answer<Object> {

        private final Entity result;

        private AggregateCriteriaBuilderAnswer(final Entity result) {
            this.result = result;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();

            if ("uniqueResult".equals(methodName)) {
                return result;
            }
            if ("list".equals(methodName)) {
                return new FixedSearchResult(Collections.singletonList(result));
            }
            if (SearchCriteriaBuilder.class.equals(invocation.getMethod().getReturnType())) {
                return invocation.getMock();
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

    }

    /**
     * Search result holding a fixed list of entities.
     */
    private static final class FixedSearchResult implements SearchResult {

        private final List<Entity> entities;

        private FixedSearchResult(final List<Entity> entities) {
            this.entities = entities;
        }

        @Override
        public List<Entity> getEntities() {
            return entities;
        }

        @Override
        public int getTotalNumberOfEntities() {
            return entities.size();
        }

    }

    /**
     * Condition a table row satisfies for one database criterion.
     */
    private interface EntityCondition {

        boolean matches(Entity entity);

    }

    /**
     * Satisfied when the belongs-to field holds the given entity.
     */
    private static final class BelongsToCondition implements EntityCondition {

        private final String fieldName;

        private final Entity value;

        private BelongsToCondition(final String fieldName, final Entity value) {
            this.fieldName = fieldName;
            this.value = value;
        }

        @Override
        public boolean matches(final Entity entity) {
            return entity.getBelongsToField(fieldName) == value;
        }

    }

    /**
     * Satisfied when the entity's id differs from the given id.
     */
    private static final class IdNotEqualCondition implements EntityCondition {

        private final Long id;

        private IdNotEqualCondition(final Long id) {
            this.id = id;
        }

        @Override
        public boolean matches(final Entity entity) {
            return !id.equals(entity.getId());
        }

    }

    /**
     * Satisfied when the string field equals the given value.
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
     * Comparison of a date field with a bound: strictly before, strictly after, or at or before.
     */
    private enum DateComparison {

        BEFORE, AFTER, AT_OR_BEFORE

    }

    /**
     * Satisfied when the non-null date field compares with the bound as given.
     */
    private static final class DateFieldCondition implements EntityCondition {

        private final String fieldName;

        private final Date bound;

        private final DateComparison comparison;

        private DateFieldCondition(final String fieldName, final Date bound, final DateComparison comparison) {
            this.fieldName = fieldName;
            this.bound = new Date(bound.getTime());
            this.comparison = comparison;
        }

        @Override
        public boolean matches(final Entity entity) {
            Date value = entity.getDateField(fieldName);

            if (value == null) {
                return false;
            }
            if (DateComparison.BEFORE == comparison) {
                return value.before(bound);
            }
            if (DateComparison.AFTER == comparison) {
                return value.after(bound);
            }

            return !value.after(bound);
        }

    }

    /**
     * Orders entities by a non-null date field, ascending or descending.
     */
    private static final class DateFieldComparator implements Comparator<Entity> {

        private final String fieldName;

        private final boolean ascending;

        private DateFieldComparator(final String fieldName, final boolean ascending) {
            this.fieldName = fieldName;
            this.ascending = ascending;
        }

        @Override
        public int compare(final Entity first, final Entity second) {
            int result = first.getDateField(fieldName).compareTo(second.getDateField(fieldName));

            if (ascending) {
                return result;
            }

            return -result;
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

}

