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
import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
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
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventType;
import com.qcadoo.mes.cmmsMachineParts.states.constants.PlannedEventState;
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
import com.qcadoo.view.internal.components.ganttChart.GanttChartScaleImpl;

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
 * {@link #resolveEventLines(Entity)} gives them, always without an entity id and with a full-width colour strip. An event
 * with only one of its start and finish dates is open-ended on the side of the missing date: its item runs from the start
 * of the item window, or to the end of the item window, on that side, and its tooltip holds a line naming the missing
 * date. An event with neither date is not placed.</li>
 * </ul>
 * User-entered display strings are HTML-escaped before they become item labels or tooltip lines. Overlapping items are left
 * to the Gantt component's collision detection.
 * <p>
 * On the component's {@code initialize} event, whose scale reports {@link GanttChartScale#getIsDatesSet()} as
 * {@code true}, the scale is fitted to the schedule before any item is placed: it starts on the day of the schedule's
 * earliest position start, or on the day of the schedule's start time when the schedule has no position on a production
 * line, and keeps the length in days of the incoming scale, extended to the day of the latest position end and capped at
 * the maximum range of the zoom level. A schedule with neither a position on a production line nor a start time keeps the
 * incoming scale. On every other event the scale is used as it comes.
 * <p>
 * Every read is one HQL query through {@link DataDefinition#find(String)}, without a row count and without loading whole
 * entities; each query constant documents its projection. Besides the columns the board shows, the queries select the
 * schedule id and state, the position ids, and the production line and division ids.
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
     * Strip colour (light blue) of planned events that do not require a shutdown.
     */
    public static final String MAINTENANCE_COLOR = "#B3DBF7";

    /**
     * Strip colour (light red) of planned events that require a shutdown.
     */
    public static final String SHUTDOWN_COLOR = "#F7968E";

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

    /**
     * Third tooltip line of a planned event without a start date; it takes no argument.
     */
    public static final String ITEM_NO_START_DATE_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.noStartDate";

    /**
     * Third tooltip line of a planned event without a finish date; it takes no argument.
     */
    public static final String ITEM_NO_FINISH_DATE_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.noFinishDate";

    /**
     * Type label of a planned event without a type, and state label of a planned event without a state.
     */
    public static final String ITEM_STATE_UNSPECIFIED_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.stateUnspecified";

    /**
     * Type label of a planned event whose type is none of the declared types, and state label of a planned event whose state
     * is none of the declared states.
     */
    public static final String ITEM_STATE_UNKNOWN_KEY = "cmmsMachineParts.productionMaintenanceGantt.item.stateUnknown";

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

    private static final String L_SCHEDULE_START_TIME = "scheduleStartTime";

    private static final String L_WINDOW_START = "windowStart";

    private static final String L_WINDOW_END = "windowEnd";

    /**
     * Id, state and start time of one production line schedule, by id.
     */
    static final String SCHEDULE_STATE_QUERY = "select s.id as " + L_SCHEDULE_IDENTIFIER + ", s."
            + ProductionLineScheduleFields.STATE + " as " + L_STATE + ", s." + ProductionLineScheduleFields.START_TIME + " as "
            + L_SCHEDULE_START_TIME + " from #orders_productionLineSchedule s where s.id = :" + L_SCHEDULE_ID;

    /**
     * Earliest start time and latest end time of the positions of one schedule that have a production line; one row whose
     * two values are null when the schedule has no such position.
     */
    static final String SCHEDULE_WINDOW_QUERY = "select min(p." + ProductionLineSchedulePositionFields.START_TIME + ") as "
            + L_WINDOW_START + ", max(p." + ProductionLineSchedulePositionFields.END_TIME + ") as " + L_WINDOW_END
            + " from #orders_productionLineSchedulePosition p where p."
            + ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE + ".id = :" + L_SCHEDULE_ID + " and p."
            + ProductionLineSchedulePositionFields.PRODUCTION_LINE + " is not null";

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
     * Planned events of every state that have a start date, a finish date or both and overlap {@code [dateFrom, dateTo)},
     * with the id and number of their own production line and of their workstation's production line, and their division
     * id. An event overlaps when its start date is null or before {@code dateTo} and its finish date is null or after
     * {@code dateFrom}; an event with both dates null is not selected.
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
            + " wsLine left join ev." + PlannedEventFields.DIVISION + " dv where (ev." + PlannedEventFields.START_DATE
            + " is not null or ev." + PlannedEventFields.FINISH_DATE + " is not null) and (ev." + PlannedEventFields.START_DATE
            + " is null or ev." + PlannedEventFields.START_DATE + " < :" + L_DATE_TO + ") and (ev."
            + PlannedEventFields.FINISH_DATE + " is null or ev." + PlannedEventFields.FINISH_DATE + " > :" + L_DATE_FROM + ")";

    /**
     * Order of the planned events of {@link #EVENTS_SELECT}: events without a start date first, then ascending start date,
     * then ascending id.
     */
    private static final String EVENTS_ORDER = " order by case when ev." + PlannedEventFields.START_DATE
            + " is null then 0 else 1 end asc, ev." + PlannedEventFields.START_DATE + " asc, ev.id asc";

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
     * <p>
     * When {@link GanttChartScale#getIsDatesSet()} returns {@code true}, the scale is first fitted to the schedule as
     * {@link #fitScale(GanttChartScale, Long, Entity)} describes, and the items are those overlapping the fitted days from
     * the start of the first day to 23:59:59 of the last day. Otherwise, and when the fit leaves the scale unchanged, the
     * items are those overlapping {@code [scale.getDateFrom(), scale.getDateTo())}. A context naming no existing schedule
     * leaves the scale unchanged.
     * <p>
     * That range is the item window. A planned event overlaps it by the rule of {@link #EVENTS_SELECT}. The item of an
     * event without a start date starts at the start of the item window, and the item of an event without a finish date
     * ends at the end of the item window; each such item's tooltip ends with the {@link #ITEM_NO_START_DATE_KEY} or
     * {@link #ITEM_NO_FINISH_DATE_KEY} line. An event with neither date yields no item.
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

        ItemWindow window = null;

        if (Boolean.TRUE.equals(scale.getIsDatesSet())) {
            window = fitScale(scale, scheduleId, schedule);
        }

        if (window == null) {
            window = new ItemWindow(scale.getDateFrom(), scale.getDateTo());
        }

        addProductionLineRows(items);
        addPositionItems(items, scale, window, scheduleId, draft, locale);
        addPlannedEventItems(items, scale, window, locale);

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
     * {@link #resolveEventLines(Entity)}: events without a start date first, then in ascending start date order, then in
     * ascending id order.
     * <p>
     * An event with both dates overlaps when its start date is before {@code dateTo} and its finish date is after
     * {@code dateFrom}. An event without a finish date is open-ended after its start and overlaps when its start date is
     * before {@code dateTo}; an event without a start date is open-ended before its finish and overlaps when its finish
     * date is after {@code dateFrom}. An event with neither date overlaps no interval. The board applies the same rule.
     * <p>
     * For example, over {@code [2026-10-08 08:00, 2026-10-08 10:00)} an event starting 2026-10-08 07:00 without a finish
     * date overlaps, and an event without a start date finishing 2026-10-08 08:00 does not.
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
     * {@value #LOG_VALUE_MAX_LENGTH} characters of the value long, followed by the value's length when it was cut. A value
     * longer than {@value #LOG_VALUE_MAX_LENGTH} characters is cut after its first {@value #LOG_VALUE_MAX_LENGTH}
     * characters, or after its first {@value #LOG_VALUE_MAX_LENGTH} characters but one when the last of them is a high
     * surrogate; a cut text never ends in a high surrogate. The text is enclosed in quotes; backslashes and quotes of the
     * value are escaped with a backslash, and control characters and the Unicode line and paragraph separators are written
     * as a backslash, the letter {@code u} and the four lower-case hexadecimal digits of the character.
     * <p>
     * For example a value made of {@code x}, a line feed and {@code WARN forged} is written on one line, with the line feed
     * written as a backslash followed by {@code u000a}. A value of 63 {@code a} followed by the two characters of the
     * surrogate pair of U+1F600 is written as the 63 {@code a} in quotes followed by {@code ... (65 characters)}.
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

        if (length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))) {
            length--;
        }

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
     * Returns the schedule id a component context value holds: null when the value is null or blank; otherwise the value
     * without leading and trailing whitespace read as a {@code long}, or null when it is not one, in which case the value is
     * logged as a warning through {@link #toLogValue(String)}.
     * <p>
     * For example {@code " 12 "} gives {@code 12}, and {@code "12a"} gives null and logs
     * {@code Invalid production line schedule id in Gantt context: "12a"}.
     *
     * @param scheduleId
     *            the text of the {@link #CONTEXT_SCHEDULE_ID} context value, may be null
     * @return the schedule id, or null when the value holds none
     */
    public Long parseScheduleId(final String scheduleId) {
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

    /**
     * Returns the schedule id of the context through {@link #parseScheduleId(String)}, or null when the context is null.
     */
    private Long getScheduleId(final JSONObject context) {
        if (context == null) {
            return null;
        }

        return parseScheduleId(context.optString(CONTEXT_SCHEDULE_ID));
    }

    /**
     * Fits the scale to the schedule and returns the item window of the fitted days, or returns null and leaves the scale
     * unchanged when the schedule has neither a position on a production line nor a start time.
     * <p>
     * The first day is the day of the earliest start of the schedule's positions that have a production line, or the day
     * of the schedule's start time when there is no such position. The last day is the first day plus the number of days
     * from the day of the incoming scale start to the day of the incoming scale end, at least zero; it moves to the day of
     * the latest end of those positions when that day is later, and back to the day before the first day plus the maximum
     * range in months of the scale's zoom level when it is later than that day. A scale that is no
     * {@link GanttChartScaleImpl} is capped at the smallest maximum range of the {@link GanttChartScaleImpl.ZoomLevel}
     * values.
     * <p>
     * For example positions from 2026-10-06 06:00 to 2026-10-06 13:30 and an incoming scale from 2026-09-27 to 2026-10-18
     * give a scale from 2026-10-06 to 2026-10-27 and the item window 2026-10-06 00:00:00 to 2026-10-27 23:59:59.
     *
     * @param scale
     *            the scale to fit
     * @param scheduleId
     *            the id of the schedule
     * @param schedule
     *            the {@link #SCHEDULE_STATE_QUERY} row of the schedule
     * @return the item window from the start of the first day to 23:59:59 of the last day, or null when the scale is not
     *         fitted
     */
    private ItemWindow fitScale(final GanttChartScale scale, final Long scheduleId, final Entity schedule) {
        Entity scheduleWindow = getPositionDD().find(SCHEDULE_WINDOW_QUERY).setLong(L_SCHEDULE_ID, scheduleId).uniqueResult();

        Date windowStart = null;
        Date windowEnd = null;

        if (scheduleWindow != null) {
            windowStart = scheduleWindow.getDateField(L_WINDOW_START);
            windowEnd = scheduleWindow.getDateField(L_WINDOW_END);
        }

        Date anchor = windowStart;

        if (anchor == null) {
            anchor = schedule.getDateField(L_SCHEDULE_START_TIME);
        }

        if (anchor == null) {
            return null;
        }

        LocalDate firstDay = new LocalDate(anchor);
        int span = Math.max(0, Days.daysBetween(new LocalDate(scale.getDateFrom()), new LocalDate(scale.getDateTo()))
                .getDays());
        LocalDate lastDay = firstDay.plusDays(span);

        if (windowEnd != null && new LocalDate(windowEnd).isAfter(lastDay)) {
            lastDay = new LocalDate(windowEnd);
        }

        LocalDate maxLastDay = firstDay.plusMonths(getMaxRangeInMonths(scale)).minusDays(1);

        if (lastDay.isAfter(maxLastDay)) {
            lastDay = maxLastDay;
        }

        Date firstDayStart = firstDay.toDateTimeAtStartOfDay().toDate();

        scale.setDateFrom(firstDayStart);
        scale.setDateTo(lastDay.toDateTimeAtStartOfDay().toDate());

        return new ItemWindow(firstDayStart, lastDay.toDateTime(new LocalTime(23, 59, 59)).toDate());
    }

    /**
     * Returns the maximum range in months of the scale's zoom level, or the smallest maximum range of the
     * {@link GanttChartScaleImpl.ZoomLevel} values when the scale is no {@link GanttChartScaleImpl}.
     */
    private static int getMaxRangeInMonths(final GanttChartScale scale) {
        if (scale instanceof GanttChartScaleImpl) {
            return ((GanttChartScaleImpl) scale).getMaxRangeInMonths();
        }

        int maxRangeInMonths = Integer.MAX_VALUE;

        for (GanttChartScaleImpl.ZoomLevel zoomLevel : GanttChartScaleImpl.ZoomLevel.values()) {
            maxRangeInMonths = Math.min(maxRangeInMonths, zoomLevel.getMaxRangeInMonths());
        }

        return maxRangeInMonths;
    }

    private void addProductionLineRows(final Map<String, List<GanttChartItem>> items) {
        List<Entity> productionLines = getProductionLineDD().find(PRODUCTION_LINE_ROWS_QUERY).setBoolean(L_PRODUCTION, true)
                .setBoolean(L_ACTIVE, true).list().getEntities();

        for (Entity productionLine : productionLines) {
            getRow(items, productionLine.getStringField(L_NUMBER));
        }
    }

    private void addPositionItems(final Map<String, List<GanttChartItem>> items, final GanttChartScale scale,
            final ItemWindow window, final Long scheduleId, final boolean draft, final Locale locale) {
        List<Entity> positions = getPositionDD().find(POSITIONS_QUERY).setLong(L_SCHEDULE_ID, scheduleId)
                .setTimestamp(L_DATE_FROM, window.getDateFrom()).setTimestamp(L_DATE_TO, window.getDateTo()).list()
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
            final ItemWindow window, final Locale locale) {
        List<Entity> events = getPlannedEventDD().find(BOARD_EVENTS_QUERY).setTimestamp(L_DATE_FROM, window.getDateFrom())
                .setTimestamp(L_DATE_TO, window.getDateTo()).list().getEntities();

        for (PlacedEvent placedEvent : placeEvents(events)) {
            Entity event = placedEvent.getEvent();

            boolean requiresShutdown = event.getBooleanField(L_REQUIRES_SHUTDOWN);
            String label = escape(event.getStringField(L_EVENT_NUMBER));
            String color = requiresShutdown ? SHUTDOWN_COLOR : MAINTENANCE_COLOR;
            Date startDate = event.getDateField(L_START_DATE);
            Date finishDate = event.getDateField(L_FINISH_DATE);
            Date itemDateFrom = startDate == null ? window.getDateFrom() : startDate;
            Date itemDateTo = finishDate == null ? window.getDateTo() : finishDate;
            GanttChartItemTooltipBuilder tooltipBuilder = getPlannedEventTooltip(event, label, requiresShutdown, startDate,
                    finishDate, locale);

            for (LineReference productionLine : placedEvent.getProductionLines()) {
                String rowName = productionLine.getNumber();

                if (rowName == null) {
                    continue;
                }

                GanttChartItem item = scale.createGanttChartItem(rowName, label, tooltipBuilder.build(), null, itemDateFrom,
                        itemDateTo);

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

    /**
     * Returns the tooltip of a planned event's items: the label as header, the {@link #ITEM_PLANNED_EVENT_KEY} line of its
     * type and state labels and the {@link #ITEM_REQUIRES_SHUTDOWN_KEY} line, followed by the
     * {@link #ITEM_NO_START_DATE_KEY} line when the start date is null and by the {@link #ITEM_NO_FINISH_DATE_KEY} line when
     * the finish date is null.
     */
    private GanttChartItemTooltipBuilder getPlannedEventTooltip(final Entity event, final String label,
            final boolean requiresShutdown, final Date startDate, final Date finishDate, final Locale locale) {
        String typeLabel = typeLabel(event.getStringField(L_EVENT_TYPE), locale);
        String stateLabel = stateLabel(event.getStringField(L_EVENT_STATE), locale);
        String requiresShutdownLabel = translate(requiresShutdown ? TRUE_KEY : FALSE_KEY, locale);

        GanttChartItemTooltipBuilder tooltipBuilder = new GanttChartItemTooltipBuilder().withHeader(label)
                .addLineToContent(translate(ITEM_PLANNED_EVENT_KEY, locale, typeLabel, stateLabel))
                .addLineToContent(translate(ITEM_REQUIRES_SHUTDOWN_KEY, locale, requiresShutdownLabel));

        if (startDate == null) {
            tooltipBuilder.addLineToContent(translate(ITEM_NO_START_DATE_KEY, locale));
        }

        if (finishDate == null) {
            tooltipBuilder.addLineToContent(translate(ITEM_NO_FINISH_DATE_KEY, locale));
        }

        return tooltipBuilder;
    }

    /**
     * Returns the HTML-escaped type label of a planned event: the translation of {@link #ITEM_STATE_UNSPECIFIED_KEY} when
     * the type is null or blank; the translation of the type's value key when the type is the string value of one of the
     * {@link PlannedEventType} constants; otherwise the translation of {@link #ITEM_STATE_UNKNOWN_KEY}. A message code is
     * built only from the string value of a declared type, never from the given type.
     * <p>
     * For example {@code 02repairs} gives the translation of {@code cmmsMachineParts.plannedEvent.type.value.02repairs},
     * and {@code 09imported} gives the translation of {@link #ITEM_STATE_UNKNOWN_KEY}.
     *
     * @param type
     *            the persisted type of the planned event, may be null
     * @param locale
     *            the locale of the translation
     * @return the escaped type label
     */
    private String typeLabel(final String type, final Locale locale) {
        String messageCode = ITEM_STATE_UNKNOWN_KEY;

        if (StringUtils.isBlank(type)) {
            messageCode = ITEM_STATE_UNSPECIFIED_KEY;
        } else {
            for (PlannedEventType declaredType : PlannedEventType.values()) {
                if (declaredType.getStringValue().equals(type)) {
                    messageCode = PLANNED_EVENT_TYPE_VALUE_PREFIX + declaredType.getStringValue();

                    break;
                }
            }
        }

        return escape(translate(messageCode, locale));
    }

    /**
     * Returns the HTML-escaped state label of a planned event: the translation of {@link #ITEM_STATE_UNSPECIFIED_KEY} when
     * the state is null or blank; the translation of the state's value key when the state is the string value of one of the
     * {@link PlannedEventState} constants; otherwise the translation of {@link #ITEM_STATE_UNKNOWN_KEY}. A message code is
     * built only from the string value of a declared state, never from the given state.
     * <p>
     * For example {@code 03planned} gives the translation of {@code cmmsMachineParts.plannedEvent.state.value.03planned},
     * and {@code 09archived} gives the translation of {@link #ITEM_STATE_UNKNOWN_KEY}.
     *
     * @param state
     *            the persisted state of the planned event, may be null
     * @param locale
     *            the locale of the translation
     * @return the escaped state label
     */
    private String stateLabel(final String state, final Locale locale) {
        String messageCode = ITEM_STATE_UNKNOWN_KEY;

        if (StringUtils.isBlank(state)) {
            messageCode = ITEM_STATE_UNSPECIFIED_KEY;
        } else {
            for (PlannedEventState declaredState : PlannedEventState.values()) {
                if (declaredState.getStringValue().equals(state)) {
                    messageCode = PLANNED_EVENT_STATE_VALUE_PREFIX + declaredState.getStringValue();

                    break;
                }
            }
        }

        return escape(translate(messageCode, locale));
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
     * Id and optional number of a production line an event is placed on; the number is null when only the id is known.
     * {@link #findShutdownEventNumbers(Entity, Date, Date)} uses an id-only reference of the given production line as the
     * division production line.
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
     * Date range whose overlapping positions and planned events become board items: from {@code dateFrom} to
     * {@code dateTo}. Its bounds are also the item bounds of a planned event on the side of its missing start or finish
     * date.
     */
    private static final class ItemWindow {

        private final Date dateFrom;

        private final Date dateTo;

        private ItemWindow(final Date dateFrom, final Date dateTo) {
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
        }

        private Date getDateFrom() {
            return dateFrom;
        }

        private Date getDateTo() {
            return dateTo;
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
