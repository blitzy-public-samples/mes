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
import static org.mockito.Mockito.spy;
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
import java.util.HashMap;
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
import org.mockito.ArgumentCaptor;
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
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.states.constants.ScheduleState;
import com.qcadoo.mes.orders.states.constants.ScheduleStateStringValues;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.SearchQueryBuilder;
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
 * propagates the resolver exception of the refresh after the move without calling the move service again, after which the
 * component fails to render.
 * <p>
 * The tests of {@link ProductionMaintenanceGanttListeners#fillTitle(ViewDefinitionState)} run with a mocked
 * {@link TranslationService}, a mocked schedule data definition answering the title query, and a spy of a real
 * {@link ProductionMaintenanceGanttChartItemResolver} parsing the schedule id the mocked Gantt component's context holds.
 * They cover the caption for a missing, malformed or unknown schedule id and for a schedule with neither number nor name;
 * the title naming the schedule by number and name, by number alone and by name alone, as plain text; the state label of
 * every declared state, of a blank state and of an undeclared state; and a view after reload, which is left untouched.
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

    private static final String L_TRANSLATED_PREFIX = "translated:";

    private static final String L_SCHEDULE_STATE_PREFIX = "orders.productionLineSchedule.state.value.";

    private ProductionMaintenanceGanttListeners productionMaintenanceGanttListeners;

    @Mock
    private ProductionMaintenanceGanttMoveService productionMaintenanceGanttMoveService;

    @Mock
    private GanttChartComponentState gantt;

    @Mock
    private ViewDefinitionState view;

    @Mock
    private GanttChartItem item;

    @Mock
    private DataDefinitionService dataDefinitionService;

    @Mock
    private DataDefinition scheduleDD;

    @Mock
    private SearchQueryBuilder scheduleQuery;

    private ProductionMaintenanceGanttChartItemResolver productionMaintenanceGanttChartItemResolver;

    private GanttChartMoveRequest moveRequest;

    @Before
    public final void init() throws JSONException {
        MockitoAnnotations.initMocks(this);

        productionMaintenanceGanttListeners = new ProductionMaintenanceGanttListeners();
        productionMaintenanceGanttChartItemResolver = spy(new ProductionMaintenanceGanttChartItemResolver());

        setField(productionMaintenanceGanttListeners, "productionMaintenanceGanttMoveService",
                productionMaintenanceGanttMoveService);
        setField(productionMaintenanceGanttListeners, "dataDefinitionService", dataDefinitionService);
        setField(productionMaintenanceGanttListeners, "productionMaintenanceGanttChartItemResolver",
                productionMaintenanceGanttChartItemResolver);

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
     * returned: the listener throws the resolver's {@link IllegalStateException} unchanged and calls the move service exactly
     * once with no further interaction. The resolver is invoked exactly twice: once by the component's built-in moveItem
     * handler and once by the refresh that {@link GanttChartComponentState#acceptMove()} runs. Rendering the component then
     * throws an {@link IllegalStateException} naming position 11, and the view receives no message.
     */
    @Test
    public final void shouldPropagateRefreshFailureAfterMoveWithoutRetry() throws Exception {
        // given
        GanttChartItemResolver resolver = mock(GanttChartItemResolver.class);
        GanttChartComponentState realGantt = createMovedRealGantt(resolver);
        GanttChartMoveRequest realMoveRequest = realGantt.getMoveRequest();
        assertNotNull(realMoveRequest);
        IllegalStateException refreshFailure = new IllegalStateException("resolver failed after commit");
        doThrow(refreshFailure).when(resolver).resolve(any(GanttChartScale.class), any(JSONObject.class), any(Locale.class));

        // when
        try {
            productionMaintenanceGanttListeners.moveItem(view, realGantt, new String[0]);

            fail("IllegalStateException expected");
        } catch (IllegalStateException e) {
            // then
            assertSame(refreshFailure, e);
        }

        verify(productionMaintenanceGanttMoveService, times(1)).move(realMoveRequest);
        verifyNoMoreInteractions(productionMaintenanceGanttMoveService);
        verify(resolver, times(2)).resolve(any(GanttChartScale.class), any(JSONObject.class), any(Locale.class));

        IllegalStateException renderFailure = null;
        try {
            realGantt.render();
        } catch (IllegalStateException e) {
            renderFailure = e;
        }
        assertNotNull(renderFailure);
        assertTrue(renderFailure.getMessage(), renderFailure.getMessage().contains("item " + L_POSITION_ID + " "));

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
        FieldComponent title = stubTitleView(null);
        String translatedTitle = "Produktions- und Instandhaltungskalender";

        given(view.getLocale()).willReturn(Locale.GERMAN);
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
        verify(gantt, times(1)).getContextValue(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID);
        verifyZeroInteractions(dataDefinitionService);
    }

    @Test
    public final void shouldFillTitleWithScheduleNumberNameAndState() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, "PS-1", "Week 41", ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        InOrder inOrder = inOrder(title);
        inOrder.verify(title).setFieldValue(scheduleTitle("PS-1 - Week 41", L_SCHEDULE_STATE_PREFIX
                + ScheduleStateStringValues.DRAFT));
        inOrder.verify(title).requestComponentUpdateState();

        verify(title, times(1)).setFieldValue(Matchers.anyObject());
        verify(dataDefinitionService, times(1)).get(OrdersConstants.PLUGIN_IDENTIFIER,
                OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE);
        verify(scheduleDD, times(1)).find(ProductionMaintenanceGanttListeners.SCHEDULE_TITLE_QUERY);
        verify(scheduleQuery, times(1)).setLong("scheduleId", 5L);
        verify(scheduleQuery, times(1)).uniqueResult();
        verifyNoMoreInteractions(dataDefinitionService, scheduleDD, scheduleQuery);
    }

    @Test
    public final void shouldReadPaddedScheduleIdOfGanttContext() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(" 5 ");
        stubScheduleRow(5L, "PS-1", "Week 41", ScheduleStateStringValues.APPROVED);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("PS-1 - Week 41", L_SCHEDULE_STATE_PREFIX
                + ScheduleStateStringValues.APPROVED));
        verify(productionMaintenanceGanttChartItemResolver, times(1)).parseScheduleId(" 5 ");
    }

    @Test
    public final void shouldPassScheduleNumberAndNameToTitleAsPlainText() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, "<b>PS-1</b>", "Week & <i>41</i>", ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("<b>PS-1</b> - Week & <i>41</i>",
                L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.DRAFT));
    }

    @Test
    public final void shouldPassTranslatedStateLabelToTitleAsPlainText() {
        // given
        injectTitleTranslations(Collections.singletonMap(L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.REJECTED,
                "<b>Rejected</b>"));
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, "PS-1", "Week 41", ScheduleStateStringValues.REJECTED);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("PS-1 - Week 41", "<b>Rejected</b>"));
    }

    @Test
    public final void shouldUseEmptyStateLabelWhenStateTranslationIsMissing() {
        // given
        Map<String, String> translations = new HashMap<String, String>();
        translations.put(L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.DRAFT, null);
        injectTitleTranslations(translations);
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, "PS-1", "Week 41", ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("PS-1 - Week 41", ""));
    }

    @Test
    public final void shouldIdentifyScheduleByTrimmedNumberWhenNameIsBlank() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, " PS-1 ", " \t ", ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("PS-1", L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.DRAFT));
    }

    @Test
    public final void shouldIdentifyScheduleByTrimmedNameWhenNumberIsBlank() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, null, " Week 41 ", ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("Week 41", L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.DRAFT));
    }

    @Test
    public final void shouldFillCaptionWhenScheduleHasNeitherNumberNorName() {
        // given
        TranslationService translationService = injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, " ", null, ScheduleStateStringValues.DRAFT);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY);
        verify(translationService, never()).translate(
                Matchers.eq(ProductionMaintenanceGanttListeners.SCHEDULE_TITLE_TRANSLATION_KEY), any(Locale.class),
                Matchers.<String> anyVararg());
        verify(translationService, never()).translate(
                Matchers.eq(L_SCHEDULE_STATE_PREFIX + ScheduleStateStringValues.DRAFT), any(Locale.class),
                Matchers.<String> anyVararg());
    }

    @Test
    public final void shouldLabelEveryDeclaredScheduleStateWithItsStateTranslation() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        List<String> expectedTitles = new ArrayList<String>();

        for (ScheduleState state : ScheduleState.values()) {
            expectedTitles.add(scheduleTitle("PS-1 - Week 41", L_SCHEDULE_STATE_PREFIX + state.getStringValue()));
        }

        // when
        for (ScheduleState state : ScheduleState.values()) {
            stubScheduleRow(5L, "PS-1", "Week 41", state.getStringValue());

            productionMaintenanceGanttListeners.fillTitle(view);
        }

        // then
        ArgumentCaptor<Object> titleCaptor = ArgumentCaptor.forClass(Object.class);

        verify(title, times(ScheduleState.values().length)).setFieldValue(titleCaptor.capture());
        assertEquals(3, ScheduleState.values().length);
        assertEquals(expectedTitles, titleCaptor.getAllValues());
    }

    @Test
    public final void shouldLabelScheduleWithoutStateAsUnspecified() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        String expectedTitle = scheduleTitle("PS-1 - Week 41",
                ProductionMaintenanceGanttChartItemResolver.ITEM_STATE_UNSPECIFIED_KEY);

        // when
        stubScheduleRow(5L, "PS-1", "Week 41", null);
        productionMaintenanceGanttListeners.fillTitle(view);
        stubScheduleRow(5L, "PS-1", "Week 41", "  ");
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title, times(2)).setFieldValue(expectedTitle);
    }

    @Test
    public final void shouldLabelScheduleWithUndeclaredStateAsUnknownWithoutTranslatingTheState() {
        // given
        TranslationService translationService = injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, "PS-1", "Week 41", "09archived");

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(scheduleTitle("PS-1 - Week 41",
                ProductionMaintenanceGanttChartItemResolver.ITEM_STATE_UNKNOWN_KEY));
        verify(translationService, never()).translate(Matchers.eq(L_SCHEDULE_STATE_PREFIX + "09archived"),
                any(Locale.class), Matchers.<String> anyVararg());
    }

    @Test
    public final void shouldFillCaptionWithoutQueryWhenScheduleIdIsMissing() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(null);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY);
        verify(title).requestComponentUpdateState();
        verifyZeroInteractions(dataDefinitionService);
    }

    @Test
    public final void shouldFillCaptionWithoutQueryWhenScheduleIdIsMalformed() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView("5a");

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY);
        verify(productionMaintenanceGanttChartItemResolver, times(1)).parseScheduleId("5a");
        verifyZeroInteractions(dataDefinitionService);
    }

    @Test
    public final void shouldFillCaptionWhenScheduleIsUnknown() {
        // given
        injectTitleTranslations(Collections.<String, String> emptyMap());
        FieldComponent title = stubTitleView(L_SCHEDULE_ID);
        stubScheduleRow(5L, null);

        // when
        productionMaintenanceGanttListeners.fillTitle(view);

        // then
        verify(title).setFieldValue(ProductionMaintenanceGanttListeners.TITLE_TRANSLATION_KEY);
        verify(scheduleQuery, times(1)).setLong("scheduleId", 5L);
        verify(scheduleQuery, times(1)).uniqueResult();
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
        verifyZeroInteractions(translationService, gantt, dataDefinitionService, productionMaintenanceGanttChartItemResolver);
    }

    private TranslationService injectTranslationService() {
        TranslationService translationService = mock(TranslationService.class);

        setField(productionMaintenanceGanttListeners, "translationService", translationService);

        return translationService;
    }

    /**
     * Injects a translation service that answers every code of the given map with its text, and every other code with the
     * code followed by its arguments in brackets when there are any.
     */
    private TranslationService injectTitleTranslations(final Map<String, String> translations) {
        TranslationService translationService = injectTranslationService();

        given(translationService.translate(anyString(), any(Locale.class), Matchers.<String> anyVararg())).willAnswer(
                new TitleTranslationAnswer(translations));

        return translationService;
    }

    /**
     * Stubs an initialized view in {@link Locale#ENGLISH} whose {@code title} reference is a new field mock, which is
     * returned, and whose {@code gantt} reference is the mocked component holding the given schedule id context value.
     */
    private FieldComponent stubTitleView(final String contextScheduleId) {
        FieldComponent title = mock(FieldComponent.class);

        given(view.isViewAfterReload()).willReturn(false);
        given(view.getLocale()).willReturn(Locale.ENGLISH);
        given(view.getComponentByReference("title")).willReturn(title);
        given(view.getComponentByReference("gantt")).willReturn(gantt);
        given(gantt.getContextValue(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID)).willReturn(
                contextScheduleId);

        return title;
    }

    /**
     * Stubs the {@link ProductionMaintenanceGanttListeners#SCHEDULE_TITLE_QUERY} of the given schedule id to answer a row
     * with the given number, name and state.
     */
    private void stubScheduleRow(final Long scheduleId, final String number, final String name, final String state) {
        Entity row = mock(Entity.class);

        given(row.getStringField("scheduleNumber")).willReturn(number);
        given(row.getStringField("scheduleName")).willReturn(name);
        given(row.getStringField("scheduleState")).willReturn(state);

        stubScheduleRow(scheduleId, row);
    }

    /**
     * Stubs the {@link ProductionMaintenanceGanttListeners#SCHEDULE_TITLE_QUERY} of the given schedule id to answer the given
     * row, null for an unknown schedule.
     */
    private void stubScheduleRow(final Long scheduleId, final Entity row) {
        given(dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE))
                .willReturn(scheduleDD);
        given(scheduleDD.find(ProductionMaintenanceGanttListeners.SCHEDULE_TITLE_QUERY)).willReturn(scheduleQuery);
        given(scheduleQuery.setLong("scheduleId", scheduleId)).willReturn(scheduleQuery);
        given(scheduleQuery.uniqueResult()).willReturn(row);
    }

    /**
     * Returns the title {@link TitleTranslationAnswer} gives for the given identification and state label code or text.
     */
    private static String scheduleTitle(final String identification, final String stateLabel) {
        return ProductionMaintenanceGanttListeners.SCHEDULE_TITLE_TRANSLATION_KEY + "[" + identification + "|" + stateLabel
                + "]";
    }

    /**
     * Answers a translation of a code of the given map with its text, and of every other code with the code followed by
     * its arguments, joined with {@code |}, in brackets when there are any.
     */
    private static final class TitleTranslationAnswer implements Answer<String> {

        private final Map<String, String> translations;

        private TitleTranslationAnswer(final Map<String, String> translations) {
            this.translations = translations;
        }

        @Override
        public String answer(final InvocationOnMock invocation) {
            Object[] arguments = invocation.getArguments();
            String code = (String) arguments[0];

            if (translations.containsKey(code)) {
                return translations.get(code);
            }

            List<String> translationArgs = new ArrayList<String>();

            for (int index = 2; index < arguments.length; index++) {
                Object argument = arguments[index];

                if (argument instanceof String[]) {
                    translationArgs.addAll(Arrays.asList((String[]) argument));
                } else {
                    translationArgs.add((String) argument);
                }
            }

            if (translationArgs.isEmpty()) {
                return code;
            }

            StringBuilder translation = new StringBuilder(code).append('[');

            for (int index = 0; index < translationArgs.size(); index++) {
                if (index > 0) {
                    translation.append('|');
                }

                translation.append(translationArgs.get(index));
            }

            return translation.append(']').toString();
        }

    }

}
