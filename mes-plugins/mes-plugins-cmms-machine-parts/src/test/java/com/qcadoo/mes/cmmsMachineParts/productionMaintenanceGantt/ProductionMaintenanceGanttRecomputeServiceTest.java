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
import static com.qcadoo.testing.model.EntityTestUtils.stubDateField;
import static com.qcadoo.testing.model.EntityTestUtils.stubStringField;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.qcadoo.mes.orders.ProductionLineScheduleService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePPSExecutorService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePSExecutorService;
import com.qcadoo.mes.orders.constants.DurationOfOrderCalculatedOnBasis;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.listeners.ProductionLinePositionNewData;
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

/**
 * Tests of {@link ProductionMaintenanceGanttRecomputeService}.
 * <p>
 * The production line schedule positions of each test live in an in-memory position table. Every {@code find()} on the
 * position data definition answers a new criteria builder that accepts only the criteria and orders the recompute queries
 * are expected to use, and answers {@code list()} and {@code uniqueResult()} with the table rows that satisfy them. The
 * {@link ProductionLineScheduleService} answers read the chain caches the way the real service does and record a copy of
 * each cache they received. Both executor services answer {@code createProductionLinePositionNewData} with a start
 * {@value #CHANGEOVER_MINUTES} minutes after the finish date they received and an end {@value #DURATION_MINUTES} minutes
 * after that start, and record every call.
 * <p>
 * Row A is production line {@value #LINE_A_ID_VALUE} and row B is production line {@value #LINE_B_ID_VALUE}. All times are
 * on {@value #DAY}; the schedule starts at {@value #SCHEDULE_START}.
 */
public class ProductionMaintenanceGanttRecomputeServiceTest {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private static final String DAY = "2026-03-02";

    private static final String SCHEDULE_START = "05:00";

    private static final int CHANGEOVER_MINUTES = 15;

    private static final int DURATION_MINUTES = 60;

    private static final long LINE_A_ID_VALUE = 1L;

    private static final long LINE_B_ID_VALUE = 2L;

    private static final Long SCHEDULE_ID = 7L;

    private static final Long MOVED_ID = 10L;

    private static final String L_ID = "id";

    private static final String PS = "PS";

    private static final String PPS = "PPS";

    private static final String FIND_EVENT = "find";

    private static final String CREATE_EVENT = "create";

    private static final String SAVE_EVENT = "save";

    private ProductionMaintenanceGanttRecomputeService recomputeService;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private ProductionLineScheduleService productionLineScheduleService;

    @Mock
    private ProductionLineScheduleServicePSExecutorService psExecutor;

    @Mock
    private ProductionLineScheduleServicePPSExecutorService ppsExecutor;

    @Mock
    private DataAccessService dataAccessService;

    @Mock
    private DataDefinition positionDD;

    @Mock
    private Entity changeover;

    private DataAccessService previousDataAccessService;

    private Entity schedule, lineA, lineB;

    private boolean executorProducesData;

    private final List<Entity> positionTable = new ArrayList<Entity>();

    private final List<Date> queryBounds = new ArrayList<Date>();

    private final List<List<Object>> positionQueries = new ArrayList<List<Object>>();

    private final List<Step> steps = new ArrayList<Step>();

    private final List<String> events = new ArrayList<String>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        recomputeService = new ProductionMaintenanceGanttRecomputeService();

