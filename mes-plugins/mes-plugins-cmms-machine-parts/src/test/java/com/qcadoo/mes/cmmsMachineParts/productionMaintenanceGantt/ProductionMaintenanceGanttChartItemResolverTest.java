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

import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.BOARD_EVENTS_QUERY;
import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.DIVISION_LINES_QUERY;
import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.POSITIONS_QUERY;
import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.PRODUCTION_LINE_ROWS_QUERY;
import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.SCHEDULE_STATE_QUERY;
import static com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_EVENTS_QUERY;
import static com.qcadoo.testing.model.EntityTestUtils.mockEntity;
import static com.qcadoo.testing.model.EntityTestUtils.stubBelongsToField;
import static com.qcadoo.testing.model.EntityTestUtils.stubBooleanField;
import static com.qcadoo.testing.model.EntityTestUtils.stubDateField;
import static com.qcadoo.testing.model.EntityTestUtils.stubStringField;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.cmmsMachineParts.states.constants.PlannedEventStateStringValues;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.mes.productionLines.constants.DivisionFieldsPL;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchQueryBuilder;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemStrip;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemTooltip;
import com.qcadoo.view.api.components.ganttChart.GanttChartScale;

/**
 * Unit tests of {@link ProductionMaintenanceGanttChartItemResolver}.
 * <p>
 * The data definitions answer {@code find(String)} with a query builder that records the query, its bound parameters and
 * every call made on it, and answers {@code list()} or {@code uniqueResult()} with projection rows computed from the fixture
 * entities by the semantics of that query. The builder fails with an {@link AssertionError} on an unknown query, on a query
 * run on another data definition, on a missing, unexpected, repeated or differently bound parameter, on a result read other
 * than the query's one, and on every other call; the criteria {@code find()} and {@code findWithAlias(String)} fail as
 * well. A projection row answers only the typed getters of the aliases its query selects, and a query result answers only
 * {@code getEntities()}.
 * <p>
 * The scale records every created item and answers with a mocked item exposing the recorded row name, label, tooltip and
 * entity id. Translations answer with their code, followed by their arguments in brackets when there are any. The warnings
 * the resolver logs are recorded.
 */
public class ProductionMaintenanceGanttChartItemResolverTest {

    private static final Long SCHEDULE_ID = 7L;

    private static final String SCALE_FROM = "2026-09-28 00:00:00";

    private static final String SCALE_TO = "2026-10-05 00:00:00";

    private static final String EVENT_TYPE = "01review";

    private static final String EVENT_TYPE_PREFIX = "cmmsMachineParts.plannedEvent.type.value.";

    private static final String EVENT_STATE_PREFIX = "cmmsMachineParts.plannedEvent.state.value.";

    private static final String TRUE_KEY = "qcadooView.true";

    private static final String FALSE_KEY = "qcadooView.false";

    private static final int STRIP_SIZE = 100;

    private static final String INVALID_SCHEDULE_ID_MESSAGE = "Invalid production line schedule id in Gantt context: ";

    private static final String UNKNOWN_SCHEDULE_MESSAGE = "Cannot find production line schedule for ";

    private static final int LOG_VALUE_MAX_LENGTH = 64;

    private static final String SCHEDULE_ID_PARAMETER = "scheduleId";

    private static final String DATE_FROM_PARAMETER = "dateFrom";

    private static final String DATE_TO_PARAMETER = "dateTo";

    private static final String PRODUCTION_PARAMETER = "production";

    private static final String ACTIVE_PARAMETER = "active";

    private static final String REQUIRES_SHUTDOWN_PARAMETER = "requiresShutdown";

    private static final String PRODUCTION_LINE_ID_PARAMETER = "productionLineId";

    private static final String DIVISION_IDS_PARAMETER = "divisionIds";

    private static final String SET_LONG = "setLong";

    private static final String SET_BOOLEAN = "setBoolean";

    private static final String SET_TIMESTAMP = "setTimestamp";

    private static final String SET_PARAMETER_LIST = "setParameterList";

    private static final String LIST = "list";

    private static final String UNIQUE_RESULT = "uniqueResult";

    private static final String SCHEDULE_IDENTIFIER_ALIAS = "scheduleIdentifier";

    private static final String STATE_ALIAS = "state";

    private static final String PRODUCTION_LINE_ID_ALIAS = "productionLineId";

    private static final String NUMBER_ALIAS = "number";

    private static final String POSITION_ID_ALIAS = "positionId";

    private static final String START_TIME_ALIAS = "startTime";

    private static final String END_TIME_ALIAS = "endTime";

    private static final String PRODUCTION_LINE_NUMBER_ALIAS = "productionLineNumber";

    private static final String ORDER_NUMBER_ALIAS = "orderNumber";

    private static final String PRODUCT_NUMBER_ALIAS = "productNumber";

    private static final String PRODUCT_NAME_ALIAS = "productName";

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

    private static final Pattern ALIAS_PATTERN = Pattern.compile(" as (\\w+)");

    private final Locale locale = Locale.ENGLISH;

    private ProductionMaintenanceGanttChartItemResolver resolver;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private TranslationService translationService;

    @Mock
    private DataDefinition scheduleDD, positionDD, productionLineDD, plannedEventDD, divisionDD;

    @Mock
    private GanttChartScale scale;

    private Entity schedule;

    private Entity lineL1, lineL2, lineL3;

    private Date scaleFrom, scaleTo;

    private JSONObject context;

    private long nextEventId;

    private long nextDivisionId;

    private Logger resolverLogger;

    private Level previousLogLevel;

    private boolean previousLogAdditivity;

    private final RecordingAppender logAppender = new RecordingAppender();

    private final List<Entity> schedules = new ArrayList<Entity>();

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> positions = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<Entity> divisions = new ArrayList<Entity>();

    private final Map<String, QuerySpec> querySpecs = new HashMap<String, QuerySpec>();

    private final Map<String, Runnable> afterQueryActions = new HashMap<String, Runnable>();

    private final List<RecordedQuery> recordedQueries = new ArrayList<RecordedQuery>();

