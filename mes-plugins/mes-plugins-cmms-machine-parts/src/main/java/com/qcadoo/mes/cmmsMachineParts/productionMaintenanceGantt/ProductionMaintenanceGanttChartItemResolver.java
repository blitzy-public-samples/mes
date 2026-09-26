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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
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
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemResolver;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemStripFactory;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemTooltipBuilder;
import com.qcadoo.view.api.components.ganttChart.GanttChartScale;

/**
 * Resolves the items of the production and maintenance Gantt board.
 * <p>
 * The board has one row per production line, keyed by the raw production line number. Every production line with
 * {@code production = true} and {@code active = true} gets a row, also when it holds no item. Two kinds of items are placed
 * on the rows:
 * <ul>
 * <li>positions of the production line schedule named in the component context, on the row of their production line, with
 * the position id as entity id while the schedule is in the draft state and no entity id otherwise;</li>
 * <li>planned events of every state overlapping the scale, on the row of every production line the rule of
 * {@link #resolveEventLines(Entity)} gives them, always without an entity id and with a full-width colour strip.</li>
 * </ul>
 * User-entered display strings are HTML-escaped before they become item labels or tooltip lines. Overlapping items are left
 * to the Gantt component's collision detection.
 * <p>
 * Every read is one HQL query that selects only the columns the board shows, through
 * {@link DataDefinition#find(String)}, without a row count and without loading whole entities:
 * <ol>
 * <li>the state of the schedule;</li>
 * <li>the numbers of the production lines seeding the rows;</li>
 * <li>the positions of the schedule overlapping the scale, joined with their production line, order and product;</li>
 * <li>the planned events overlapping the scale that are placed on at least one production line, each with the ids and
 * numbers of its own production line and of its workstation's production line, and its division id;</li>
 * <li>only when some of those events have neither production line, the production lines of their divisions, in one
 * query for all of them.</li>
 * </ol>
 * {@link #findShutdownEventNumbers(Entity, Date, Date)} reads planned events the same way, restricted to events that
 * require a shutdown and can be placed on one given production line.
 * <p>
 * The schedule is read from the component context, for example a view opened with
 * {@code context={"gantt.productionLineScheduleId":"12"}} passes {@code {"productionLineScheduleId":"12"}} here. A missing,
 * malformed or unknown schedule id yields an empty board.
 */
@Service
public class ProductionMaintenanceGanttChartItemResolver implements GanttChartItemResolver {

    /**
     * Component context key holding the id of the production line schedule shown on the board.
     */
    public static final String CONTEXT_SCHEDULE_ID = "productionLineScheduleId";

    /**
     * Strip colour of planned events that do not require a shutdown.
     */
    public static final String MAINTENANCE_COLOR = "#2E86C1";

    /**
     * Strip colour of planned events that require a shutdown.
     */
    public static final String SHUTDOWN_COLOR = "#C0392B";

    /**
     * Tooltip line of a position: {0} product number, {1} product name.
     */
    public static final String ITEM_PRODUCT_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.product";

    /**
     * First tooltip line of a planned event: {0} type label, {1} state label.
     */
    public static final String ITEM_PLANNED_EVENT_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.plannedEvent";

    /**
     * Second tooltip line of a planned event: {0} yes/no label of the requires-shutdown flag.
     */
    public static final String ITEM_REQUIRES_SHUTDOWN_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.requiresShutdown";

    private static final String PLANNED_EVENT_TYPE_VALUE_PREFIX = "cmmsMachineParts.plannedEvent.type.value.";

    private static final String PLANNED_EVENT_STATE_VALUE_PREFIX = "cmmsMachineParts.plannedEvent.state.value.";

    private static final String TRUE_KEY = "qcadooView.true";

    private static final String FALSE_KEY = "qcadooView.false";

    private static final int STRIP_SIZE = 100;

    private static final int LOG_VALUE_MAX_LENGTH = 64;

    private static final char LINE_SEPARATOR = 0x2028;

    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private static final String L_SCHEDULE_ID = "scheduleId";

    private static final String L_DATE_FROM = "dateFrom";

    private static final String L_DATE_TO = "dateTo";

    private static final String L_PRODUCTION = "production";

    private static final String L_ACTIVE = "active";

