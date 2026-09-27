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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
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
import com.qcadoo.model.api.search.JoinType;
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
 * <li>On a cross-row move the origin chain runs first and is seeded from the row predecessor (the latest other position with an
 * order starting at or before the vacated start; positions without an order are never a predecessor); the destination chain
 * follows and is seeded from the moved position. A {@code null} origin line runs the destination chain only.</li>
 * <li>On a same-row move one chain runs: the positions starting at or before the drop start are recomputed after the row
 * predecessor, then the caches are seeded from the moved position and the remaining positions are recomputed.</li>
 * <li>With no predecessor the caches start empty and the scheduling services use their own database lookups.</li>
 * <li>Every candidate list and predecessor is read before the first executor call. The moved position is never recomputed, and
 * each position is recomputed at most once.</li>
 * <li>After those reads and before the first scheduling service or executor call, the ids of the technologies of all
 * candidates' orders are read with one {@link #ORDER_TECHNOLOGY_IDS_QUERY} projection over the distinct order ids in chain
 * order; the projection loads no order or technology entity. A call without candidates runs no such query.</li>
 * <li>Each candidate is recomputed as the batch step does: its own order reference is passed to the scheduling services and
 * put into the order cache, and that order's technology reference is passed to the executor; both are loaded when first
 * used.</li>
 * <li>After each recomputed position is saved, the pending changes of the current Hibernate session are flushed to the
 * database and the session is cleared; the next position starts with an empty session. The entities the service and its
 * callers hold are detached values and load their references again when next used.</li>
 * </ul>
 * The service joins the caller's transaction; a call that recomputes a position works on that transaction's current Hibernate
 * session. It throws {@link ProductionMaintenanceGanttMoveService.MoveRejectedException} with
 * {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} and the arguments of
 * {@link ProductionMaintenanceGanttMoveService#recomputeFailedArgs(Entity, Entity, Date)} for the candidate it names below:
 * <ul>
 * <li>before any executor call, when a candidate has no order (that candidate, without an order), when the projection returns
 * no row for a candidate's order (the first candidate with that order, without an order), or when it returns a row without a
 * technology id (the first candidate with that order, with its order);</li>
 * <li>before any scheduling service or executor call, when the call has candidates and the schedule has no
 * {@link ProductionLineScheduleFields#START_TIME}; a call without candidates does not read it (the first candidate of the
 * call, with its order);</li>
 * <li>when the executor produces no data for the chain's production line, including when the schedule names no known
 * calculation basis (the candidate being recomputed, with its order);</li>
 * <li>when a scheduling service, the executor's {@code createProductionLinePositionNewData} or its {@code savePosition}
 * throws a runtime exception other than a {@link ProductionMaintenanceGanttMoveService.MoveRejectedException} or a
 * concurrency conflict (see {@link ProductionMaintenanceGanttMoveService#isConcurrencyConflict(Throwable)}), which propagate
 * unchanged (the candidate being recomputed, with its order; the rejection's cause is that exception).</li>
 * </ul>
 * The production line argument is the candidate's production line before the first executor call and the chain's production
 * line from then on; the start argument is the candidate's start time as read before this call changed it. Each rejection
 * is logged once at warn level with a fixed reason and the ids of the candidate, the schedule, the production line and the
 * candidate's order, and with the stack trace of its cause when it has one.
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

    /** Warn-log reason of a candidate without an order. */
    private static final String NO_ORDER_REASON = "the position has no order";

    /** Warn-log reason of a candidate whose order the order technology projection returned no row for. */
    private static final String ORDER_NOT_LOADED_REASON = "the order technology projection returned no row for the position's "
            + "order";

    /** Warn-log reason of a candidate whose loaded order has no technology. */
    private static final String NO_TECHNOLOGY_REASON = "the position's order has no technology";

    /** Warn-log reason of a candidate for which the executor produced no data for the chain's production line. */
    private static final String NO_EXECUTOR_DATA_REASON = "the executor produced no data for the production line";

    /** Warn-log reason of a candidate whose scheduling service or executor call threw an exception. */
    private static final String SCHEDULING_FAILED_REASON = "a scheduling service or executor call failed";

    private static final String L_ORDER_ID = "orderId";

    private static final String L_TECHNOLOGY_ID = "technologyId";

    private static final String L_ORDER_IDS = "orderIds";

    /**
     * HQL projection of the given orders' ids and the ids of their technologies ({@code null} for an order without a
     * technology), one row per order that exists; bound with the order ids under {@code orderIds}.
     */
    static final String ORDER_TECHNOLOGY_IDS_QUERY = "select o.id as " + L_ORDER_ID + ", t.id as " + L_TECHNOLOGY_ID
            + " from #orders_order o left join o." + OrderFields.TECHNOLOGY + " t where o.id in (:" + L_ORDER_IDS + ")";

    @Autowired
    private SessionFactory sessionFactory;

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
     *             with {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} and the order number, production line
     *             number and start of the candidate when a candidate cannot be recomputed, including when the schedule has no
     *             start time
     * @throws org.hibernate.HibernateException
     *             unchanged, when the current Hibernate session cannot be obtained or flushed after a recomputed position is
     *             saved, including a flush that fails with a serialization failure
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

        List<Entity> chainCandidates = new ArrayList<Entity>(originCandidates);
        chainCandidates.addAll(destinationCandidates);
        requireOrdersWithTechnologies(schedule, chainCandidates);
        Date scheduleStartTime = requireScheduleStartTime(schedule, chainCandidates);

        if (originLine != null) {
            Map<Long, Date> originFinishCache = new HashMap<Long, Date>();
            Map<Long, Entity> originOrderCache = new HashMap<Long, Entity>();
            seedCaches(originPredecessor, originLine, originFinishCache, originOrderCache);
            processChain(schedule, scheduleStartTime, basis, originLine, originCandidates, originFinishCache,
                    originOrderCache);
        }

        Map<Long, Date> destinationFinishCache = new HashMap<Long, Date>();
        Map<Long, Entity> destinationOrderCache = new HashMap<Long, Entity>();
        seedCaches(movedPosition, destinationLine, destinationFinishCache, destinationOrderCache);
        processChain(schedule, scheduleStartTime, basis, destinationLine, destinationCandidates, destinationFinishCache,
                destinationOrderCache);
    }

    private void recomputeSameRow(final DataDefinition positionDD, final Entity schedule,
            final DurationOfOrderCalculatedOnBasis basis, final Entity movedPosition, final Entity line, final Date vacatedStart,
            final Date slotStart) {
        Long movedPositionId = movedPosition.getId();
        Date affectedStart = vacatedStart.before(slotStart) ? vacatedStart : slotStart;

        List<Entity> candidates = findCandidates(positionDD, schedule, line, movedPositionId, affectedStart);
        Entity predecessor = findPredecessor(positionDD, schedule, line, movedPositionId, affectedStart);
        requireOrdersWithTechnologies(schedule, candidates);
        Date scheduleStartTime = requireScheduleStartTime(schedule, candidates);

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
        processChain(schedule, scheduleStartTime, basis, line, beforeAnchor, finishCache, orderCache);
        seedCaches(movedPosition, line, finishCache, orderCache);
        processChain(schedule, scheduleStartTime, basis, line, afterAnchor, finishCache, orderCache);
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
     * Returns the schedule's latest position with an order on the given production line, other than the moved position,
     * starting at or before the affected start, or {@code null} when there is none. Positions without an order are excluded
     * by an inner join on the position's order.
     */
    private Entity findPredecessor(final DataDefinition positionDD, final Entity schedule, final Entity line,
            final Long movedPositionId, final Date affectedStart) {
        return positionDD.find()
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE_SCHEDULE, schedule))
                .add(SearchRestrictions.belongsTo(ProductionLineSchedulePositionFields.PRODUCTION_LINE, line))
                .createAlias(ProductionLineSchedulePositionFields.ORDER, ProductionLineSchedulePositionFields.ORDER,
                        JoinType.INNER)
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
     * Requires every given candidate to have an order and every such order to exist with a technology. The order ids are
     * taken from the candidates' order references without loading the orders; the ids of their technologies are read with
     * one {@link #ORDER_TECHNOLOGY_IDS_QUERY} projection over the distinct order ids in candidate order, which loads no
     * entity. An empty candidate list runs no query.
     *
     * @throws ProductionMaintenanceGanttMoveService.MoveRejectedException
     *             with {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} when a candidate has no order, or
     *             when the projection returns no row, or a row without a technology id, for one of the orders; the arguments
     *             name that candidate or the first candidate with that order
     */
    private void requireOrdersWithTechnologies(final Entity schedule, final List<Entity> candidates) {
        if (candidates.isEmpty()) {
            return;
        }

        Map<Long, Entity> firstCandidateByOrderId = new LinkedHashMap<Long, Entity>();
        for (Entity candidate : candidates) {
            Entity order = candidate.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);
            if (order == null) {
                throw recomputeFailed(schedule, candidate, null, lineOf(candidate), startOf(candidate), NO_ORDER_REASON, null);
            }
            if (!firstCandidateByOrderId.containsKey(order.getId())) {
                firstCandidateByOrderId.put(order.getId(), candidate);
            }
        }

        List<Entity> rows = getOrderDD().find(ORDER_TECHNOLOGY_IDS_QUERY)
                .setParameterList(L_ORDER_IDS, new ArrayList<Long>(firstCandidateByOrderId.keySet())).list().getEntities();

        Set<Long> existingOrders = new HashSet<Long>();
        Set<Long> ordersWithTechnology = new HashSet<Long>();
        for (Entity row : rows) {
            existingOrders.add(row.getLongField(L_ORDER_ID));
            if (row.getLongField(L_TECHNOLOGY_ID) != null) {
                ordersWithTechnology.add(row.getLongField(L_ORDER_ID));
            }
        }

        for (Map.Entry<Long, Entity> orderCandidate : firstCandidateByOrderId.entrySet()) {
            Entity candidate = orderCandidate.getValue();
            if (!existingOrders.contains(orderCandidate.getKey())) {
                throw recomputeFailed(schedule, candidate, null, lineOf(candidate), startOf(candidate), ORDER_NOT_LOADED_REASON,
                        null);
            }
            if (!ordersWithTechnology.contains(orderCandidate.getKey())) {
                throw recomputeFailed(schedule, candidate, candidate.getBelongsToField(ProductionLineSchedulePositionFields.ORDER),
                        lineOf(candidate), startOf(candidate), NO_TECHNOLOGY_REASON, null);
            }
        }

        LOG.debug("Checked the orders and technologies of {} production line schedule positions to recompute",
                candidates.size());
    }

    /**
     * Returns the schedule's {@link ProductionLineScheduleFields#START_TIME} when the given candidates are not empty, and
     * {@code null} without reading it when they are.
     *
     * @throws ProductionMaintenanceGanttMoveService.MoveRejectedException
     *             with {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} when there are candidates and the
     *             schedule has no start time; the arguments name the first of the given candidates with its order
     */
    private Date requireScheduleStartTime(final Entity schedule, final List<Entity> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        Date scheduleStartTime = schedule.getDateField(ProductionLineScheduleFields.START_TIME);
        if (scheduleStartTime == null) {
            Entity firstCandidate = candidates.get(0);
            throw recomputeFailed(schedule, firstCandidate,
                    firstCandidate.getBelongsToField(ProductionLineSchedulePositionFields.ORDER), lineOf(firstCandidate),
                    startOf(firstCandidate), "the production line schedule has no start time; " + candidates.size()
                            + " production line schedule positions cannot be recomputed", null);
        }
        return scheduleStartTime;
    }

    /**
     * Recomputes the given positions one after another on one production line from the given schedule start time, sharing the
     * chain caches. After each position is saved, the pending changes of the current Hibernate session are flushed and the
     * session is cleared.
     */
    private void processChain(final Entity schedule, final Date scheduleStartTime, final DurationOfOrderCalculatedOnBasis basis,
            final Entity line, final List<Entity> candidates, final Map<Long, Date> finishCache,
            final Map<Long, Entity> orderCache) {
        LOG.debug("Recomputing {} production line schedule positions on production line {}", candidates.size(), line.getId());
        for (Entity candidate : candidates) {
            recomputePosition(schedule, basis, line, scheduleStartTime, candidate, finishCache, orderCache);
            flushAndClearSession();
        }
    }

    /**
     * Writes the pending changes of the current Hibernate session to the database, then detaches every object the session
     * holds.
     */
    private void flushAndClearSession() {
        Session session = sessionFactory.getCurrentSession();
        session.flush();
        session.clear();
    }

    /**
     * Recomputes one position on the given production line with the candidate's order and that order's technology, using the
     * executor service of the calculation basis, updates the chain caches with its new finish date and order, and saves it
     * through the same executor service.
     *
     * @throws ProductionMaintenanceGanttMoveService.MoveRejectedException
     *             with {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY}, naming the position with its order,
     *             the given production line and the start time it had on entry, when the executor produces no data
     *             for the production line, or when a scheduling service or executor call throws a runtime exception that is
     *             neither a {@link ProductionMaintenanceGanttMoveService.MoveRejectedException} nor a concurrency conflict;
     *             those two propagate unchanged
     */
    private void recomputePosition(final Entity schedule, final DurationOfOrderCalculatedOnBasis basis, final Entity line,
            final Date scheduleStartTime, final Entity candidate, final Map<Long, Date> finishCache,
            final Map<Long, Entity> orderCache) {
        Date storedStart = startOf(candidate);
        Entity order = candidate.getBelongsToField(ProductionLineSchedulePositionFields.ORDER);
        try {
            Date finishDate = productionLineScheduleService.getFinishDate(finishCache, scheduleStartTime, line, order);
            finishDate = productionLineScheduleService.getFinishDateWithChildren(candidate, finishDate);
            Entity previousOrder = productionLineScheduleService.getPreviousOrder(orderCache, line, finishDate);
            Entity technology = order.getBelongsToField(OrderFields.TECHNOLOGY);

            Map<Long, ProductionLinePositionNewData> positionNewData = new HashMap<Long, ProductionLinePositionNewData>();
            if (DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY == basis) {
                productionLineScheduleServicePSExecutorService.createProductionLinePositionNewData(positionNewData, line,
                        finishDate, candidate, technology, previousOrder);
            } else if (DurationOfOrderCalculatedOnBasis.PLAN_FOR_SHIFT == basis) {
                productionLineScheduleServicePPSExecutorService.createProductionLinePositionNewData(positionNewData, line,
                        finishDate, candidate, technology, previousOrder);
            }
            ProductionLinePositionNewData newData = positionNewData.get(line.getId());
            if (newData == null) {
                throw recomputeFailed(schedule, candidate, order, line, storedStart, NO_EXECUTOR_DATA_REASON, null);
            }

            finishCache.put(line.getId(), newData.getFinishDate());
            orderCache.put(line.getId(), order);
            candidate.setField(ProductionLineSchedulePositionFields.START_TIME, newData.getStartDate());
            candidate.setField(ProductionLineSchedulePositionFields.END_TIME, newData.getFinishDate());

            if (DurationOfOrderCalculatedOnBasis.TIME_CONSUMING_TECHNOLOGY == basis) {
                productionLineScheduleServicePSExecutorService.savePosition(candidate, newData);
            } else {
                productionLineScheduleServicePPSExecutorService.savePosition(candidate, newData);
            }
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            if (ProductionMaintenanceGanttMoveService.isConcurrencyConflict(e)) {
                throw e;
            }
            throw recomputeFailed(schedule, candidate, order, line, storedStart, SCHEDULING_FAILED_REASON, e);
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
     * Logs at warn level that the recompute of the position failed, with the ids of the position, the schedule, the given
     * production line and the position's order, the given fixed reason and, when a cause is given, its stack trace, and returns
     * the {@link ProductionMaintenanceGanttMoveService#RECOMPUTE_FAILED_KEY} rejection whose arguments are
     * {@link ProductionMaintenanceGanttMoveService#recomputeFailedArgs(Entity, Entity, Date)} of the given order, production
     * line and start time, and whose cause is the given cause.
     * <p>
     * Example: position 22 of schedule 7 on production line 2 with order 53 and the reason
     * {@code the executor produced no data for the production line} logs
     * {@code Recompute of production line schedule position 22 (production line schedule 7, production line 2, order 53) failed:
     * the executor produced no data for the production line}.
     *
     * @param schedule
     *            production line schedule being recomputed
     * @param position
     *            position at which the recompute failed
     * @param order
     *            loaded order of the position, or null when it has none or it was not loaded
     * @param line
     *            production line of the position
     * @param startTime
     *            start time of the position as read before this call changed it
     * @param reason
     *            fixed text of the failure
     * @param cause
     *            exception that made the recompute fail, or null
     * @return the rejection
     */
    private static ProductionMaintenanceGanttMoveService.MoveRejectedException recomputeFailed(final Entity schedule,
            final Entity position, final Entity order, final Entity line, final Date startTime, final String reason,
            final RuntimeException cause) {
        Object[] logArguments = { position.getId(), schedule.getId(), idOf(line),
                idOf(position.getBelongsToField(ProductionLineSchedulePositionFields.ORDER)), reason, cause };
        if (cause == null) {
            logArguments = Arrays.copyOf(logArguments, logArguments.length - 1);
        }
        LOG.warn("Recompute of production line schedule position {} (production line schedule {}, production line {}, order {})"
                + " failed: {}", logArguments);

        ProductionMaintenanceGanttMoveService.MoveRejectedException rejection;
        rejection = new ProductionMaintenanceGanttMoveService.MoveRejectedException(
                ProductionMaintenanceGanttMoveService.RECOMPUTE_FAILED_KEY,
                ProductionMaintenanceGanttMoveService.recomputeFailedArgs(order, line, startTime));
        if (cause != null) {
            rejection.initCause(cause);
        }
        return rejection;
    }

    /**
     * Returns the id of the entity, or null for a null entity.
     */
    private static Long idOf(final Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getId();
    }

    /**
     * Returns the production line of the position.
     */
    private static Entity lineOf(final Entity position) {
        return position.getBelongsToField(ProductionLineSchedulePositionFields.PRODUCTION_LINE);
    }

    /**
     * Returns the start time of the position.
     */
    private static Date startOf(final Entity position) {
        return position.getDateField(ProductionLineSchedulePositionFields.START_TIME);
    }

    private DataDefinition getPositionDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE_POSITION);
    }

    private DataDefinition getOrderDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER);
    }

}
