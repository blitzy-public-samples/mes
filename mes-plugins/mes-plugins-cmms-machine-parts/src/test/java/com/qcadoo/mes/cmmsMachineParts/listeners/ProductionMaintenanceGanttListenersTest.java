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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.dao.ConcurrencyFailureException;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService;
import com.qcadoo.view.api.ComponentState.MessageType;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FieldComponent;
import com.qcadoo.view.api.components.ganttChart.GanttChartItem;
import com.qcadoo.view.api.components.ganttChart.GanttChartItemResolver;
import com.qcadoo.view.api.components.ganttChart.GanttChartScale;
import com.qcadoo.view.internal.components.ganttChart.GanttChartComponentPattern;
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
 * move service: it returns, it throws a move rejection, it throws a serialization failure in the cause chain, it throws a
 * serialization failure that is the cause of an SQL exception's next exception, it throws a concurrency failure, or it throws
 * any other exception; one further test covers a component without a move request.
 * <p>
 * Two tests run the listener against a real, move-enabled {@link GanttChartComponentState} whose moveItem event dropped
 * ORD-1 onto LINE-2, with the move service mocked: one renders the refreshed board with the accepted move result, and one
 * renders an accepted move result with {@code reloadRequired} and the reload-required message when the refresh after the
 * move fails.
 * <p>
 * Two tests cover {@link ProductionMaintenanceGanttListeners#fillTitle(ViewDefinitionState)} with a mocked
 * {@link TranslationService}: on an initialized view and on a view after reload.
 */
public class ProductionMaintenanceGanttListenersTest {

    private static final Long L_POSITION_ID = 11L;

    private static final String L_SCHEDULE_ID = "5";

    private static final String L_OPTIMISTIC_LOCK = "qcadooView.validate.global.optimisticLock";

    private static final String L_SHUTDOWN_WINDOW = "cmmsMachineParts.productionMaintenanceGantt.move.error.shutdownWindow";

    private static final String L_RECOMPUTE_FAILED = "cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed";

    private static final String L_PLANNED_EVENT_NUMBER = "PE-7";

    private static final String L_CONTENT = "content";

    private static final String L_MOVE_RESULT = "moveResult";

    private static final String L_ITEM_ID = "itemId";

    private static final String L_ACCEPTED = "accepted";

    private static final String L_RELOAD_REQUIRED = "reloadRequired";

    private static final String L_TRANSLATED_PREFIX = "translated:";

    private static final String L_MESSAGE = "message";

    private static final String L_RELOAD_REQUIRED_MESSAGE = "qcadooView.gantt.move.reloadRequired";

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

    /**
     * Runs against a real {@link GanttChartComponentState} whose resolver fails on the refresh after the move service
     * returned: the listener throws nothing, calls the move service exactly once and resolves exactly twice (the built-in
     * handler and one refresh); the content is only an accepted move result for position 11 with {@code reloadRequired} set
     * to true and the reload-required message, the component carries no message, and the view receives none.
     */
    @Test
    public final void shouldAnswerAcceptedMoveRequiringReloadWithoutRetryWhenRefreshFailsAfterMove() throws Exception {
        // given
        GanttChartItemResolver resolver = mock(GanttChartItemResolver.class);
        GanttChartComponentState realGantt = createMovedRealGantt(resolver);
        GanttChartMoveRequest realMoveRequest = realGantt.getMoveRequest();
        assertNotNull(realMoveRequest);
        doThrow(new IllegalStateException("resolver failed after commit")).when(resolver).resolve(any(GanttChartScale.class),
                any(JSONObject.class), any(Locale.class));

        // when
        productionMaintenanceGanttListeners.moveItem(view, realGantt, new String[0]);

        // then
        verify(productionMaintenanceGanttMoveService, times(1)).move(realMoveRequest);
        verifyNoMoreInteractions(productionMaintenanceGanttMoveService);
        verify(resolver, times(2)).resolve(any(GanttChartScale.class), any(JSONObject.class), any(Locale.class));

        JSONObject rendered = realGantt.render();
        JSONObject content = rendered.getJSONObject(L_CONTENT);
        assertEquals(Collections.singleton(L_MOVE_RESULT), keySet(content));
        JSONObject result = content.getJSONObject(L_MOVE_RESULT);
        assertEquals(new HashSet<String>(Arrays.asList(L_ITEM_ID, L_ACCEPTED, L_RELOAD_REQUIRED, L_MESSAGE)), keySet(result));
        assertTrue(result.getBoolean(L_ACCEPTED));
        assertTrue(result.getBoolean(L_RELOAD_REQUIRED));
        assertEquals(L_RELOAD_REQUIRED_MESSAGE, result.getString(L_MESSAGE));
        assertEquals(L_POSITION_ID.longValue(), result.getLong(L_ITEM_ID));

        assertEquals(0, rendered.getJSONArray("messages").length());
        verify(view, never()).addMessage(anyString(), any(MessageType.class), Matchers.<String> anyVararg());
    }

    /**
     * Runs against a real {@link GanttChartComponentState} whose resolver keeps working: after the move service returns, the
     * content holds the refreshed rows and items together with an accepted move result for position 11 that has only the
     * keys {@code itemId} and {@code accepted}, and there is no message.
     */
    @Test
    public final void shouldRenderRefreshedBoardWithAcceptedMoveResultWhenMoveReturns() throws Exception {
        // given
        GanttChartItemResolver resolver = mock(GanttChartItemResolver.class);
        GanttChartComponentState realGantt = createMovedRealGantt(resolver);

        // when
        productionMaintenanceGanttListeners.moveItem(view, realGantt, new String[0]);

        // then
        verify(productionMaintenanceGanttMoveService, times(1)).move(realGantt.getMoveRequest());

        JSONObject rendered = realGantt.render();
        JSONObject content = rendered.getJSONObject(L_CONTENT);
        JSONArray rows = content.getJSONArray("rows");
        assertEquals(2, rows.length());
        assertEquals("LINE-1", rows.getString(0));
        assertEquals("LINE-2", rows.getString(1));
        assertEquals(1, content.getJSONArray("items").length());
        JSONObject result = content.getJSONObject(L_MOVE_RESULT);
        assertEquals(new HashSet<String>(Arrays.asList(L_ITEM_ID, L_ACCEPTED)), keySet(result));
        assertTrue(result.getBoolean(L_ACCEPTED));
        assertEquals(L_POSITION_ID.longValue(), result.getLong(L_ITEM_ID));
        assertFalse(result.has(L_RELOAD_REQUIRED));
        assertEquals(0, rendered.getJSONArray("messages").length());
    }

    /**
     * Creates a real move-enabled {@link GanttChartComponentState} for 2026-03-02 to 2026-03-03 at zoom level H1 whose
     * resolver answers, with the scale it receives, row LINE-1 holding ORD-1 (position 11, 08:00-10:00) and an empty row
     * LINE-2, and performs on it the built-in moveItem event dropping position 11 on LINE-2 at 2026-03-02 09:00:00.
     * Two-key translations answer with the fallback key and one-key translations with {@code "translated:"} and the key.
     */
    private GanttChartComponentState createMovedRealGantt(final GanttChartItemResolver resolver) throws Exception {
        TranslationService translationService = mock(TranslationService.class);
        given(translationService.translate(anyString(), anyString(), any(Locale.class), Matchers.<String> anyVararg()))
                .willAnswer(new Answer<String>() {

                    @Override
                    public String answer(final InvocationOnMock invocation) {
                        return (String) invocation.getArguments()[1];
                    }

                });
        given(translationService.translate(anyString(), any(Locale.class), Matchers.<String> anyVararg())).willAnswer(
                new Answer<String>() {

                    @Override
                    public String answer(final InvocationOnMock invocation) {
                        return L_TRANSLATED_PREFIX + invocation.getArguments()[0];
                    }

                });
        given(resolver.resolve(any(GanttChartScale.class), any(JSONObject.class), any(Locale.class))).willAnswer(
                new Answer<Map<String, List<GanttChartItem>>>() {

                    @Override
                    public Map<String, List<GanttChartItem>> answer(final InvocationOnMock invocation) {
                        GanttChartScale scale = (GanttChartScale) invocation.getArguments()[0];
                        List<GanttChartItem> originRowItems = new ArrayList<GanttChartItem>();
                        originRowItems.add(scale.createGanttChartItem("LINE-1", "ORD-1", L_POSITION_ID, new DateTime(2026, 3,
                                2, 8, 0, 0, 0).toDate(), new DateTime(2026, 3, 2, 10, 0, 0, 0).toDate()));
                        Map<String, List<GanttChartItem>> board = new LinkedHashMap<String, List<GanttChartItem>>();
                        board.put("LINE-1", originRowItems);
                        board.put("LINE-2", new ArrayList<GanttChartItem>());
                        return board;
                    }

                });

        GanttChartComponentPattern pattern = mock(GanttChartComponentPattern.class);
        setField(pattern, "allowItemMove", true);

        GanttChartComponentState realGantt = new GanttChartComponentState(resolver, pattern);
        realGantt.setTranslationService(translationService);
        realGantt.setTranslationPath("cmmsMachineParts.productionMaintenanceGantt.window.mainTab.gantt");
        realGantt.setName("gantt");

        JSONObject headerParameters = new JSONObject();
        headerParameters.put("scale", "H1");
        headerParameters.put("dateFrom", "2026-03-02");
        headerParameters.put("dateTo", "2026-03-03");
        JSONObject componentContent = new JSONObject();
        componentContent.put("headerParameters", headerParameters);
        JSONObject context = new JSONObject();
        context.put(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID, L_SCHEDULE_ID);
        JSONObject json = new JSONObject();
        json.put(L_CONTENT, componentContent);
        json.put("context", context);
        realGantt.initialize(json, Locale.ENGLISH);

        JSONObject payload = new JSONObject();
        payload.put(L_ITEM_ID, L_POSITION_ID);
        payload.put("row", "LINE-2");
        payload.put("dateFrom", "2026-03-02 09:00:00");
        payload.put("originalRow", "LINE-1");
        payload.put("originalName", "ORD-1");
        payload.put("originalDateFrom", "2026-03-02 08:00:00");
        payload.put("originalDateTo", "2026-03-02 10:00:00");
        realGantt.performEvent(view, "moveItem", payload.toString());
        return realGantt;
    }

    private Set<String> keySet(final JSONObject json) {
        Set<String> keys = new HashSet<String>();
        Iterator<?> iterator = json.keys();
        while (iterator.hasNext()) {
            keys.add((String) iterator.next());
        }
        return keys;
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
    public final void shouldRejectWithLockMessageWhenSerializationFailureIsCauseOfNextException() {
        // given
        SQLException batchFailure = new SQLException("batch failed", "08000");
        SQLException nextFailure = new SQLException("connection failure", "08003");

        batchFailure.setNextException(nextFailure);
        batchFailure.initCause(nextFailure);
        nextFailure.initCause(new SQLException("could not serialize access due to concurrent update", "40001"));

        doThrow(new RuntimeException("commit failed", batchFailure)).when(productionMaintenanceGanttMoveService).move(
                moveRequest);

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

    @Test
    public final void shouldFillTitleWithTranslatedLabelWhenViewIsInitialized() {
        // given
        TranslationService translationService = injectTranslationService();
        FieldComponent title = mock(FieldComponent.class);
        String translatedTitle = "Produktions- und Instandhaltungskalender";

        given(view.isViewAfterReload()).willReturn(false);
        given(view.getLocale()).willReturn(Locale.GERMAN);
        given(view.getComponentByReference("title")).willReturn(title);
        given(translationService.translate(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY, Locale.GERMAN))
                .willReturn(translatedTitle);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        InOrder inOrder = inOrder(title);
        inOrder.verify(title).setFieldValue(translatedTitle);
        inOrder.verify(title).requestComponentUpdateState();

        verify(translationService, times(1)).translate(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY,
                Locale.GERMAN);
        verify(title, times(1)).setFieldValue(translatedTitle);
        verify(title, times(1)).requestComponentUpdateState();
    }

    @Test
    public final void shouldLeaveTitleUnchangedAfterReload() {
        // given
        TranslationService translationService = injectTranslationService();

        given(view.isViewAfterReload()).willReturn(true);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(view, never()).getComponentByReference(anyString());
        verify(view, never()).getLocale();
        verifyZeroInteractions(translationService);
    }

    private TranslationService injectTranslationService() {
        TranslationService translationService = mock(TranslationService.class);

        setField(productionMaintenanceGanttListeners, "translationService", translationService);

        return translationService;
    }

}
