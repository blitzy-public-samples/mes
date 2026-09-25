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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
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
 * <li>planned events of every state overlapping the scale, on the row of every production line returned by
 * {@link #resolveEventLines(Entity)}, always without an entity id and with a full-width colour strip.</li>
 * </ul>
 * User-entered display strings are HTML-escaped before they become item labels or tooltip lines. Overlapping items are left
 * to the Gantt component's collision detection.
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

        Entity schedule = getSchedule(context);

        if (schedule == null) {
            return items;
        }

        addProductionLineRows(items);
        addPositionItems(items, scale, schedule, locale);
        addPlannedEventItems(items, scale, locale);

        return items;
    }

    /**
     * Returns the production lines a planned event is placed on: its own production line; otherwise the production line of
     * its workstation; otherwise every production line of its division; otherwise none.
     *
     * @param event
     *            planned event
     * @return the event's production lines, empty when none resolves
     */
    public List<Entity> resolveEventLines(final Entity event) {
        Entity productionLine = event.getBelongsToField(PlannedEventFields.PRODUCTION_LINE);

        if (productionLine != null) {
            return Collections.singletonList(productionLine);
        }

        Entity workstation = event.getBelongsToField(PlannedEventFields.WORKSTATION);

        if (workstation != null) {
            Entity workstationProductionLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);

            if (workstationProductionLine != null) {
                return Collections.singletonList(workstationProductionLine);
            }
        }

        Entity division = event.getBelongsToField(PlannedEventFields.DIVISION);

        if (division != null) {
            List<Entity> divisionProductionLines = division.getManyToManyField(DivisionFieldsPL.PRODUCTION_LINES);

            if (divisionProductionLines != null && !divisionProductionLines.isEmpty()) {
                return new ArrayList<Entity>(divisionProductionLines);
            }
        }

        return Collections.emptyList();
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

    private Entity getSchedule(final JSONObject context) {
        if (context == null) {
            return null;
        }

        String scheduleId = context.optString(CONTEXT_SCHEDULE_ID);

        if (StringUtils.isBlank(scheduleId)) {
            return null;
        }

        Long id;

        try {
            id = Long.valueOf(scheduleId.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid production line schedule id in Gantt context: " + scheduleId);

            return null;
        }

        Entity schedule = getScheduleDD().get(id);

        if (schedule == null) {
            LOG.warn("Cannot find production line schedule for " + id);
        }

        return schedule;
    }

    private void addProductionLineRows(final Map<String, List<GanttChartItem>> items) {
        List<Entity> productionLines = getProductionLineDD().find()
                .add(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true))
                .add(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true)).list().getEntities();

        for (Entity productionLine : productionLines) {
            getRow(items, productionLine.getStringField(ProductionLineFields.NUMBER));
        }
    }

    private void addPositionItems(final Map<String, List<GanttChartItem>> items, final GanttChartScale scale,
            final Entity schedule, final Locale locale) {
        boolean draft = ScheduleStateStringValues.DRAFT.equals(schedule.getStringField(ProductionLineScheduleFields.STATE));

        List<Entity> positions = getPositionDD().find()
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule))
                .add(SearchRestrictions.isNotNull(ProductionLineSchedulePositionFields.PRODUCTION_LINE))
                .add(SearchRestrictions.lt(ProductionLineSchedulePositionFields.START_TIME, scale.getDateTo()))
                .add(SearchRestrictions.gt(ProductionLineSchedulePositionFields.END_TIME, scale.getDateFrom()))
                .addOrder(SearchOrders.asc(ProductionLineSchedulePositionFields.START_TIME)).list().getEntities();

        for (Entity position : positions) {
            String rowName = getProductionLineNumber(position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE));

            if (rowName == null) {
                continue;
            }

            Long entityId = draft ? position.getId() : null;
            String label = positionLabel(position);

            GanttChartItem item = scale.createGanttChartItem(rowName, label, getPositionTooltip(position, label, locale).build(),
                    entityId, position.getDateField(ProductionLineSchedulePositionFields.START_TIME),
                    position.getDateField(ProductionLineSchedulePositionFields.END_TIME));

            addItem(items, rowName, item);
        }
    }

    private void addPlannedEventItems(final Map<String, List<GanttChartItem>> items, final GanttChartScale scale,
            final Locale locale) {
        List<Entity> plannedEvents = getPlannedEventDD().find()
                .add(SearchRestrictions.lt(PlannedEventFields.START_DATE, scale.getDateTo()))
                .add(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, scale.getDateFrom()))
                .addOrder(SearchOrders.asc(PlannedEventFields.START_DATE)).list().getEntities();

        for (Entity plannedEvent : plannedEvents) {
            List<Entity> productionLines = resolveEventLines(plannedEvent);

            if (productionLines.isEmpty()) {
                continue;
            }

            boolean requiresShutdown = plannedEvent.getBooleanField(PlannedEventFields.REQUIRES_SHUTDOWN);
            String label = escape(plannedEvent.getStringField(PlannedEventFields.NUMBER));
            String color = requiresShutdown ? SHUTDOWN_COLOR : MAINTENANCE_COLOR;
            Date startDate = plannedEvent.getDateField(PlannedEventFields.START_DATE);
            Date finishDate = plannedEvent.getDateField(PlannedEventFields.FINISH_DATE);
            GanttChartItemTooltipBuilder tooltipBuilder = getPlannedEventTooltip(plannedEvent, label, requiresShutdown, locale);

            for (Entity productionLine : productionLines) {
                String rowName = getProductionLineNumber(productionLine);

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

    private GanttChartItemTooltipBuilder getPositionTooltip(final Entity position, final String label, final Locale locale) {
        Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);
        Entity product = null;

        if (order != null) {
            product = order.getBelongsToField(OrderFields.PRODUCT);
        }

        String productNumber = StringUtils.EMPTY;
        String productName = StringUtils.EMPTY;

        if (product != null) {
            productNumber = product.getStringField(ProductFields.NUMBER);
            productName = product.getStringField(ProductFields.NAME);
        }

        return new GanttChartItemTooltipBuilder().withHeader(label)
                .addLineToContent(translate(ITEM_PRODUCT_KEY, locale, escape(productNumber), escape(productName)));
    }

    private GanttChartItemTooltipBuilder getPlannedEventTooltip(final Entity plannedEvent, final String label,
            final boolean requiresShutdown, final Locale locale) {
        String typeLabel = escape(
                translate(PLANNED_EVENT_TYPE_VALUE_PREFIX + plannedEvent.getStringField(PlannedEventFields.TYPE), locale));
        String stateLabel = translate(PLANNED_EVENT_STATE_VALUE_PREFIX + plannedEvent.getStringField(PlannedEventFields.STATE),
                locale);
        String requiresShutdownLabel = translate(requiresShutdown ? TRUE_KEY : FALSE_KEY, locale);

        return new GanttChartItemTooltipBuilder().withHeader(label)
                .addLineToContent(translate(ITEM_PLANNED_EVENT_KEY, locale, typeLabel, stateLabel))
                .addLineToContent(translate(ITEM_REQUIRES_SHUTDOWN_KEY, locale, requiresShutdownLabel));
    }

    private String getProductionLineNumber(final Entity productionLine) {
        if (productionLine == null) {
            return null;
        }

        return productionLine.getStringField(ProductionLineFields.NUMBER);
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

}
