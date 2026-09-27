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

import org.springframework.stereotype.Service;

import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FormComponent;
import com.qcadoo.view.constants.QcadooViewConstants;

/**
 * Listeners that the cmmsMachineParts plugin adds to the {@code orders/productionLineScheduleDetails} view.
 */
@Service
public class ProductionLineScheduleDetailsListenersCMP {

    private static final String L_PRODUCTION_MAINTENANCE_GANTT_URL = "../page/cmmsMachineParts/productionMaintenanceGantt.html";

    private static final String L_GANTT_SCHEDULE_CONTEXT_KEY = "gantt.productionLineScheduleId";

    /**
     * Redirects to the production and maintenance Gantt board of the schedule shown in the form, passing the schedule id to
     * the board's {@code gantt} component as the context value {@code productionLineScheduleId}; does nothing for an unsaved
     * schedule.
     * <p>
     * For schedule id 5 the target is
     * {@code ../page/cmmsMachineParts/productionMaintenanceGantt.html?context={"gantt.productionLineScheduleId":"5"}}, opened in
     * the current window after the current window state is saved.
     *
     * @param view
     *            the schedule details view
     * @param state
     *            the component that fired the event
     * @param args
     *            the event arguments, not read
     */
    public void showProductionMaintenanceGantt(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        FormComponent form = (FormComponent) view.getComponentByReference(QcadooViewConstants.L_FORM);

        Long productionLineScheduleId = form.getEntityId();

        if (productionLineScheduleId == null) {
            return;
        }

        String url = L_PRODUCTION_MAINTENANCE_GANTT_URL + "?context={\"" + L_GANTT_SCHEDULE_CONTEXT_KEY + "\":\""
                + productionLineScheduleId + "\"}";

        view.redirectTo(url, false, true);
    }

}
