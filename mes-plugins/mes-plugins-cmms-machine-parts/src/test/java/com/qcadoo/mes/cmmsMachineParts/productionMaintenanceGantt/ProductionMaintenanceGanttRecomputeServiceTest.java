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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.InExpression;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import com.qcadoo.mes.orders.ProductionLineScheduleService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePPSExecutorService;
import com.qcadoo.mes.orders.ProductionLineScheduleServicePSExecutorService;
import com.qcadoo.mes.orders.constants.DurationOfOrderCalculatedOnBasis;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.listeners.ProductionLinePositionNewData;
import com.qcadoo.mes.technologies.constants.TechnologiesConstants;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.JoinType;
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
 * position data definition answers a new criteria builder that accepts only the criteria, orders and inner order alias the
 * recompute queries are expected to use, and reads no row. The builder's {@code list()} and {@code uniqueResult()} select the
 * rows that satisfy them from the position table as it is at that call, excluding the rows without an order when the inner
 * order alias was created, and record a read event.
 * {@link SearchRestrictions} converts every entity through a mocked {@link DataAccessService} into a reference holding only
 * the entity's id: belongs-to criteria built from distinct entities with one id are equal, and a belongs-to criterion selects
 * the rows whose field holds an entity with that id.
 * <p>
 * The {@link ProductionLineScheduleService} answers run one recompute step per position and read the chain caches the way
 * the real service does: {@code getFinishDate} answers the line's cached finish date or the schedule start;
 * {@code getFinishDateWithChildren} answers the position's children end time from {@code childrenEndTimes} when it is
 * after the date received, and that date otherwise; {@code getPreviousOrder} answers the line's cached order or, with none
 * cached, the order of the in-memory order table on that line with the latest finish date at or before the date received.
 * The {@code getFinishDateWithChildren}, {@code getPreviousOrder} and executor answers fail unless the date they receive is
 * the one the preceding call of the same step answered, and the executor answers also fail unless the previous order they
 * receive is the one {@code getPreviousOrder} answered. The {@code getFinishDate} answer starts the step with the line, the
 * order and a copy of the finish cache it received and records the date it answered in it; the
 * {@code getFinishDateWithChildren} and {@code getPreviousOrder} answers record what they received and answered in the step.
 * <p>
 * Both executor services answer {@code createProductionLinePositionNewData} by recording their executor and the finish date
 * and previous order they received in the step, recording every call as a create event and, while
 * {@code executorProducesData} is set, putting new data only into the map they received: a start
 * {@value #CHANGEOVER_MINUTES} minutes after the finish date and an end {@value #DURATION_MINUTES} minutes after that start,
 * under the id of the line they received or of {@code executorDataLine}. Their first call of a test adds the rows created by
 * {@code positionInsertedAtFirstCreate} to the position table. Both answer {@code savePosition} by requiring the position's
 * step to have run on the same executor and recording a save event.
 * <p>
 * Row A is production line {@value #LINE_A_ID_VALUE} and row B is production line {@value #LINE_B_ID_VALUE}. All times are
 * on {@value #DAY}; the schedule starts at {@value #SCHEDULE_START}. Positions belong to the fixture schedule unless a test
 * places them in another schedule.
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

    private static final Long OTHER_SCHEDULE_ID = 8L;

    private static final Long MOVED_ID = 10L;

    private static final String L_ID = "id";

    private static final String PS = "PS";

    private static final String PPS = "PPS";

    private static final String READ_EVENT = "read";

    private static final String CREATE_EVENT = "create";

    private static final String SAVE_EVENT = "save";

    private static final String ORDER_FIND_EVENT = "orderFind";

    private static final String TECHNOLOGY_FIND_EVENT = "technologyFind";

    private static final String IN_PROPERTY_NAME_FIELD = "propertyName";

    private static final String IN_VALUES_FIELD = "values";

    /**
     * Element a position query records for {@code createAlias(ORDER, ORDER, JoinType.INNER)}, the only alias the position
     * criteria builder accepts.
     */
    private static final List<Object> INNER_ORDER_ALIAS = Collections.unmodifiableList(Arrays.<Object> asList(
            ProductionLineSchedulePositionFields.ORDER, ProductionLineSchedulePositionFields.ORDER, JoinType.INNER));

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

    @Mock
    private DataDefinition orderDD;

    @Mock
    private DataDefinition technologyDD;

    private DataAccessService previousDataAccessService;

    private Entity schedule, lineA, lineB;

    private boolean executorProducesData;

    private Entity executorDataLine;

    private final List<Entity> positionTable = new ArrayList<Entity>();

    private final List<Entity> rowsInsertedAtFirstCreate = new ArrayList<Entity>();

    private final List<Entity> orderTable = new ArrayList<Entity>();

    private final Map<Entity, Date> childrenEndTimes = new IdentityHashMap<Entity, Date>();

    private final List<Date> queryBounds = new ArrayList<Date>();

    private final List<List<Object>> positionQueries = new ArrayList<List<Object>>();

    private final List<Step> steps = new ArrayList<Step>();

    private final List<String> events = new ArrayList<String>();

    /**
     * Rows of the order load that replace, or with a {@code null} value remove, the row of their id.
     */
    private final Map<Long, Entity> loadedOrderOverrides = new HashMap<Long, Entity>();

    /**
     * Rows of the technology load that replace, or with a {@code null} value remove, the row of their id.
     */
    private final Map<Long, Entity> loadedTechnologyOverrides = new HashMap<Long, Entity>();

    private final List<List<Long>> orderQueries = new ArrayList<List<Long>>();

    private final List<List<Long>> technologyQueries = new ArrayList<List<Long>>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        recomputeService = new ProductionMaintenanceGanttRecomputeService();

        setField(recomputeService, "dataDefinitionService", dataDefinitionService);
        setField(recomputeService, "productionLineScheduleService", productionLineScheduleService);
        setField(recomputeService, "productionLineScheduleServicePSExecutorService", psExecutor);
        setField(recomputeService, "productionLineScheduleServicePPSExecutorService", ppsExecutor);

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(new DatabaseReferenceAnswer());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION)).willReturn(positionDD);
        given(positionDD.find()).willAnswer(new PositionFindAnswer());
        stubOrderAndTechnologyDataDefinitions();

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
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds a1 06:00-07:00, p 10:00 and q 13:00; origin and
        // destination are distinct entities of row A
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "11:00", "12:00");
        Entity p = position(12L, lineA, order(53L), "10:00", "11:00");
        Entity q = position(13L, lineA, order(54L), "13:00", "14:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        recompute(moved, originLine, "08:00", destinationLine, "11:00");

        // then: one candidate query and one predecessor query; p then q are saved, each once; p is chained from a1, q from
        // m's new end and order
        InOrder inOrder = inOrder(p, q, psExecutor);
        verifyRecomputedAndSaved(inOrder, p, "07:15", "08:15");
        verifyRecomputedAndSaved(inOrder, q, "12:15", "13:15");
        verifyEachSavedOnce(p, q);

        assertChainedFrom(p, destinationLine, "07:00", a1Order);
        assertChainedFrom(q, destinationLine, "12:00", movedOrder);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
        assertEquals(2, positionQueries.size());
    }

    @Test
    public final void shouldPlaceTieAtDropBeforeMovedPositionOnLaterSameRowMove() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds a1 06:00-07:00, t 11:00 and q 13:00; origin and
        // destination are distinct entities of row A
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "11:00", "12:00");
        Entity t = position(12L, lineA, order(53L), "11:00", "12:00");
        Entity q = position(13L, lineA, order(54L), "13:00", "14:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        recompute(moved, originLine, "08:00", destinationLine, "11:00");

        // then: one candidate query and one predecessor query; t is saved before q, each once; t is chained from a1, q
        // from m
        InOrder inOrder = inOrder(t, q, psExecutor);
        verifyRecomputedAndSaved(inOrder, t, "07:15", "08:15");
        verifyRecomputedAndSaved(inOrder, q, "12:15", "13:15");
        verifyEachSavedOnce(t, q);

        assertChainedFrom(t, destinationLine, "07:00", a1Order);
        assertChainedFrom(q, destinationLine, "12:00", movedOrder);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(2, steps.size());
        assertEquals(2, positionQueries.size());
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
        // given: m moves on A from 12:00-13:00 to 09:00-10:00; A holds a1 06:00, p 08:00 and q 10:00; origin and destination
        // are distinct entities of row A
        Entity a1 = position(11L, lineA, order(52L), "06:00", "07:00");
        Entity p = position(12L, lineA, order(53L), "08:00", "09:00");
        Entity q = position(13L, lineA, order(54L), "10:00", "11:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "09:00", "10:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        recompute(moved, originLine, "12:00", destinationLine, "09:00");

        // then: only q is recomputed, once, chained from m; one candidate query and one predecessor query read row A
        InOrder inOrder = inOrder(q, psExecutor);
        verifyRecomputedAndSaved(inOrder, q, "10:15", "11:15");
        verifyEachSavedOnce(q);

        assertChainedFrom(q, destinationLine, "10:00", movedOrder);

        verify(psExecutor, times(1)).createProductionLinePositionNewData(
                Matchers.<Map<Long, ProductionLinePositionNewData>> any(), any(Entity.class), any(Date.class),
                any(Entity.class), any(Entity.class), any(Entity.class));
        assertEquals(1, steps.size());

        assertEquals(2, positionQueries.size());
        for (List<Object> query : positionQueries) {
            assertTrue(query.contains(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA)));
        }

        assertUntouched(a1);
        assertUntouched(p);
        assertUntouched(moved);
    }

    @Test
    public final void shouldExcludeMovedPositionById() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds twin 13:00 with m's order under another id; origin
        // and destination are distinct entities of row A
        Entity sharedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, sharedOrder, "11:00", "12:00");
        Entity twin = position(12L, lineA, sharedOrder, "13:00", "14:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        recompute(moved, originLine, "08:00", destinationLine, "11:00");

        // then: one candidate query and one predecessor query, each excluding m by id; twin is recomputed once from m, m is
        // not
        assertEquals(2, positionQueries.size());
        for (List<Object> query : positionQueries) {
            assertTrue(query.contains(SearchRestrictions.idNe(MOVED_ID)));
        }

        InOrder inOrder = inOrder(twin, psExecutor);
        verifyRecomputedAndSaved(inOrder, twin, "12:15", "13:15");
        verifyEachSavedOnce(twin);

        assertChainedFrom(twin, destinationLine, "12:00", sharedOrder);

        assertUntouched(moved);
        assertEquals(1, steps.size());
    }

    @Test
    public final void shouldChainOriginRowFromLatestPredecessorWithOrder() {
        // given: m moves from A 09:00 to B 10:00-11:00; A holds a1 06:00-07:00, aWithoutOrder 08:00-08:45 without an order
        // and a2 11:00; B holds no position after the drop start
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity aWithoutOrder = position(14L, lineA, null, "08:00", "08:45");
        Entity moved = position(MOVED_ID, lineB, order(51L), "10:00", "11:00");
        Entity a2 = position(12L, lineA, order(53L), "11:00", "12:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "10:00");

        // then: a2 is chained from a1, the latest position with an order at or before the vacated start; aWithoutOrder is
        // neither the predecessor nor recomputed
        InOrder inOrder = inOrder(a2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "07:15", "08:15");
        verifyEachSavedOnce(a2);

        assertChainedFrom(a2, lineA, "07:00", a1Order);

        // then: the predecessor query joins the position's order inner after the schedule and line criteria and still
        // excludes m by id; neither candidate query joins the order
        assertEquals(3, positionQueries.size());
        assertEquals(Arrays.<Object> asList(
                SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule),
                SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA), INNER_ORDER_ALIAS,
                SearchRestrictions.idNe(MOVED_ID), SearchRestrictions.le(ProductionLineSchedulePositionFields.START_TIME,
                        at("09:00")), SearchOrders.desc(ProductionLineSchedulePositionFields.START_TIME)),
                positionQueries.get(1));
        assertFalse(positionQueries.get(0).contains(INNER_ORDER_ALIAS));
        assertFalse(positionQueries.get(2).contains(INNER_ORDER_ALIAS));

        assertUntouched(a1);
        assertUntouched(aWithoutOrder);
        assertUntouched(moved);
        assertEquals(1, steps.size());
    }

    @Test
    public final void shouldChainLaterSameRowMoveFromLatestPredecessorWithOrder() {
        // given: m moves on A from 08:00-09:00 to 11:00-12:00; A holds a1 06:00-07:00, aWithoutOrder 07:30-07:45 without an
        // order, p 10:00 and q 13:00; origin and destination are distinct entities of row A
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "07:00");
        Entity aWithoutOrder = position(14L, lineA, null, "07:30", "07:45");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineA, movedOrder, "11:00", "12:00");
        Entity p = position(12L, lineA, order(53L), "10:00", "11:00");
        Entity q = position(13L, lineA, order(54L), "13:00", "14:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        recompute(moved, originLine, "08:00", destinationLine, "11:00");

        // then: p is chained from a1, the latest position with an order at or before the vacated start, and q from m;
        // aWithoutOrder is neither the predecessor nor recomputed
        InOrder inOrder = inOrder(p, q, psExecutor);
        verifyRecomputedAndSaved(inOrder, p, "07:15", "08:15");
        verifyRecomputedAndSaved(inOrder, q, "12:15", "13:15");
        verifyEachSavedOnce(p, q);

        assertChainedFrom(p, destinationLine, "07:00", a1Order);
        assertChainedFrom(q, destinationLine, "12:00", movedOrder);

        // then: the candidate query does not join the order; the predecessor query joins it inner and excludes m by id
        assertEquals(2, positionQueries.size());
        assertFalse(positionQueries.get(0).contains(INNER_ORDER_ALIAS));
        assertTrue(positionQueries.get(1).contains(INNER_ORDER_ALIAS));
        assertTrue(positionQueries.get(1).contains(SearchRestrictions.idNe(MOVED_ID)));

        assertUntouched(a1);
        assertUntouched(aWithoutOrder);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldIgnorePositionsOfOtherSchedulesOnAffectedRows() {
        // given: m moves from A 09:00 to B 11:00-12:00; A holds a1 06:00-08:00 and a2 11:00, B holds b2 13:00; another
        // schedule holds oA1 08:00-08:30 and oA2 10:00 on A, and oB1 10:00 and oB2 11:30 on B
        Entity otherSchedule = mockEntity(OTHER_SCHEDULE_ID);
        Entity a1Order = order(52L);
        Entity a1 = position(11L, lineA, a1Order, "06:00", "08:00");
        Entity oA1 = position(otherSchedule, 14L, lineA, order(61L), "08:00", "08:30");
        Entity oA2 = position(otherSchedule, 15L, lineA, order(62L), "10:00", "10:30");
        Entity a2 = position(12L, lineA, order(53L), "11:00", "12:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity oB1 = position(otherSchedule, 24L, lineB, order(63L), "10:00", "10:30");
        Entity oB2 = position(otherSchedule, 25L, lineB, order(64L), "11:30", "12:30");
        Entity b2 = position(22L, lineB, order(54L), "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: the two candidate queries and the predecessor query read the fixture schedule; a2 is chained from a1, b2
        // from m; no position of the other schedule is read into a chain or written
        SearchCriterion fixtureScheduleCriterion = SearchRestrictions.belongsTo(
                ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);

        assertEquals(3, positionQueries.size());
        for (List<Object> query : positionQueries) {
            assertTrue(query.contains(fixtureScheduleCriterion));
        }

        InOrder inOrder = inOrder(a2, b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "08:15", "09:15");
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");

        assertChainedFrom(a2, lineA, "08:00", a1Order);
        assertChainedFrom(b2, lineB, "12:00", movedOrder);

        assertUntouched(a1);
        assertUntouched(oA1);
        assertUntouched(oA2);
        assertUntouched(oB1);
        assertUntouched(oB2);
        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldRecomputeNothingWhenNeitherRowHasPositionsAfterAffectedStart() {
        // given: m moves from A 09:00 to B 11:00-12:00; A holds only a1 06:00-08:00 and B only b1 08:00-09:00
        Entity a1 = position(11L, lineA, order(52L), "06:00", "08:00");
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b1 = position(21L, lineB, order(53L), "08:00", "09:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: both candidate queries and the predecessor query run, and no scheduling service or executor is called
        assertEquals(3, positionQueries.size());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
        assertTrue(steps.isEmpty());

        assertUntouched(a1);
        assertUntouched(b1);
        assertUntouched(moved);
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
        verify(b2, never()).setField(eq(ProductionLineSchedulePositionFields.PRODUCTION_LINE), any());
        verify(b2, times(2)).setField(anyString(), any());

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
    public final void shouldRejectWithRecomputeFailedWhenExecutorProducesDataOnlyForAnotherLine() {
        // given: the executor puts its data only under row A; m moves from A 09:00 to B 11:00-12:00; A holds no position
        // after the vacated start; B holds b2 13:00
        executorDataLine = lineA;

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

        verify(psExecutor).createProductionLinePositionNewData(Matchers.<Map<Long, ProductionLinePositionNewData>> any(),
                same(lineB), eq(at("12:00")), same(b2), any(Entity.class), any(Entity.class));
        assertEquals(1, steps.size());
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
        assertEquals(3, Collections.frequency(events, READ_EVENT));
        assertTrue(events.contains(CREATE_EVENT));
        assertTrue(events.lastIndexOf(READ_EVENT) < events.indexOf(CREATE_EVENT));
        assertEquals(3, steps.size());
    }

    @Test
    public final void shouldReadEveryCandidateBeforeFirstExecutorCall() {
        // given: m moves from A 09:00 to B 12:00-13:00; A holds a2 11:00; B holds b2 13:00; the first executor call adds
        // aLate 15:00 on A and bLate 16:00 on B to the position table
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "12:00", "13:00");
        Entity a2 = position(12L, lineA, order(52L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(54L), "13:00", "14:00");
        Entity aLate = positionInsertedAtFirstCreate(14L, lineA, order(55L), "15:00", "16:00");
        Entity bLate = positionInsertedAtFirstCreate(24L, lineB, order(56L), "16:00", "17:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "12:00");

        // then: a2 then b2 are recomputed and saved; the rows added by the first executor call are never read into a chain
        assertTrue(positionTable.contains(aLate));
        assertTrue(positionTable.contains(bLate));

        InOrder inOrder = inOrder(a2, b2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "05:15", "06:15");
        verifyRecomputedAndSaved(inOrder, b2, "13:15", "14:15");

        assertChainedFrom(b2, lineB, "13:00", movedOrder);

        assertUntouched(aLate);
        assertUntouched(bLate);
        assertUntouched(moved);
        assertEquals(3, Collections.frequency(events, READ_EVENT));
        assertTrue(events.lastIndexOf(READ_EVENT) < events.indexOf(CREATE_EVENT));
        assertEquals(2, steps.size());
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
    public final void shouldRecomputeStartTimeOrderBeforeIdOrder() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b25 14:00, b26 12:30 and b24 13:00, added in that order
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity b25 = position(25L, lineB, order(55L), "14:00", "15:00");
        Entity b26Order = order(56L);
        Entity b26 = position(26L, lineB, b26Order, "12:30", "13:30");
        Entity b24Order = order(54L);
        Entity b24 = position(24L, lineB, b24Order, "13:00", "14:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: b26, b24, then b25 are recomputed and saved; b26 is chained from m, b24 from b26, b25 from b24
        assertEquals(3, steps.size());
        assertSame(b26, steps.get(0).position);
        assertSame(b24, steps.get(1).position);
        assertSame(b25, steps.get(2).position);

        InOrder inOrder = inOrder(b26, b24, b25, psExecutor);
        verifyRecomputedAndSaved(inOrder, b26, "12:15", "13:15");
        verifyRecomputedAndSaved(inOrder, b24, "13:30", "14:30");
        verifyRecomputedAndSaved(inOrder, b25, "14:45", "15:45");

        assertChainedFrom(b26, lineB, "12:00", movedOrder);
        assertChainedFrom(b24, lineB, "13:15", b26Order);
        assertChainedFrom(b25, lineB, "14:30", b24Order);

        assertUntouched(moved);
    }

    @Test
    public final void shouldRecomputeFromFinishDateWithChildren() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b2 12:00, whose children end at 12:40, and b3 14:00
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity b2Order = order(53L);
        Entity b2 = position(22L, lineB, b2Order, "12:00", "13:00");
        Entity b3 = position(23L, lineB, order(54L), "14:00", "15:00");

        childrenEndTimes.put(b2, at("12:40"));

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: b2's cached finish 12:00 is replaced by its children's end 12:40 for the previous order and the executor;
        // b3 is chained from b2's new end and order
        Step b2Step = step(b2);
        assertEquals(Collections.singletonMap(lineB.getId(), at("12:00")), b2Step.finishCacheSeen);
        assertEquals(at("12:00"), b2Step.scheduledFinishDate);
        assertEquals(at("12:00"), b2Step.childrenInputDate);
        assertEquals(at("12:40"), b2Step.childrenFinishDate);
        assertEquals(at("12:40"), b2Step.previousOrderDate);
        assertEquals(at("12:40"), b2Step.finishDate);
        assertSame(movedOrder, b2Step.previousOrder);

        InOrder inOrder = inOrder(b2, b3, psExecutor);
        verifyRecomputedAndSaved(inOrder, b2, "12:55", "13:55");
        verifyRecomputedAndSaved(inOrder, b3, "14:10", "15:10");

        assertChainedFrom(b3, lineB, "13:55", b2Order);

        assertUntouched(moved);
        assertEquals(2, steps.size());
    }

    @Test
    public final void shouldTakePreviousOrderFromOrderLookupWithoutPredecessor() {
        // given: m moves from A 09:00 to B 10:00-11:00; A holds only a2 11:00 after the vacated start; the orders on A are
        // priorOrder finishing 04:30 and a2's order finishing 07:00
        Entity moved = position(MOVED_ID, lineB, order(51L), "10:00", "11:00");
        Entity priorOrder = orderOnLine(60L, lineA, "04:30");
        Entity a2Order = orderOnLine(53L, lineA, "07:00");
        Entity a2 = position(12L, lineA, a2Order, "11:00", "12:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "10:00");

        // then: a2 is scheduled from the schedule start with priorOrder, the latest order on A finishing by the schedule start
        Step a2Step = step(a2);
        assertTrue(a2Step.finishCacheSeen.isEmpty());
        assertTrue(a2Step.orderCacheSeen.isEmpty());
        assertEquals(at(SCHEDULE_START), a2Step.scheduledFinishDate);
        assertEquals(at(SCHEDULE_START), a2Step.previousOrderDate);
        assertEquals(at(SCHEDULE_START), a2Step.finishDate);
        assertSame(priorOrder, a2Step.previousOrderAnswered);
        assertSame(priorOrder, a2Step.previousOrder);

        InOrder inOrder = inOrder(a2, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "05:15", "06:15");

        assertUntouched(moved);
        assertEquals(1, steps.size());
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
    public final void shouldRejectBeforeAnySchedulingCallWhenScheduleHasNoStartTime() {
        // given: the schedule has no start time; m moves from A 09:00 to B 11:00-12:00; A holds a2 11:00; B holds b2 13:00
        stubDateField(schedule, ProductionLineScheduleFields.START_TIME, null);

        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity a2 = position(12L, lineA, order(52L), "11:00", "12:00");
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

        // then: the position queries ran; no scheduling service or executor was called and no candidate was changed
        assertEquals(3, positionQueries.size());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
        assertTrue(steps.isEmpty());
        verify(a2, never()).setField(anyString(), any());
        verify(b2, never()).setField(anyString(), any());
        assertUntouched(moved);
    }

    @Test
    public final void shouldRejectSameRowMoveBeforeAnySchedulingCallWhenScheduleHasNoStartTime() {
        // given: the schedule has no start time; m moves on A from 08:00-09:00 to 11:00-12:00; A holds p 10:00 and q 13:00;
        // origin and destination are distinct entities of row A
        stubDateField(schedule, ProductionLineScheduleFields.START_TIME, null);

        Entity moved = position(MOVED_ID, lineA, order(51L), "11:00", "12:00");
        Entity p = position(12L, lineA, order(52L), "10:00", "11:00");
        Entity q = position(13L, lineA, order(53L), "13:00", "14:00");
        Entity originLine = entityWithIdOf(lineA);
        Entity destinationLine = entityWithIdOf(lineA);

        // when
        try {
            recompute(moved, originLine, "08:00", destinationLine, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
            assertEquals(0, e.getArgs().length);
        }

        // then: the position queries ran; no scheduling service or executor was called and no candidate was changed
        assertEquals(2, positionQueries.size());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
        assertTrue(steps.isEmpty());
        verify(p, never()).setField(anyString(), any());
        verify(q, never()).setField(anyString(), any());
        assertUntouched(moved);
    }

    @Test
    public final void shouldRecomputeNothingWithoutScheduleStartTimeWhenNeitherRowHasPositionsAfterAffectedStart() {
        // given: the schedule has no start time; m moves from A 09:00 to B 11:00-12:00; A holds only a1 06:00-08:00 and B
        // only b1 08:00-09:00
        stubDateField(schedule, ProductionLineScheduleFields.START_TIME, null);

        Entity a1 = position(11L, lineA, order(52L), "06:00", "08:00");
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b1 = position(21L, lineB, order(53L), "08:00", "09:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: no rejection; the position queries ran, the schedule start was not read, and no scheduling service or
        // executor was called
        assertEquals(3, positionQueries.size());
        verify(schedule, never()).getDateField(ProductionLineScheduleFields.START_TIME);
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
        assertTrue(steps.isEmpty());

        assertUntouched(a1);
        assertUntouched(b1);
        assertUntouched(moved);
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

    @Test
    public final void shouldRequireSchedule() {
        // given: m moves from A 09:00 to B 11:00-12:00 without a schedule
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");

        // when
        try {
            recomputeService.recompute(null, moved, lineA, at("09:00"), lineB, at("11:00"));

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRequireMovedPosition() {
        // given: no moved position; the move is from A 09:00 to B 11:00

        // when
        try {
            recomputeService.recompute(schedule, null, lineA, at("09:00"), lineB, at("11:00"));

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRequireDestinationLine() {
        // given: m moves from A 09:00 to 11:00-12:00 without a destination line
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");

        // when
        try {
            recomputeService.recompute(schedule, moved, lineA, at("09:00"), null, at("11:00"));

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRequireSlotStart() {
        // given: m moves from A 09:00 to B without a slot start
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");

        // when
        try {
            recomputeService.recompute(schedule, moved, lineA, at("09:00"), lineB, null);

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRequireVacatedStartWithOriginLine() {
        // given: m moves from A to B 11:00-12:00 without a vacated start
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");

        // when
        try {
            recomputeService.recompute(schedule, moved, lineA, null, lineB, at("11:00"));

            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // then
            assertNotNull(e.getMessage());
        }

        verifyZeroInteractions(dataDefinitionService, positionDD, productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRejectBeforeAnyExecutorCallForCandidateOrderWithoutTechnology() {
        // given: time consuming technology schedule; m moves from A 09:00 to B 11:00-12:00; A holds a2 11:00; B holds b2 13:00
        // whose order has no technology
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity a2 = position(12L, lineA, order(52L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L, null), "13:00", "14:00");

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        assertEquals(Collections.singletonList(Arrays.asList(52L, 53L)), orderQueries);
        verify(technologyDD, never()).find();
        verify(a2, never()).setField(anyString(), any());
        verify(b2, never()).setField(anyString(), any());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRejectBeforeAnyExecutorCallForCandidateOrderWithoutTechnologyOnPlanForShiftSchedule() {
        // given: plan for shift schedule; m moves on A from 08:00-09:00 to 11:00-12:00; A holds p 10:00 and q 13:00 whose
        // order has no technology
        stubStringField(schedule, ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS,
                DurationOfOrderCalculatedOnBasis.PLAN_FOR_SHIFT.getStringValue());

        Entity moved = position(MOVED_ID, lineA, order(51L), "11:00", "12:00");
        Entity p = position(12L, lineA, order(52L), "10:00", "11:00");
        Entity q = position(13L, lineA, order(53L, null), "13:00", "14:00");

        // when
        try {
            recompute(moved, lineA, "08:00", lineA, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        assertEquals(Collections.singletonList(Arrays.asList(52L, 53L)), orderQueries);
        verify(technologyDD, never()).find();
        verify(p, never()).setField(anyString(), any());
        verify(q, never()).setField(anyString(), any());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldLoadCandidateOrdersAndTechnologiesWithOneQueryEachBeforeFirstExecutorCall() {
        // given: m moves from A 09:00 to B 11:00-12:00; A holds a1 07:00-08:00, a2 10:00 and a3 12:00; B holds b2 13:00 and
        // b3 15:00, whose order is a3's order under another reference; the order load returns other instances of the
        // candidates' orders, each with its own technology, which the technology load returns
        Entity a1Order = order(50L);
        Entity a1 = position(11L, lineA, a1Order, "07:00", "08:00");
        Entity movedOrder = order(51L);
        Entity moved = position(MOVED_ID, lineB, movedOrder, "11:00", "12:00");
        Entity a2OrderReference = order(52L);
        Entity a2 = position(12L, lineA, a2OrderReference, "10:00", "11:00");
        Entity a3OrderReference = order(53L);
        Entity a3 = position(13L, lineA, a3OrderReference, "12:00", "13:00");
        Entity b2OrderReference = order(54L);
        Entity b2 = position(22L, lineB, b2OrderReference, "13:00", "14:00");
        Entity b3OrderReference = order(53L);
        Entity b3 = position(23L, lineB, b3OrderReference, "15:00", "16:00");

        Entity loadedTechnology52 = mockEntity(252L);
        Entity loadedTechnology53 = mockEntity(253L);
        Entity loadedTechnology54 = mockEntity(254L);
        Entity loadedOrder52 = overrideLoadedOrder(52L, loadedTechnology52);
        Entity loadedOrder53 = overrideLoadedOrder(53L, loadedTechnology53);
        Entity loadedOrder54 = overrideLoadedOrder(54L, loadedTechnology54);

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: one order query over the distinct candidate order ids and one technology query over the distinct technology
        // ids of the loaded orders, both after the last position query and before the first executor call
        verify(orderDD, times(1)).find();
        verify(technologyDD, times(1)).find();
        assertEquals(Collections.singletonList(Arrays.asList(52L, 53L, 54L)), orderQueries);
        assertEquals(Collections.singletonList(Arrays.asList(252L, 253L, 254L)), technologyQueries);
        assertEquals(3, positionQueries.size());
        assertTrue(events.lastIndexOf(READ_EVENT) < events.indexOf(ORDER_FIND_EVENT));
        assertTrue(events.indexOf(ORDER_FIND_EVENT) < events.indexOf(TECHNOLOGY_FIND_EVENT));
        assertTrue(events.indexOf(TECHNOLOGY_FIND_EVENT) < events.indexOf(CREATE_EVENT));

        // then: every candidate is recomputed with its loaded order and the loaded technology of that order
        InOrder inOrder = inOrder(a2, a3, b2, b3, psExecutor);
        verifyRecomputedAndSaved(inOrder, a2, "08:15", "09:15");
        verifyRecomputedAndSaved(inOrder, a3, "09:30", "10:30");
        verifyRecomputedAndSaved(inOrder, b2, "12:15", "13:15");
        verifyRecomputedAndSaved(inOrder, b3, "13:30", "14:30");

        assertSame(loadedOrder52, step(a2).order);
        assertSame(loadedOrder53, step(a3).order);
        assertSame(loadedOrder54, step(b2).order);
        assertSame(loadedOrder53, step(b3).order);

        assertChainedFrom(a2, lineA, "08:00", a1Order);
        assertChainedFrom(a3, lineA, "09:15", loadedOrder52);
        assertChainedFrom(b2, lineB, "12:00", movedOrder);
        assertChainedFrom(b3, lineB, "13:15", loadedOrder54);

        verify(psExecutor).createProductionLinePositionNewData(Matchers.<Map<Long, ProductionLinePositionNewData>> any(),
                same(lineA), eq(at("08:00")), same(a2), same(loadedTechnology52), same(a1Order));
        verify(psExecutor).createProductionLinePositionNewData(Matchers.<Map<Long, ProductionLinePositionNewData>> any(),
                same(lineA), eq(at("09:15")), same(a3), same(loadedTechnology53), same(loadedOrder52));
        verify(psExecutor).createProductionLinePositionNewData(Matchers.<Map<Long, ProductionLinePositionNewData>> any(),
                same(lineB), eq(at("12:00")), same(b2), same(loadedTechnology54), same(movedOrder));
        verify(psExecutor).createProductionLinePositionNewData(Matchers.<Map<Long, ProductionLinePositionNewData>> any(),
                same(lineB), eq(at("13:15")), same(b3), same(loadedTechnology53), same(loadedOrder54));

        // then: the candidates' own order references serve only for their ids
        verify(a2OrderReference, atLeastOnce()).getId();
        verify(a3OrderReference, atLeastOnce()).getId();
        verify(b2OrderReference, atLeastOnce()).getId();
        verify(b3OrderReference, atLeastOnce()).getId();
        verifyNoMoreInteractions(a2OrderReference, a3OrderReference, b2OrderReference, b3OrderReference);

        assertUntouched(a1);
        assertUntouched(moved);
        assertEquals(4, steps.size());
    }

    @Test
    public final void shouldNotLoadOrdersOrTechnologiesWithoutCandidates() {
        // given: m moves from A 09:00 to B 11:00-12:00; A holds only a1 07:00 and B only b1 08:00, both at or before their
        // row's affected start
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity a1 = position(11L, lineA, order(52L), "07:00", "08:00");
        Entity b1 = position(21L, lineB, order(53L), "08:00", "09:00");

        // when
        recompute(moved, lineA, "09:00", lineB, "11:00");

        // then: the position queries ran; neither the order nor the technology data definition was used
        assertEquals(3, positionQueries.size());
        verify(dataDefinitionService, never()).get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER);
        verify(dataDefinitionService, never()).get(TechnologiesConstants.PLUGIN_IDENTIFIER,
                TechnologiesConstants.MODEL_TECHNOLOGY);
        verifyZeroInteractions(orderDD, technologyDD, productionLineScheduleService, psExecutor, ppsExecutor);
        assertTrue(orderQueries.isEmpty());
        assertTrue(technologyQueries.isEmpty());
        assertTrue(steps.isEmpty());

        assertUntouched(a1);
        assertUntouched(b1);
        assertUntouched(moved);
    }

    @Test
    public final void shouldRejectBeforeAnyExecutorCallWhenOrderLoadDoesNotReturnCandidateOrder() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00 whose order the order load does not return
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity b2 = position(22L, lineB, order(53L), "13:00", "14:00");

        omitLoadedOrder(53L);

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        assertEquals(Collections.singletonList(Collections.singletonList(53L)), orderQueries);
        verify(technologyDD, never()).find();
        verify(b2, never()).setField(anyString(), any());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
    }

    @Test
    public final void shouldRejectBeforeAnyExecutorCallWhenTechnologyLoadDoesNotReturnOrderTechnology() {
        // given: m moves from A 09:00 to B 11:00-12:00; B holds b2 13:00 whose order's technology the technology load does
        // not return
        Entity moved = position(MOVED_ID, lineB, order(51L), "11:00", "12:00");
        Entity technology = mockEntity(81L);
        Entity b2 = position(22L, lineB, order(53L, technology), "13:00", "14:00");

        omitLoadedTechnology(81L);

        // when
        try {
            recompute(moved, lineA, "09:00", lineB, "11:00");

            fail("MoveRejectedException expected");
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            // then
            assertEquals(ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY, e.getMessageKey());
        }

        assertEquals(Collections.singletonList(Collections.singletonList(53L)), orderQueries);
        assertEquals(Collections.singletonList(Collections.singletonList(81L)), technologyQueries);
        verify(b2, never()).setField(anyString(), any());
        verifyZeroInteractions(productionLineScheduleService, psExecutor, ppsExecutor);
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
     * with new data holding those times and the fixture changeover, and that no other field of the position, its production
     * line included, was set.
     */
    private void verifyRecomputedAndSaved(final InOrder inOrder, final Entity position, final String startTime,
            final String endTime) {
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.START_TIME, at(startTime));
        inOrder.verify(position).setField(ProductionLineSchedulePositionFields.END_TIME, at(endTime));
        inOrder.verify(psExecutor).savePosition(same(position), newData(startTime, endTime));

        verify(position, never()).setField(eq(ProductionLineSchedulePositionFields.PRODUCTION_LINE), any());
        verify(position, times(2)).setField(anyString(), any());
    }

    /**
     * Verifies that the PS executor saved each given position exactly once and saved no other position.
     */
    private void verifyEachSavedOnce(final Entity... positions) {
        for (Entity position : positions) {
            verify(psExecutor, times(1)).savePosition(same(position), any(ProductionLinePositionNewData.class));
        }

        verify(psExecutor, times(positions.length)).savePosition(any(Entity.class), any(ProductionLinePositionNewData.class));
    }

    /**
     * Asserts that the position was recomputed once on the given line with chain caches holding exactly the given finish
     * date and previous order under that line, that {@code getFinishDate} and {@code getFinishDateWithChildren} answered that
     * finish date, that {@code getPreviousOrder} received it, and that the executor received that finish date and previous
     * order.
     */
    private void assertChainedFrom(final Entity position, final Entity line, final String finishDate,
            final Entity previousOrder) {
        Step step = step(position);

        assertSame(line, step.line);
        assertEquals(Collections.singletonMap(line.getId(), at(finishDate)), step.finishCacheSeen);
        assertEquals(Collections.singletonMap(line.getId(), previousOrder), step.orderCacheSeen);
        assertEquals(at(finishDate), step.scheduledFinishDate);
        assertEquals(at(finishDate), step.childrenFinishDate);
        assertEquals(at(finishDate), step.previousOrderDate);
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
        return position(schedule, id, productionLine, order, startTime, endTime);
    }

    /**
     * Creates a position of the given schedule and adds it to the position table.
     */
    private Entity position(final Entity positionSchedule, final Long id, final Entity productionLine, final Entity order,
            final String startTime, final String endTime) {
        Entity position = mockEntity(id, positionDD);

        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, positionSchedule);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.ORDER, order);
        stubDateField(position, ProductionLineSchedulePositionFields.START_TIME, at(startTime));
        stubDateField(position, ProductionLineSchedulePositionFields.END_TIME, at(endTime));

        positionTable.add(position);

        return position;
    }

    /**
     * Creates a position of the fixture schedule that the executor answers add to the position table at the first
     * {@code createProductionLinePositionNewData} call.
     */
    private Entity positionInsertedAtFirstCreate(final Long id, final Entity productionLine, final Entity order,
            final String startTime, final String endTime) {
        Entity position = position(id, productionLine, order, startTime, endTime);

        positionTable.remove(position);
        rowsInsertedAtFirstCreate.add(position);

        return position;
    }

    /**
     * Creates an order on the given production line finishing at the given time and adds it to the order table.
     */
    private Entity orderOnLine(final Long id, final Entity productionLine, final String finishTime) {
        Entity order = order(id);

        stubBelongsToField(order, OrderFields.PRODUCTION_LINE, productionLine);
        stubDateField(order, OrderFields.FINISH_DATE, at(finishTime));

        orderTable.add(order);

        return order;
    }

    private static Entity order(final Long id) {
        return order(id, mockEntity(id + 100L));
    }

    private static Entity order(final Long id, final Entity technology) {
        Entity order = mockEntity(id);

        stubBelongsToField(order, OrderFields.TECHNOLOGY, technology);

        return order;
    }

    /**
     * Returns a new entity, distinct from the given one, with the given entity's id.
     */
    private static Entity entityWithIdOf(final Entity entity) {
        return mockEntity(entity.getId());
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
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule.getId()));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineA.getId()));
        conditions.put(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB),
                new BelongsToCondition(ProductionLineSchedulePositionFields.PRODUCTION_LINE, lineB.getId()));

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
     * Stubs the order and the technology data definitions. Every {@code find()} on either answers a new criteria builder over
     * the loaded order table or the loaded technology table, as they are at query time, records the queried ids and adds a
     * find event of its own.
     */
    private void stubOrderAndTechnologyDataDefinitions() {
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER)).willReturn(orderDD);
        given(dataDefinitionService.get(TechnologiesConstants.PLUGIN_IDENTIFIER, TechnologiesConstants.MODEL_TECHNOLOGY))
                .willReturn(technologyDD);
        given(orderDD.find()).willAnswer(new IdInFindAnswer(ORDER_FIND_EVENT, orderQueries) {

            @Override
            protected Map<Long, Entity> table() {
                return loadedOrderTable();
            }

        });
        given(technologyDD.find()).willAnswer(new IdInFindAnswer(TECHNOLOGY_FIND_EVENT, technologyQueries) {

            @Override
            protected Map<Long, Entity> table() {
                return loadedTechnologyTable();
            }

        });
    }

    /**
     * Makes the order load return, for the given id, a new order with the given technology instead of the order of the
     * positions, and returns that order.
     */
    private Entity overrideLoadedOrder(final Long id, final Entity technology) {
        Entity loadedOrder = order(id, technology);

        loadedOrderOverrides.put(id, loadedOrder);

        return loadedOrder;
    }

    /**
     * Makes the order load return no order for the given id.
     */
    private void omitLoadedOrder(final Long id) {
        loadedOrderOverrides.put(id, null);
    }

    /**
     * Makes the technology load return no technology for the given id.
     */
    private void omitLoadedTechnology(final Long id) {
        loadedTechnologyOverrides.put(id, null);
    }

    /**
     * Returns the loaded order table: the order of every table position under its id, with the order load overrides applied.
     */
    private Map<Long, Entity> loadedOrderTable() {
        Map<Long, Entity> table = new LinkedHashMap<Long, Entity>();

        for (Entity position : positionTable) {
            Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);

            if (order != null) {
                table.put(order.getId(), order);
            }
        }

        applyOverrides(table, loadedOrderOverrides);

        return table;
    }

    /**
     * Returns the loaded technology table: the technology of every row of the loaded order table under its id, with the
     * technology load overrides applied.
     */
    private Map<Long, Entity> loadedTechnologyTable() {
        Map<Long, Entity> table = new LinkedHashMap<Long, Entity>();

        for (Entity order : loadedOrderTable().values()) {
            Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);

            if (technology != null) {
                table.put(technology.getId(), technology);
            }
        }

        applyOverrides(table, loadedTechnologyOverrides);

        return table;
    }

    /**
     * Puts every override with a value into the table under its id and removes the table row of every override without one.
     */
    private static void applyOverrides(final Map<Long, Entity> table, final Map<Long, Entity> overrides) {
        for (Map.Entry<Long, Entity> override : overrides.entrySet()) {
            if (override.getValue() == null) {
                table.remove(override.getKey());
            } else {
                table.put(override.getKey(), override.getValue());
            }
        }
    }


    /**
     * One recompute of one position: the chain caches and arguments the scheduling services and the executor received, and
     * the values the scheduling services answered. {@code finishDate} and {@code previousOrder} are the executor's
     * arguments.
     */
    private static final class Step {

        private final Entity line;

        private final Entity order;

        private final Map<Long, Date> finishCacheSeen;

        private Date scheduledFinishDate;

        private Entity position;

        private Date childrenInputDate;

        private Date childrenFinishDate;

        private Map<Long, Entity> orderCacheSeen;

        private Date previousOrderDate;

        private Entity previousOrderAnswered;

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
     * when the cache holds none, and starts a new recompute step holding a copy of the cache and the answered date.
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
            Step step = new Step(line, order, new HashMap<Long, Date>(finishCache));

            steps.add(step);

            Date cachedFinishDate = finishCache.get(line.getId());

            if (cachedFinishDate == null) {
                step.scheduledFinishDate = scheduleStartTime;
            } else {
                step.scheduledFinishDate = cachedFinishDate;
            }

            return step.scheduledFinishDate;
        }

    }

    /**
     * Answers {@link ProductionLineScheduleService#getFinishDateWithChildren} with the position's children end time from
     * {@code childrenEndTimes} when it is after the finish date received, and with the received finish date otherwise.
     * Requires the received finish date to be the one {@code getFinishDate} answered in the current recompute step, and
     * records the position, the received date and the answered date in that step.
     */
    private final class FinishDateWithChildrenAnswer implements Answer<Date> {

        @Override
        public Date answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Entity position = (Entity) arguments[0];
            Date finishDate = (Date) arguments[1];
            Step step = currentStep();

            assertNull("finish date with children requested twice", step.position);
            assertEquals("finish date with children requested for another finish date", step.scheduledFinishDate,
                    finishDate);

            step.position = position;
            step.childrenInputDate = finishDate;
            step.childrenFinishDate = finishDate;

            Date childrenEndTime = childrenEndTimes.get(position);

            if (childrenEndTime != null && childrenEndTime.after(finishDate)) {
                step.childrenFinishDate = childrenEndTime;
            }

            return step.childrenFinishDate;
        }

    }

    /**
     * Answers {@link ProductionLineScheduleService#getPreviousOrder} with the line's cached order or, when the cache holds
     * none, with the order of the order table on that line whose finish date is the latest at or before the received date,
     * or {@code null} when there is none. Requires the received date to be the one {@code getFinishDateWithChildren}
     * answered in the current recompute step, and records a copy of the cache, the received date and the answered order in
     * that step.
     */
    private final class PreviousOrderAnswer implements Answer<Entity> {

        @Override
        @SuppressWarnings("unchecked")
        public Entity answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            Map<Long, Entity> orderCache = (Map<Long, Entity>) arguments[0];
            Entity line = (Entity) arguments[1];
            Date orderStartDate = (Date) arguments[2];
            Step step = currentStep();

            assertSame(step.line, line);
            assertNotNull("previous order requested before finish date with children", step.position);
            assertNull("previous order requested twice", step.orderCacheSeen);
            assertEquals("previous order requested for another date", step.childrenFinishDate, orderStartDate);

            step.orderCacheSeen = new HashMap<Long, Entity>(orderCache);
            step.previousOrderDate = orderStartDate;
            step.previousOrderAnswered = orderCache.get(line.getId());

            if (step.previousOrderAnswered == null) {
                step.previousOrderAnswered = findLatestOrderFinishedBy(line, orderStartDate);
            }

            return step.previousOrderAnswered;
        }

        /**
         * Returns the order of the order table whose production line matches the given line by id and whose finish date is
         * the latest at or before the given date; among orders with equal latest finish dates, the first in table order; or
         * {@code null} when there is none.
         */
        private Entity findLatestOrderFinishedBy(final Entity line, final Date date) {
            Entity latestOrder = null;
            Date latestFinishDate = null;

            for (Entity order : orderTable) {
                Entity orderLine = order.getBelongsToField(OrderFields.PRODUCTION_LINE);
                Date orderFinishDate = order.getDateField(OrderFields.FINISH_DATE);

                if (orderLine == null || !line.getId().equals(orderLine.getId()) || orderFinishDate == null
                        || orderFinishDate.after(date)) {
                    continue;
                }
                if (latestFinishDate == null || orderFinishDate.after(latestFinishDate)) {
                    latestOrder = order;
                    latestFinishDate = orderFinishDate;
                }
            }

            return latestOrder;
        }

    }

    /**
     * Answers {@code createProductionLinePositionNewData} of an executor service: requires the finish date and previous
     * order to be the ones {@code getFinishDateWithChildren} and {@code getPreviousOrder} answered in the current recompute
     * step, records the call in that step and, while the fixture produces data, puts new data starting
     * {@value #CHANGEOVER_MINUTES} minutes after the finish date and ending {@value #DURATION_MINUTES} minutes after that
     * start under the id of the line it received, or of {@code executorDataLine} when that is set. The first call of a test
     * adds the rows created by {@code positionInsertedAtFirstCreate} to the position table.
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
            assertNotNull("executor called before previous order", step.orderCacheSeen);
            assertEquals("executor called with another finish date", step.childrenFinishDate, finishDate);
            assertSame("executor called with another previous order", step.previousOrderAnswered, arguments[5]);

            step.executor = executor;
            step.finishDate = finishDate;
            step.previousOrder = (Entity) arguments[5];

            if (!events.contains(CREATE_EVENT)) {
                positionTable.addAll(rowsInsertedAtFirstCreate);
            }

            events.add(CREATE_EVENT);

            if (executorProducesData) {
                Date startDate = plusMinutes(finishDate, CHANGEOVER_MINUTES);
                Entity dataLine = line;

                if (executorDataLine != null) {
                    dataLine = executorDataLine;
                }

                positionNewData.put(dataLine.getId(), new ProductionLinePositionNewData(startDate, plusMinutes(startDate,
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
     * its criteria and orders as a new position query. Reads no table row.
     */
    private final class PositionFindAnswer implements Answer<SearchCriteriaBuilder> {

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            List<Object> query = new ArrayList<Object>();

            positionQueries.add(query);

            return mock(SearchCriteriaBuilder.class, new PositionCriteriaBuilderAnswer(positionConditions(), positionOrders(),
                    query));
        }

    }

    /**
     * Records known criteria of {@code add}, known orders of {@code addOrder}, {@code createAlias(ORDER, ORDER,
     * JoinType.INNER)} as {@link #INNER_ORDER_ALIAS} and the limit of {@code setMaxResults}, and answers them with the builder
     * itself. Answers {@code list()} and {@code uniqueResult()} with the rows of the position table, as it is at that call,
     * that satisfy every recorded criterion and, when the inner order alias was recorded, have an order, sorted by the recorded
     * orders and cut to the limit, and records a read event. Fails on unknown criteria, unknown orders, any other
     * {@code createAlias} call (another association, alias or join type, or the two-argument form), a {@code uniqueResult()}
     * matching more than one row and every other {@link SearchCriteriaBuilder} method.
     */
    private final class PositionCriteriaBuilderAnswer implements Answer<Object> {

        private final Map<SearchCriterion, EntityCondition> conditions;

        private final Map<SearchOrder, Comparator<Entity>> orders;

        private final List<Object> query;

        private Integer maxResults;

        private PositionCriteriaBuilderAnswer(final Map<SearchCriterion, EntityCondition> conditions,
                final Map<SearchOrder, Comparator<Entity>> orders, final List<Object> query) {
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
            if ("createAlias".equals(methodName)) {
                List<Object> alias = Arrays.asList(arguments);

                if (!INNER_ORDER_ALIAS.equals(alias)) {
                    throw new AssertionError("Unexpected alias: " + alias);
                }

                query.add(INNER_ORDER_ALIAS);

                return invocation.getMock();
            }
            if ("setMaxResults".equals(methodName)) {
                maxResults = (Integer) arguments[0];

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                events.add(READ_EVENT);

                return new FixedSearchResult(select());
            }
            if ("uniqueResult".equals(methodName)) {
                events.add(READ_EVENT);

                List<Entity> selected = select();

                if (selected.size() > 1) {
                    throw new AssertionError("uniqueResult matched " + selected.size() + " rows");
                }
                if (selected.isEmpty()) {
                    return null;
                }

                return selected.get(0);
            }
            if (SearchCriteriaBuilder.class.equals(invocation.getMethod().getDeclaringClass())) {
                throw new AssertionError("Unexpected builder method: " + methodName);
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        private List<Entity> select() {
            List<Entity> selected = new ArrayList<Entity>();

            for (Entity row : positionTable) {
                if (matchesEveryCriterion(row) && matchesInnerOrderAlias(row)) {
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

        /**
         * Returns whether the row has an order, or {@code true} when the query recorded no inner order alias.
         */
        private boolean matchesInnerOrderAlias(final Entity row) {
            return !query.contains(INNER_ORDER_ALIAS)
                    || row.getBelongsToField(ProductionLineSchedulePositionFields.ORDER) != null;
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
     * Satisfied when the belongs-to field holds an entity with the given id.
     */
    private static final class BelongsToCondition implements EntityCondition {

        private final String fieldName;

        private final Long id;

        private BelongsToCondition(final String fieldName, final Long id) {
            this.fieldName = fieldName;
            this.id = id;
        }

        @Override
        public boolean matches(final Entity entity) {
            Entity value = entity.getBelongsToField(fieldName);

            return value != null && id.equals(value.getId());
        }

    }

    /**
     * Answers {@link DataAccessService#convertToDatabaseEntity} with a {@link DatabaseReference} to the id of the given
     * entity.
     */
    private static final class DatabaseReferenceAnswer implements Answer<DatabaseReference> {

        @Override
        public DatabaseReference answer(final InvocationOnMock invocation) {
            return new DatabaseReference(((Entity) invocation.getArguments()[0]).getId());
        }

    }

    /**
     * Database entity of a belongs-to criterion: equal to every reference with the same id, and written as {@code #<id>}.
     */
    private static final class DatabaseReference {

        private final Long id;

        private DatabaseReference(final Long id) {
            if (id == null) {
                throw new AssertionError("Entity without id converted to a database entity");
            }

            this.id = id;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof DatabaseReference && id.equals(((DatabaseReference) other).id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "#" + id;
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

    /**
     * Answers {@code find()} on the order or the technology data definition with a new {@link IdInCriteriaBuilderAnswer}
     * builder over the rows {@link #table()} returns at query time, records a new id query and adds the given find event.
     */
    private abstract class IdInFindAnswer implements Answer<SearchCriteriaBuilder> {

        private final String event;

        private final List<List<Long>> queries;

        IdInFindAnswer(final String event, final List<List<Long>> queries) {
            this.event = event;
            this.queries = queries;
        }

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            List<Long> ids = new ArrayList<Long>();

            queries.add(ids);
            events.add(event);

            return mock(SearchCriteriaBuilder.class, new IdInCriteriaBuilderAnswer(table(), ids));
        }

        /**
         * Returns the rows of the queried table under their ids.
         */
        protected abstract Map<Long, Entity> table();

    }

    /**
     * Accepts one {@code add} of an {@code id in (...)} criterion and records its ids, and answers {@code list()} with the table
     * rows whose id is one of them. Fails on any other criterion, on a second criterion, on {@code list()} without a criterion
     * and on every other builder method.
     */
    private static final class IdInCriteriaBuilderAnswer implements Answer<Object> {

        private final Map<Long, Entity> table;

        private final List<Long> ids;

        private boolean criterionAdded;

        private IdInCriteriaBuilderAnswer(final Map<Long, Entity> table, final List<Long> ids) {
            this.table = table;
            this.ids = ids;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();

            if ("add".equals(methodName)) {
                if (criterionAdded) {
                    throw new AssertionError("Unexpected second criterion");
                }

                ids.addAll(idsOf((SearchCriterion) invocation.getArguments()[0]));
                criterionAdded = true;

                return invocation.getMock();
            }
            if ("list".equals(methodName)) {
                if (!criterionAdded) {
                    throw new AssertionError("list() without an id criterion");
                }

                List<Entity> selected = new ArrayList<Entity>();

                for (Map.Entry<Long, Entity> row : table.entrySet()) {
                    if (ids.contains(row.getKey())) {
                        selected.add(row.getValue());
                    }
                }

                return new FixedSearchResult(selected);
            }
            if (SearchCriteriaBuilder.class.equals(invocation.getMethod().getDeclaringClass())) {
                throw new AssertionError("Unexpected builder method: " + methodName);
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        /**
         * Returns the values of an {@link InExpression} on the {@value #L_ID} property, read from its private fields, or fails
         * for any other criterion.
         */
        private static List<Long> idsOf(final SearchCriterion criterion) {
            Criterion hibernateCriterion = criterion.getHibernateCriterion();

            if (!(hibernateCriterion instanceof InExpression)
                    || !L_ID.equals(ReflectionTestUtils.getField(hibernateCriterion, IN_PROPERTY_NAME_FIELD))) {
                throw new AssertionError("Unexpected criterion: " + hibernateCriterion);
            }

            List<Long> criterionIds = new ArrayList<Long>();

            for (Object value : (Object[]) ReflectionTestUtils.getField(hibernateCriterion, IN_VALUES_FIELD)) {
                criterionIds.add((Long) value);
            }

            return criterionIds;
        }

    }

}