        setField(recomputeService, "dataDefinitionService", dataDefinitionService);
        setField(recomputeService, "productionLineScheduleService", productionLineScheduleService);
        setField(recomputeService, "productionLineScheduleServicePSExecutorService", psExecutor);
        setField(recomputeService, "productionLineScheduleServicePPSExecutorService", ppsExecutor);

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(
                AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION)).willReturn(positionDD);
        given(positionDD.find()).willAnswer(new PositionFindAnswer());

        given(productionLineScheduleService.getFinishDate(Matchers.<Map<Long, Date>> any(), any(Date.class),
                any(Entity.class), any(Entity.class))).willAnswer(new FinishDateAnswer());
        given(productionLineScheduleService.getFinishDateWithChildren(any(Entity.class), any(Date.class))).willAnswer(
                new FinishDateWithChildrenAnswer());
        given(productionLineScheduleService.getPreviousOrder(Matchers.<Map<Long, Entity>> any(), any(Entity.class),
                any(Date.class))).willAnswer(new PreviousOrderAnswer());

        willAnswer(new CreateDataAnswer(PS)).given(psExecutor).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class),
                any(Entity.class), any(Entity.class), any(Entity.class));
        willAnswer(new SaveAnswer(PS)).given(psExecutor).savePosition(any(Entity.class),
                any(ProductionLinePositionNewData.class));
        willAnswer(new CreateDataAnswer(PPS)).given(ppsExecutor).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class),
                any(Entity.class), any(Entity.class), any(Entity.class));
        willAnswer(new SaveAnswer(PPS)).given(ppsExecutor).savePosition(any(Entity.class),
                any(ProductionLinePositionNewData.class));

        lineA = mockEntity(LINE_A_ID_VALUE);
        lineB = mockEntity(LINE_B_ID_VALUE);

        schedule = mockEntity(SCHEDULE_ID);
        stubDateField(schedule, ProductionLineScheduleFields.START_TIME, at(SCHEDULE_START));
        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS,
                DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY.getStringValue());

        executorProducesData = true;
    }

    @After
    public void restoreSearchRestrictions() throws Exception {
        swapSearchRestrictionsDataAccessService(previousDataAccessService);
    }

    @Test
    public final void shouldRecomputeOnlyPositionsAfterDropOnDestinationRow() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b1 08:00, b2 12:00 and b3 14:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity b1 = position(21L, lineB, order(52L), "08:00", "09:00");
        Entity b2Order = order(53L);
        Entity b2 = position(22L, lineB, b2Order, "12:00", "13:00");
        Entity b3 = position(23L, lineB, order(54L), "14:00", "15:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: b2 then b3 are saved; b2 is chained from m, b3 from b2
        InOrder inOrder = inOrder(b2, b3, psExecutor);
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");
        verifyRecomputedAndSaved(inOrder, b3, "13:30", "14:30");

        assertChainedFrom(b2, lineB, "12:00", movedOrder);
        assertChainedFrom(b3, lineB, "13:15", b2Order);

        assertUntouched(b1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldRecomputeOnlyPositionsAfterVacatedStartOnOriginRow() {
        // given: m moves from A 09:00 to B 10:00-11:00; A holds a1 06:00-08:00, a2 11:00 and a3 13:00
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "08:00");
        Entity moved = position(MOVED_ID, lineB, order(51L), "10:00", "11:00");
        Entity a2Order = order(53L);
        Entity a2 = position(12L, lineA, a2Order, "11:00", "12:00");
        Entity a3 = position(13L, lineA, order(54L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "10:00");

        // then: a2 then a3 are saved; a2 is chained from predecessor a1, a3 from a2
        InOrder inOrder = inOrder(a2, a3, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "08:15", "09:15");
        verifyRecomputedAndSaved(inOrder, a3, "09:30", "10:30");

        assertChainedFrom(a2, lineA, "08:00", a1Order);
        assertChainedFrom(a3, lineA, "09:15", a2Order);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldLeavePositionsAtOrBeforeAffectedStartUntouched() {
        // given: m moves from A 09:00 to B 11:00-12:00; A holds aBefore 07:00, aTie 09:00-10:00 and a2 12:00; B holds
        // bBefore 10:00, bTie 11:00 and b2 13:00
        Entity aBefore = position(11L, lineA, order(52L), "07:00", "08:00");
        Entity aTieOrder = order(53L);
        Entity aTie = position(12L, lineA, aTieOrder, "09:00", "10:00");
        Entity a2 = position(13L, lineA, order(54L), "12:00", "13:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity bBefore = position(21L, lineB, order(55L), "10:00", "10:30");
        Entity bTie = position(22L, lineB, order(56L), "11:00", "12:00");
        Entity b2 = position(23L, lineB, order(57L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: only a2 and b2 are recomputed; a2 is chained from aTie, b2 from m
        InOrder inOrder = inOrder(a2, b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "10:15", "11:15");
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");

        assertChainedFrom(a2, lineA, "10:00", aTieOrder);
        assertChainedFrom(b2, lineB, "12:00", movedOrder);

        assertUntouched(aBefore);
        assertUntouched(aTie);
        assertUntouched(bBefore);
        assertUntouched(bTie);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldChainLaterSameRowMovePastAnotherPosition() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds a1 06:00-07:00, p 10:00 and q 13:00
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "11:00", "12:00");
        Entity p = position(12L, lineA, order(53L), "10:00", "11:00");
        Entity q = position(13L, lineA, order(54L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "08:00", lineA, "11:00");

        // then: p then q are saved; p is chained from a1, q from m's new end and order
        InOrder inOrder = inOrder(p, q, psExecutor);
        verifyRecomputedAndSaved(inOrder, p, "07:15", "08:15");
        verifyRecomputedAndSaved(inOrder, q, "12:15", "13:15");

        assertChainedFrom(p, lineA, "07:00", a1Order);
        assertChainedFrom(q, lineA, "12:00", movedOrder);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldPlaceTieAtDropBeforeMovedPositionOnLaterSameRowMove() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds a1 06:00-07:00, t 11:00 and q 13:00
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "11:00", "12:00");
        Entity t = position(12L, lineA, order(53L), "11:00", "12:00");
        Entity q = position(13L, lineA, order(54L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "08:00", lineA, "11:00");

        // then: t is saved before q; t is chained from a1, q from m
        InOrder inOrder = inOrder(t, q, psExecutor);
        verifyRecomputedAndSaved(inOrder, t, "07:15", "08:15");
        verifyRecomputedAndSaved(inOrder, q, "12:15", "13:15");

        assertChainedFrom(t, lineA, "07:00", a1Order);
        assertChainedFrom(q, lineA, "12:00", movedOrder);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldNotRecomputeTieAtDestinationDropStart() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds t 11:00 and b2 13:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity t = position(21L, lineB, order(52L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: only b2 is recomputed, chained from m
        InOrder inOrder = inOrder(b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");

        assertChainedFrom(b2, lineB, "12:00", movedOrder);

        assertUntouched(t);
        assertUntouched(moved);
        assertEquals(1, steps.size());
    }

    @Test
    public final void shouldRecomputeEarlierSameRowMoveAsOneChain() {
        // given: m moves on A from 12:00-13:00 to 09:00-10:00; A holds a1 06:00, p 08:00 and q 10:00
        Entity a1 = position(11L, lineA, order(52L), "06:00", "07:00");
        Entity p = position(12L, lineA, order(53L), "08:00", "09:00");
        Entity q = position(13L, lineA, order(54L), "10:00", "11:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "09:00", "10:00");

        // when
        recompute(moved, lineA, "12:00", lineA, "09:00");

        // then: only q is recomputed, once, chained from m; every query reads row A
        InOrder inOrder = inOrder(q, psExecutor);
        verifyRecomputedAndSaved(inOrder, q, "10:15", "11:15");

        assertChainedFrom(q, lineA, "10:00", movedOrder);

        verify(psExecutor, times(1)).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class),
                any(Entity.class), any(Entity.class), any(Entity.class));
        verify(psExecutor, times(1)).savePosition(any(Entity.class), any(ProductionLinePositionNewData.class));
        assertEquals(1, steps.size());

        assertFalse(positionQueries.isEmpty());
        for (List<Object> query : positionQueries) {
            assertTrue(query.contains(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA)));
        }

        assertUntouched(a1);
        assertUntouched(p);
        assertUntouched(moved);
    }

    @Test
    public final void shouldExcludeMovedPositionById() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds twin 13:00 with m's order under another id
        Entity sharedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, sharedOrder, "11:00", "12:00");
        Entity twin = position(12L, lineA, sharedOrder, "13:00", "14:00");

        // when
        recompute(moved, lineA, "08:00", lineA, "11:00");

        // then: every query excludes m by id; twin is recomputed from m, m is not
        assertFalse(positionQueries.isEmpty());
        for (List<Object> query : positionQueries) {
            assertTrue(query.contains(SearchRestrictions.idNe(MOVED_ID)));
        }

        InOrder inOrder = inOrder(twin, psExecutor);
        verifyRecomputedAndSaved(inOrder, twin, "12:15", "13:15");

        assertChainedFrom(twin, lineA, "12:00", sharedOrder);

        assertUntouched(moved);
        assertEquals(1, steps.size());
    }


    @Test
    public final void shouldDispatchTimeConsumingTechnologyScheduleToPsExecutor() {
        // given: time consuming technology schedule; m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity technology = mockEntity(81L);
        Entity b2 = position(22L, lineB, order(53L, technology), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: the PS executor creates and saves b2's data; the PPS executor is not used
        InOrder inOrder = inOrder(psExecutor);
        inOrder.verify(psExecutor).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), same(lineB), eq(at("12:00")), same(b2),
                same(technology), same(movedOrder));
        inOrder.verify(psExecutor).savePosition(same(b2), newData("12:15", "13:15"));

        assertEquals(PS, step(b2).executor);
        verifyZeroInteractions(ppsExecutor);
    }

    @Test
    public final void shouldDispatchPlanForShiftScheduleToPpsExecutor() {
        // given: plan for shift schedule; m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00
        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS,
                DurationOfOrderCalculatedOnBasis.PLAN_FOR_SHIFT.getStringValue());

        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity technology = mockEntity(81L);
        Entity b2 = position(22L, lineB, order(53L, technology), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: the PPS executor creates and saves b2's data; the PS executor is not used
        InOrder inOrder = inOrder(b2, ppsExecutor);
        inOrder.verify(ppsExecutor).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), same(lineB), eq(at("12:00")), same(b2),
                same(technology), same(movedOrder));
        inOrder.verify(b2).setField(ProductionLineSchedulePositionFields.START_TIME, at("12:15"));
        inOrder.verify(b2).setField(ProductionLineSchedulePositionFields.END_TIME, at("13:15"));
        inOrder.verify(ppsExecutor).savePosition(same(b2), newData("12:15", "13:15"));

        assertEquals(PPS, step(b2).executor);
        verifyZeroInteractions(psExecutor);
    }

    @Test
    public final void shouldRejectWithRecomputeFailedWhenExecutorProducesNoData() {
        // given: the executor puts no data for the line; m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00
        executorProducesData = false;

        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L), "13:00", "14:00");

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
            assertEquals(0, e.getArgs().length);
        }

        assertEquals(PS, step(b2).executor);
        verify(b2, never()).setField(anyString(), any());
        verify(psExecutor, never()).savePosition(any(Entity.class), any(ProductionLinePositionNewData.class));
        verifyZeroInteractions(ppsExecutor);
    }

    @Test
    public final void shouldStartOriginChainWithEmptyCachesWithoutPredecessor() {
        // given: m moves from A 09:00 to B 10:00-11:00; A holds only a2 11:00 after the vacated start
        Entity moved = position(MOVED_ID, lineB, order(51L), "10:00", "11:00");
        Entity a2 = position(12L, lineA, order(53L), "11:00", "12:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "10:00");

        // then: a2 received empty caches, the schedule start and no previous order
        Step a2Step = step(a2);
        assertTrue(a2Step.finishCacheSeen.isEmpty());
        assertTrue(a2Step.orderCacheSeen.isEmpty());
        assertEquals(at(SCHEDULE_START), a2Step.finishDate);
        assertNull(a2Step.previousOrder);

        InOrder inOrder = inOrder(a2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "05:15", "06:15");

        assertUntouched(moved);
        assertEquals(1, steps.size());
    }

    @Test
    public final void shouldRecomputeOriginRowBeforeDestinationRow() {
        // given: m moves from A 09:00 to B 12:00-13:00; A holds a2 11:00 and a3 13:00; B holds b2 13:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "12:00", "13:00");
        Entity a2Order = order(52L);
        Entity a2 = position(12L, lineA, a2Order, "11:00", "12:00");
        Entity a3 = position(13L, lineA, order(53L), "13:00", "14:00");
        Entity b2 = position(22L, lineB, order(54L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "12:00");

        // then: a2, a3, then b2 are saved; every read precedes the first executor call; b2 sees only row B caches
        InOrder inOrder = inOrder(a2, a3, b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "05:15", "06:15");
        verifyRecomputedAndSaved(inOrder, a3, "06:30", "07:30");
        verifyRecomputedAndSaved(inOrder, b2, "13:15", "14:15");

        assertChainedFrom(a3, lineA, "06:15", a2Order);
        assertChainedFrom(b2, lineB, "13:00", movedOrder);

        assertEquals(3, positionQueries.size());
        assertTrue(events.lastIndexOf(FIND_EVENT) < events.indexOf(CREATE_EVENT));
        assertEquals(3, steps.size());
    }

    @Test
    public final void shouldRecomputeEqualStartsInIdOrder() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b24 and then b23, both at 13:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity b24 = position(24L, lineB, order(54L), "13:00", "14:00");
        Entity b23Order = order(53L);
        Entity b23 = position(23L, lineB, b23Order, "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: b23 is saved before b24
        InOrder inOrder = inOrder(b23, b24, psExecutor);
        verifyRecomputedAndSaved(inOrder, b23, "12:15", "13:15");
        verifyRecomputedAndSaved(inOrder, b24, "13:30", "14:30");

        assertChainedFrom(b23, lineB, "12:00", movedOrder);
        assertChainedFrom(b24, lineB, "13:15", b23Order);
    }

    @Test
    public final void shouldRecomputeOnlyDestinationRowWithoutOriginLine() {
        // given: m had no line and is dropped on B 11:00-12:00; A holds a2 11:00; B holds b2 13:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity a2 = position(12L, lineA, order(52L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L), "13:00", "14:00");

        // when
        recompute(moved, null, null, lineB, "11:00");

        // then: only row B is read and recomputed
        InOrder inOrder = inOrder(b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");

        assertChainedFrom(b2, lineB, "12:00", movedOrder);

        assertEquals(1, positionQueries.size());
        assertTrue(positionQueries.get(0).contains(
                SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB)));

        assertUntouched(a2);
        assertUntouched(moved);
        assertEquals(1, steps.size());
    }

    @Test
    public final void shouldRejectWithRecomputeFailedForUnknownCalculationBasis() {
        // given: the schedule names no known calculation basis; m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00
        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS, "03unknown");

        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L), "13:00", "14:00");

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        verify(b2, never()).setField(anyString(), any());
        verifyZeroInteractions(psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRejectWithRecomputeFailedForCandidateWithoutOrder() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00 without an order
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, null, "13:00", "14:00");

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        verify(b2, never()).setField(anyString(), any());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRequireSavedMovedPosition() {
        // given: the moved position has no id
        Entity unsavedPosition = mockEntity();

        given(unsavedPosition.getId()).willReturn(null);

        // when
        try {
            recomputeService.recompute(schedule, unsavedPosition, lineA, at("09:00"), lineB, at("11:00"));

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }


    /**
     * Registers the vacated start and the slot start as accepted query bounds and runs the recompute for the fixture
     * schedule.
     */
    private void recompute(final Entity movedPosition, final Entity originLine, final String vacatedStart,
            final Entity destinationLine, final String slotStart) {
        Date vacated = null;

        if (vacatedStart != null) {
            vacated = at(vacatedStart);
            queryBounds.add(vacated);
        }

        Date slot = at(slotStart);

        queryBounds.add(slot);

        recomputeService.recompute(schedule, movedPosition, originLine, vacated, destinationLine, slot);
    }

    /**
     * Verifies, in order, that the position's start and end were set to the given times and that the PS executor saved it
     * with new data holding those times and the fixture changeover.
     */
    private void verifyRecomputedAndSaved(final InOrder inOrder, final Entity position, final String startTime,
            final String endTime) {
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.START_TIME, at(startTime));
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.END_TIME, at(endTime));
        inOrder.verify(psExecutor).savePosition(same(position), newData(startTime, endTime));
    }

    /**
     * Asserts that the position was recomputed once on the given line with chain caches holding exactly the given finish
     * date and previous order under that line, and that the executor received that finish date and previous order.
     */
    private void assertChainedFrom(final Entity position, final Entity line, final String finishDate,
            final Entity previousOrder) {
        Step step = step(position);

        assertSame(line, step.line);
        assertEquals(Collections.singletonMap(line.getId(), at(finishDate)), step.finishCacheSeen);
        assertEquals(Collections.singletonMap(line.getId(), previousOrder), step.orderCacheSeen);
        assertEquals(at(finishDate), step.finishDate);
        assertSame(previousOrder, step.previousOrder);
    }

    /**
     * Asserts that the position was neither read into a chain, nor changed, nor saved.
     */
    private void assertUntouched(final Entity position) {
        for (Step step : steps) {
            assertNotSame(position, step.position);
        }

        verify(position, never()).setField(anyString(), any());
        verify(psExecutor, never()).savePosition(same(position), any(ProductionLinePositionNewData.class));
        verify(ppsExecutor, never()).savePosition(same(position), any(ProductionLinePositionNewData.class));
    }

    /**
     * Returns the single recorded recompute step of the position.
     */
    private Step step(final Entity position) {
        Step found = null;

        for (Step step : steps) {
            if (step.position == position) {
                assertNull("position recomputed more than once", found);

                found = step;
            }
        }

        assertNotNull("position not recomputed", found);

        return found;
    }

    private Step currentStep() {
        assertFalse("no recompute step started", steps.isEmpty());

        return steps.get(steps.size() - 1);
    }

    private ProductionLinePositionNewData newData(final String startTime, final String endTime) {
        return argThat(new NewDataMatcher(at(startTime), at(endTime), changeover));
    }

    /**
     * Creates a position of the fixture schedule and adds it to the position table.
     */
    private Entity position(final Long id, final Entity productionLine, final Entity order, final String startTime,
            final String endTime) {
        Entity position = mockEntity(id, positionDD);

        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.ORDER, order);
        stubDateField(position, ProductionLineSchedulePositionFields.START_TIME, at(startTime));
        stubDateField(position, ProductionLineSchedulePositionFields.END_TIME, at(endTime));

        positionTable.add(position);

        return position;
    }

    private static Entity order(final Long id) {
        return order(id, mockEntity(id + 100L));
    }

    private static Entity order(final Long id, final Entity technology) {
        Entity order = mockEntity(id);

        stubBelongsToField(order, OrderFields.TECHNOLOGY, technology);

        return order;
    }

    private static Date at(final String time) {
        String value = DAY + " " + time + ":00";

        try {
            return new SimpleDateFormat(DATE_TIME_PATTERN).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Date plusMinutes(final Date date, final int minutes) {
        return new Date(date.getTime() + minutes * 60000L);
    }

    private static DataAccessService swapSearchRestrictionsDataAccessService(final DataAccessService dataAccessService)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = SearchRestrictions.class.getDeclaredField("dataAccessService");

        field.setAccessible(true);

        DataAccessService previous = (DataAccessService) field.get(null);

        field.set(null, dataAccessService);

        return previous;
    }

    /**
     * Returns the criteria the position queries may use, each with the condition a table row satisfies for it: the fixture
     * schedule, rows A and B, the id of every table row, and "after" and "at or before" every registered query bound.
     */
    private Map<SearchCriterion, EntityCondition> positionConditions() {
        Map<SearchCriterion, EntityCondition> conditions = new LinkedHashMap<SearchCriterion, EntityCondition>();

        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB));

        for (Entity position : positionTable) {
            conditions.put(SearchRestrictions.idNe(position.getId()), new IdNotEqualCondition(position.getId()));
        }

        for (Date bound : queryBounds) {
            conditions.put(SearchRestrictions.gt(ProductionLineSchedulePositionFields.START_TIME, bound),
                    new StartTimeCondition(bound, true));
            conditions.put(SearchRestrictions.le(ProductionLineSchedulePositionFields.START_TIME, bound),
                    new StartTimeCondition(bound, false));
        }

        return conditions;
    }

    /**
     * Returns the orders the position queries may use: start time ascending, id ascending and start time descending.
     */
    private static Map<SearchOrder, Comparator<Entity>> positionOrders() {
        Map<SearchOrder, Comparator<Entity>> orders = new LinkedHashMap<SearchOrder, Comparator<Entity>>();

        orders.put(SearchOrders.asc(ProductionLineSchedulePositionFields.START_TIME), new StartTimeComparator(true));
        orders.put(SearchOrders.asc(L_ID), new IdComparator());
        orders.put(SearchOrders.desc(ProductionLineSchedulePositionFields.START_TIME), new StartTimeComparator(false));

        return orders;
    }


    /**
     * One recompute of one position: the chain caches and arguments the scheduling services and the executor received.
     */
    private static final class Step {

        private final Entity line;

        private final Entity order;

        private final Map<Long, Date> finishCacheSeen;

        private Entity position;

        private Map<Long, Entity> orderCacheSeen;

        private String executor;

        private Date finishDate;

        private Entity previousOrder;

        private Step(final Entity line, final Entity order, final Map<Long, Date> finishCacheSeen) {
            this.line = line;
            this.order = order;
            this.finishCacheSeen = finishCacheSeen;
        }

    }

    /**
     * Answers {@link ProductionLineScheduleService#getFinishDate} with the line's cached finish date, or the schedule start
     * when the cache holds none, and starts a new recompute step holding a copy of the cache.
     */
    private final class FinishDateAnswer implements Answer<Date> {

        @Override
        @SuppressWarnings("unchecked")
        public Date answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Map<Long, Date> finishCache = (Map<Long, Date>) arguments[0];
            Date scheduleStartTime = (Date) arguments[1];
            Entity line = (Entity) arguments[2];
            Entity order = (Entity) arguments[3];

            steps.add(new Step(line, order, new HashMap<Long, Date>(finishCache)));

            Date cachedFinishDate = finishCache.get(line.getId());

            if (cachedFinishDate == null) {
                return scheduleStartTime;
            }

            return cachedFinishDate;
        }

    }

    /**
     * Answers {@link ProductionLineScheduleService#getFinishDateWithChildren} with the finish date it received and records
     * the position in the current recompute step.
     */
    private final class FinishDateWithChildrenAnswer implements Answer<Date> {

        @Override
        public Date answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Step step = currentStep();

            assertNull("finish date with children requested twice", step.position);

            step.position = (Entity) arguments[0];

            return (Date) arguments[1];
        }

    }

    /**
     * Answers {@link ProductionLineScheduleService#getPreviousOrder} with the line's cached order and records a copy of the
     * cache in the current recompute step.
     */
    private final class PreviousOrderAnswer implements Answer<Entity> {

        @Override
        @SuppressWarnings("unchecked")
        public Entity answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Map<Long, Entity> orderCache = (Map<Long, Entity>) arguments[0];
            Entity line = (Entity) arguments[1];
            Step step = currentStep();

            assertSame(step.line, line);

            step.orderCacheSeen = new HashMap<Long, Entity>(orderCache);

            return orderCache.get(line.getId());
        }

    }

    /**
     * Answers {@code createProductionLinePositionNewData} of an executor service: records the call in the current recompute
     * step and, while the fixture produces data, puts new data for the line starting {@value #CHANGEOVER_MINUTES} minutes
     * after the finish date and ending {@value #DURATION_MINUTES} minutes after that start.
     */
    private final class CreateDataAnswer implements Answer<Void> {

        private final String executor;

        private CreateDataAnswer(final String executor) {
            this.executor = executor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Void answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Map<Long, ProductionLinePositionNewData> positionNewData = (Map<Long, ProductionLinePositionNewData>) arguments[0];
            Entity line = (Entity) arguments[1];
            Date finishDate = (Date) arguments[2];
            Step step = currentStep();

            assertSame(step.line, line);
            assertSame(step.position, arguments[3]);
            assertSame(step.order.getBelongsToField(OrderFields.TECHNOLOGY), arguments[4]);
            assertNull("executor called twice for one position", step.executor);

            step.executor = executor;
            step.finishDate = finishDate;
            step.previousOrder = (Entity) arguments[5];

            events.add(CREATE_EVENT);

            if (executorProducesData) {
                Date startDate = plusMinutes(finishDate, CHANGEOVER_MINUTES);

                positionNewData.put(line.getId(), new ProductionLinePositionNewData(startDate, plusMinutes(startDate,
                        DURATION_MINUTES), changeover));
            }

            return null;
        }

    }

    /**
     * Answers {@code savePosition} of an executor service: requires the position's recompute step to have run on the same
     * executor and records a save event.
     */
    private final class SaveAnswer implements Answer<Void> {

        private final String executor;

        private SaveAnswer(final String executor) {
            this.executor = executor;
        }

        @Override
        public Void answer(final InvocationOnMock invocation) {
            Step step = step((Entity) invocation.getArguments()[0]);

            assertEquals(executor, step.executor);

            events.add(SAVE_EVENT);

            return null;
        }

    }

    /**
     * Answers {@code find()} on the position data definition with a new criteria builder over the position table and records
     * its criteria and orders as a new position query.
     */
    private final class PositionFindAnswer implements Answer<SearchCriteriaBuilder> {

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            List<Object> query = new ArrayList<Object>();

            positionQueries.add(query);
            events.add(FIND_EVENT);

            return mock(SearchCriteriaBuilder.class, new PositionCriteriaBuilderAnswer(new ArrayList<Entity>(positionTable),
                    positionConditions(), positionOrders(), query));
        }

    }

    /**
     * Records known criteria of {@code add}, known orders of {@code addOrder} and the limit of {@code setMaxResults}, and
     * answers them with the builder itself. Answers {@code list()} and {@code uniqueResult()} with the table rows that satisfy
     * every recorded criterion, sorted by the recorded orders and cut to the limit. Fails on unknown criteria, unknown orders,
     * a {@code uniqueResult()} matching more than one row and every other builder method.
     */
    private static final class PositionCriteriaBuilderAnswer implements Answer<Object> {

        private final List<Entity> rows;

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<Object> query;

        private Integer maxResults;

        private PositionCriteriaBuilderAnswer(final List<Entity> rows, final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders, final List<Object> query) {
            this.rows = rows;
            this.conditions = conditions;
            this.orders = orders;
            this.query = query;
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
     * Satisfied when the start time is strictly after the bound ({@code after = true}) or at or before it
     * ({@code after = false}).
     */
    private static final class StartTimeCondition implements EntityCondition {

        private final Date bound;

        private final boolean after;

        private StartTimeCondition(final Date bound, final boolean after) {
            this.bound = new Date(bound.getTime());
            this.after = after;
        }

        @Override
        public boolean matches(final Entity entity) {
            Date startTime = entity.getDateField(ProductionLineSchedulePositionFields.START_TIME);

            if (startTime == null) {
                return false;
            }
            if (after) {
                return startTime.after(bound);
            }

            return !startTime.after(bound);
        }

    }

    /**
     * Orders entities by their non-null start time, ascending or descending.
     */
    private static final class StartTimeComparator implements Comparator<Entity> {

        private final boolean ascending;

        private StartTimeComparator(final boolean ascending) {
            this.ascending = ascending;
        }

        @Override
        public int compare(final Entity first, final Entity second) {
            int result = first.getDateField(ProductionLineSchedulePositionFields.START_TIME).compareTo(
                    second.getDateField(ProductionLineSchedulePositionFields.START_TIME));

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

    /**
     * Matches new position data with the given start date, finish date and changeover.
     */
    private static final class NewDataMatcher extends ArgumentMatcher<ProductionLinePositionNewData> {

        private final Date startDate;

        private final Date finishDate;

        private final Entity changeover;

        private NewDataMatcher(final Date startDate, final Date finishDate, final Entity changeover) {
            this.startDate = startDate;
            this.finishDate = finishDate;
            this.changeover = changeover;
        }

        @Override
        public boolean matches(final Object argument) {
            if (!(argument instanceof ProductionLinePositionNewData)) {
                return false;
            }

            ProductionLinePositionNewData newData = (ProductionLinePositionNewData) argument;

            return startDate.equals(newData.getStartDate()) && finishDate.equals(newData.getFinishDate())
                    && changeover == newData.getChangeover();
        }

    }

}