    private static final String L_REQUIRES_SHUTDOWN = "requiresShutdown";

    private static final String L_PRODUCTION_LINE_ID = "productionLineId";

    private static final String L_DIVISION_IDS = "divisionIds";

    private static final String L_SCHEDULE_IDENTIFIER = "scheduleIdentifier";

    private static final String L_STATE = "state";

    private static final String L_NUMBER = "number";

    private static final String L_POSITION_ID = "positionId";

    private static final String L_START_TIME = "startTime";

    private static final String L_END_TIME = "endTime";

    private static final String L_PRODUCTION_LINE_NUMBER = "productionLineNumber";

    private static final String L_ORDER_NUMBER = "orderNumber";

    private static final String L_PRODUCT_NUMBER = "productNumber";

    private static final String L_PRODUCT_NAME = "productName";

    private static final String L_EVENT_NUMBER = "eventNumber";

    private static final String L_EVENT_TYPE = "eventType";

    private static final String L_EVENT_STATE = "eventState";

    private static final String L_START_DATE = "startDate";

    private static final String L_FINISH_DATE = "finishDate";

    private static final String L_EVENT_LINE_ID = "eventLineId";

    private static final String L_EVENT_LINE_NUMBER = "eventLineNumber";

    private static final String L_WORKSTATION_LINE_ID = "workstationLineId";

    private static final String L_WORKSTATION_LINE_NUMBER = "workstationLineNumber";

    private static final String L_DIVISION_ID = "divisionId";

    /**
     * Id and state of one production line schedule, by id.
     */
    static final String SCHEDULE_STATE_QUERY = "select s.id as " + L_SCHEDULE_IDENTIFIER + ", s."
            + ProductionLineScheduleFields.STATE + " as " + L_STATE + " from #orders_productionLineSchedule s where s.id = :"
            + L_SCHEDULE_ID;

    /**
     * Ids and numbers of the production lines with {@code production = true} and {@code active = true}.
     */
    static final String PRODUCTION_LINE_ROWS_QUERY = "select pl.id as " + L_PRODUCTION_LINE_ID + ", pl."
            + ProductionLineFields.NUMBER + " as " + L_NUMBER + " from #productionLines_productionLine pl where pl."
            + ProductionLineFields.PRODUCTION + " = :" + L_PRODUCTION + " and pl." + ProductionLineFields.ACTIVE + " = :"
            + L_ACTIVE;

    /**
     * Positions of one schedule that have a production line and overlap {@code [dateFrom, dateTo)}, with the numbers of their
     * production line, order and product and the product name, in ascending start time and id order.
     */
    static final String POSITIONS_QUERY = "select p.id as " + L_POSITION_ID + ", p."
            + ProductionLineSchedulePositionFields.START_TIME + " as " + L_START_TIME + ", p."
            + ProductionLineSchedulePositionFields.END_TIME + " as " + L_END_TIME + ", pl." + ProductionLineFields.NUMBER + " as "
            + L_PRODUCTION_LINE_NUMBER + ", o." + OrderFields.NUMBER + " as " + L_ORDER_NUMBER + ", pr." + ProductFields.NUMBER
            + " as " + L_PRODUCT_NUMBER + ", pr." + ProductFields.NAME + " as " + L_PRODUCT_NAME
            + " from #orders_productionLineSchedulePosition p join p." + ProductionLineSchedulePositionFields.PRODUCTION_LINE
            + " pl left join p." + ProductionLineSchedulePositionFields.ORDER + " o left join o." + OrderFields.PRODUCT
            + " pr where p." + ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE + ".id = :" + L_SCHEDULE_ID
            + " and p." + ProductionLineSchedulePositionFields.START_TIME + " < :" + L_DATE_TO + " and p."
            + ProductionLineSchedulePositionFields.END_TIME + " > :" + L_DATE_FROM + " order by p."
            + ProductionLineSchedulePositionFields.START_TIME + " asc, p.id asc";

