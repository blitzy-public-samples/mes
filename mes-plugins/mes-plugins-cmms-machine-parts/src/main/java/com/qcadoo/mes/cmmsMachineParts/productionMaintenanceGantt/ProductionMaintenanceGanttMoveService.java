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

import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.Preconditions;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.constants.ProductionLineSchedulePositionFields;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.mes.productionLines.constants.ProductionLineFields;
import com.qcadoo.mes.productionLines.constants.ProductionLinesConstants;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.validators.ErrorMessage;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Moves a production line schedule position on the production and maintenance Gantt board.
 * <p>
 * {@link #move(GanttChartMoveRequest)} runs in one transaction with serializable isolation and performs these steps in order,
 * throwing {@link MoveRejectedException} at the first failure:
 * <ol>
 * <li>loads the position named by the move request and requires it to belong to the production line schedule named under
 * {@link ProductionMaintenanceGanttChartItemResolver#CONTEXT_SCHEDULE_ID} in the component context;</li>
 * <li>requires that schedule to be in the {@link ScheduleStateStringValues#DRAFT} state;</li>
 * <li>loads the production line whose number equals the target row name;</li>
 * <li>runs {@link ProductionMaintenanceGanttMoveValidator#validate(Entity, Entity, GanttChartMoveRequest)} against the position
 * as stored;</li>
 * <li>saves the position with the target production line and the dropped start and end through
 * {@link DataDefinition#save(Entity)}, which runs the model's validators and save hooks;</li>
 * <li>recomputes the positions after the move on the origin and destination rows through
 * {@link ProductionMaintenanceGanttRecomputeService#recompute(Entity, Entity, Entity, Date, Entity, Date)}.</li>
 * </ol>
 * The first three steps reject with {@link #OPTIMISTIC_LOCK_KEY}. An invalid save rejects with the saved entity's first global
 * error, else its first field error, else {@link #SAVE_FAILED_KEY}. A recompute failure rejects with
 * {@link #RECOMPUTE_FAILED_KEY}, except a concurrency conflict (see {@link #isConcurrencyConflict(Throwable)}), which is
 * rethrown unchanged. Every exception thrown out of {@code move}, a {@link MoveRejectedException} included, rolls the
 * transaction back, leaving no change of the move persisted; a conflict detected by the database at commit propagates to the
 * caller as well.
 * <p>
 * Example, as called from a {@code moveItem} listener that runs outside any transaction:
 *
 * <pre>
 * try {
 *     productionMaintenanceGanttMoveService.move(moveRequest);
 *     gantt.acceptMove();
 * } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
 *     gantt.rejectMove(e.getMessageKey(), e.getArgs());
 * } catch (RuntimeException e) {
 *     if (ProductionMaintenanceGanttMoveService.isConcurrencyConflict(e)) {
 *         gantt.rejectMove(ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);
 *     } else {
 *         throw e;
 *     }
 * }
 * </pre>
 */
@Service
public class ProductionMaintenanceGanttMoveService {

    /** Message key of a move rejected because the board no longer matches the stored data. */
    public static final String OPTIMISTIC_LOCK_KEY = ProductionMaintenanceGanttMoveValidator.OPTIMISTIC_LOCK_KEY;

    /** Message key of a move rejected because the moved position could not be saved. */
    public static final String SAVE_FAILED_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.saveFailed";

    /** Message key of a move rejected because a following position could not be recomputed. */
    public static final String RECOMPUTE_FAILED_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed";

    /** SQLSTATE reported by the database for a serialization failure. */
    private static final String SERIALIZATION_FAILURE_SQL_STATE = "40001";

    private static final Logger LOG = LoggerFactory.getLogger(ProductionMaintenanceGanttMoveService.class);

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ProductionMaintenanceGanttMoveValidator productionMaintenanceGanttMoveValidator;

    @Autowired
    private ProductionMaintenanceGanttRecomputeService productionMaintenanceGanttRecomputeService;

    /**
     * Moves the position of the move request to the target row and slot, in one transaction with serializable isolation, and
     * recomputes the following positions on the origin and destination rows.
     *
     * @param request
     *            the move request produced by the Gantt chart component's {@code moveItem} event, must not be null
     * @throws MoveRejectedException
     *             carrying the message key and arguments of the first failed step
     * @throws IllegalArgumentException
     *             when the request is null
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void move(final GanttChartMoveRequest request) {
        Preconditions.checkArgument(request != null, "move request is required");

        DataDefinition positionDD = getPositionDD();

        Long positionId = request.getItemId();
        Entity position = null;

        if (positionId != null) {
            position = positionDD.get(positionId);
        }

        if (position == null) {
            throw staleBoardRejection("position not found", positionId);
        }

        Entity schedule = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE);
        Long contextScheduleId = getContextScheduleId(request.getContext());

        if (schedule == null || contextScheduleId == null || !contextScheduleId.equals(schedule.getId())) {
            throw staleBoardRejection("position outside the board's schedule", positionId);
        }

        if (!ScheduleStateStringValues.DRAFT.equals(schedule.getStringField(ProductionLineScheduleFields.STATE))) {
            throw staleBoardRejection("schedule no longer in draft", schedule.getId());
        }

        Entity targetLine = findProductionLineByNumber(request.getTargetRowName());

        if (targetLine == null) {
            throw staleBoardRejection("no production line for target row", request.getTargetRowName());
        }

        Optional<ProductionMaintenanceGanttMoveValidator.MoveRejection> rejection = productionMaintenanceGanttMoveValidator
                .validate(position, targetLine, request);

        if (rejection.isPresent()) {
            throw new MoveRejectedException(rejection.get().getMessageKey(), rejection.get().getArgs());
        }

        Entity originLine = position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE);
        Date vacatedStart = position.getDateField(ProductionLineSchedulePositionFields.START_TIME);

        position.setField(ProductionLineSchedulePositionFields.PRODUCTION_LINE, targetLine);
        position.setField(ProductionLineSchedulePositionFields.START_TIME, request.getDateFrom());
        position.setField(ProductionLineSchedulePositionFields.END_TIME, request.getDateTo());

        Entity savedPosition = positionDD.save(position);

        if (savedPosition == null || !savedPosition.isValid()) {
            throw saveRejection(savedPosition);
        }

        recompute(schedule, savedPosition, originLine, vacatedStart, targetLine, request.getDateFrom());

        LOG.debug("Gantt move of production line schedule position {} to production line {} saved and recomputed",
                savedPosition.getId(), targetLine.getId());
    }

    /**
     * Returns true when the throwable, or any throwable in its cause chain, is a {@link ConcurrencyFailureException} (which
     * includes {@link org.springframework.dao.OptimisticLockingFailureException} and
     * {@link org.springframework.dao.CannotSerializeTransactionException}), or a {@link SQLException} whose SQLSTATE, or the
     * SQLSTATE of an exception in its {@link SQLException#getNextException()} chain, is {@code 40001}.
     *
     * @param throwable
     *            the throwable to inspect, may be null
     * @return true for a concurrency conflict, false otherwise and for null
     */
    public static boolean isConcurrencyConflict(final Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = throwable;

        while (current != null && visited.add(current)) {
            if (current instanceof ConcurrencyFailureException) {
                return true;
            }

            if (current instanceof SQLException && isSerializationFailure((SQLException) current, visited)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Returns true when the SQL exception, or an exception in its next-exception chain, reports the serialization failure
     * SQLSTATE. Exceptions of the next-exception chain are added to the visited set.
     */
    private static boolean isSerializationFailure(final SQLException sqlException, final Set<Throwable> visited) {
        if (SERIALIZATION_FAILURE_SQL_STATE.equals(sqlException.getSQLState())) {
            return true;
        }

        SQLException next = sqlException.getNextException();

        while (next != null && visited.add(next)) {
            if (SERIALIZATION_FAILURE_SQL_STATE.equals(next.getSQLState())) {
                return true;
            }

            next = next.getNextException();
        }

        return false;
    }

    /**
     * Recomputes the positions after the move and turns a failure other than a concurrency conflict into a
     * {@link #RECOMPUTE_FAILED_KEY} rejection.
     */
    private void recompute(final Entity schedule, final Entity savedPosition, final Entity originLine, final Date vacatedStart,
            final Entity targetLine, final Date slotStart) {
        try {
            productionMaintenanceGanttRecomputeService.recompute(schedule, savedPosition, originLine, vacatedStart, targetLine,
                    slotStart);
        } catch (MoveRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isConcurrencyConflict(e)) {
                throw e;
            }

            LOG.warn("Recompute after the Gantt move of production line schedule position " + savedPosition.getId() + " failed",
                    e);

            MoveRejectedException recomputeFailed = new MoveRejectedException(RECOMPUTE_FAILED_KEY);
            recomputeFailed.initCause(e);

            throw recomputeFailed;
        }
    }

    /**
     * Returns the rejection of an invalid save: the first global error, else the first field error, else
     * {@link #SAVE_FAILED_KEY}.
     */
    private MoveRejectedException saveRejection(final Entity savedPosition) {
        if (savedPosition != null) {
            List<ErrorMessage> globalErrors = savedPosition.getGlobalErrors();

            if (globalErrors != null && !globalErrors.isEmpty()) {
                return toRejection(globalErrors.get(0));
            }

            Map<String, ErrorMessage> fieldErrors = savedPosition.getErrors();

            if (fieldErrors != null) {
                for (ErrorMessage fieldError : fieldErrors.values()) {
                    return toRejection(fieldError);
                }
            }
        }

        return new MoveRejectedException(SAVE_FAILED_KEY);
    }

    private MoveRejectedException toRejection(final ErrorMessage errorMessage) {
        LOG.debug("Save of the moved production line schedule position rejected with {}", errorMessage.getMessage());

        return new MoveRejectedException(errorMessage.getMessage(), errorMessage.getVars());
    }

    /**
     * Returns an {@link #OPTIMISTIC_LOCK_KEY} rejection and logs the failed precondition at debug level.
     */
    private MoveRejectedException staleBoardRejection(final String failedPrecondition, final Object subject) {
        LOG.debug("Gantt move rejected with the optimistic lock message: {} ({})", failedPrecondition, subject);

        return new MoveRejectedException(OPTIMISTIC_LOCK_KEY);
    }

    /**
     * Returns the production line schedule id held by the component context, or null when it is missing or not a number.
     */
    private Long getContextScheduleId(final JSONObject context) {
        if (context == null) {
            return null;
        }

        String scheduleId = context.optString(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID);

        if (StringUtils.isBlank(scheduleId)) {
            return null;
        }

        try {
            return Long.valueOf(scheduleId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the production line with the given number, or null when the number is null or no production line has it.
     */
    private Entity findProductionLineByNumber(final String number) {
        if (number == null) {
            return null;
        }

        return getProductionLineDD().find().add(SearchRestrictions.eq(ProductionLineFields.NUMBER, number)).setMaxResults(1)
                .uniqueResult();
    }

    private DataDefinition getPositionDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION);
    }

    private DataDefinition getProductionLineDD() {
        return dataDefinitionService.get(ProductionLinesConstants.PLUGIN_IDENTIFIER, ProductionLinesConstants.MODEL_PRODUCTION_LINE);
    }

    /**
     * Carries the message key and message arguments of a rejected move.
     */
    public static class MoveRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String messageKey;

        private final String[] args;

        /**
         * Creates the exception of a rejected move.
         *
         * @param messageKey
         *            message key of the rejection reason
         * @param args
         *            message arguments of the rejection reason
         */
        public MoveRejectedException(final String messageKey, final String... args) {
            super(messageKey);
            this.messageKey = messageKey;
            if (args == null) {
                this.args = new String[0];
            } else {
                this.args = args.clone();
            }
        }

        /**
         * Returns the message key of the rejection reason.
         *
         * @return message key
         */
        public String getMessageKey() {
            return messageKey;
        }

        /**
         * Returns a copy of the message arguments of the rejection reason, never null.
         *
         * @return message arguments
         */
        public String[] getArgs() {
            return args.clone();
        }

    }

}