    private final List<RecordedItem> recordedItems = new ArrayList<RecordedItem>();

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);

        resolver = new ProductionMaintenanceGanttChartItemResolver();

        setField(resolver, "dataDefinitionService", dataDefinitionService);
        setField(resolver, "translationService", translationService);

        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE))
                .willReturn(scheduleDD);
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION)).willReturn(positionDD);
        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE)).willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT)).willReturn(plannedEventDD);
        given(dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_DIVISION)).willReturn(divisionDD);

        querySpecs.put(SCHEDULE_STATE_QUERY, new QuerySpec(scheduleDD, UNIQUE_RESULT, SCHEDULE_ID_PARAMETER, SET_LONG));
        querySpecs.put(PRODUCTION_LINE_ROWS_QUERY, new QuerySpec(productionLineDD, LIST, PRODUCTION_PARAMETER, SET_BOOLEAN,
                ACTIVE_PARAMETER, SET_BOOLEAN));
        querySpecs.put(POSITIONS_QUERY, new QuerySpec(positionDD, LIST, SCHEDULE_ID_PARAMETER, SET_LONG, DATE_FROM_PARAMETER,
                SET_TIMESTAMP, DATE_TO_PARAMETER, SET_TIMESTAMP));
        querySpecs.put(BOARD_EVENTS_QUERY, new QuerySpec(plannedEventDD, LIST, DATE_FROM_PARAMETER, SET_TIMESTAMP,
                DATE_TO_PARAMETER, SET_TIMESTAMP));
        querySpecs.put(SHUTDOWN_EVENTS_QUERY, new QuerySpec(plannedEventDD, LIST, DATE_FROM_PARAMETER, SET_TIMESTAMP,
                DATE_TO_PARAMETER, SET_TIMESTAMP, REQUIRES_SHUTDOWN_PARAMETER, SET_BOOLEAN, PRODUCTION_LINE_ID_PARAMETER,
                SET_LONG));
        querySpecs.put(DIVISION_LINES_QUERY, new QuerySpec(divisionDD, LIST, DIVISION_IDS_PARAMETER, SET_PARAMETER_LIST));

        for (DataDefinition dataDefinition : dataDefinitions()) {
            stubQueries(dataDefinition);
        }

        schedule = mockEntity(SCHEDULE_ID, scheduleDD);
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.DRAFT);

        schedules.add(schedule);

        lineL1 = productionLine(1L, "L1");
        lineL2 = productionLine(2L, "L2");
        lineL3 = productionLine(3L, "L3");

        productionLines.addAll(Arrays.asList(lineL1, lineL2, lineL3));

        scaleFrom = date(SCALE_FROM);
        scaleTo = date(SCALE_TO);

        given(scale.getDateFrom()).willReturn(scaleFrom);
        given(scale.getDateTo()).willReturn(scaleTo);
        given(
                scale.createGanttChartItem(Matchers.any(String.class), Matchers.any(String.class),
                        Matchers.any(GanttChartItemTooltip.class), Matchers.any(Long.class), Matchers.any(Date.class),
                        Matchers.any(Date.class))).willAnswer(new CreateItemAnswer(recordedItems));
        given(
                scale.createGanttChartItem(Matchers.any(String.class), Matchers.any(String.class), Matchers.any(Long.class),
                        Matchers.any(Date.class), Matchers.any(Date.class))).willAnswer(new CreateItemAnswer(recordedItems));

        given(translationService.translate(Matchers.anyString(), Matchers.any(Locale.class), Matchers.<String> anyVararg()))
                .willAnswer(new TranslationAnswer());

        context = scheduleContext(String.valueOf(SCHEDULE_ID));

        nextEventId = 100L;
        nextDivisionId = 200L;

        resolverLogger = Logger.getLogger(ProductionMaintenanceGanttChartItemResolver.class);
        previousLogLevel = resolverLogger.getLevel();
        previousLogAdditivity = resolverLogger.getAdditivity();

        resolverLogger.setLevel(Level.WARN);
        resolverLogger.setAdditivity(false);
        resolverLogger.addAppender(logAppender);
    }

    @After
    public void restoreResolverLogger() {
        resolverLogger.removeAppender(logAppender);
        resolverLogger.setLevel(previousLogLevel);
        resolverLogger.setAdditivity(previousLogAdditivity);
    }

    @Test
    public final void shouldPlacePositionsAndPlannedEventsOnTheirProductionLineRows() {
        // given
        positions.add(position(11L, lineL1, order("ORD-1", product("P-1", "Product 1")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-1", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL2, null, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        assertEquals(1, items.get("L1").size());
        assertEquals("L1", items.get("L1").get(0).getRowName());
        assertEquals("ORD-1", items.get("L1").get(0).getName());
        assertEquals(Long.valueOf(11L), items.get("L1").get(0).getEntityId());

        assertEquals(1, items.get("L2").size());
        assertEquals("L2", items.get("L2").get(0).getRowName());
        assertEquals("EV-1", items.get("L2").get(0).getName());
        assertNull(items.get("L2").get(0).getEntityId());

        assertTrue(items.get("L3").isEmpty());

        assertEquals(2, recordedItems.size());
        assertEquals("L1", recordedItemLabelled("ORD-1").rowName);
        assertEquals("L2", recordedItemLabelled("EV-1").rowName);
    }

    @Test
    public final void shouldPlaceWorkstationOnlyEventOnWorkstationLine() {
        // given
        plannedEvents.add(plannedEvent("EV-WS", PlannedEventStateStringValues.NEW, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, workstation(lineL3), division(lineL1, lineL2)));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(items.get("L1").isEmpty());
        assertTrue(items.get("L2").isEmpty());
        assertEquals(1, items.get("L3").size());
        assertEquals("EV-WS", items.get("L3").get(0).getName());
        assertEquals("L3", items.get("L3").get(0).getRowName());

        assertEquals(1, recordedItems.size());
        assertEquals("L3", recordedItems.get(0).rowName);
        assertTrue(queriesOf(DIVISION_LINES_QUERY).isEmpty());
    }

    @Test
    public final void shouldPlaceDivisionOnlyEventOnEveryLineOfItsDivision() {
        // given
        Entity division = division(lineL1, lineL2);

        plannedEvents.add(plannedEvent("EV-DIV", PlannedEventStateStringValues.PLANNED, true, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", null, null, division));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(1, items.get("L1").size());
        assertEquals("EV-DIV", items.get("L1").get(0).getName());
        assertEquals("L1", items.get("L1").get(0).getRowName());
        assertNull(items.get("L1").get(0).getEntityId());

        assertEquals(1, items.get("L2").size());
        assertEquals("EV-DIV", items.get("L2").get(0).getName());
        assertEquals("L2", items.get("L2").get(0).getRowName());
        assertNull(items.get("L2").get(0).getEntityId());

        assertNotSame(items.get("L1").get(0), items.get("L2").get(0));
        assertTrue(items.get("L3").isEmpty());

        assertEquals(2, recordedItems.size());
        assertEquals(Arrays.asList("L1", "L2"), recordedRowNames());

        assertEquals(1, queriesOf(DIVISION_LINES_QUERY).size());
        assertEquals(mapOf(DIVISION_IDS_PARAMETER, Collections.singletonList(division.getId())),
                queriesOf(DIVISION_LINES_QUERY).get(0).parameters);
    }

    @Test
    public final void shouldOmitEventWhoseDivisionHasNoLines() {
        // given
        plannedEvents.add(plannedEvent("EV-EMPTY", PlannedEventStateStringValues.PLANNED, true, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", null, null, division()));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        for (List<GanttChartItem> row : items.values()) {
            assertTrue(row.isEmpty());
        }

        assertTrue(recordedItems.isEmpty());

        verify(scale, never()).createGanttChartItem(Matchers.any(String.class), Matchers.any(String.class),
                Matchers.any(GanttChartItemTooltip.class), Matchers.any(Long.class), Matchers.any(Date.class),
                Matchers.any(Date.class));

        assertTrue(queriesOf(BOARD_EVENTS_QUERY).get(0).rows.isEmpty());
        assertTrue(queriesOf(DIVISION_LINES_QUERY).isEmpty());
    }

    @Test
    public final void shouldSeedEmptyRowsForActiveProductionLines() {
        // given
        productionLines.clear();
        productionLines.addAll(Arrays.asList(lineL3, productionLine(8L, "L0", true, false), lineL1,
                productionLine(9L, "L9", false, true), lineL2));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(items instanceof TreeMap);
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        for (List<GanttChartItem> row : items.values()) {
            assertTrue(row.isEmpty());
        }

        List<RecordedQuery> productionLineQueries = queriesOf(PRODUCTION_LINE_ROWS_QUERY);

        assertEquals(1, productionLineQueries.size());
        assertEquals(mapOf(PRODUCTION_PARAMETER, true, ACTIVE_PARAMETER, true), productionLineQueries.get(0).parameters);
    }

    @Test
    public final void shouldNotSeedRowForProductionLineWithoutNumber() {
        // given
        productionLines.add(productionLine(4L, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));
    }

    @Test
    public final void shouldCreateMaintenanceItemsWithoutEntityId() {
        // given
        plannedEvents.add(plannedEvent("EV-NEW", PlannedEventStateStringValues.NEW, false, "2026-09-28 06:00:00",
                "2026-09-28 07:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-PLANNED", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 06:00:00",
                "2026-09-29 07:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-REALIZED", PlannedEventStateStringValues.REALIZED, false, "2026-09-30 06:00:00",
                "2026-09-30 07:00:00", null, workstation(lineL3), null));
        plannedEvents.add(plannedEvent("EV-CANCELED", PlannedEventStateStringValues.CANCELED, true, "2026-10-01 06:00:00",
                "2026-10-01 07:00:00", null, null, division(lineL1, lineL3)));
        plannedEvents.add(plannedEvent("EV-EDITING", PlannedEventStateStringValues.IN_EDITING, false,
                "2026-10-02 06:00:00", "2026-10-02 07:00:00", lineL3, null, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(6, recordedItems.size());

        for (RecordedItem recordedItem : recordedItems) {
            assertNull(recordedItem.entityId);
        }

        for (List<GanttChartItem> row : items.values()) {
            for (GanttChartItem item : row) {
                assertNull(item.getEntityId());
            }
        }

        assertEquals(Arrays.asList("L1", "L3"), rowNamesOf(recordedItemsLabelled("EV-CANCELED")));

        List<RecordedQuery> plannedEventQueries = queriesOf(BOARD_EVENTS_QUERY);

        assertEquals(1, plannedEventQueries.size());
        assertEquals(mapOf(DATE_FROM_PARAMETER, scaleFrom, DATE_TO_PARAMETER, scaleTo), plannedEventQueries.get(0).parameters);
    }

    @Test
    public final void shouldUsePersistedStartAndEndAsItemBounds() {
        // given
        positions.add(position(21L, lineL1, order("ORD-B", product("P-B", "Bounds")), "2026-09-27 22:00:00",
                "2026-09-28 03:30:00"));
        plannedEvents.add(plannedEvent("EV-B", PlannedEventStateStringValues.PLANNED, false, "2026-10-04 20:00:00",
                "2026-10-05 04:00:00", lineL2, null, null));

        // when
        resolver.resolve(scale, context, locale);

        // then
        RecordedItem positionItem = recordedItemLabelled("ORD-B");

        assertEquals(date("2026-09-27 22:00:00"), positionItem.dateFrom);
        assertEquals(date("2026-09-28 03:30:00"), positionItem.dateTo);

        RecordedItem eventItem = recordedItemLabelled("EV-B");

        assertEquals(date("2026-10-04 20:00:00"), eventItem.dateFrom);
        assertEquals(date("2026-10-05 04:00:00"), eventItem.dateTo);
    }

    @Test
    public final void shouldEscapeUserEnteredDisplayStrings() {
        // given
        Entity escapedLine = productionLine(5L, "L&1");

        productionLines.add(escapedLine);
        positions.add(position(31L, escapedLine, order("<b>ORD</b>", product("P<1>", "A & B")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("<i>E1", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", escapedLine, null, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(items.containsKey("L&1"));
        assertFalse(items.containsKey("L&amp;1"));
        assertEquals(2, items.get("L&1").size());

        RecordedItem positionItem = recordedItemLabelled("&lt;b&gt;ORD&lt;/b&gt;");

        assertEquals("L&1", positionItem.rowName);
        assertEquals("&lt;b&gt;ORD&lt;/b&gt;", positionItem.tooltip.getHeader().get());
        assertEquals(1, positionItem.tooltip.getContent().size());
        assertTrue(positionItem.tooltip.getContent().get(0).contains("A &amp; B"));
        assertEquals(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "P&lt;1&gt;", "A &amp; B"),
                positionItem.tooltip.getContent().get(0));

        RecordedItem eventItem = recordedItemLabelled("&lt;i&gt;E1");

        assertEquals("L&1", eventItem.rowName);
        assertEquals("&lt;i&gt;E1", eventItem.tooltip.getHeader().get());
    }

    @Test
    public final void shouldCreatePositionItemWithPositionIdForDraftSchedule() {
        // given
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.DRAFT);

        positions.add(position(41L, lineL1, order("ORD-D", product("P-D", "Draft")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Long.valueOf(41L), recordedItemLabelled("ORD-D").entityId);
        assertEquals(Long.valueOf(41L), items.get("L1").get(0).getEntityId());
    }

    @Test
    public final void shouldCreatePositionItemWithoutEntityIdForApprovedSchedule() {
        // given
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.APPROVED);

        positions.add(position(42L, lineL1, order("ORD-A", product("P-A", "Approved")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(1, items.get("L1").size());
        assertNull(recordedItemLabelled("ORD-A").entityId);
        assertNull(items.get("L1").get(0).getEntityId());
    }

    @Test
    public final void shouldCreatePositionItemWithoutEntityIdForRejectedSchedule() {
        // given
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.REJECTED);

        positions.add(position(43L, lineL2, order("ORD-R", product("P-R", "Rejected")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(1, items.get("L2").size());
        assertNull(recordedItemLabelled("ORD-R").entityId);
        assertNull(items.get("L2").get(0).getEntityId());
    }

    @Test
    public final void shouldQueryPositionsOfContextScheduleOverlappingScale() {
        // given
        Entity otherSchedule = mockEntity(8L, scheduleDD);

        stubStringField(otherSchedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.DRAFT);
        schedules.add(otherSchedule);

        positions.add(position(103L, lineL2, order("ORD-END", product("P-END", "Scale end")), "2026-10-04 20:00:00",
                "2026-10-05 02:00:00"));
        positions.add(position(101L, lineL1, order("ORD-START", product("P-START", "Scale start")), "2026-09-27 20:00:00",
                "2026-09-28 02:00:00"));
        positions.add(position(102L, lineL1, order("ORD-BEFORE", product("P-BEFORE", "Before")), "2026-09-27 20:00:00",
                SCALE_FROM));
        positions.add(position(104L, lineL3, order("ORD-AFTER", product("P-AFTER", "After")), SCALE_TO,
                "2026-10-05 04:00:00"));
        positions.add(position(otherSchedule, 105L, lineL1, order("ORD-OTHER", product("P-OTHER", "Other schedule")),
                "2026-09-29 08:00:00", "2026-09-29 12:00:00"));

        // when
        resolver.resolve(scale, context, locale);

        // then
        List<RecordedQuery> scheduleQueries = queriesOf(SCHEDULE_STATE_QUERY);

        assertEquals(1, scheduleQueries.size());
        assertEquals(mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID), scheduleQueries.get(0).parameters);

        List<RecordedQuery> positionQueries = queriesOf(POSITIONS_QUERY);

        assertEquals(1, positionQueries.size());
        assertEquals(mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID, DATE_FROM_PARAMETER, scaleFrom, DATE_TO_PARAMETER, scaleTo),
                positionQueries.get(0).parameters);
        assertEquals(Arrays.asList("ORD-START", "ORD-END"), stringFieldsOf(positionQueries.get(0).rows, ORDER_NUMBER_ALIAS));
        assertEquals(Arrays.asList("ORD-START", "ORD-END"), recordedLabels());
    }

    @Test
    public final void shouldReturnEmptyMapWhenContextHasNoScheduleId() {
        // given
        JSONObject contextWithoutScheduleId = contextOf("orderId", String.valueOf(SCHEDULE_ID));

        positions.add(position(141L, lineL1, order("ORD-C1", product("P-C1", "Context")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, contextWithoutScheduleId, locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenContextIsNull() {
        // given
        JSONObject nullContext = null;

        positions.add(position(142L, lineL1, order("ORD-C2", product("P-C2", "Context")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, nullContext, locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIdIsBlank() {
        // given
        JSONObject blankIdContext = scheduleContext("  ");

        positions.add(position(143L, lineL1, order("ORD-C3", product("P-C3", "Context")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, blankIdContext, locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIdIsMalformed() {
        // given
        JSONObject malformedIdContext = scheduleContext("7a");

        positions.add(position(144L, lineL1, order("ORD-C4", product("P-C4", "Context")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, malformedIdContext, locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
        verifyZeroInteractions(dataDefinitionService, scheduleDD, positionDD, productionLineDD, plannedEventDD, divisionDD);
        assertEquals(Collections.singletonList(INVALID_SCHEDULE_ID_MESSAGE + "\"7a\""), loggedWarnings());
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIsUnknown() {
        // given
        JSONObject unknownIdContext = scheduleContext("8");

        positions.add(position(145L, lineL1, order("ORD-C5", product("P-C5", "Context")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, unknownIdContext, locale);

        // then
        assertTrue(items.isEmpty());
        assertEquals(1, queriesOf(SCHEDULE_STATE_QUERY).size());
        assertEquals(mapOf(SCHEDULE_ID_PARAMETER, 8L), queriesOf(SCHEDULE_STATE_QUERY).get(0).parameters);
        verifyNoItemQueried();
        assertEquals(Collections.singletonList(UNKNOWN_SCHEDULE_MESSAGE + "8"), loggedWarnings());
    }

    @Test
    public final void shouldReadScheduleIdGivenAsNumberOrPaddedString() {
        // given
        positions.add(position(44L, lineL1, order("ORD-N", product("P-N", "Number")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> numberItems = resolver.resolve(scale, scheduleContext(SCHEDULE_ID), locale);
        Map<String, List<GanttChartItem>> paddedItems = resolver.resolve(scale, scheduleContext(" 7 "), locale);

        // then
        assertEquals(1, numberItems.get("L1").size());
        assertEquals(1, paddedItems.get("L1").size());

        List<RecordedQuery> scheduleQueries = queriesOf(SCHEDULE_STATE_QUERY);

        assertEquals(2, scheduleQueries.size());
        assertEquals(mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID), scheduleQueries.get(0).parameters);
        assertEquals(mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID), scheduleQueries.get(1).parameters);
    }

    @Test
    public final void shouldAddRowForProductionLineReferencedByItemButMissingFromSeed() {
        // given
        productionLines.clear();
        productionLines.add(lineL1);

        positions.add(position(51L, lineL2, order("ORD-X", product("P-X", "Extra")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-X", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL3, null, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));
        assertTrue(items.get("L1").isEmpty());
        assertEquals("ORD-X", items.get("L2").get(0).getName());
        assertEquals("EV-X", items.get("L3").get(0).getName());
    }

    @Test
    public final void shouldAddShutdownAndMaintenanceStripsToPlannedEventItems() {
        // given
        positions.add(position(61L, lineL3, order("ORD-S", product("P-S", "Strip")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-SHUTDOWN", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-MAINTENANCE", PlannedEventStateStringValues.PLANNED, false,
                "2026-09-29 06:00:00", "2026-09-29 10:00:00", lineL2, null, null));

        // when
        resolver.resolve(scale, context, locale);

        // then
        GanttChartItemStrip shutdownStrip = captureSingleStrip(recordedItemLabelled("EV-SHUTDOWN").item);

        assertEquals(ProductionMaintenanceGanttChartItemResolver.SHUTDOWN_COLOR, shutdownStrip.getColor());
        assertEquals(STRIP_SIZE, shutdownStrip.getSize());

        GanttChartItemStrip maintenanceStrip = captureSingleStrip(recordedItemLabelled("EV-MAINTENANCE").item);

        assertEquals(ProductionMaintenanceGanttChartItemResolver.MAINTENANCE_COLOR, maintenanceStrip.getColor());
        assertEquals(STRIP_SIZE, maintenanceStrip.getSize());

        verify(recordedItemLabelled("ORD-S").item, never()).addBackgroundStrip(Matchers.any(GanttChartItemStrip.class));
    }

    @Test
    public final void shouldDescribePlannedEventTypeStateAndShutdownInTooltip() {
        // given
        Entity shutdownEvent = plannedEvent("EV-T1", PlannedEventStateStringValues.CANCELED, true, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL1, null, null);

        stubStringField(shutdownEvent, PlannedEventFields.TYPE, "02repairs&co");

        plannedEvents.add(shutdownEvent);
        plannedEvents.add(plannedEvent("EV-T2", PlannedEventStateStringValues.NEW, false, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", lineL2, null, null));

        // when
        resolver.resolve(scale, context, locale);

        // then
        GanttChartItemTooltip shutdownTooltip = recordedItemLabelled("EV-T1").tooltip;

        assertEquals("EV-T1", shutdownTooltip.getHeader().get());
        assertEquals(Arrays.asList(
                translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PLANNED_EVENT_KEY, EVENT_TYPE_PREFIX
                        + "02repairs&amp;co", EVENT_STATE_PREFIX + PlannedEventStateStringValues.CANCELED),
                translated(ProductionMaintenanceGanttChartItemResolver.ITEM_REQUIRES_SHUTDOWN_KEY, TRUE_KEY)),
                shutdownTooltip.getContent());

        GanttChartItemTooltip maintenanceTooltip = recordedItemLabelled("EV-T2").tooltip;

        assertEquals("EV-T2", maintenanceTooltip.getHeader().get());
        assertEquals(Arrays.asList(
                translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PLANNED_EVENT_KEY, EVENT_TYPE_PREFIX + EVENT_TYPE,
                        EVENT_STATE_PREFIX + PlannedEventStateStringValues.NEW),
                translated(ProductionMaintenanceGanttChartItemResolver.ITEM_REQUIRES_SHUTDOWN_KEY, FALSE_KEY)),
                maintenanceTooltip.getContent());
    }

    @Test
    public final void shouldDescribePositionOrderAndProductInTooltip() {
        // given
        positions.add(position(71L, lineL1, order("ORD-T", product("P-T", "Tooltip product")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        resolver.resolve(scale, context, locale);

        // then
        GanttChartItemTooltip tooltip = recordedItemLabelled("ORD-T").tooltip;

        assertEquals("ORD-T", tooltip.getHeader().get());
        assertEquals(
                Collections.singletonList(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "P-T",
                        "Tooltip product")), tooltip.getContent());
    }

    @Test
    public final void shouldUseEmptyLabelAndProductForPositionWithoutOrderOrProduct() {
        // given
        positions.add(position(81L, lineL1, null, "2026-09-28 08:00:00", "2026-09-28 12:00:00"));
        positions.add(position(82L, lineL2, order("ORD-NP", null), "2026-09-28 08:00:00", "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(2, recordedItems.size());

        RecordedItem noOrderItem = recordedItemLabelled("");

        assertEquals("L1", noOrderItem.rowName);
        assertEquals(Long.valueOf(81L), noOrderItem.entityId);
        assertEquals(Collections.singletonList(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "", "")),
                noOrderItem.tooltip.getContent());

        RecordedItem noProductItem = recordedItemLabelled("ORD-NP");

        assertEquals("L2", noProductItem.rowName);
        assertEquals(Collections.singletonList(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "", "")),
                noProductItem.tooltip.getContent());

        assertEquals(1, items.get("L1").size());
        assertEquals(1, items.get("L2").size());
    }

    @Test
    public final void shouldSkipPositionWithoutProductionLine() {
        // given
        positions.add(position(91L, null, order("ORD-NL", product("P-NL", "No line")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(recordedItems.isEmpty());
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));
        assertTrue(queriesOf(POSITIONS_QUERY).get(0).rows.isEmpty());
    }

    @Test
    public final void shouldSkipItemsOnProductionLineWithoutNumber() {
        // given
        Entity unnumberedLine = productionLine(6L, null);

        positions.add(position(92L, unnumberedLine, order("ORD-UN", product("P-UN", "Unnumbered")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-UN", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, null, division(unnumberedLine, lineL2)));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));
        assertEquals(1, recordedItems.size());
        assertEquals("L2", recordedItemLabelled("EV-UN").rowName);
        assertEquals(1, items.get("L2").size());
    }

    @Test
    public final void shouldSkipItemsTheScaleDoesNotCreate() {
        // given
        doReturn(null).when(scale).createGanttChartItem(Matchers.any(String.class), Matchers.any(String.class),
                Matchers.any(GanttChartItemTooltip.class), Matchers.any(Long.class), Matchers.any(Date.class),
                Matchers.any(Date.class));

        positions.add(position(93L, lineL1, order("ORD-NULL", product("P-NULL", "Null item")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-NULL", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL2, null, null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        for (List<GanttChartItem> row : items.values()) {
            assertTrue(row.isEmpty());
        }

        verify(scale, times(2)).createGanttChartItem(Matchers.any(String.class), Matchers.any(String.class),
                Matchers.any(GanttChartItemTooltip.class), Matchers.any(Long.class), Matchers.any(Date.class),
                Matchers.any(Date.class));
    }

    @Test
    public final void shouldUseEmptyTooltipLinesWhenTranslationsAreMissing() {
        // given
        doReturn(null).when(translationService).translate(Matchers.anyString(), Matchers.any(Locale.class),
                Matchers.<String> anyVararg());

        positions.add(position(94L, lineL1, order("ORD-TR", product("P-TR", "Translation")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-TR", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL2, null, null));

        // when
        resolver.resolve(scale, context, locale);

        // then
        assertEquals(Collections.singletonList(""), recordedItemLabelled("ORD-TR").tooltip.getContent());
        assertEquals(Arrays.asList("", ""), recordedItemLabelled("EV-TR").tooltip.getContent());
    }

    @Test
    public final void shouldResolveEventLinesToOwnProductionLine() {
        // given
        Entity event = plannedEvent("EV-L", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL1, workstation(lineL2), division(lineL2, lineL3));

        // when
        List<Entity> eventLines = resolver.resolveEventLines(event);

        // then
        assertEquals(Collections.singletonList(lineL1), eventLines);
    }

    @Test
    public final void shouldResolveEventLinesToWorkstationLineWhenEventHasNoLine() {
        // given
        Entity event = plannedEvent("EV-W", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, workstation(lineL3), division(lineL1, lineL2));

        // when
        List<Entity> eventLines = resolver.resolveEventLines(event);

        // then
        assertEquals(Collections.singletonList(lineL3), eventLines);
    }

    @Test
    public final void shouldResolveEventLinesToDivisionLinesWhenEventHasNeitherLineNorWorkstation() {
        // given
        Entity event = plannedEvent("EV-D", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, null, division(lineL1, lineL2));

        // when
        List<Entity> eventLines = resolver.resolveEventLines(event);

        // then
        assertEquals(Arrays.asList(lineL1, lineL2), eventLines);
    }

    @Test
    public final void shouldResolveEventLinesToDivisionLinesWhenWorkstationHasNoLine() {
        // given
        Entity event = plannedEvent("EV-WD", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, workstation(null), division(lineL2, lineL3));

        // when
        List<Entity> eventLines = resolver.resolveEventLines(event);

        // then
        assertEquals(Arrays.asList(lineL2, lineL3), eventLines);
    }

    @Test
    public final void shouldResolveNoEventLinesForDivisionWithoutLines() {
        // given
        Entity emptyDivisionEvent = plannedEvent("EV-E", PlannedEventStateStringValues.PLANNED, false,
                "2026-09-29 06:00:00", "2026-09-29 10:00:00", null, workstation(null), division());

        Entity nullLinesDivision = mockEntity(301L);

        given(nullLinesDivision.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)).willReturn(null);

        Entity nullLinesDivisionEvent = plannedEvent("EV-N", PlannedEventStateStringValues.PLANNED, false,
                "2026-09-29 06:00:00", "2026-09-29 10:00:00", null, null, nullLinesDivision);

        // when
        List<Entity> emptyDivisionLines = resolver.resolveEventLines(emptyDivisionEvent);
        List<Entity> nullDivisionLines = resolver.resolveEventLines(nullLinesDivisionEvent);

        // then
        assertTrue(emptyDivisionLines.isEmpty());
        assertTrue(nullDivisionLines.isEmpty());
    }

    @Test
    public final void shouldResolveNoEventLinesWithoutLineWorkstationOrDivision() {
        // given
        Entity event = plannedEvent("EV-0", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, null, null);

        // when
        List<Entity> eventLines = resolver.resolveEventLines(event);

        // then
        assertTrue(eventLines.isEmpty());
    }

    @Test
    public final void shouldReturnEscapedOrderNumberAsPositionLabel() {
        // given
        Entity position = position(95L, lineL1, order("<b>ORD</b> & \"1\"", product("P-L", "Label")),
                "2026-09-28 08:00:00", "2026-09-28 12:00:00");

        // when
        String label = resolver.positionLabel(position);

        // then
        assertEquals("&lt;b&gt;ORD&lt;/b&gt; &amp; &quot;1&quot;", label);
    }

    @Test
    public final void shouldReturnEmptyPositionLabelWithoutOrderOrOrderNumber() {
        // given
        Entity positionWithoutOrder = position(96L, lineL1, null, "2026-09-28 08:00:00", "2026-09-28 12:00:00");
        Entity positionWithoutOrderNumber = position(97L, lineL1, order(null, product("P-E", "Empty")),
                "2026-09-28 08:00:00", "2026-09-28 12:00:00");

        // when
        String labelWithoutOrder = resolver.positionLabel(positionWithoutOrder);
        String labelWithoutOrderNumber = resolver.positionLabel(positionWithoutOrderNumber);

        // then
        assertEquals("", labelWithoutOrder);
        assertEquals("", labelWithoutOrderNumber);
    }

    @Test
    public final void shouldUsePositionLabelAsPositionItemLabel() {
        // given
        Entity position = position(98L, lineL2, order("ORD&LBL", product("P-LBL", "Label item")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00");

        positions.add(position);

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(resolver.positionLabel(position), items.get("L2").get(0).getName());
        assertEquals("ORD&amp;LBL", items.get("L2").get(0).getName());
        assertSame(recordedItemLabelled("ORD&amp;LBL").item, items.get("L2").get(0));
    }

    @Test
    public final void shouldReadBoardThroughProjectionQueriesWithoutRowCountOrPaging() {
        // given
        Entity division = division(lineL1, lineL3);

        positions.add(position(111L, lineL1, order("ORD-Q", product("P-Q", "Query")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        plannedEvents.add(plannedEvent("EV-Q-LINE", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-Q-DIV", PlannedEventStateStringValues.NEW, true, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", null, null, division));

        // when
        resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList(SCHEDULE_STATE_QUERY, PRODUCTION_LINE_ROWS_QUERY, POSITIONS_QUERY, BOARD_EVENTS_QUERY,
                DIVISION_LINES_QUERY), recordedHql());

        assertQuery(recordedQueries.get(0), Arrays.asList(SET_LONG, UNIQUE_RESULT), mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID));
        assertQuery(recordedQueries.get(1), Arrays.asList(SET_BOOLEAN, SET_BOOLEAN, LIST),
                mapOf(PRODUCTION_PARAMETER, true, ACTIVE_PARAMETER, true));
        assertQuery(recordedQueries.get(2), Arrays.asList(SET_LONG, SET_TIMESTAMP, SET_TIMESTAMP, LIST),
                mapOf(SCHEDULE_ID_PARAMETER, SCHEDULE_ID, DATE_FROM_PARAMETER, scaleFrom, DATE_TO_PARAMETER, scaleTo));
        assertQuery(recordedQueries.get(3), Arrays.asList(SET_TIMESTAMP, SET_TIMESTAMP, LIST),
                mapOf(DATE_FROM_PARAMETER, scaleFrom, DATE_TO_PARAMETER, scaleTo));
        assertQuery(recordedQueries.get(4), Arrays.asList(SET_PARAMETER_LIST, LIST),
                mapOf(DIVISION_IDS_PARAMETER, Collections.singletonList(division.getId())));

        for (DataDefinition dataDefinition : dataDefinitions()) {
            verify(dataDefinition, never()).find();
            verify(dataDefinition, never()).findWithAlias(Matchers.anyString());
        }

        assertEquals(Arrays.asList("ORD-Q", "EV-Q-LINE", "EV-Q-DIV", "EV-Q-DIV"), recordedLabels());
        assertEquals(Arrays.asList("L1", "L2", "L1", "L3"), recordedRowNames());
    }

    @Test
    public final void shouldReadPositionsInOneQueryWithoutPerPositionLookups() {
        // given
        positions.add(position(121L, lineL1, order("ORD-P1", product("P-1", "Product 1")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));
        positions.add(position(122L, lineL2, order("ORD-P2", product("P-2", "Product 2")), "2026-09-28 09:00:00",
                "2026-09-28 13:00:00"));
        positions.add(position(123L, lineL3, order("ORD-P3", product("P-3", "Product 3")), "2026-09-28 10:00:00",
                "2026-09-28 14:00:00"));
        positions.add(position(124L, lineL1, order("ORD-P4", product("P-4", "Product 4")), "2026-09-28 12:00:00",
                "2026-09-28 16:00:00"));

        // when
        resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList(SCHEDULE_STATE_QUERY, PRODUCTION_LINE_ROWS_QUERY, POSITIONS_QUERY, BOARD_EVENTS_QUERY),
                recordedHql());
        assertEquals(4, queriesOf(POSITIONS_QUERY).get(0).rows.size());

        verify(dataDefinitionService).get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE);
        verify(dataDefinitionService).get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION);
        verify(dataDefinitionService).get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE);
        verify(dataDefinitionService).get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT);
        verifyNoMoreInteractions(dataDefinitionService);

        verify(scheduleDD).find(SCHEDULE_STATE_QUERY);
        verify(productionLineDD).find(PRODUCTION_LINE_ROWS_QUERY);
        verify(positionDD).find(POSITIONS_QUERY);
        verify(plannedEventDD).find(BOARD_EVENTS_QUERY);
        verifyNoMoreInteractions(scheduleDD, productionLineDD, positionDD, plannedEventDD, divisionDD);

        assertEquals(Arrays.asList("ORD-P1", "ORD-P2", "ORD-P3", "ORD-P4"), recordedLabels());
        assertEquals(Arrays.asList("L1", "L2", "L3", "L1"), recordedRowNames());
        assertEquals(Collections.singletonList(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "P-3",
                "Product 3")), recordedItemLabelled("ORD-P3").tooltip.getContent());
        assertEquals(Collections.singletonList(translated(ProductionMaintenanceGanttChartItemResolver.ITEM_PRODUCT_KEY, "P-4",
                "Product 4")), recordedItemLabelled("ORD-P4").tooltip.getContent());
    }

    @Test
    public final void shouldReadBoardEventsOfEveryStateAndExcludeUnplaceableEvents() {
        // given
        plannedEvents.add(plannedEvent("EV-U-DIV", PlannedEventStateStringValues.CANCELED, true, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", null, null, division(lineL2)));
        plannedEvents.add(plannedEvent("EV-U-NOWHERE", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 10:00:00", null, null, null));
        plannedEvents.add(plannedEvent("EV-U-WS-NO-LINE", PlannedEventStateStringValues.PLANNED, false,
                "2026-09-29 07:00:00", "2026-09-29 10:00:00", null, workstation(null), null));
        plannedEvents.add(plannedEvent("EV-U-DIV-NO-LINE", PlannedEventStateStringValues.PLANNED, true,
                "2026-09-29 08:00:00", "2026-09-29 10:00:00", null, workstation(null), division()));
        plannedEvents.add(plannedEvent("EV-U-BEFORE", PlannedEventStateStringValues.REALIZED, false, "2026-09-27 20:00:00",
                SCALE_FROM, lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-U-AFTER", PlannedEventStateStringValues.IN_EDITING, false, SCALE_TO,
                "2026-10-05 06:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-U-LINE", PlannedEventStateStringValues.REALIZED, false, "2026-09-27 20:00:00",
                "2026-09-28 01:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-U-WS", PlannedEventStateStringValues.IN_EDITING, false, "2026-10-04 20:00:00",
                "2026-10-05 01:00:00", null, workstation(lineL3), null));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        List<RecordedQuery> eventQueries = queriesOf(BOARD_EVENTS_QUERY);

        assertEquals(1, eventQueries.size());
        assertQuery(eventQueries.get(0), Arrays.asList(SET_TIMESTAMP, SET_TIMESTAMP, LIST),
                mapOf(DATE_FROM_PARAMETER, scaleFrom, DATE_TO_PARAMETER, scaleTo));
        assertEquals(Arrays.asList("EV-U-LINE", "EV-U-DIV", "EV-U-WS"), stringFieldsOf(eventQueries.get(0).rows,
                EVENT_NUMBER_ALIAS));

        assertEquals(Arrays.asList("EV-U-LINE", "EV-U-DIV", "EV-U-WS"), recordedLabels());
        assertEquals(Collections.singletonList("EV-U-LINE"), itemNames(items.get("L1")));
        assertEquals(Collections.singletonList("EV-U-DIV"), itemNames(items.get("L2")));
        assertEquals(Collections.singletonList("EV-U-WS"), itemNames(items.get("L3")));
    }

    @Test
    public final void shouldReadLinesOfEveryDivisionOnlyEventInOneQueryInFirstSeenOrder() {
        // given
        Entity divisionA = division(lineL1, lineL2);
        Entity divisionB = division(lineL3);
        Entity divisionC = division(lineL3);
        Entity divisionD = division(lineL1);
        Entity divisionE = division(lineL2);

        plannedEvents.add(plannedEvent("EV-E1", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 11:30:00",
                "2026-09-29 12:00:00", null, null, divisionE));
        plannedEvents.add(plannedEvent("EV-A2", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 11:00:00",
                "2026-09-29 12:00:00", null, null, divisionA));
        plannedEvents.add(plannedEvent("EV-OWN", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 09:00:00",
                "2026-09-29 10:00:00", lineL1, null, divisionC));
        plannedEvents.add(plannedEvent("EV-B1", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 07:00:00", null, null, divisionB));
        plannedEvents.add(plannedEvent("EV-WS", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 10:00:00",
                "2026-09-29 11:00:00", null, workstation(lineL2), divisionD));
        plannedEvents.add(plannedEvent("EV-A1", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 07:00:00",
                "2026-09-29 08:00:00", null, workstation(null), divisionA));
        plannedEvents.add(plannedEvent("EV-B2", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 08:00:00",
                "2026-09-29 09:00:00", null, null, divisionB));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        List<RecordedQuery> divisionQueries = queriesOf(DIVISION_LINES_QUERY);

        assertEquals(1, divisionQueries.size());
        assertQuery(divisionQueries.get(0), Arrays.asList(SET_PARAMETER_LIST, LIST),
                mapOf(DIVISION_IDS_PARAMETER, Arrays.asList(divisionB.getId(), divisionA.getId(), divisionE.getId())));

        assertEquals(Arrays.asList("EV-A1", "EV-OWN", "EV-A2"), itemNames(items.get("L1")));
        assertEquals(Arrays.asList("EV-A1", "EV-WS", "EV-A2", "EV-E1"), itemNames(items.get("L2")));
        assertEquals(Arrays.asList("EV-B1", "EV-B2"), itemNames(items.get("L3")));
    }

    @Test
    public final void shouldNotQueryDivisionLinesWhenEveryEventHasOwnOrWorkstationLine() {
        // given
        plannedEvents.add(plannedEvent("EV-OWN", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 07:00:00", lineL1, workstation(lineL3), division(lineL2)));
        plannedEvents.add(plannedEvent("EV-WS", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 08:00:00",
                "2026-09-29 09:00:00", null, workstation(lineL2), division(lineL3)));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(queriesOf(DIVISION_LINES_QUERY).isEmpty());
        verify(dataDefinitionService, never()).get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_DIVISION);
        verifyZeroInteractions(divisionDD);

        assertEquals(Collections.singletonList("EV-OWN"), itemNames(items.get("L1")));
        assertEquals(Collections.singletonList("EV-WS"), itemNames(items.get("L2")));
        assertTrue(items.get("L3").isEmpty());
    }

    @Test
    public final void shouldPlaceEventOnOwnLineBeforeWorkstationLineBeforeDivisionLines() {
        // given
        Entity divisionOnlyDivision = division(lineL1, lineL3);

        plannedEvents.add(plannedEvent("EV-P-OWN", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 07:00:00", lineL1, workstation(lineL2), division(lineL2, lineL3)));
        plannedEvents.add(plannedEvent("EV-P-WS", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 07:00:00",
                "2026-09-29 08:00:00", null, workstation(lineL3), division(lineL1, lineL2)));
        plannedEvents.add(plannedEvent("EV-P-DIV", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 08:00:00",
                "2026-09-29 09:00:00", null, workstation(null), divisionOnlyDivision));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(Arrays.asList("EV-P-OWN", "EV-P-DIV"), itemNames(items.get("L1")));
        assertTrue(items.get("L2").isEmpty());
        assertEquals(Arrays.asList("EV-P-WS", "EV-P-DIV"), itemNames(items.get("L3")));

        List<RecordedQuery> divisionQueries = queriesOf(DIVISION_LINES_QUERY);

        assertEquals(1, divisionQueries.size());
        assertEquals(mapOf(DIVISION_IDS_PARAMETER, Collections.singletonList(divisionOnlyDivision.getId())),
                divisionQueries.get(0).parameters);
    }

    @Test
    public final void shouldOmitDivisionOnlyEventWhoseDivisionLinesAreRemovedBeforeTheyAreRead() {
        // given
        final Entity division = division(lineL1, lineL2);

        plannedEvents.add(plannedEvent("EV-GONE", PlannedEventStateStringValues.PLANNED, false, "2026-09-29 06:00:00",
                "2026-09-29 07:00:00", null, null, division));

        afterQueryActions.put(BOARD_EVENTS_QUERY, new Runnable() {

            @Override
            public void run() {
                division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES).clear();
            }

        });

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertEquals(1, queriesOf(BOARD_EVENTS_QUERY).get(0).rows.size());
        assertEquals(1, queriesOf(DIVISION_LINES_QUERY).size());
        assertTrue(queriesOf(DIVISION_LINES_QUERY).get(0).rows.isEmpty());

        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        for (List<GanttChartItem> row : items.values()) {
            assertTrue(row.isEmpty());
        }

        assertTrue(recordedItems.isEmpty());
    }

    @Test
    public final void shouldFindShutdownEventNumbersOfEventsPlacedOnTargetLineInStartOrder() {
        // given
        Date dateFrom = date("2026-09-29 08:00:00");
        Date dateTo = date("2026-09-29 12:00:00");

        plannedEvents.add(plannedEvent("EV-S-DIV", PlannedEventStateStringValues.NEW, true, "2026-09-29 11:00:00",
                "2026-09-29 13:00:00", null, workstation(null), division(lineL1, lineL2)));
        plannedEvents.add(plannedEvent("EV-S-SUPERSET-WS", PlannedEventStateStringValues.PLANNED, true,
                "2026-09-29 08:30:00", "2026-09-29 09:30:00", lineL1, workstation(lineL2), null));
        plannedEvents.add(plannedEvent("EV-S-OWN", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 09:00:00",
                "2026-09-29 10:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-S-WS-OTHER", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 07:00:00",
                "2026-09-29 08:30:00", null, workstation(lineL3), division(lineL2)));
        plannedEvents.add(plannedEvent("EV-S-SUPERSET-DIV", PlannedEventStateStringValues.PLANNED, true,
                "2026-09-29 10:00:00", "2026-09-29 10:45:00", lineL1, null, division(lineL2)));
        plannedEvents.add(plannedEvent("EV-S-WS", PlannedEventStateStringValues.REALIZED, true, "2026-09-29 07:30:00",
                "2026-09-29 08:15:00", null, workstation(lineL2), null));
        plannedEvents.add(plannedEvent("EV-S-NO-SHUTDOWN", PlannedEventStateStringValues.PLANNED, false,
                "2026-09-29 09:00:00", "2026-09-29 10:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-S-BEFORE", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 06:00:00",
                "2026-09-29 08:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-S-AFTER", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 12:00:00",
                "2026-09-29 13:00:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-S-CANCELED", PlannedEventStateStringValues.CANCELED, true,
                "2026-09-29 10:30:00", "2026-09-29 11:30:00", lineL2, null, null));
        plannedEvents.add(plannedEvent("EV-S-DIV-OTHER", PlannedEventStateStringValues.PLANNED, true,
                "2026-09-29 09:00:00", "2026-09-29 10:00:00", null, null, division(lineL1, lineL3)));

        // when
        List<String> eventNumbers = resolver.findShutdownEventNumbers(lineL2, dateFrom, dateTo);

        // then
        assertEquals(Arrays.asList("EV-S-WS", "EV-S-OWN", "EV-S-CANCELED", "EV-S-DIV"), eventNumbers);

        assertEquals(Collections.singletonList(SHUTDOWN_EVENTS_QUERY), recordedHql());
        assertQuery(recordedQueries.get(0), Arrays.asList(SET_TIMESTAMP, SET_TIMESTAMP, SET_BOOLEAN, SET_LONG, LIST),
                mapOf(DATE_FROM_PARAMETER, dateFrom, DATE_TO_PARAMETER, dateTo, REQUIRES_SHUTDOWN_PARAMETER, true,
                        PRODUCTION_LINE_ID_PARAMETER, lineL2.getId()));
        assertEquals(Arrays.asList("EV-S-WS-OTHER", "EV-S-WS", "EV-S-SUPERSET-WS", "EV-S-OWN", "EV-S-SUPERSET-DIV",
                "EV-S-CANCELED", "EV-S-DIV"), stringFieldsOf(recordedQueries.get(0).rows, EVENT_NUMBER_ALIAS));

        verifyZeroInteractions(divisionDD);
        verify(dataDefinitionService, never()).get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_DIVISION);
    }

    @Test
    public final void shouldFindNoShutdownEventNumbersWhenNoEventIsPlacedOnTargetLine() {
        // given
        Date dateFrom = date("2026-09-29 08:00:00");
        Date dateTo = date("2026-09-29 12:00:00");

        plannedEvents.add(plannedEvent("EV-S-L1", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 09:00:00",
                "2026-09-29 10:00:00", lineL1, null, null));
        plannedEvents.add(plannedEvent("EV-S-DIV-L1-L3", PlannedEventStateStringValues.PLANNED, true,
                "2026-09-29 09:00:00", "2026-09-29 10:00:00", null, null, division(lineL1, lineL3)));

        // when
        List<String> eventNumbers = resolver.findShutdownEventNumbers(lineL2, dateFrom, dateTo);

        // then
        assertTrue(eventNumbers.isEmpty());
        assertEquals(Collections.singletonList(SHUTDOWN_EVENTS_QUERY), recordedHql());
        assertTrue(recordedQueries.get(0).rows.isEmpty());
    }

    @Test
    public final void shouldFindNoShutdownEventNumbersWithoutQueryForNullLine() {
        // given
        plannedEvents.add(plannedEvent("EV-S-ANY", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 09:00:00",
                "2026-09-29 10:00:00", lineL1, null, null));

        // when
        List<String> eventNumbers = resolver.findShutdownEventNumbers(null, date("2026-09-29 08:00:00"),
                date("2026-09-29 12:00:00"));

        // then
        assertTrue(eventNumbers.isEmpty());
        assertTrue(recordedQueries.isEmpty());
        verifyZeroInteractions(dataDefinitionService, plannedEventDD);
    }

    @Test
    public final void shouldFindNoShutdownEventNumbersWithoutQueryForLineWithoutId() {
        // given
        Entity unsavedLine = mockEntity();

        given(unsavedLine.getId()).willReturn(null);
        stubStringField(unsavedLine, ProductionLineFields.NUMBER, "L-NEW");

        plannedEvents.add(plannedEvent("EV-S-ANY", PlannedEventStateStringValues.PLANNED, true, "2026-09-29 09:00:00",
                "2026-09-29 10:00:00", null, null, division(lineL1, lineL2)));

        // when
        List<String> eventNumbers = resolver.findShutdownEventNumbers(unsavedLine, date("2026-09-29 08:00:00"),
                date("2026-09-29 12:00:00"));

        // then
        assertTrue(eventNumbers.isEmpty());
        assertTrue(recordedQueries.isEmpty());
        verifyZeroInteractions(dataDefinitionService, plannedEventDD);
    }

    @Test
    public final void shouldRequireShutdownIntervalStart() {
        // given
        Date dateTo = date("2026-09-29 12:00:00");

        // when
        NullPointerException exception = null;

        try {
            resolver.findShutdownEventNumbers(lineL1, null, dateTo);
        } catch (NullPointerException e) {
            exception = e;
        }

        // then
        assertNotNull("A null interval start was accepted", exception);
        assertEquals(DATE_FROM_PARAMETER, exception.getMessage());
        assertTrue(recordedQueries.isEmpty());
        verifyZeroInteractions(dataDefinitionService);
    }

    @Test
    public final void shouldRequireShutdownIntervalEnd() {
        // given
        Date dateFrom = date("2026-09-29 08:00:00");

        // when
        NullPointerException exception = null;

        try {
            resolver.findShutdownEventNumbers(lineL1, dateFrom, null);
        } catch (NullPointerException e) {
            exception = e;
        }

        // then
        assertNotNull("A null interval end was accepted", exception);
        assertEquals(DATE_TO_PARAMETER, exception.getMessage());
        assertTrue(recordedQueries.isEmpty());
        verifyZeroInteractions(dataDefinitionService);
    }

    @Test
    public final void shouldWriteNullAsLogValue() {
        // given
        String value = null;

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("null", logValue);
    }

    @Test
    public final void shouldQuoteLogValue() {
        // given
        String value = "7a";

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("\"7a\"", logValue);
    }

    @Test
    public final void shouldEscapeCarriageReturnLineFeedAndTabInLogValue() {
        // given
        String value = "x\r\nWARN forged\tend";

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("\"x\\u000d\\u000aWARN forged\\u0009end\"", logValue);
    }

    @Test
    public final void shouldEscapeOtherControlCharactersInLogValue() {
        // given
        String value = new String(new char[] { 0x00, 'a', 0x1b, 'b', 0x7f, 'c', 0x85, 0x9f });

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("\"\\u0000a\\u001bb\\u007fc\\u0085\\u009f\"", logValue);
    }

    @Test
    public final void shouldEscapeUnicodeLineAndParagraphSeparatorsInLogValue() {
        // given
        String value = new String(new char[] { 'a', 0x2028, 'b', 0x2029, 'c' });

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("\"a\\u2028b\\u2029c\"", logValue);
    }

    @Test
    public final void shouldEscapeBackslashAndQuoteInLogValue() {
        // given
        String value = "a\\b\"c";

        // when
        String logValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(value);

        // then
        assertEquals("\"a\\\\b\\\"c\"", logValue);
    }

    @Test
    public final void shouldKeepLogValueOfMaximumLengthWhole() {
        // given
        String plainValue = StringUtils.repeat('a', LOG_VALUE_MAX_LENGTH);
        String lineBreakLastValue = StringUtils.repeat('a', LOG_VALUE_MAX_LENGTH - 1) + "\n";

        // when
        String plainLogValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(plainValue);
        String lineBreakLastLogValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(lineBreakLastValue);

        // then
        assertEquals("\"" + plainValue + "\"", plainLogValue);
        assertEquals("\"" + StringUtils.repeat('a', LOG_VALUE_MAX_LENGTH - 1) + "\\u000a\"", lineBreakLastLogValue);
    }

    @Test
    public final void shouldCutLogValueLongerThanMaximumLength() {
        // given
        String oneCharacterTooLong = StringUtils.repeat('b', LOG_VALUE_MAX_LENGTH + 1);
        String lineBreakAfterMaximumLength = StringUtils.repeat('c', LOG_VALUE_MAX_LENGTH) + "\nWARN forged";

        // when
        String oneCharacterTooLongLogValue = ProductionMaintenanceGanttChartItemResolver.toLogValue(oneCharacterTooLong);
        String lineBreakAfterMaximumLengthLogValue = ProductionMaintenanceGanttChartItemResolver
                .toLogValue(lineBreakAfterMaximumLength);

        // then
        assertEquals("\"" + StringUtils.repeat('b', LOG_VALUE_MAX_LENGTH) + "\"... (65 characters)",
                oneCharacterTooLongLogValue);
        assertEquals("\"" + StringUtils.repeat('c', LOG_VALUE_MAX_LENGTH) + "\"... (76 characters)",
                lineBreakAfterMaximumLengthLogValue);
    }

    @Test
    public final void shouldLogMalformedScheduleIdOnOneEscapedLine() {
        // given
        JSONObject forgedIdContext = scheduleContext("x\r\nWARN forged");

        positions.add(position(131L, lineL1, order("ORD-F", product("P-F", "Forged")), "2026-09-28 08:00:00",
                "2026-09-28 12:00:00"));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, forgedIdContext, locale);

        // then
        assertTrue(items.isEmpty());
        verifyZeroInteractions(dataDefinitionService, scheduleDD, positionDD, productionLineDD, plannedEventDD, divisionDD);
        assertTrue(recordedQueries.isEmpty());

        List<String> warnings = loggedWarnings();

        assertEquals(Collections.singletonList(INVALID_SCHEDULE_ID_MESSAGE + "\"x\\u000d\\u000aWARN forged\""), warnings);
        assertFalse(warnings.get(0).contains("\n"));
        assertFalse(warnings.get(0).contains("\r"));
    }

    @Test
    public final void shouldLogOverlongMalformedScheduleIdCut() {
        // given
        String overlongId = StringUtils.repeat('9', 200);

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, scheduleContext(overlongId), locale);

        // then
        assertTrue(items.isEmpty());
        verifyZeroInteractions(dataDefinitionService);
        assertEquals(Collections.singletonList(INVALID_SCHEDULE_ID_MESSAGE + "\"" + StringUtils.repeat('9', LOG_VALUE_MAX_LENGTH)
                + "\"... (200 characters)"), loggedWarnings());
    }

    private void verifyNoScheduleQueried() {
        verify(scheduleDD, never()).find(Matchers.anyString());
        assertTrue(queriesOf(SCHEDULE_STATE_QUERY).isEmpty());
    }

    private void verifyNoItemQueried() {
        for (RecordedQuery query : recordedQueries) {
            assertEquals(SCHEDULE_STATE_QUERY, query.hql);
        }

        verify(productionLineDD, never()).find(Matchers.anyString());
        verify(positionDD, never()).find(Matchers.anyString());
        verify(plannedEventDD, never()).find(Matchers.anyString());
        verify(divisionDD, never()).find(Matchers.anyString());
        assertTrue(recordedItems.isEmpty());
    }

    /**
     * Returns the rendered messages of the logged events, failing on an event of another level than warning.
     */
    private List<String> loggedWarnings() {
        List<String> messages = new ArrayList<String>();

        for (LoggingEvent event : logAppender.events) {
            assertEquals(Level.WARN, event.getLevel());

            messages.add(event.getRenderedMessage());
        }

        return messages;
    }

    private void assertQuery(final RecordedQuery query, final List<String> expectedCalls,
            final Map<String, Object> expectedParameters) {
        assertEquals(expectedCalls, query.calls);
        assertEquals(expectedParameters, query.parameters);
    }

    private List<RecordedQuery> queriesOf(final String hql) {
        List<RecordedQuery> queries = new ArrayList<RecordedQuery>();

        for (RecordedQuery query : recordedQueries) {
            if (hql.equals(query.hql)) {
                queries.add(query);
            }
        }

        return queries;
    }

    private List<String> recordedHql() {
        List<String> hql = new ArrayList<String>();

        for (RecordedQuery query : recordedQueries) {
            hql.add(query.hql);
        }

        return hql;
    }

    private List<DataDefinition> dataDefinitions() {
        return Arrays.asList(scheduleDD, positionDD, productionLineDD, plannedEventDD, divisionDD);
    }

    private void stubQueries(final DataDefinition dataDefinition) {
        given(dataDefinition.find()).willThrow(new AssertionError("Criteria query with a row count"));
        given(dataDefinition.findWithAlias(Matchers.anyString())).willThrow(
                new AssertionError("Criteria query with a row count"));
        given(dataDefinition.find(Matchers.anyString())).willAnswer(new FindQueryAnswer(dataDefinition));
    }

    private GanttChartItemStrip captureSingleStrip(final GanttChartItem item) {
        ArgumentCaptor<GanttChartItemStrip> stripCaptor = ArgumentCaptor.forClass(GanttChartItemStrip.class);

        verify(item, times(1)).addBackgroundStrip(stripCaptor.capture());

        return stripCaptor.getValue();
    }

    private RecordedItem recordedItemLabelled(final String label) {
        List<RecordedItem> labelled = recordedItemsLabelled(label);

        assertEquals("items labelled '" + label + "'", 1, labelled.size());

        return labelled.get(0);
    }

    private List<RecordedItem> recordedItemsLabelled(final String label) {
        List<RecordedItem> labelled = new ArrayList<RecordedItem>();

        for (RecordedItem recordedItem : recordedItems) {
            if (label.equals(recordedItem.label)) {
                labelled.add(recordedItem);
            }
        }

        return labelled;
    }

    private List<String> recordedRowNames() {
        return rowNamesOf(recordedItems);
    }

    private List<String> recordedLabels() {
        List<String> labels = new ArrayList<String>();

        for (RecordedItem recordedItem : recordedItems) {
            labels.add(recordedItem.label);
        }

        return labels;
    }

    private static List<String> rowNamesOf(final List<RecordedItem> items) {
        List<String> rowNames = new ArrayList<String>();

        for (RecordedItem item : items) {
            rowNames.add(item.rowName);
        }

        return rowNames;
    }

    private static List<String> itemNames(final List<GanttChartItem> items) {
        List<String> names = new ArrayList<String>();

        for (GanttChartItem item : items) {
            names.add(item.getName());
        }

        return names;
    }

    private static List<String> stringFieldsOf(final List<Entity> rows, final String alias) {
        List<String> values = new ArrayList<String>();

        for (Entity row : rows) {
            values.add(row.getStringField(alias));
        }

        return values;
    }

    private static String translated(final String code, final String... args) {
        if (args.length == 0) {
            return code;
        }

        return code + "[" + StringUtils.join(args, "|") + "]";
    }

    private static JSONObject scheduleContext(final Object scheduleId) {
        return contextOf(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, scheduleId);
    }

    private static JSONObject contextOf(final String key, final Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns a map of the given keys and values in the given order; the arguments alternate between key and value.
     */
    private static Map<String, Object> mapOf(final Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();

        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }

        return map;
    }

    private static Date date(final String value) {
        try {
            return new SimpleDateFormat(DateUtils.L_DATE_TIME_FORMAT).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Entity productionLine(final Long id, final String number) {
        return productionLine(id, number, true, true);
    }

    private static Entity productionLine(final Long id, final String number, final boolean production, final boolean active) {
        Entity productionLine = mockEntity(id);

        stubStringField(productionLine, ProductionLineFields.NUMBER, number);
        stubBooleanField(productionLine, ProductionLineFields.PRODUCTION, production);
        stubBooleanField(productionLine, ProductionLineFields.ACTIVE, active);

        return productionLine;
    }

    private static Entity product(final String number, final String name) {
        Entity product = mockEntity();

        stubStringField(product, ProductFields.NUMBER, number);
        stubStringField(product, ProductFields.NAME, name);

        return product;
    }

    private static Entity order(final String number, final Entity product) {
        Entity order = mockEntity();

        stubStringField(order, OrderFields.NUMBER, number);
        stubBelongsToField(order, OrderFields.PRODUCT, product);

        return order;
    }

    private Entity position(final Long id, final Entity productionLine, final Entity order, final String startTime,
            final String endTime) {
        return position(schedule, id, productionLine, order, startTime, endTime);
    }

    private Entity position(final Entity positionSchedule, final Long id, final Entity productionLine, final Entity order,
            final String startTime, final String endTime) {
        Entity position = mockEntity(id, positionDD);

        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, positionSchedule);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE, productionLine);
        stubBelongsToField(position, ProductionLineSchedulePositionFields.ORDER, order);
        stubDateField(position, ProductionLineSchedulePositionFields.START_TIME, date(startTime));
        stubDateField(position, ProductionLineSchedulePositionFields.END_TIME, date(endTime));

        return position;
    }

    private Entity plannedEvent(final String number, final String state, final boolean requiresShutdown,
            final String startDate, final String finishDate, final Entity productionLine, final Entity workstation,
            final Entity division) {
        Entity plannedEvent = mockEntity(nextEventId++, plannedEventDD);

        stubStringField(plannedEvent, PlannedEventFields.NUMBER, number);
        stubStringField(plannedEvent, PlannedEventFields.TYPE, EVENT_TYPE);
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
     * Returns a new division with the next division id and the given production lines, and adds it to the fixture divisions.
     */
    private Entity division(final Entity... lines) {
        Entity division = mockEntity(nextDivisionId++);

        given(division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)).willReturn(
                new ArrayList<Entity>(Arrays.asList(lines)));

        divisions.add(division);

        return division;
    }

    /**
     * Returns the fields of the rows the query selects from the fixture, in the query's order.
     */
    private List<Map<String, Object>> emulate(final RecordedQuery query) {
        Map<String, Object> parameters = query.parameters;

        if (SCHEDULE_STATE_QUERY.equals(query.hql)) {
            return scheduleStateFields((Long) parameters.get(SCHEDULE_ID_PARAMETER));
        }
        if (PRODUCTION_LINE_ROWS_QUERY.equals(query.hql)) {
            return productionLineRowFields((Boolean) parameters.get(PRODUCTION_PARAMETER),
                    (Boolean) parameters.get(ACTIVE_PARAMETER));
        }
        if (POSITIONS_QUERY.equals(query.hql)) {
            return positionFields((Long) parameters.get(SCHEDULE_ID_PARAMETER), (Date) parameters.get(DATE_FROM_PARAMETER),
                    (Date) parameters.get(DATE_TO_PARAMETER));
        }
        if (BOARD_EVENTS_QUERY.equals(query.hql)) {
            return boardEventFields((Date) parameters.get(DATE_FROM_PARAMETER), (Date) parameters.get(DATE_TO_PARAMETER));
        }
        if (SHUTDOWN_EVENTS_QUERY.equals(query.hql)) {
            return shutdownEventFields((Date) parameters.get(DATE_FROM_PARAMETER), (Date) parameters.get(DATE_TO_PARAMETER),
                    (Boolean) parameters.get(REQUIRES_SHUTDOWN_PARAMETER), (Long) parameters.get(PRODUCTION_LINE_ID_PARAMETER));
        }
        if (DIVISION_LINES_QUERY.equals(query.hql)) {
            return divisionLineFields((Collection<?>) parameters.get(DIVISION_IDS_PARAMETER));
        }

        throw new AssertionError("No emulation of the query: " + query.hql);
    }

    /**
     * Id and state of the fixture schedule with the given id.
     */
    private List<Map<String, Object>> scheduleStateFields(final Long scheduleId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity fixtureSchedule : schedules) {
            if (scheduleId.equals(fixtureSchedule.getId())) {
                rows.add(mapOf(SCHEDULE_IDENTIFIER_ALIAS, fixtureSchedule.getId(), STATE_ALIAS,
                        fixtureSchedule.getStringField(ProductionLineScheduleFields.STATE)));
            }
        }

        return rows;
    }

    /**
     * Ids and numbers of the fixture production lines with the given production and active flags, in fixture order.
     */
    private List<Map<String, Object>> productionLineRowFields(final Boolean production, final Boolean active) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity productionLine : productionLines) {
            if (production.booleanValue() == productionLine.getBooleanField(ProductionLineFields.PRODUCTION)
                    && active.booleanValue() == productionLine.getBooleanField(ProductionLineFields.ACTIVE)) {
                rows.add(mapOf(PRODUCTION_LINE_ID_ALIAS, productionLine.getId(), NUMBER_ALIAS, numberOf(productionLine)));
            }
        }

        return rows;
    }

    /**
     * Fixture positions of the schedule that have a production line and overlap {@code [dateFrom, dateTo)}, ordered by start
     * time and id, with the numbers of their line, order and product and the product name, null for a missing order or
     * product.
     */
    private List<Map<String, Object>> positionFields(final Long scheduleId, final Date dateFrom, final Date dateTo) {
        List<Entity> matching = new ArrayList<Entity>();

        for (Entity position : positions) {
            Entity positionSchedule = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE);
            Entity productionLine = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE);

            if (positionSchedule != null
                    && scheduleId.equals(positionSchedule.getId())
                    && productionLine != null
                    && overlaps(position.getDateField(ProductionLineSchedulePositionFields.START_TIME),
                            position.getDateField(ProductionLineSchedulePositionFields.END_TIME), dateFrom, dateTo)) {
                matching.add(position);
            }
        }

        Collections.sort(matching, new DateThenIdOrder(ProductionLineSchedulePositionFields.START_TIME));

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity position : matching) {
            Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);
            Entity product = order == null ? null : order.getBelongsToField(OrderFields.PRODUCT);

            rows.add(mapOf(POSITION_ID_ALIAS, position.getId(), START_TIME_ALIAS,
                    position.getDateField(ProductionLineSchedulePositionFields.START_TIME), END_TIME_ALIAS,
                    position.getDateField(ProductionLineSchedulePositionFields.END_TIME), PRODUCTION_LINE_NUMBER_ALIAS,
                    numberOf(position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE)),
                    ORDER_NUMBER_ALIAS, order == null ? null : order.getStringField(OrderFields.NUMBER), PRODUCT_NUMBER_ALIAS,
                    product == null ? null : product.getStringField(ProductFields.NUMBER), PRODUCT_NAME_ALIAS,
                    product == null ? null : product.getStringField(ProductFields.NAME)));
        }

        return rows;
    }

    /**
     * Fields of the fixture events overlapping {@code [dateFrom, dateTo)} that have a production line, a workstation with a
     * production line or a division with at least one production line.
     */
    private List<Map<String, Object>> boardEventFields(final Date dateFrom, final Date dateTo) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity event : overlappingEvents(dateFrom, dateTo)) {
            if (eventLineOf(event) != null || workstationLineOf(event) != null
                    || !linesOf(event.getBelongsToField(PlannedEventFields.DIVISION)).isEmpty()) {
                rows.add(eventFields(event));
            }
        }

        return rows;
    }

    /**
     * Fields of the fixture events overlapping {@code [dateFrom, dateTo)} with the given requires-shutdown flag whose own
     * production line, workstation production line or one of whose division production lines has the given id.
     */
    private List<Map<String, Object>> shutdownEventFields(final Date dateFrom, final Date dateTo,
            final Boolean requiresShutdown, final Long productionLineId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity event : overlappingEvents(dateFrom, dateTo)) {
            boolean placedOnLine = hasId(eventLineOf(event), productionLineId)
                    || hasId(workstationLineOf(event), productionLineId)
                    || containsId(linesOf(event.getBelongsToField(PlannedEventFields.DIVISION)), productionLineId);

            if (placedOnLine && requiresShutdown.booleanValue() == event.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN)) {
                rows.add(eventFields(event));
            }
        }

        return rows;
    }

    /**
     * Division id, line id and line number of every production line of the fixture divisions with the given ids, ordered by
     * line number with missing numbers last, then by line id.
     */
    private List<Map<String, Object>> divisionLineFields(final Collection<?> divisionIds) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Entity division : divisions) {
            if (divisionIds.contains(division.getId())) {
                for (Entity productionLine : linesOf(division)) {
                    rows.add(mapOf(DIVISION_ID_ALIAS, division.getId(), PRODUCTION_LINE_ID_ALIAS, productionLine.getId(),
                            PRODUCTION_LINE_NUMBER_ALIAS, numberOf(productionLine)));
                }
            }
        }

        Collections.sort(rows, new LineNumberThenIdOrder());

        return rows;
    }

    /**
     * Fixture events overlapping {@code [dateFrom, dateTo)}, ordered by start date and id.
     */
    private List<Entity> overlappingEvents(final Date dateFrom, final Date dateTo) {
        List<Entity> matching = new ArrayList<Entity>();

        for (Entity event : plannedEvents) {
            if (overlaps(event.getDateField(PlannedEventFields.START_DATE), event.getDateField(PlannedEventFields.FINISH_DATE),
                    dateFrom, dateTo)) {
                matching.add(event);
            }
        }

        Collections.sort(matching, new DateThenIdOrder(PlannedEventFields.START_DATE));

        return matching;
    }

    private static Map<String, Object> eventFields(final Entity event) {
        Entity eventLine = eventLineOf(event);
        Entity workstationLine = workstationLineOf(event);
        Entity division = event.getBelongsToField(PlannedEventFields.DIVISION);

        return mapOf(EVENT_NUMBER_ALIAS, event.getStringField(PlannedEventFields.NUMBER), EVENT_TYPE_ALIAS,
                event.getStringField(PlannedEventFields.TYPE), EVENT_STATE_ALIAS, event.getStringField(PlannedEventFields.STATE),
                REQUIRES_SHUTDOWN_ALIAS, event.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN), START_DATE_ALIAS,
                event.getDateField(PlannedEventFields.START_DATE), FINISH_DATE_ALIAS,
                event.getDateField(PlannedEventFields.FINISH_DATE), EVENT_LINE_ID_ALIAS, idOf(eventLine),
                EVENT_LINE_NUMBER_ALIAS, numberOf(eventLine), WORKSTATION_LINE_ID_ALIAS, idOf(workstationLine),
                WORKSTATION_LINE_NUMBER_ALIAS, numberOf(workstationLine), DIVISION_ID_ALIAS, idOf(division));
    }

    private static Entity eventLineOf(final Entity event) {
        return event.getBelongsToField(PlannedEventFields.PRODUCTION_LINE);
    }

    private static Entity workstationLineOf(final Entity event) {
        Entity workstation = event.getBelongsToField(PlannedEventFields.WORKSTATION);

        return workstation == null ? null : workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
    }

    private static List<Entity> linesOf(final Entity division) {
        if (division == null) {
            return Collections.emptyList();
        }

        List<Entity> lines = division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES);

        return lines == null ? Collections.<Entity> emptyList() : lines;
    }

    private static boolean overlaps(final Date startDate, final Date finishDate, final Date dateFrom, final Date dateTo) {
        return startDate.before(dateTo) && finishDate.after(dateFrom);
    }

    private static boolean hasId(final Entity entity, final Long id) {
        return entity != null && id.equals(entity.getId());
    }

    private static boolean containsId(final List<Entity> entities, final Long id) {
        for (Entity entity : entities) {
            if (hasId(entity, id)) {
                return true;
            }
        }

        return false;
    }

    private static Long idOf(final Entity entity) {
        return entity == null ? null : entity.getId();
    }

    private static String numberOf(final Entity productionLine) {
        return productionLine == null ? null : productionLine.getStringField(ProductionLineFields.NUMBER);
    }

    /**
     * Returns the aliases the query selects, in select order.
     */
    private static Set<String> aliasesOf(final String hql) {
        Set<String> aliases = new LinkedHashSet<String>();
        Matcher matcher = ALIAS_PATTERN.matcher(hql);

        while (matcher.find()) {
            aliases.add(matcher.group(1));
        }

        return aliases;
    }

    /**
     * Returns a projection row for every field map, failing when a map's keys are not exactly the aliases the query selects.
     */
    private static List<Entity> toRows(final String hql, final List<Map<String, Object>> fieldMaps) {
        Set<String> aliases = aliasesOf(hql);
        List<Entity> rows = new ArrayList<Entity>();

        for (Map<String, Object> fields : fieldMaps) {
            if (!aliases.equals(fields.keySet())) {
                throw new AssertionError("Row fields " + fields.keySet() + " differ from the aliases " + aliases
                        + " of the query: " + hql);
            }

            rows.add(mock(Entity.class, new ProjectionRowAnswer(fields)));
        }

        return rows;
    }

    /**
     * Expected data definition, result method and parameter setters of one query.
     */
    private static final class QuerySpec {

        private final DataDefinition dataDefinition;

        private final String resultMethod;

        private final Map<String, String> setters = new LinkedHashMap<String, String>();

        /**
         * @param parametersAndSetters
         *            parameter names, each followed by the name of the builder method that must bind it
         */
        private QuerySpec(final DataDefinition dataDefinition, final String resultMethod, final String... parametersAndSetters) {
            this.dataDefinition = dataDefinition;
            this.resultMethod = resultMethod;

            for (int index = 0; index < parametersAndSetters.length; index += 2) {
                setters.put(parametersAndSetters[index], parametersAndSetters[index + 1]);
            }
        }

    }

    /**
     * One query created through {@code find(String)}: its text, its bound parameters, the builder methods called on it and
     * the projection rows it returned.
     */
    private static final class RecordedQuery {

        private final String hql;

        private final QuerySpec querySpec;

        private final Map<String, Object> parameters = new LinkedHashMap<String, Object>();

        private final List<String> calls = new ArrayList<String>();

        private final List<Entity> rows = new ArrayList<Entity>();

        private boolean executed;

        private RecordedQuery(final String hql, final QuerySpec querySpec) {
            this.hql = hql;
            this.querySpec = querySpec;
        }

    }

    /**
     * Answers {@code find(String)} of one data definition with a recording query builder, failing on an unknown query and on
     * a query of another data definition.
     */
    private final class FindQueryAnswer implements Answer<SearchQueryBuilder> {

        private final DataDefinition dataDefinition;

        private FindQueryAnswer(final DataDefinition dataDefinition) {
            this.dataDefinition = dataDefinition;
        }

        @Override
        public SearchQueryBuilder answer(final InvocationOnMock invocation) {
            String hql = (String) invocation.getArguments()[0];
            QuerySpec querySpec = querySpecs.get(hql);

            if (querySpec == null) {
                throw new AssertionError("Unknown query: " + hql);
            }
            if (querySpec.dataDefinition != dataDefinition) {
                throw new AssertionError("Query run on another data definition: " + hql);
            }

            RecordedQuery query = new RecordedQuery(hql, querySpec);

            recordedQueries.add(query);

            return mock(SearchQueryBuilder.class, new QueryBuilderAnswer(query));
        }

    }

    /**
     * Records the parameters bound on a query builder and answers its result method with the emulated rows; fails on every
     * other call and on any call after the result was read.
     */
    private final class QueryBuilderAnswer implements Answer<Object> {

        private final RecordedQuery query;

        private QueryBuilderAnswer(final RecordedQuery query) {
            this.query = query;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) {
            String methodName = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();

            if ("toString".equals(methodName)) {
                return "Query builder of " + query.hql;
            }
            if (query.executed) {
                throw new AssertionError(methodName + " called after the result was read: " + query.hql);
            }

            query.calls.add(methodName);

            if (LIST.equals(methodName) || UNIQUE_RESULT.equals(methodName)) {
                return readResult(methodName);
            }
            if (arguments.length == 2 && arguments[0] instanceof String) {
                bind(methodName, (String) arguments[0], arguments[1]);

                return invocation.getMock();
            }

            throw new AssertionError("Unexpected " + methodName + " on the query: " + query.hql);
        }

        private void bind(final String setter, final String parameter, final Object value) {
            String expectedSetter = query.querySpec.setters.get(parameter);

            if (expectedSetter == null) {
                throw new AssertionError("Unexpected parameter " + parameter + " of the query: " + query.hql);
            }
            if (!expectedSetter.equals(setter)) {
                throw new AssertionError("Parameter " + parameter + " bound through " + setter + " instead of " + expectedSetter);
            }
            if (query.parameters.containsKey(parameter)) {
                throw new AssertionError("Parameter " + parameter + " bound twice on the query: " + query.hql);
            }

            query.parameters.put(parameter, value);
        }

        private Object readResult(final String resultMethod) {
            if (!query.querySpec.resultMethod.equals(resultMethod)) {
                throw new AssertionError("Result read through " + resultMethod + " instead of " + query.querySpec.resultMethod
                        + ": " + query.hql);
            }
            if (!query.querySpec.setters.keySet().equals(query.parameters.keySet())) {
                throw new AssertionError("Query run with the parameters " + query.parameters.keySet() + " instead of "
                        + query.querySpec.setters.keySet() + ": " + query.hql);
            }

            query.executed = true;
            query.rows.addAll(toRows(query.hql, emulate(query)));

            Runnable afterQueryAction = afterQueryActions.get(query.hql);

            if (afterQueryAction != null) {
                afterQueryAction.run();
            }

            if (UNIQUE_RESULT.equals(resultMethod)) {
                if (query.rows.size() > 1) {
                    throw new AssertionError("Unique result of " + query.rows.size() + " rows: " + query.hql);
                }

                return query.rows.isEmpty() ? null : query.rows.get(0);
            }

            return mock(SearchResult.class, new SearchResultAnswer(new ArrayList<Entity>(query.rows)));
        }

    }

    /**
     * Answers {@code getEntities()} of a query result with the given rows and fails on every other call.
     */
    private static final class SearchResultAnswer implements Answer<Object> {

        private final List<Entity> entities;

        private SearchResultAnswer(final List<Entity> entities) {
            this.entities = entities;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) {
            String methodName = invocation.getMethod().getName();

            if ("getEntities".equals(methodName)) {
                return entities;
            }
            if ("toString".equals(methodName)) {
                return "Query result of " + entities.size() + " rows";
            }

            throw new AssertionError("Unexpected " + methodName + " on a query result");
        }

    }

    /**
     * Answers the typed field getters of a projection row with the value of the given alias, failing on an alias the row does
     * not have, on a value of another type and on every other call.
     */
    private static final class ProjectionRowAnswer implements Answer<Object> {

        private final Map<String, Object> fields;

        private ProjectionRowAnswer(final Map<String, Object> fields) {
            this.fields = fields;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) {
            String methodName = invocation.getMethod().getName();

            if ("toString".equals(methodName)) {
                return "Projection row " + fields;
            }

            Class<?> fieldType = fieldTypeOf(methodName);

            if (fieldType == null) {
                throw new AssertionError("Projection row " + fields + " read through " + methodName);
            }

            String alias = (String) invocation.getArguments()[0];

            if (!fields.containsKey(alias)) {
                throw new AssertionError("Projection row " + fields + " has no field " + alias);
            }

            Object value = fields.get(alias);

            if (value != null && !fieldType.isInstance(value)) {
                throw new AssertionError("Field " + alias + " of " + fields + " read through " + methodName);
            }
            if (Boolean.class.equals(fieldType)) {
                return Boolean.TRUE.equals(value);
            }

            return value;
        }

        private static Class<?> fieldTypeOf(final String methodName) {
            if ("getStringField".equals(methodName)) {
                return String.class;
            }
            if ("getLongField".equals(methodName)) {
                return Long.class;
            }
            if ("getDateField".equals(methodName)) {
                return Date.class;
            }
            if ("getBooleanField".equals(methodName)) {
                return Boolean.class;
            }

            return null;
        }

    }

    /**
     * Orders entities by a date field, then by id.
     */
    private static final class DateThenIdOrder implements Comparator<Entity> {

        private final String dateField;

        private DateThenIdOrder(final String dateField) {
            this.dateField = dateField;
        }

        @Override
        public int compare(final Entity first, final Entity second) {
            int byDate = first.getDateField(dateField).compareTo(second.getDateField(dateField));

            if (byDate != 0) {
                return byDate;
            }

            return first.getId().compareTo(second.getId());
        }

    }

    /**
     * Orders division line fields by line number with missing numbers last, then by line id.
     */
    private static final class LineNumberThenIdOrder implements Comparator<Map<String, Object>> {

        @Override
        public int compare(final Map<String, Object> first, final Map<String, Object> second) {
            String firstNumber = (String) first.get(PRODUCTION_LINE_NUMBER_ALIAS);
            String secondNumber = (String) second.get(PRODUCTION_LINE_NUMBER_ALIAS);

            if (firstNumber == null && secondNumber != null) {
                return 1;
            }
            if (firstNumber != null && secondNumber == null) {
                return -1;
            }
            if (firstNumber != null) {
                int byNumber = firstNumber.compareTo(secondNumber);

                if (byNumber != 0) {
                    return byNumber;
                }
            }

            return ((Long) first.get(PRODUCTION_LINE_ID_ALIAS)).compareTo((Long) second.get(PRODUCTION_LINE_ID_ALIAS));
        }

    }

    /**
     * Records every logging event it receives.
     */
    private static final class RecordingAppender extends AppenderSkeleton {

        private final List<LoggingEvent> events = new ArrayList<LoggingEvent>();

        @Override
        protected void append(final LoggingEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

    }

    /**
     * Arguments of one {@code createGanttChartItem} call and the item returned for it.
     */
    private static final class RecordedItem {

        private final String rowName;

        private final String label;

        private final GanttChartItemTooltip tooltip;

        private final Long entityId;

        private final Date dateFrom;

        private final Date dateTo;

        private GanttChartItem item;

        private RecordedItem(final String rowName, final String label, final GanttChartItemTooltip tooltip,
                final Long entityId, final Date dateFrom, final Date dateTo) {
            this.rowName = rowName;
            this.label = label;
            this.tooltip = tooltip;
            this.entityId = entityId;
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
        }

    }

    /**
     * Records the arguments of both {@code createGanttChartItem} overloads and answers with a mocked item.
     */
    private static final class CreateItemAnswer implements Answer<GanttChartItem> {

        private final List<RecordedItem> recordedItems;

        private CreateItemAnswer(final List<RecordedItem> recordedItems) {
            this.recordedItems = recordedItems;
        }

        @Override
        public GanttChartItem answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            RecordedItem recordedItem;

            if (arguments.length == 6) {
                recordedItem = new RecordedItem((String) arguments[0], (String) arguments[1],
                        (GanttChartItemTooltip) arguments[2], (Long) arguments[3], (Date) arguments[4], (Date) arguments[5]);
            } else {
                recordedItem = new RecordedItem((String) arguments[0], (String) arguments[1], null, (Long) arguments[2],
                        (Date) arguments[3], (Date) arguments[4]);
            }

            recordedItem.item = mock(GanttChartItem.class, new RecordedItemAnswer(recordedItem));
            recordedItems.add(recordedItem);

            return recordedItem.item;
        }

    }

    /**
     * Answers the getters of a mocked item with the recorded row name, label, tooltip and entity id.
     */
    private static final class RecordedItemAnswer implements Answer<Object> {

        private final RecordedItem recordedItem;

        private RecordedItemAnswer(final RecordedItem recordedItem) {
            this.recordedItem = recordedItem;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            String methodName = invocation.getMethod().getName();

            if ("getRowName".equals(methodName)) {
                return recordedItem.rowName;
            }
            if ("getName".equals(methodName)) {
                return recordedItem.label;
            }
            if ("getTooltip".equals(methodName)) {
                return recordedItem.tooltip;
            }
            if ("getEntityId".equals(methodName)) {
                return recordedItem.entityId;
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

    }

    /**
     * Answers a translation with its code, followed by its arguments in brackets when there are any.
     */
    private static final class TranslationAnswer implements Answer<String> {

        @Override
        public String answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            List<String> translationArgs = new ArrayList<String>();

            for (int index = 2; index < arguments.length; index++) {
                Object argument = arguments[index];

                if (argument instanceof String[]) {
                    translationArgs.addAll(Arrays.asList((String[]) argument));
                } else {
                    translationArgs.add((String) argument);
                }
            }

            return translated((String) arguments[0], translationArgs.toArray(new String[translationArgs.size()]));
        }

    }

}
