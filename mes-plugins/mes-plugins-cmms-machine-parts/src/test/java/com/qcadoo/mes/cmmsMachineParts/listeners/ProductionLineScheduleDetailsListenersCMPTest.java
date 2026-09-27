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

import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FormComponent;
import com.qcadoo.view.constants.QcadooViewConstants;

/**
 * Unit tests of {@link ProductionLineScheduleDetailsListenersCMP#showProductionMaintenanceGantt(ViewDefinitionState,
 * ComponentState, String[])}.
 * <p>
 * The view returns the mocked schedule form under the reference {@code form}. One test gives the form a saved schedule with id 7
 * and asserts the exact board URL with its component context; the other gives the form no entity id and asserts that no
 * redirect of either overload takes place.
 */
public class ProductionLineScheduleDetailsListenersCMPTest {

    private static final Long L_PRODUCTION_LINE_SCHEDULE_ID = 7L;

    private static final String L_BOARD_URL_FOR_SCHEDULE_7 = "../page/cmmsMachineParts/productionMaintenanceGantt.html"
            + "?context={\"gantt.productionLineScheduleId\":\"7\"}";

    private ProductionLineScheduleDetailsListenersCMP productionLineScheduleDetailsListenersCMP;

    @Mock
    private ViewDefinitionState view;

    @Mock
    private ComponentState state;

    @Mock
    private FormComponent form;

    @Before
    public final void init() {
        MockitoAnnotations.initMocks(this);

        productionLineScheduleDetailsListenersCMP = new ProductionLineScheduleDetailsListenersCMP();

        given(view.getComponentByReference(QcadooViewConstants.L_FORM)).willReturn(form);
    }

    @Test
    public final void shouldRedirectToBoardWithScheduleContext() {
        // given
        given(form.getEntityId()).willReturn(L_PRODUCTION_LINE_SCHEDULE_ID);

        // when
        productionLineScheduleDetailsListenersCMP.showProductionMaintenanceGantt(view, state, new String[0]);

        // then
        verify(view).redirectTo(L_BOARD_URL_FOR_SCHEDULE_7, false, true);
        verify(view, never()).redirectTo(anyString(), anyBoolean(), anyBoolean(), Matchers.<Map<String, Object>> any());
    }

    @Test
    public final void shouldNotRedirectForUnsavedSchedule() {
        // given
        given(form.getEntityId()).willReturn(null);

        // when
        productionLineScheduleDetailsListenersCMP.showProductionMaintenanceGantt(view, state, new String[0]);

        // then
        verify(view, never()).redirectTo(anyString(), anyBoolean(), anyBoolean());
        verify(view, never()).redirectTo(anyString(), anyBoolean(), anyBoolean(), Matchers.<Map<String, Object>> any());
    }

}
