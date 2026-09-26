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
package com.qcadoo.mes.cmmsMachineParts.listeners;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService;
import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.internal.components.ganttChart.GanttChartComponentState;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Listeners of the production and maintenance Gantt board view ({@code cmmsMachineParts/productionMaintenanceGantt}).
 * <p>
 * {@link #moveItem(ViewDefinitionState, ComponentState, String[])} is bound to the {@code moveItem} event of the board's
 * {@code gantt} component and runs after the component's built-in {@code moveItem} handler.
 */
@Service
public class ProductionMaintenanceGanttListeners {

    @Autowired
    private ProductionMaintenanceGanttMoveService productionMaintenanceGanttMoveService;

    /**
     * Runs the move of the dropped item through {@link ProductionMaintenanceGanttMoveService#move(GanttChartMoveRequest)} and
     * decides the move result of the Gantt chart component:
     * <ul>
     * <li>no move request on the component (the built-in handler already rejected the move): returns without calling the move
     * service and without accepting or rejecting;</li>
     * <li>{@code move} returns: calls {@link GanttChartComponentState#acceptMove()}, which renders the refreshed board;</li>
     * <li>{@code move} throws {@link ProductionMaintenanceGanttMoveService.MoveRejectedException}: calls
     * {@link GanttChartComponentState#rejectMove(String, String...)} with the exception's message key and arguments;</li>
     * <li>{@code move} throws a runtime exception that
     * {@link ProductionMaintenanceGanttMoveService#isConcurrencyConflict(Throwable)} reports as a concurrency conflict: calls
     * {@code rejectMove} with {@link ProductionMaintenanceGanttMoveService#OPTIMISTIC_LOCK_KEY} and no arguments;</li>
     * <li>{@code move} throws any other runtime exception, or an error: propagates it unchanged without accepting or
     * rejecting.</li>
     * </ul>
     * {@code acceptMove} is called only after {@code move} has returned.
     *
     * @param view
     *            view definition state of the board
     * @param state
     *            the board's Gantt chart component state
     * @param args
     *            event arguments
     */
    public void moveItem(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        GanttChartComponentState gantt = (GanttChartComponentState) state;

        GanttChartMoveRequest moveRequest = gantt.getMoveRequest();

        if (moveRequest == null) {
            return;
        }

        try {
            productionMaintenanceGanttMoveService.move(moveRequest);
        } catch (ProductionMaintenanceGanttMoveService.MoveRejectedException e) {
            gantt.rejectMove(e.getMessageKey(), e.getArgs());

            return;
        } catch (RuntimeException e) {
            if (ProductionMaintenanceGanttMoveService.isConcurrencyConflict(e)) {
                gantt.rejectMove(ProductionMaintenanceGanttMoveService.OPTIMISTIC_LOCK_KEY);

                return;
            }

            throw e;
        }

        gantt.acceptMove();
    }

}
