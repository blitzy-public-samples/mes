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
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.base.Preconditions;
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
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;

/**
 * Recomputes the start time, end time and changeover of the production line schedule positions that follow a moved position,
 * on the production line the position left (origin row) and on the production line it was dropped on (destination row).
 * <p>
 * For every recomputed position the service runs the per-position scheduling step of batch plan generation for one fixed
 * production line: {@link ProductionLineScheduleService#getFinishDate}, {@link ProductionLineScheduleService#getFinishDateWithChildren},
 * {@link ProductionLineScheduleService#getPreviousOrder}, then {@code createProductionLinePositionNewData} and
 * {@code savePosition} of the PS or PPS executor service selected by the schedule's
 * {@link ProductionLineScheduleFields#DURATION_OF_ORDER_CALCULATED_ON_BASIS}.
 * <p>
 * Scope and order of one call:
 * <ul>
 * <li>A row is recomputed from its affected start: the vacated start on the origin row, the drop start on the destination row,
 * and the earlier of the two when origin and destination are the same row.</li>
 * <li>Only positions of the schedule on that row, other than the moved position (compared by id), whose start time is strictly
 * after the affected start are recomputed, in (start time, id) order. Positions starting at or before the affected start are
 * neither read into a chain nor written.</li>
 * <li>On a cross-row move the origin chain runs first and is seeded from the row predecessor (the latest other position starting
 * at or before the vacated start); the destination chain follows and is seeded from the moved position. A {@code null} origin
 * line runs the destination chain only.</li>
 * <li>On a same-row move one chain runs: the positions starting at or before the drop start are recomputed after the row
 * predecessor, then the caches are seeded from the moved position and the remaining positions are recomputed.</li>
 * <li>With no predecessor the caches start empty and the scheduling services use their own database lookups.</li>
 * <li>Every candidate list and predecessor is read before the first executor call. The moved position is never recomputed, and
 * each position is recomputed at most once.</li>
 * </ul>
 * The service joins the caller's transaction. When a candidate has no order, or the executor produces no data for the chain's
 * production line (including when the schedule names no known calculation basis), it throws
 * {@link ProductionMaintenanceGanttMoveService.MoveRejectedException} with
 * {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY}.
 * <p>
 * Example, as called by the move service after it saved the moved position:
 * 
 * <pre>
 * productionMaintenanceGanttRecomputeService.recompute(schedule, savedPosition, originLine, vacatedStart, targetLine,
 *         moveRequest.getDateFrom());
 * </pre>
 */
