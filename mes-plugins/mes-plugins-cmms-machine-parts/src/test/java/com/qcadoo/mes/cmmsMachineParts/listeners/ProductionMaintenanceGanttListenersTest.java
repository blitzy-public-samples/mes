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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.sql.SQLException;
import java.util.Date;

import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.ConcurrencyFailureException;

import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.internal.components.ganttChart.GanttChartComponentState;
import com.qcadoo.view.internal.components.ganttChart.GanttChartMoveRequest;

/**
 * Unit tests of {@link ProductionMaintenanceGanttListeners#moveItem(ViewDefinitionState, com.qcadoo.view.api.ComponentState,
 * String[])}.
 * <p>
 * The listener runs against a mocked {@link ProductionMaintenanceGanttMoveService} and a mocked
 * {@link GanttChartComponentState}; {@link ProductionMaintenanceGanttMoveService#isConcurrencyConflict(Throwable)} runs for
 * real. Fixture: the component holds a move request for position 11 of schedule 5, rendered on row LINE-1 as ORD-1 from
 * 2026-03-02 08:00:00 to 10:00:00 and dropped on row LINE-2 from 2026-03-02 09:00 to 11:00. Each test covers one outcome of the
 * move service: it returns, it throws a move rejection, it throws a serialization failure, it throws a concurrency failure, or
 * it throws any other exception; one further test covers a component without a move request.
 */
public class ProductionMaintenanceGanttListenersTest {

    private static final Long L_POSITION_ID = 11L;

    private static final String L_SCHEDULE_ID = "5";

    private static final String L_OPTIMISTIC_LOCK = "qcadooView.validate.global.optimisticLock";

    private static final String L_SHUTDOWN_WINDOW = "cmmsMachineParts.productionMaintenanceGantt.move.error.shutdownWindow";

    private static final String L_RECOMPUTE_FAILED = "cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed";

    private static final String L_PLANNED_EVENT_NUMBER = "PE-7";

    private ProductionMaintenanceGanttListeners productionMaintenanceGanttListeners;

    @Mock
    private ProductionMaintenanceGanttMoveService productionMaintenanceGanttMoveService;

    @Mock
    private GanttChartComponentState gantt;

    @Mock
    private ViewDefinitionState view;

    @Mock
    private GanttChartItem item;

    private GanttChartMoveRequest moveRequest;

    @Before
    public final void init() throws JSONException {
        MockitoAnnotations.initMocks(this);

        productionMaintenanceGanttListeners = new ProductionMaintenanceGanttListeners();

        setField(productionMaintenanceGanttListeners, "productionMaintenanceGanttMoveService",
                productionMaintenanceGanttMoveService);

        given(item.getEntityId()).willReturn(L_POSITION_ID);

        JSONObject context = new JSONObject();
        context.put(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, L_SCHEDULE_ID);

        Date dateFrom = new DateTime(2026, 3, 2, 9, 0, 0, 0).toDate();
        Date dateTo = new DateTime(2026, 3, 2, 11, 0, 0, 0).toDate();

        moveRequest = new GanttChartMoveRequest(item, "LINE-2", "LINE-1", "ORD-1", "2026-03-02 08:00:00", "2026-03-02 10:00:00",
                dateFrom, dateTo, context);

        given(gantt.getMoveRequest()).willReturn(moveRequest);
    }

    @Test
    public final void shouldAcceptOnlyAfterMoveServiceReturns() {
        // given

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        InOrder inOrder = inOrder(productionMaintenanceGanttMoveService, gantt);
        inOrder.verify(productionMaintenanceGanttMoveService).move(moveRequest);
        inOrder.verify(gantt).acceptMove();

        verify(productionMaintenanceGanttMoveService, times(1)).move(moveRequest);
        verify(gantt, times(1)).acceptMove();
        verify(gantt, never()).rejectMove(anyString(), Matchers.<String> anyVararg());
    }

    @Test
    public final void shouldRejectWithKeyOfMoveRejection() {
        // given
        doThrow(new ProductionMaintenanceGanttMoveService.MoveRejectedException(L_SHUTDOWN_WINDOW, L_PLANNED_EVENT_NUMBER))
                .when(productionMaintenanceGanttMoveService).move(moveRequest);

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        verify(gantt, times(1)).rejectMove(L_SHUTDOWN_WINDOW, L_PLANNED_EVENT_NUMBER);
        verify(gantt, times(1)).rejectMove(anyString(), Matchers.<String> anyVararg());
        verify(gantt, never()).rejectMove(L_OPTIMISTIC_LOCK);
        verify(gantt, never()).acceptMove();
    }

    @Test
    public final void shouldRejectWithLockMessageOnSerializationFailure() {
        // given
        SQLException serializationFailure = new SQLException("could not serialize access due to concurrent update", "40001");
        RuntimeException commitFailure = new RuntimeException("commit failed",
                new RuntimeException("flush failed", serializationFailure));

        doThrow(commitFailure).when(productionMaintenanceGanttMoveService).move(moveRequest);

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        verify(productionMaintenanceGanttMoveService, times(1)).move(moveRequest);
        verify(gantt, times(1)).rejectMove(L_OPTIMISTIC_LOCK);
        verify(gantt, times(1)).rejectMove(anyString(), Matchers.<String> anyVararg());
        verify(gantt, never()).acceptMove();
    }

    @Test
    public final void shouldRejectWithLockMessageOnConcurrencyFailure() {
        // given
        doThrow(new ConcurrencyFailureException("conflict")).when(productionMaintenanceGanttMoveService).move(moveRequest);

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        verify(productionMaintenanceGanttMoveService, times(1)).move(moveRequest);
        verify(gantt, times(1)).rejectMove(L_OPTIMISTIC_LOCK);
        verify(gantt, times(1)).rejectMove(anyString(), Matchers.<String> anyVararg());
        verify(gantt, never()).acceptMove();
    }

    @Test
    public final void shouldPropagateOtherExceptionsWithoutRejecting() {
        // given
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        doThrow(unexpected).when(productionMaintenanceGanttMoveService).move(moveRequest);

        // when
        try {
            productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

            fail("IllegalStateException expected");
        } catch (IllegalStateException e) {
            // then
            assertSame(unexpected, e);
        }

        verify(productionMaintenanceGanttMoveService, times(1)).move(moveRequest);
        verify(gantt, never()).rejectMove(anyString(), Matchers.<String> anyVararg());
        verify(gantt, never()).acceptMove();
    }

    @Test
    public final void shouldNotAcceptWhenMoveServiceThrows() {
        // given
        doThrow(new ProductionMaintenanceGanttMoveService.MoveRejectedException(L_RECOMPUTE_FAILED))
                .when(productionMaintenanceGanttMoveService).move(moveRequest);

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        verify(productionMaintenanceGanttMoveService, times(1)).move(moveRequest);
        verify(gantt, times(1)).rejectMove(L_RECOMPUTE_FAILED);
        verify(gantt, never()).acceptMove();
    }

    @Test
    public final void shouldIgnoreRequestAlreadyRejectedByFramework() {
        // given
        given(gantt.getMoveRequest()).willReturn(null);

        // when
        productionMaintenanceGanttListeners.moveItem(view, gantt, new String[0]);

        // then
        verify(gantt, times(1)).getMoveRequest();
        verifyZeroInteractions(productionMaintenanceGanttMoveService);
        verify(gantt, never()).acceptMove();
        verify(gantt, never()).rejectMove(anyString(), Matchers.<String> anyVararg());
    }

}