    /**
     * Planned events of every state overlapping {@code [dateFrom, dateTo)}, with the id and number of their own production
     * line and of their workstation's production line, and their division id.
     */
    private static final String EVENTS_SELECT = "select ev." + PlannedEventFields.NUMBER + " as " + L_EVENT_NUMBER + ", ev."
            + PlannedEventFields.TYPE + " as " + L_EVENT_TYPE + ", ev." + PlannedEventFields.STATE + " as " + L_EVENT_STATE
            + ", ev." + PlannedEventFields.REQUIRES_SHUTDOWN + " as " + L_REQUIRES_SHUTDOWN + ", ev."
            + PlannedEventFields.START_DATE + " as " + L_START_DATE + ", ev." + PlannedEventFields.FINISH_DATE + " as "
            + L_FINISH_DATE + ", evLine.id as " + L_EVENT_LINE_ID + ", evLine." + ProductionLineFields.NUMBER + " as "
            + L_EVENT_LINE_NUMBER + ", wsLine.id as " + L_WORKSTATION_LINE_ID + ", wsLine." + ProductionLineFields.NUMBER
            + " as " + L_WORKSTATION_LINE_NUMBER + ", dv.id as " + L_DIVISION_ID
            + " from #cmmsMachineParts_plannedEvent ev left join ev." + PlannedEventFields.PRODUCTION_LINE
            + " evLine left join ev." + PlannedEventFields.WORKSTATION + " ws left join ws." + WorkstationFieldsPL.PRODUCTION_LINE
            + " wsLine left join ev." + PlannedEventFields.DIVISION + " dv where ev." + PlannedEventFields.START_DATE + " < :"
            + L_DATE_TO + " and ev." + PlannedEventFields.FINISH_DATE + " > :" + L_DATE_FROM;

    private static final String EVENTS_ORDER = " order by ev." + PlannedEventFields.START_DATE + " asc, ev.id asc";

    /**
     * {@link #EVENTS_SELECT} restricted to events that have a production line, a workstation with a production line, or a
     * division with at least one production line.
     */
    static final String BOARD_EVENTS_QUERY = EVENTS_SELECT + " and (evLine.id is not null or wsLine.id is not null"
            + " or dv." + DivisionFieldsPL.PRODUCTION_LINES + " is not empty)" + EVENTS_ORDER;

    /**
     * {@link #EVENTS_SELECT} restricted to events that require a shutdown and whose own production line, workstation's
     * production line or one of whose division's production lines is the given production line.
     */
    static final String SHUTDOWN_EVENTS_QUERY = EVENTS_SELECT + " and ev." + PlannedEventFields.REQUIRES_SHUTDOWN
            + " = :" + L_REQUIRES_SHUTDOWN + " and (evLine.id = :" + L_PRODUCTION_LINE_ID + " or wsLine.id = :"
            + L_PRODUCTION_LINE_ID + " or dv.id in (select lineDivision.id from #basic_division lineDivision join lineDivision."
            + DivisionFieldsPL.PRODUCTION_LINES + " divisionLine where divisionLine.id = :" + L_PRODUCTION_LINE_ID + "))"
            + EVENTS_ORDER;

    /**
     * Production lines of the given divisions, with their division id, in ascending number and id order.
     */
    static final String DIVISION_LINES_QUERY = "select dv.id as " + L_DIVISION_ID + ", dvLine.id as "
            + L_PRODUCTION_LINE_ID + ", dvLine." + ProductionLineFields.NUMBER + " as " + L_PRODUCTION_LINE_NUMBER
            + " from #basic_division dv join dv." + DivisionFieldsPL.PRODUCTION_LINES + " dvLine where dv.id in (:"
            + L_DIVISION_IDS + ") order by dvLine." + ProductionLineFields.NUMBER + " asc, dvLine.id asc";

    private static final Logger LOG = LoggerFactory.getLogger(ProductionMaintenanceGanttChartItemResolver.class);

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private TranslationService translationService;

