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

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService;
import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FieldComponent;
import com.qcadoo.view.internal.components.ganttChart.GanttChartComponentState;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Listeners of the production and maintenance Gantt board view ({@code cmmsMachineParts/productionMaintenanceGantt}).
 * <p>
 * {@link #moveItem(ViewDefinitionState, ComponentState, String[])} is bound to the {@code moveItem} event of the board's
 * {@code gantt} component and runs after the component's built-in {@code moveItem} handler.
 * {@link #fillTitle(ViewDefinitionState)} is the view's {@code beforeRender} hook that writes the translated title into the
 * {@code title} field when the view is initialized.
 */
@Service
public class ProductionMaintenanceGanttListeners {

    /**
     * Translation key of the board title written into the {@code title} field.
     */
    public static final String TITLE_TRANSLATION_KEY = "cmmsMachineParts.productionMaintenanceGantt.window.mainTab.title.label";

    private static final String L_TITLE = "title";

    @Autowired
    private ProductionMaintenanceGanttMoveService productionMaintenanceGanttMoveService;

    @Autowired
    private TranslationService translationService;

    /**
     * Runs the move of the dropped item through {@link ProductionMaintenanceGanttMoveService#move(GanttChartMoveRequest)} and
     * decides the move result of the Gantt chart component:
     * <ul>
     * <li>no move request on the component (the built-in handler already rejected the move): returns without calling the move
     * service and without accepting or rejecting;</li>
     * <li>{@code move} returns: calls {@link GanttChartComponentState#acceptMove()}, which renders the refreshed board with
     * the accepted move result; an exception {@code acceptMove} throws because the board cannot be refreshed propagates
     * unchanged, and the move service is not called again;</li>
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

    /**
     * Writes the board title into the view's {@code title} field:
     * <ul>
     * <li>view rendered for an {@code initialize*} or {@code reset} event ({@link ViewDefinitionState#isViewAfterReload()}
     * returns {@code false}): sets the field value to {@link #TITLE_TRANSLATION_KEY} translated for the view's locale and
     * requests the field's state update;</li>
     * <li>view rendered after any other event: returns without reading or changing the field, which keeps the value the client
     * sent.</li>
     * </ul>
     *
     * @param view
     *            view definition state of the board
     */
    public void fillTitle(final ViewDefinitionState view) {
        if (view.isViewAfterReload()) {
            return;
        }

        FieldComponent title = (FieldComponent) view.getComponentByReference(L_TITLE);

        title.setFieldValue(translationService.translate(TITLE_TRANSLATION_KEY, view.getLocale()));
        title.requestComponentUpdateState();
    }

}
