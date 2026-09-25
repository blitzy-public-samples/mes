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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
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

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.localization.api.utils.DateUtils;
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
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.search.SearchResult;
import com.qcadoo.model.internal.api.DataAccessService;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemStrip;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemTooltip;
import com.qcadoo.view.api.components.ganttChart.GanttChartScale;

/**
 * Unit tests of {@link ProductionMaintenanceGanttChartItemResolver}.
 * <p>
 * The data definitions answer every {@code find()} with a builder that records the criteria and orders added to it and lists
 * the fixture entities of its model. The scale records every created item and answers with a mocked item exposing the
 * recorded row name, label, tooltip and entity id. Translations answer with their code, followed by their arguments in
 * brackets when there are any.
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

    private final Locale locale = Locale.ENGLISH;

    private ProductionMaintenanceGanttChartItemResolver resolver;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private TranslationService translationService;

    @Mock
    private DataAccessService dataAccessService;

    @Mock
    private DataDefinition scheduleDD, positionDD, productionLineDD, plannedEventDD;

    @Mock
    private GanttChartScale scale;

    private DataAccessService previousDataAccessService;

    private Entity schedule;

    private Entity lineL1, lineL2, lineL3;

    private Date scaleFrom, scaleTo;

    private JSONObject context;

    private long nextEventId;

    private final List<Entity> productionLines = new ArrayList<Entity>();

    private final List<Entity> positions = new ArrayList<Entity>();

    private final List<Entity> plannedEvents = new ArrayList<Entity>();

    private final List<List<Object>> productionLineQueries = new ArrayList<List<Object>>();

    private final List<List<Object>> positionQueries = new ArrayList<List<Object>>();

    private final List<List<Object>> plannedEventQueries = new ArrayList<List<Object>>();

    private final List<RecordedItem> recordedItems = new ArrayList<RecordedItem>();

    @Before
    public void init() throws Exception {
        MockitoAnnotations.initMocks(this);

        resolver = new ProductionMaintenanceGanttChartItemResolver();

        setField(resolver, "dataDefinitionService", dataDefinitionService);
        setField(resolver, "translationService", translationService);

        given(dataAccessService.convertToDatabaseEntity(Matchers.any(Entity.class))).willAnswer(
                AdditionalAnswers.returnsFirstArg());

        previousDataAccessService = swapSearchRestrictionsDataAccessService(dataAccessService);

        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE))
                .willReturn(scheduleDD);
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION)).willReturn(positionDD);
        given(dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER,
                ProductionLinesConstants.MODEL_PRODUCTION_LINE)).willReturn(productionLineDD);
        given(dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_PLANNED_EVENT)).willReturn(plannedEventDD);

        given(productionLineDD.find()).willAnswer(new FindAnswer(productionLines, productionLineQueries));
        given(positionDD.find()).willAnswer(new FindAnswer(positions, positionQueries));
        given(plannedEventDD.find()).willAnswer(new FindAnswer(plannedEvents, plannedEventQueries));

        schedule = mockEntity(SCHEDULE_ID, scheduleDD);
        stubStringField(schedule, ProductionLineScheduleFields.STATE, ScheduleStateStringValues.DRAFT);

        given(scheduleDD.get(SCHEDULE_ID)).willReturn(schedule);

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
    }

    @After
    public void restoreSearchRestrictions() throws Exception {
        swapSearchRestrictionsDataAccessService(previousDataAccessService);
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
    }

    @Test
    public final void shouldPlaceDivisionOnlyEventOnEveryLineOfItsDivision() {
        // given
        plannedEvents.add(plannedEvent("EV-DIV", PlannedEventStateStringValues.PLANNED, true, "2026-09-30 06:00:00",
                "2026-09-30 10:00:00", null, null, division(lineL1, lineL2)));

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
    }

    @Test
    public final void shouldSeedEmptyRowsForActiveProductionLines() {
        // given
        productionLines.clear();
        productionLines.addAll(Arrays.asList(lineL3, lineL1, lineL2));

        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, context, locale);

        // then
        assertTrue(items instanceof TreeMap);
        assertEquals(Arrays.asList("L1", "L2", "L3"), new ArrayList<String>(items.keySet()));

        for (List<GanttChartItem> row : items.values()) {
            assertTrue(row.isEmpty());
        }

        assertEquals(1, productionLineQueries.size());
        assertEquals(
                Arrays.<Object> asList(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true),
                        SearchRestrictions.eq(ProductionLineFields.ACTIVE, true)), productionLineQueries.get(0));
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
        assertEquals(1, plannedEventQueries.size());
        assertEquals(
                Arrays.<Object> asList(SearchRestrictions.lt(PlannedEventFields.START_DATE, scaleTo),
                        SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, scaleFrom),
                        SearchOrders.asc(PlannedEventFields.START_DATE)), plannedEventQueries.get(0));
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
        // when
        resolver.resolve(scale, context, locale);

        // then
        verify(scheduleDD).get(SCHEDULE_ID);

        assertEquals(1, positionQueries.size());
        assertEquals(Arrays.<Object> asList(
                SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule),
                SearchRestrictions.isNotNull(ProductionLineSchedulePositionFields.PRODUCTION_LINE),
                SearchRestrictions.lt(ProductionLineSchedulePositionFields.START_TIME, scaleTo),
                SearchRestrictions.gt(ProductionLineSchedulePositionFields.END_TIME, scaleFrom),
                SearchOrders.asc(ProductionLineSchedulePositionFields.START_TIME)), positionQueries.get(0));
    }

    @Test
    public final void shouldReturnEmptyMapWhenContextHasNoScheduleId() {
        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, new JSONObject(), locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenContextIsNull() {
        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, null, locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIdIsBlank() {
        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, scheduleContext("  "), locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIdIsMalformed() {
        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, scheduleContext("7a"), locale);

        // then
        assertTrue(items.isEmpty());
        verifyNoScheduleQueried();
        verifyNoItemQueried();
    }

    @Test
    public final void shouldReturnEmptyMapWhenScheduleIsUnknown() {
        // when
        Map<String, List<GanttChartItem>> items = resolver.resolve(scale, scheduleContext("8"), locale);

        // then
        assertTrue(items.isEmpty());
        verify(scheduleDD).get(8L);
        verifyNoItemQueried();
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
        verify(scheduleDD, times(2)).get(SCHEDULE_ID);
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


    private void verifyNoScheduleQueried() {
        verify(scheduleDD, never()).get(Matchers.any(Long.class));
    }

    private void verifyNoItemQueried() {
        verify(productionLineDD, never()).find();
        verify(positionDD, never()).find();
        verify(plannedEventDD, never()).find();
        assertTrue(recordedItems.isEmpty());
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

    private static List<String> rowNamesOf(final List<RecordedItem> items) {
        List<String> rowNames = new ArrayList<String>();

        for (RecordedItem item : items) {
            rowNames.add(item.rowName);
        }

        return rowNames;
    }

    private static String translated(final String code, final String... args) {
        if (args.length == 0) {
            return code;
        }

        return code + "[" + StringUtils.join(args, "|") + "]";
    }

    private static JSONObject scheduleContext(final Object scheduleId) {
        try {
            return new JSONObject().put(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, scheduleId);
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
        Entity position = mockEntity(id, positionDD);

        stubBelongsToField(position, ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule);
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

    private static Entity division(final Entity... productionLines) {
        Entity division = mockEntity();

        given(division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES)).willReturn(
                new ArrayList<Entity>(Arrays.asList(productionLines)));

        return division;
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
     * Answers {@code find()} with a new builder that records its criteria and lists a copy of the given entities.
     */
    private static final class FindAnswer implements Answer<SearchCriteriaBuilder> {

        private final List<Entity> entities;

        private final List<List<Object>> queries;

        private FindAnswer(final List<Entity> entities, final List<List<Object>> queries) {
            this.entities = entities;
            this.queries = queries;
        }

        @Override
        public SearchCriteriaBuilder answer(final InvocationOnMock invocation) {
            List<Object> criteria = new ArrayList<Object>();

            queries.add(criteria);

            SearchResult searchResult = mock(SearchResult.class, new SearchResultAnswer(new ArrayList<Entity>(entities)));

            return mock(SearchCriteriaBuilder.class, new RecordingCriteriaBuilderAnswer(criteria, searchResult));
        }

    }

    /**
     * Records the arguments of {@code add} and {@code addOrder}, answers {@code list()} with the given result and every other
     * builder method with the builder itself.
     */
    private static final class RecordingCriteriaBuilderAnswer implements Answer<Object> {

        private final List<Object> criteria;

        private final SearchResult searchResult;

        private RecordingCriteriaBuilderAnswer(final List<Object> criteria, final SearchResult searchResult) {
            this.criteria = criteria;
            this.searchResult = searchResult;
        }

        @Override
        public Object answer(final InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            String methodName = method.getName();

            if ("add".equals(methodName) || "addOrder".equals(methodName)) {
                criteria.add(invocation.getArguments()[0]);
            }
            if ("list".equals(methodName)) {
                return searchResult;
            }
            if (SearchCriteriaBuilder.class.equals(method.getReturnType())) {
                return invocation.getMock();
            }

            return Mockito.RETURNS_DEFAULTS.answer(invocation);
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