    /**
     * Returns the board rows, keyed by production line number in natural order, each holding the positions and planned
     * events placed on that production line.
     *
     * @param scale
     *            the scale whose date range bounds the items and which creates them
     * @param context
     *            the component context holding {@link #CONTEXT_SCHEDULE_ID}
     * @param locale
     *            the locale of tooltip translations
     * @return rows keyed by production line number; empty when the context names no existing schedule
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, List<GanttChartItem>> resolve(final GanttChartScale scale, final JSONObject context, final Locale locale) {
        Map<String, List<GanttChartItem>> items = new TreeMap<String, List<GanttChartItem>>();

        Long scheduleId = getScheduleId(context);

        if (scheduleId == null) {
            return items;
        }

        Entity schedule = getScheduleDD().find(SCHEDULE_STATE_QUERY).setLong(L_SCHEDULE_ID, scheduleId).uniqueResult();

        if (schedule == null) {
            LOG.warn("Cannot find production line schedule for " + scheduleId);

            return items;
        }

        boolean draft = ScheduleStateStringValues.DRAFT.equals(schedule.getStringField(L_STATE));

        addProductionLineRows(items);
        addPositionItems(items, scale, scheduleId, draft, locale);
        addPlannedEventItems(items, scale, locale);

        return items;
    }

    /**
     * Returns the production lines a planned event is placed on: its own production line; otherwise the production line of
     * its workstation; otherwise every production line of its division; otherwise none. The board and
     * {@link #findShutdownEventNumbers(Entity, Date, Date)} apply the same rule to the line references they query.
     *
     * @param event
     *            planned event
     * @return the event's production lines, empty when none resolves
     */
    public List<Entity> resolveEventLines(final Entity event) {
        Entity eventLine = event.getBelongsToField(PlannedEventFields.PRODUCTION_LINE);
        Entity workstationLine = null;
        List<Entity> divisionLines = null;

        if (eventLine == null) {
            Entity workstation = event.getBelongsToField(PlannedEventFields.WORKSTATION);

            if (workstation != null) {
                workstationLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
            }

            if (workstationLine == null) {
                Entity division = event.getBelongsToField(PlannedEventFields.DIVISION);

                if (division != null) {
                    divisionLines = division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES);
                }
            }
        }