@Service
public class ProductionMaintenanceGanttRecomputeService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductionMaintenanceGanttRecomputeService.class);

    private static final String L_ID = "id";

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ProductionLineScheduleService productionLineScheduleService;

    @Autowired
    private ProductionLineScheduleServicePSExecutorService productionLineScheduleServicePSExecutorService;

    @Autowired
    private ProductionLineScheduleServicePPSExecutorService productionLineScheduleServicePPSExecutorService;

    /**
     * Recomputes positions after the affected start on the origin and destination rows of a moved position.
     * 
     * @param schedule
     *            production line schedule that holds the moved position
     * @param movedPosition
     *            the moved position as saved at its dropped slot; it must have an id
     * @param originLine
     *            production line the position was on before the move, or {@code null} when it had none
     * @param vacatedStart
     *            start time the position had before the move; required when {@code originLine} is not {@code null}
     * @param destinationLine
     *            production line the position was dropped on
     * @param slotStart
     *            start time of the dropped slot
     * @throws ProductionMaintenanceGanttMoveService.MoveRejectedException
     *             with {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} when a candidate cannot be recomputed
     * @throws IllegalArgumentException
     *             when a required argument is missing
     */
    public void recompute(final Entity schedule, final Entity movedPosition, final Entity originLine, final Date vacatedStart,
            final Entity destinationLine, final Date slotStart) {
        Preconditions.checkArgument(schedule != null, "schedule is required");
        Preconditions.checkArgument(movedPosition != null && movedPosition.getId() != null, "saved moved position is required");
        Preconditions.checkArgument(destinationLine != null, "destination production line is required");
        Preconditions.checkArgument(slotStart != null, "slot start is required");
        Preconditions.checkArgument(originLine == null || vacatedStart != null, "vacated start is required with an origin line");

        DataDefinition positionDD = getPositionDD();
        DurationOfOrderCalculatedOnBasis basis = resolveBasis(schedule);
        boolean sameRow = originLine != null && originLine.getId().equals(destinationLine.getId());

        if (sameRow) {
            recomputeSameRow(positionDD, schedule, basis, movedPosition, destinationLine, vacatedStart, slotStart);
        } else {
            recomputeCrossRow(positionDD, schedule, basis, movedPosition, originLine, vacatedStart, destinationLine, slotStart);
        }
    }

    private void recomputeCrossRow(final DataDefinition positionDD, final Entity schedule,
            final DurationOfOrderCalculatedOnBasis basis, final Entity movedPosition, final Entity originLine,
            final Date vacatedStart, final Entity destinationLine, final Date slotStart) {
        Long movedPositionId = movedPosition.getId();

        List<Entity> originCandidates = Collections.emptyList();
        Entity originPredecessor = null;
        if (originLine != null) {
            originCandidates = findCandidates(positionDD, schedule, originLine, movedPositionId, vacatedStart);
            originPredecessor = findPredecessor(positionDD, schedule, originLine, movedPositionId, vacatedStart);
        }
        List<Entity> destinationCandidates = findCandidates(positionDD, schedule, destinationLine, movedPositionId, slotStart);

        if (originLine != null) {
            Map<Long, Date> originFinishCache = new HashMap<Long, Date>();
            Map<Long, Entity> originOrderCache = new HashMap<Long, Entity>();
            seedCaches(originPredecessor, originLine, originFinishCache, originOrderCache);
            processChain(schedule, basis, originLine, originCandidates, originFinishCache, originOrderCache);
        }

        Map<Long, Date> destinationFinishCache = new HashMap<Long, Date>();
        Map<Long, Entity> destinationOrderCache = new HashMap<Long, Entity>();
        seedCaches(movedPosition, destinationLine, destinationFinishCache, destinationOrderCache);
        processChain(schedule, basis, destinationLine, destinationCandidates, destinationFinishCache, destinationOrderCache);
    }

    private void recomputeSameRow(final DataDefinition positionDD, final Entity schedule,
            final DurationOfOrderCalculatedOnBasis basis, final Entity movedPosition, final Entity line, final Date vacatedStart,
            final Date slotStart) {
        Long movedPositionId = movedPosition.getId();
        Date affectedStart = vacatedStart.before(slotStart) ? vacatedStart : slotStart;

        List<Entity> candidates = findCandidates(positionDD, schedule, line, movedPositionId, affectedStart);
        Entity predecessor = findPredecessor(positionDD, schedule, line, movedPositionId, affectedStart);

        List<Entity> beforeAnchor = new ArrayList<Entity>();
        List<Entity> afterAnchor = new ArrayList<Entity>();
        for (Entity candidate : candidates) {
            Date candidateStart = candidate.getDateField(ProductionLineSchedulePositionFields.START_TIME);
            if (candidateStart != null && !candidateStart.after(slotStart)) {
                beforeAnchor.add(candidate);
            } else {
                afterAnchor.add(candidate);
            }
        }

        Map<Long, Date> finishCache = new HashMap<Long, Date>();
        Map<Long, Entity> orderCache = new HashMap<Long, Entity>();
        seedCaches(predecessor, line, finishCache, orderCache);
        processChain(schedule, basis, line, beforeAnchor, finishCache, orderCache);
        seedCaches(movedPosition, line, finishCache, orderCache);
        processChain(schedule, basis, line, afterAnchor, finishCache, orderCache);
    }

    /**
     * Returns the schedule's positions on the given production line, other than the moved position, starting strictly after the
     * affected start, ordered by start time and id.
     */
    private List<Entity> findCandidates(final DataDefinition positionDD, final Entity schedule, final Entity line,
            final Long movedPositionId, final Date affectedStart) {
        return positionDD.find()
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule))
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, line))
                .add(SearchRestrictions.idNe(movedPositionId))
                .add(SearchRestrictions.gt(ProductionLineSchedulePositionFields.START_TIME, affectedStart))
                .addOrder(SearchOrders.asc(ProductionLineSchedulePositionFields.START_TIME)).addOrder(SearchOrders.asc(L_ID))
                .list().getEntities();
    }

    /**
     * Returns the schedule's latest position on the given production line, other than the moved position, starting at or before
     * the affected start, or {@code null} when there is none.
     */
    private Entity findPredecessor(final DataDefinition positionDD, final Entity schedule, final Entity line,
            final Long movedPositionId, final Date affectedStart) {
        return positionDD.find()
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule))
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, line))
                .add(SearchRestrictions.idNe(movedPositionId))
                .add(SearchRestrictions.le(ProductionLineSchedulePositionFields.START_TIME, affectedStart))
                .addOrder(SearchOrders.desc(ProductionLineSchedulePositionFields.START_TIME)).setMaxResults(1).uniqueResult();
    }

    /**
     * Puts the end time and the order of the given position into the chain caches under the production line id. Missing values
     * leave the corresponding cache entry unchanged; a {@code null} position leaves both caches unchanged.
     */
    private void seedCaches(final Entity position, final Entity line, final Map<Long, Date> finishCache,
            final Map<Long, Entity> orderCache) {
        if (position == null) {
            return;
        }
        Date endTime = position.getDateField(ProductionLineSchedulePositionFields.END_TIME);
        if (endTime != null) {
            finishCache.put(line.getId(), endTime);
        }
        Entity order = position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);
        if (order != null) {
            orderCache.put(line.getId(), order);
        }
    }

    /**
     * Recomputes the given positions one after another on one production line, sharing the chain caches.
     */
    private void processChain(final Entity schedule, final DurationOfOrderCalculatedOnBasis basis, final Entity line,
            final List<Entity> candidates, final Map<Long, Date> finishCache, final Map<Long, Entity> orderCache) {
        LOG.debug("Recomputing {} production line schedule positions on production line {}", candidates.size(), line.getId());
        Date scheduleStartTime = schedule.getDateField(ProductionLineScheduleFields.START_TIME);
        for (Entity candidate : candidates) {
            recomputePosition(basis, line, scheduleStartTime, candidate, finishCache, orderCache);
        }
    }

    /**
     * Recomputes one position on the given production line with the executor service of the calculation basis, updates the
     * chain caches with its new finish date and order, and saves it through the same executor service.
     */
    private void recomputePosition(final DurationOfOrderCalculatedOnBasis basis, final Entity line, final Date scheduleStartTime,
            final Entity candidate, final Map<Long, Date> finishCache, final Map<Long, Entity> orderCache) {
        Entity order = requirePresent(candidate.getBelongsToField(ProductionLineSchedulePositionFields.ORDER));
        Date finishDate = productionLineScheduleService.getFinishDate(finishCache, scheduleStartTime, line, order);
        finishDate = productionLineScheduleService.getFinishDateWithChildren(candidate, finishDate);
        Entity previousOrder = productionLineScheduleService.getPreviousOrder(orderCache, line, finishDate);
        Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);

        Map<Long, ProductionLinePositionNewData> positionNewData = new HashMap<Long, ProductionLinePositionNewData>();
        if (DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY == basis) {
            productionLineScheduleServicePSExecutorService.createProductionLinePositionNewData(positionNewData, line, finishDate,
                    candidate, technology, previousOrder);
        } else if (DurationOfOrderCalculatedOnBasis.PLAN_FOR_SHIFT == basis) {
            productionLineScheduleServicePPSExecutorService.createProductionLinePositionNewData(positionNewData, line, finishDate,
                    candidate, technology, previousOrder);
        }
        ProductionLinePositionNewData newData = requirePresent(positionNewData.get(line.getId()));

        finishCache.put(line.getId(), newData.getFinishDate());
        orderCache.put(line.getId(), order);
        candidate.setField(ProductionLineSchedulePositionFields.START_TIME, newData.getStartDate());
        candidate.setField(ProductionLineSchedulePositionFields.END_TIME, newData.getFinishDate());

        if (DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY == basis) {
            productionLineScheduleServicePSExecutorService.savePosition(candidate, newData);
        } else {
            productionLineScheduleServicePPSExecutorService.savePosition(candidate, newData);
        }
    }

    /**
     * Returns the calculation basis whose string value equals the schedule's
     * {@link ProductionLineScheduleFields#DURATION_OF_ORDER_CALCULATED_ON_BASIS}, or {@code null} when none does.
     */
    private DurationOfOrderCalculatedOnBasis resolveBasis(final Entity schedule) {
        String basis = schedule.getStringField(ProductionLineScheduleFields.DURATION_OF_ORDER_CALCULATED_ON_BASIS);
        for (DurationOfOrderCalculatedOnBasis value : DurationOfOrderCalculatedOnBasis.values()) {
            if (value.getStringValue().equals(basis)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Returns the given value, or throws {@link ProductionMaintenanceGanttMoveService.MoveRejectedException} with
     * {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} when it is {@code null}.
     */
    private static <T> T requirePresent(final T value) {
        if (value == null) {
            throw new ProductionMaintenanceGanttMoveService.MoveRejectedException(
                    ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY);
        }
        return value;
    }

    private DataDefinition getPositionDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION);
    }

}
