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

import java.util.Collections;
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
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.OrderStateStringValues;
import com.qcadoo.mes.orders.validators.ProductionLineSchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
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
 * The target slot is the half-open interval {@code [dateFrom, dateTo)} of the move request. Every check only reads data; the
 * basic parameter is read without being created. Every check expects the position as currently stored, before any change of
 * the move is applied to it.
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
                    + " rejected with " + rejection.get().getMessageKey());
        }

        return rejection;
    }

    /**
     * Checks that the target production line can run the position's order.
     * <p>
     * The target production line is eligible when its own {@code production} field is true and it is active; no
     * production line is queried. An ineligible target production line rejects, and so does a position without an order.
     * <p>
     * The candidate production lines are returned by
     * {@link ProductionLineSchedulePositionValidators#getProductionLinesFromTechnology(Entity, List, boolean, boolean)} for the
     * target production line as the only eligible line, the schedule's {@code allowProductionLineChange} flag and the
     * {@code canChangeProdLineForAcceptedOrders} parameter. That parameter is read without creating the basic parameter, and is
     * false when no basic parameter exists. The check passes only when the target production line is a candidate.
     * <p>
     * The order's own production line is kept as the only candidate, and neither the order's state nor its technology is
     * needed, when the order has a production line and production line change is not allowed. It is also kept, and the
     * technology is not needed, when the order has a production line and is accepted while
     * {@code canChangeProdLineForAcceptedOrders} is false. The check rejects without looking up candidates when the order has
     * a production line, production line change is allowed and the order has no state, or when the order's production line
     * is not kept and the order has no technology.
     *
     * @param position
     *            the moved production line schedule position
     * @param targetLine
     *            the production line of the target row
     * @return a {@link #ROUTING_MISMATCH_KEY} rejection, or an empty optional when the check passes
     */
    public Optional<MoveRejection> checkRouting(final Entity position, final Entity targetLine) {
        boolean targetLineEligible = targetLine.getBooleanField(ProductionLineFields.PRODUCTION)
                && targetLine.isActive();

        if (!targetLineEligible) {
            return Optional.of(new MoveRejection(ROUTING_MISMATCH_KEY));
        }

        Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);

        if (order == null) {
            return Optional.of(new MoveRejection(ROUTING_MISMATCH_KEY));
        }

        Entity schedule = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE);
        boolean allowProductionLineChange = schedule.getBooleanField(ProductionLineScheduleFields.ALLOW_PRODUCTION_LINE_CHANGE);
        boolean canChangeProdLineForAcceptedOrders = canChangeProdLineForAcceptedOrders();

        if (!hasRoutingInputs(order, allowProductionLineChange, canChangeProdLineForAcceptedOrders)) {
            return Optional.of(new MoveRejection(ROUTING_MISMATCH_KEY));
        }

        List<Entity> candidateLines = productionLineSchedulePositionValidators.getProductionLinesFromTechnology(position,
                Collections.singletonList(targetLine), allowProductionLineChange, canChangeProdLineForAcceptedOrders);

        if (containsId(candidateLines, targetLine.getId())) {
            return Optional.empty();
        }

        return Optional.of(new MoveRejection(ROUTING_MISMATCH_KEY));
    }

    /**
     * Checks that no planned event requiring a shutdown of the target production line overlaps the slot.
     * <p>
     * An event overlaps the slot when its start date is before {@code dateTo} and its finish date is after {@code dateFrom}.
     * Events of every state are considered. An event concerns the target production line when the rule of
     * {@link ProductionMaintenanceGanttChartItemResolver#resolveEventLines(Entity)} places it on that line. The events are
     * read by {@link ProductionMaintenanceGanttChartItemResolver#findShutdownEventNumbers(Entity, Date, Date)}, in ascending
     * start date order.
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

        List<String> eventNumbers = productionMaintenanceGanttChartItemResolver.findShutdownEventNumbers(targetLine, dateFrom,
                dateTo);

        if (eventNumbers.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MoveRejection(SHUTDOWN_WINDOW_KEY, eventNumbers.get(0)));
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

    /**
     * Returns the {@code canChangeProdLineForAcceptedOrders} flag of the basic parameter. Returns false, without calling
     * {@link ParameterService#getParameter()}, when the basic parameter data definition is missing or holds no parameter; no
     * basic parameter is created.
     */
    private boolean canChangeProdLineForAcceptedOrders() {
        DataDefinition parameterDD = dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER);

        if (parameterDD == null || parameterDD.count() == 0L) {
            return false;
        }

        return parameterService.getParameter().getBooleanField(ParameterFieldsO.CAN_CHANGE_PROD_LINE_FOR_ACCEPTED_ORDERS);
    }

    /**
     * Returns true when the order holds every field
     * {@link ProductionLineSchedulePositionValidators#getProductionLinesFromTechnology(Entity, List, boolean, boolean)} reads
     * for the given flags.
     * <p>
     * An order with a production line needs nothing more when production line change is not allowed. When production line
     * change is allowed, it needs a state, and needs nothing more when that state is accepted and
     * {@code canChangeProdLineForAcceptedOrders} is false. In every other case the order needs a technology.
     */
    private boolean hasRoutingInputs(final Entity order, final boolean allowProductionLineChange,
            final boolean canChangeProdLineForAcceptedOrders) {
        Entity orderProductionLine = order.getBelongsToField(OrderFields.PRODUCTION_LINE);

        if (orderProductionLine != null) {
            if (!allowProductionLineChange) {
                return true;
            }

            String orderState = order.getStringField(OrderFields.STATE);

            if (orderState == null) {
                return false;
            }
            if (OrderStateStringValues.ACCEPTED.equals(orderState) && !canChangeProdLineForAcceptedOrders) {
                return true;
            }
        }

        return order.getBelongsToField(OrderFields.TECHNOLOGY) != null;
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