        return selectEventLines(eventLine, workstationLine, divisionLines);
    }

    /**
     * Returns the numbers of the planned events of every state that require a shutdown, overlap the half-open interval
     * {@code [dateFrom, dateTo)} and are placed on the given production line by the rule of
     * {@link #resolveEventLines(Entity)}, in ascending start date order.
     * <p>
     * The events are read in one query restricted to shutdown events overlapping the interval whose own production line,
     * workstation production line or division production lines include the given line. An event that has neither its own
     * nor a workstation production line is therefore returned only when its division includes the given line, and the rule
     * is applied with the given line as that division production line.
     *
     * @param productionLine
     *            production line the events must be placed on
     * @param dateFrom
     *            start of the interval, must not be null
     * @param dateTo
     *            end of the interval, must not be null
     * @return event numbers, empty when the production line or its id is null or no such event exists
     */
    public List<String> findShutdownEventNumbers(final Entity productionLine, final Date dateFrom, final Date dateTo) {
        Objects.requireNonNull(dateFrom, L_DATE_FROM);
        Objects.requireNonNull(dateTo, L_DATE_TO);

        List<String> eventNumbers = new ArrayList<String>();

        if (productionLine == null || productionLine.getId() == null) {
            return eventNumbers;
        }

        Long productionLineId = productionLine.getId();

        List<Entity> events = getPlannedEventDD().find(SHUTDOWN_EVENTS_QUERY).setTimestamp(L_DATE_FROM, dateFrom)
                .setTimestamp(L_DATE_TO, dateTo).setBoolean(L_REQUIRES_SHUTDOWN, true)
                .setLong(L_PRODUCTION_LINE_ID, productionLineId).list().getEntities();

        List<LineReference> divisionLines = Collections.singletonList(new LineReference(productionLineId, null));

        for (Entity event : events) {
            if (containsLine(selectEventLines(eventLineReference(event), workstationLineReference(event), divisionLines),
                    productionLineId)) {
                eventNumbers.add(event.getStringField(L_EVENT_NUMBER));
            }
        }

        return eventNumbers;
    }

    /**
     * Returns the label of a position's item: the HTML-escaped number of its order, or an empty string when the position has
     * no order.
     *
     * @param position
     *            production line schedule position
     * @return the item label
     */
    public String positionLabel(final Entity position) {
        Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);

        if (order == null) {
            return StringUtils.EMPTY;
        }

        return escape(order.getStringField(OrderFields.NUMBER));
    }

    /**
     * Returns a text of the value that holds no line break or other control character and is at most
     * {@value #LOG_VALUE_MAX_LENGTH} characters of the value long, followed by the value's length when it was cut. The text
     * is enclosed in quotes; backslashes and quotes of the value are escaped with a backslash, and control characters and
     * the Unicode line and paragraph separators are written as a backslash, the letter {@code u} and the four lower-case
     * hexadecimal digits of the character.
     * <p>
     * For example a value made of {@code x}, a line feed and {@code WARN forged} is written on one line, with the line feed
     * written as a backslash followed by {@code u000a}.
     *
     * @param value
     *            the value to write into a log message, may be null
     * @return the log text of the value
     */
    static String toLogValue(final String value) {
        if (value == null) {
            return "null";
        }

        int length = Math.min(value.length(), LOG_VALUE_MAX_LENGTH);
        StringBuilder logValue = new StringBuilder(length + 2).append('"');

        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);

            if (character == '\\' || character == '"') {
                logValue.append('\\').append(character);
            } else if (Character.isISOControl(character) || character == LINE_SEPARATOR || character == PARAGRAPH_SEPARATOR) {
                logValue.append(String.format("\\u%04x", (int) character));
            } else {
                logValue.append(character);
            }
        }

        logValue.append('"');

        if (value.length() > LOG_VALUE_MAX_LENGTH) {
            logValue.append("... (").append(value.length()).append(" characters)");
        }

        return logValue.toString();
    }

    /**
     * Returns the schedule id of the context, or null when the context is null or its id is missing, blank or not a number;
     * a malformed id is logged through {@link #toLogValue(String)}.
     */
    private Long getScheduleId(final JSONObject context) {
        if (context == null) {
            return null;
        }

        String scheduleId = context.optString(CONTEXT_SCHEDULE_ID);

        if (StringUtils.isBlank(scheduleId)) {
            return null;
        }

        try {
            return Long.valueOf(scheduleId.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid production line schedule id in Gantt context: " + toLogValue(scheduleId));

            return null;
        }
    }

    private void addProductionLineRows(final Map<String, List<GanttChartItem>> items) {
        List<Entity> productionLines = getProductionLineDD().find(PRODUCTION_LINE_ROWS_QUERY).setBoolean(L_PRODUCTION, true)
                .setBoolean(L_ACTIVE, true).list().getEntities();

        for (Entity productionLine : productionLines) {
            getRow(items, productionLine.getStringField(L_NUMBER));
        }
    }

    private void addPositionItems(final Map<String, List<GanttChartItem>> items, final GanttChartScale scale,
            final Long scheduleId, final boolean draft, final Locale locale) {
        List<Entity> positions = getPositionDD().find(POSITIONS_QUERY).setLong(L_SCHEDULE_ID, scheduleId)
                .setTimestamp(L_DATE_FROM, scale.getDateFrom()).setTimestamp(L_DATE_TO, scale.getDateTo()).list()
                .getEntities();

        for (Entity position : positions) {
            String rowName = position.getStringField(L_PRODUCTION_LINE_NUMBER);

            if (rowName == null) {
                continue;
            }

            Long entityId = draft ? position.getLongField(L_POSITION_ID) : null;
            String label = escape(position.getStringField(L_ORDER_NUMBER));

            GanttChartItemTooltipBuilder tooltipBuilder = new GanttChartItemTooltipBuilder().withHeader(label).addLineToContent(
                    translate(ITEM_PRODUCT_KEY, locale, escape(position.getStringField(L_PRODUCT_NUMBER)),
                            escape(position.getStringField(L_PRODUCT_NAME))));

            GanttChartItem item = scale.createGanttChartItem(rowName, label, tooltipBuilder.build(), entityId,
                    position.getDateField(L_START_TIME), position.getDateField(L_END_TIME));

            addItem(items, rowName, item);
        }
    }

    private void addPlannedEventItems(final Map<String, List<GanttChartItem>> items, final GanttChartScale scale,
            final Locale locale) {
        List<Entity> events = getPlannedEventDD().find(BOARD_EVENTS_QUERY).setTimestamp(L_DATE_FROM, scale.getDateFrom())
                .setTimestamp(L_DATE_TO, scale.getDateTo()).list().getEntities();

        for (PlacedEvent placedEvent : placeEvents(events)) {
            Entity event = placedEvent.getEvent();

            boolean requiresShutdown = event.getBooleanField(L_REQUIRES_SHUTDOWN);
            String label = escape(event.getStringField(L_EVENT_NUMBER));
            String color = requiresShutdown ? SHUTDOWN_COLOR : MAINTENANCE_COLOR;
            Date startDate = event.getDateField(L_START_DATE);
            Date finishDate = event.getDateField(L_FINISH_DATE);
            GanttChartItemTooltipBuilder tooltipBuilder = getPlannedEventTooltip(event, label, requiresShutdown, locale);

            for (LineReference productionLine : placedEvent.getProductionLines()) {
                String rowName = productionLine.getNumber();

                if (rowName == null) {
                    continue;
                }

                GanttChartItem item = scale.createGanttChartItem(rowName, label, tooltipBuilder.build(), null, startDate,
                        finishDate);

                if (item != null) {
                    item.addBackgroundStrip(GanttChartItemStripFactory.create(color, STRIP_SIZE));
                }

                addItem(items, rowName, item);
            }
        }
    }

    /**
     * Returns the queried planned events that are placed on at least one production line, in their query order, each with
     * the production lines the rule of {@link #resolveEventLines(Entity)} gives it. The production lines of the divisions of
     * events that have neither their own nor a workstation production line are read in one query.
     */
    private List<PlacedEvent> placeEvents(final List<Entity> events) {
        Set<Long> divisionIds = new LinkedHashSet<Long>();

        for (Entity event : events) {
            Long divisionId = event.getLongField(L_DIVISION_ID);

            if (event.getLongField(L_EVENT_LINE_ID) == null && event.getLongField(L_WORKSTATION_LINE_ID) == null
                    && divisionId != null) {
                divisionIds.add(divisionId);
            }
        }

        Map<Long, List<LineReference>> divisionLines = findDivisionLines(divisionIds);

        List<PlacedEvent> placedEvents = new ArrayList<PlacedEvent>();

        for (Entity event : events) {
            List<LineReference> productionLines = selectEventLines(eventLineReference(event), workstationLineReference(event),
                    divisionLines.get(event.getLongField(L_DIVISION_ID)));

            if (!productionLines.isEmpty()) {
                placedEvents.add(new PlacedEvent(event, productionLines));
            }
        }

        return placedEvents;
    }

    /**
     * Returns the production lines of the given divisions, keyed by division id, read in one query; returns an empty map
     * without a query when no division id is given.
     */
    private Map<Long, List<LineReference>> findDivisionLines(final Set<Long> divisionIds) {
        Map<Long, List<LineReference>> divisionLines = new HashMap<Long, List<LineReference>>();

        if (divisionIds.isEmpty()) {
            return divisionLines;
        }

        List<Entity> rows = getDivisionDD().find(DIVISION_LINES_QUERY)
                .setParameterList(L_DIVISION_IDS, new ArrayList<Long>(divisionIds)).list().getEntities();

        for (Entity row : rows) {
            Long divisionId = row.getLongField(L_DIVISION_ID);

            if (!divisionLines.containsKey(divisionId)) {
                divisionLines.put(divisionId, new ArrayList<LineReference>());
            }

            divisionLines.get(divisionId).add(
                    new LineReference(row.getLongField(L_PRODUCTION_LINE_ID), row.getStringField(L_PRODUCTION_LINE_NUMBER)));
        }

        return divisionLines;
    }

    /**
     * Returns the event's own production line when it is not null; otherwise its workstation's production line when that is
     * not null; otherwise a copy of its division's production lines when they are neither null nor empty; otherwise an empty
     * list.
     */
    private static <T> List<T> selectEventLines(final T eventLine, final T workstationLine, final List<T> divisionLines) {
        if (eventLine != null) {
            return Collections.singletonList(eventLine);
        }

        if (workstationLine != null) {
            return Collections.singletonList(workstationLine);
        }

        if (divisionLines != null && !divisionLines.isEmpty()) {
            return new ArrayList<T>(divisionLines);
        }

        return Collections.emptyList();
    }

    /**
     * Returns the reference of the queried event row's own production line, or null when it has none.
     */
    private static LineReference eventLineReference(final Entity event) {
        return lineReference(event.getLongField(L_EVENT_LINE_ID), event.getStringField(L_EVENT_LINE_NUMBER));
    }

    /**
     * Returns the reference of the queried event row's workstation production line, or null when it has none.
     */
    private static LineReference workstationLineReference(final Entity event) {
        return lineReference(event.getLongField(L_WORKSTATION_LINE_ID), event.getStringField(L_WORKSTATION_LINE_NUMBER));
    }

    /**
     * Returns the reference of the production line with the given id and number, or null when the id is null.
     */
    private static LineReference lineReference(final Long id, final String number) {
        if (id == null) {
            return null;
        }

        return new LineReference(id, number);
    }

    /**
     * Returns whether the production line references include the production line with the given id.
     */
    private static boolean containsLine(final List<LineReference> productionLines, final Long productionLineId) {
        for (LineReference productionLine : productionLines) {
            if (productionLineId.equals(productionLine.getId())) {
                return true;
            }
        }

        return false;
    }

    private GanttChartItemTooltipBuilder getPlannedEventTooltip(final Entity event, final String label,
            final boolean requiresShutdown, final Locale locale) {
        String typeLabel = escape(translate(PLANNED_EVENT_TYPE_VALUE_PREFIX + event.getStringField(L_EVENT_TYPE), locale));
        String stateLabel = translate(PLANNED_EVENT_STATE_VALUE_PREFIX + event.getStringField(L_EVENT_STATE), locale);
        String requiresShutdownLabel = translate(requiresShutdown ? TRUE_KEY : FALSE_KEY, locale);

        return new GanttChartItemTooltipBuilder().withHeader(label)
                .addLineToContent(translate(ITEM_PLANNED_EVENT_KEY, locale, typeLabel, stateLabel))
                .addLineToContent(translate(ITEM_REQUIRES_SHUTDOWN_KEY, locale, requiresShutdownLabel));
    }

    /**
     * Returns the row with the given name, adding an empty row when it is absent; returns null and adds no row when the name
     * is null.
     */
    private List<GanttChartItem> getRow(final Map<String, List<GanttChartItem>> items, final String rowName) {
        if (rowName == null) {
            return null;
        }

        if (!items.containsKey(rowName)) {
            items.put(rowName, new ArrayList<GanttChartItem>());
        }

        return items.get(rowName);
    }

    private void addItem(final Map<String, List<GanttChartItem>> items, final String rowName, final GanttChartItem item) {
        if (item == null) {
            return;
        }

        getRow(items, rowName).add(item);
    }

    /**
     * Returns the translation of the message code, or an empty string when the translation is null.
     */
    private String translate(final String messageCode, final Locale locale, final String... args) {
        return StringUtils.defaultString(translationService.translate(messageCode, locale, args));
    }

    private String escape(final String value) {
        return StringEscapeUtils.escapeHtml4(StringUtils.defaultString(value));
    }

    private DataDefinition getScheduleDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE);
    }

    private DataDefinition getPositionDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION);
    }

    private DataDefinition getProductionLineDD() {
        return dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER, ProductionLinesConstants.MODEL_PRODUCTION_LINE);
    }

    private DataDefinition getPlannedEventDD() {
        return dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER, CmmsMachinePartsConstants.MODEL_PLANNED_EVENT);
    }

    private DataDefinition getDivisionDD() {
        return dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_DIVISION);
    }

    /**
     * Id and number of a production line an event is placed on.
     */
    private static final class LineReference {

        private final Long id;

        private final String number;

        private LineReference(final Long id, final String number) {
            this.id = id;
            this.number = number;
        }

        private Long getId() {
            return id;
        }

        private String getNumber() {
            return number;
        }

    }

    /**
     * A queried planned event row with the production lines it is placed on.
     */
    private static final class PlacedEvent {

        private final Entity event;

        private final List<LineReference> productionLines;

        private PlacedEvent(final Entity event, final List<LineReference> productionLines) {
            this.event = event;
            this.productionLines = productionLines;
        }

        private Entity getEvent() {
            return event;
        }

        private List<LineReference> getProductionLines() {
            return productionLines;
        }

    }

}
