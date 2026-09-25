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

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qcadoo.localization.api.utils.DateUtils;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.cmmsMachineParts.constants.CmmsMachinePartsConstants;
import com.qcadoo.mes.cmmsMachineParts.constants.PlannedEventFields;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Validates the drop of a production line schedule position on the production and maintenance Gantt board.
 * <p>
 * {@link #validate(Entity, Entity, GanttChartMoveRequest)} runs four checks in this order and returns the first rejection:
 * <ol>
 * <li>{@link #checkRouting(Entity, Entity)} - the order's technology allows the target production line;</li>
 * <li>{@link #checkShutdownWindow(Entity, Date, Date)} - no planned event requiring a shutdown of the target production line
 * overlaps the target slot;</li>
 * <li>{@link #checkWorkingHours(Entity, Date)} - the target slot starts in the working time of the target production line;</li>
 * <li>{@link #checkConcurrentEdit(Entity, GanttChartMoveRequest)} - the stored position still matches the item as rendered on
 * the board.</li>
 * </ol>
 * The target slot is the half-open interval {@code [dateFrom, dateTo)} of the move request. Every check only reads data and
 * expects the position as currently stored, before any change of the move is applied to it.
 */
@Service
public class ProductionMaintenanceGanttMoveValidator {

    /**
     * Message key of a drop onto a production line the order's technology does not allow.
     */
    public static final String ROUTING_MISMATCH_KEY = "orders.error.inappropriateProductionLineForPositionOrder";

    /**
     * Message key of a drop overlapping a planned event that requires a shutdown: {0} event number.
     */
    public static final String SHUTDOWN_WINDOW_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.shutdownWindow";

    /**
     * Message key of a drop starting outside the working time of the target production line.
     */
    public static final String OUTSIDE_WORKING_HOURS_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.outsideWorkingHours";

    /**
     * Message key of a drop of an item whose stored position no longer matches the board.
     */
    public static final String OPTIMISTIC_LOCK_KEY = "qcadooView.validate.global.optimisticLock";

    private static final Logger LOG = LoggerFactory.getLogger(ProductionMaintenanceGanttMoveValidator.class);

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ProductionLineSchedulePositionValidators productionLineSchedulePositionValidators;

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private ShiftsService shiftsService;

    @Autowired
    private ProductionMaintenanceGanttChartItemResolver productionMaintenanceGanttChartItemResolver;

    /**
     * Runs the routing, shutdown window, working hours and concurrent edit checks in this order and returns the first
     * rejection.
     *
     * @param position
     *            the moved production line schedule position, as currently stored
     * @param targetLine
     *            the production line of the target row
     * @param request
     *            the move request holding the target slot and the item as rendered on the board
     * @return the rejection of the first failed check, or an empty optional when every check passes
     */
    public Optional<MoveRejection> validate(final Entity position, final Entity targetLine, final GanttChartMoveRequest request) {
        Optional<MoveRejection> rejection = checkRouting(position, targetLine);

        if (!rejection.isPresent()) {
            rejection = checkShutdownWindow(targetLine, request.getDateFrom(), request.getDateTo());
        }

        if (!rejection.isPresent()) {
            rejection = checkWorkingHours(targetLine, request.getDateFrom());
        }

        if (!rejection.isPresent()) {
            rejection = checkConcurrentEdit(position, request);
        }

        if (rejection.isPresent() && LOG.isDebugEnabled()) {
            LOG.debug("Gantt move of position " + position.getId() + " to production line " + targetLine.getId()
                    + " rejected with " + rejection.get().getMessageKey() + " " + Arrays.toString(rejection.get().getArgs()));
        }

        return rejection;
    }

    /**
     * Checks that the target production line can run the position's order.
     * <p>
     * The eligible production lines are those with {@code production = true} and {@code active = true}. The candidate
     * production lines are returned by
     * {@link ProductionLineSchedulePositionValidators#getProductionLinesFromTechnology(Entity, List, boolean, boolean)} for the
     * eligible lines, the schedule's {@code allowProductionLineChange} flag and the {@code canChangeProdLineForAcceptedOrders}
     * parameter. The check passes only when the target production line is both a candidate and eligible.
     *
     * @param position
     *            the moved production line schedule position
     * @param targetLine
     *            the production line of the target row
     * @return a {@link #ROUTING_MISMATCH_KEY} rejection, or an empty optional when the check passes
     */
    public Optional<MoveRejection> checkRouting(final Entity position, final Entity targetLine) {
        List<Entity> eligibleLines = getProductionLineDD().find()
                .add(SearchRestrictions.eq(ProductionLineFields.PRODUCTION, true))
                .add(SearchRestrictions.eq(ProductionLineFields.ACTIVE, true)).list().getEntities();

        Entity schedule = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE);
        boolean allowProductionLineChange = schedule.getBooleanField(ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE);
        boolean canChangeProdLineForAcceptedOrders = parameterService.getParameter().getBooleanField(
                ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS);

        List<Entity> candidateLines = productionLineSchedulePositionValidators.getProductionLinesFromTechnology(position,
                eligibleLines, allowProductionLineChange, canChangeProdLineForAcceptedOrders);

        Long targetLineId = targetLine.getId();

        if (containsId(candidateLines, targetLineId) && containsId(eligibleLines, targetLineId)) {
            return Optional.empty();
        }

        return Optional.of(new MoveRejection(ROUTING_MISMATCH_KEY));
    }

    /**
     * Checks that no planned event requiring a shutdown of the target production line overlaps the slot.
     * <p>
     * An event overlaps the slot when its start date is before {@code dateTo} and its finish date is after {@code dateFrom}.
     * Events of every state are considered. An event concerns the target production line when the line is one of the lines
     * returned by {@link ProductionMaintenanceGanttChartItemResolver#resolveEventLines(Entity)}. Events are examined in
     * ascending start date order.
     *
     * @param targetLine
     *            the production line of the target row
     * @param dateFrom
     *            start of the slot, must not be null
     * @param dateTo
     *            end of the slot, must not be null
     * @return a {@link #SHUTDOWN_WINDOW_KEY} rejection naming the number of the first overlapping event, or an empty optional
     *         when the check passes
     */
    public Optional<MoveRejection> checkShutdownWindow(final Entity targetLine, final Date dateFrom, final Date dateTo) {
        Objects.requireNonNull(dateFrom, "dateFrom");
        Objects.requireNonNull(dateTo, "dateTo");

        List<Entity> plannedEvents = getPlannedEventDD().find()
                .add(SearchRestrictions.eq(PlannedEventFields.REQUIRES_SHUTDOWN, true))
                .add(SearchRestrictions.lt(PlannedEventFields.START_DATE, dateTo))
                .add(SearchRestrictions.gt(PlannedEventFields.FINISH_DATE, dateFrom))
                .addOrder(SearchOrders.asc(PlannedEventFields.START_DATE)).list().getEntities();

        Long targetLineId = targetLine.getId();

        for (Entity plannedEvent : plannedEvents) {
            List<Entity> eventLines = productionMaintenanceGanttChartItemResolver.resolveEventLines(plannedEvent);

            if (containsId(eventLines, targetLineId)) {
                String eventNumber = plannedEvent.getStringField(PlannedEventFields.NUMBER);

                return Optional.of(new MoveRejection(SHUTDOWN_WINDOW_KEY, eventNumber));
            }
        }

        return Optional.empty();
    }

    /**
     * Checks that the slot starts in the working time of the target production line.
     * <p>
     * The check passes when {@link ShiftsService#getNearestWorkingDate(DateTime, Entity)} returns a date that is not after
     * {@code dateFrom}. It fails when that date is later than {@code dateFrom}. When no nearest working date exists, the check
     * passes only when {@link ShiftsService#findAll(Entity)} returns no shift for the target production line.
     *
     * @param targetLine
     *            the production line of the target row
     * @param dateFrom
     *            start of the slot, must not be null
     * @return an {@link #OUTSIDE_WORKING_HOURS_KEY} rejection, or an empty optional when the check passes
     */
    public Optional<MoveRejection> checkWorkingHours(final Entity targetLine, final Date dateFrom) {
        Objects.requireNonNull(dateFrom, "dateFrom");

        Optional<DateTime> nearestWorkingDate = shiftsService.getNearestWorkingDate(new DateTime(dateFrom), targetLine);

        if (nearestWorkingDate.isPresent()) {
            if (nearestWorkingDate.get().isAfter(dateFrom.getTime())) {
                return Optional.of(new MoveRejection(OUTSIDE_WORKING_HOURS_KEY));
            }

            return Optional.empty();
        }

        if (shiftsService.findAll(targetLine).isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MoveRejection(OUTSIDE_WORKING_HOURS_KEY));
    }

    /**
     * Checks that the stored position still matches the item as rendered on the board.
     * <p>
     * The stored production line number, start time, end time and item label must equal the original row name, original
     * start, original end and original name of the move request. Times are compared in the
     * {@value DateUtils#L_DATE_TIME_FORMAT} format, and the label is
     * {@link ProductionMaintenanceGanttChartItemResolver#positionLabel(Entity)}.
     *
     * @param position
     *            the moved production line schedule position, as currently stored
     * @param request
     *            the move request holding the item as rendered on the board
     * @return an {@link #OPTIMISTIC_LOCK_KEY} rejection, or an empty optional when the check passes
     */
    public Optional<MoveRejection> checkConcurrentEdit(final Entity position, final GanttChartMoveRequest request) {
        Entity productionLine = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE);
        String productionLineNumber = null;

        if (productionLine != null) {
            productionLineNumber = productionLine.getStringField(ProductionLineFields.NUMBER);
        }

        String startTime = DateUtils.toDateTimeString(position.getDateField(ProductionLineSchedulePositionFields.START_TIME));
        String endTime = DateUtils.toDateTimeString(position.getDateField(ProductionLineSchedulePositionFields.END_TIME));
        String label = productionMaintenanceGanttChartItemResolver.positionLabel(position);

        boolean unchanged = Objects.equals(productionLineNumber, request.getOriginalRowName())
                && Objects.equals(startTime, request.getOriginalDateFrom())
                && Objects.equals(endTime, request.getOriginalDateTo()) && Objects.equals(label, request.getOriginalName());

        if (unchanged) {
            return Optional.empty();
        }

        return Optional.of(new MoveRejection(OPTIMISTIC_LOCK_KEY));
    }

    /**
     * Returns true when a non-null entity of the list has the given non-null id.
     */
    private boolean containsId(final List<Entity> entities, final Long id) {
        if (id == null || entities == null) {
            return false;
        }

        for (Entity entity : entities) {
            if (entity != null && id.equals(entity.getId())) {
                return true;
            }
        }

        return false;
    }

    private DataDefinition getProductionLineDD() {
        return dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER, ProductionLinesConstants.MODEL_PRODUCTION_LINE);
    }

    private DataDefinition getPlannedEventDD() {
        return dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER, CmmsMachinePartsConstants.MODEL_PLANNED_EVENT);
    }

    /**
     * Reason of a rejected move: a message key and its message arguments.
     */
    public static final class MoveRejection {

        private final String messageKey;

        private final String[] args;

        /**
         * Creates the reason of a rejected move.
         *
         * @param messageKey
         *            message key of the reason
         * @param args
         *            message arguments of the reason; null is stored as no arguments
         */
        public MoveRejection(final String messageKey, final String... args) {
            this.messageKey = messageKey;

            if (args == null) {
                this.args = new String[0];
            } else {
                this.args = args.clone();
            }
        }

        /**
         * Returns the message key of the reason.
         *
         * @return message key
         */
        public String getMessageKey() {
            return messageKey;
        }

        /**
         * Returns a copy of the message arguments of the reason, never null.
         *
         * @return message arguments
         */
        public String[] getArgs() {
            return args.clone();
        }

    }

}
