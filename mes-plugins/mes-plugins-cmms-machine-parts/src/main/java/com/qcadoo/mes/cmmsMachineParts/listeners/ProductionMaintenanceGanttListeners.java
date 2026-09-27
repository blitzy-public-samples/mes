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

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttChartItemResolver;
import com.qcadoo.mes.cmmsMachineParts.productionMaintenanceGantt.ProductionMaintenanceGanttMoveService;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ProductionLineScheduleFields;
import com.qcadoo.mes.orders.states.constants.ScheduleState;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
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
 * {@link #fillTitle(ViewDefinitionState)} is the view's {@code beforeRender} hook that writes the translated title, naming
 * the displayed schedule, into the {@code title} field when the view is initialized.
 */
@Service
public class ProductionMaintenanceGanttListeners {

    /**
     * Translation key of the board title written into the {@code title} field when the displayed schedule cannot be
     * identified.
     */
    public static final String TITLE_TRANSLATION_KEY = "cmmsMachineParts.productionMaintenanceGantt.window.mainTab.title.label";

    /**
     * Translation key of the board title naming the displayed schedule: {0} schedule identification, {1} state label.
     */
    public static final String SCHEDULE_TITLE_TRANSLATION_KEY = "cmmsMachineParts.productionMaintenanceGantt.window.mainTab.title.schedule";

    private static final String L_TITLE = "title";

    private static final String L_GANTT = "gantt";

    private static final String L_SCHEDULE_ID = "scheduleId";

    private static final String L_SCHEDULE_NUMBER = "scheduleNumber";

    private static final String L_SCHEDULE_NAME = "scheduleName";

    private static final String L_SCHEDULE_STATE = "scheduleState";

    private static final String L_IDENTIFICATION_SEPARATOR = " - ";

    private static final String SCHEDULE_STATE_VALUE_PREFIX = "orders.productionLineSchedule.state.value.";

    /**
     * Number, name and state of one production line schedule, by id.
     */
    static final String SCHEDULE_TITLE_QUERY = "select s." + ProductionLineScheduleFields.NUMBER + " as " + L_SCHEDULE_NUMBER
            + ", s." + ProductionLineScheduleFields.NAME + " as " + L_SCHEDULE_NAME + ", s." + ProductionLineScheduleFields.STATE
            + " as " + L_SCHEDULE_STATE + " from #orders_productionLineSchedule s where s.id = :" + L_SCHEDULE_ID;

    @Autowired
    private ProductionMaintenanceGanttMoveService productionMaintenanceGanttMoveService;

    @Autowired
    private TranslationService translationService;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ProductionMaintenanceGanttChartItemResolver productionMaintenanceGanttChartItemResolver;

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
     * returns {@code false}): sets the field value to the title of {@link #buildTitle(ViewDefinitionState)} and requests the
     * field's state update;</li>
     * <li>view rendered after any other event: returns without reading or changing any component and without reading the
     * schedule, which keeps the value the client sent.</li>
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

        title.setFieldValue(buildTitle(view));
        title.requestComponentUpdateState();
    }

    /**
     * Returns the board title for the view's locale. The schedule is the one whose id the {@code gantt} component's context
     * holds under {@link ProductionMaintenanceGanttChartItemResolver#CONTEXT_SCHEDULE_ID}, read through
     * {@link ProductionMaintenanceGanttChartItemResolver#parseScheduleId(String)} and {@link #SCHEDULE_TITLE_QUERY}. When the
     * schedule exists and {@link #scheduleIdentification(Entity)} is not empty, the title is
     * {@link #SCHEDULE_TITLE_TRANSLATION_KEY} translated with that identification and the
     * {@link #scheduleStateLabel(String, Locale)} of its state; otherwise, including a missing, malformed or unknown id, it is
     * {@link #TITLE_TRANSLATION_KEY} translated.
     * <p>
     * For example schedule {@code PS-1} named {@code Week 41} in the draft state gives, in English,
     * {@code Production and maintenance calendar: schedule PS-1 - Week 41 (Draft)}.
     */
    private String buildTitle(final ViewDefinitionState view) {
        Locale locale = view.getLocale();
        GanttChartComponentState gantt = (GanttChartComponentState) view.getComponentByReference(L_GANTT);
        Long scheduleId = productionMaintenanceGanttChartItemResolver.parseScheduleId(gantt
                .getContextValue(ProductionMaintenanceGanttChartItemResolver.CONTEXT_SCHEDULE_ID));

        if (scheduleId != null) {
            Entity schedule = dataDefinitionService
                    .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_PRODUCTION_LINE_SCHEDULE)
                    .find(SCHEDULE_TITLE_QUERY).setLong(L_SCHEDULE_ID, scheduleId).uniqueResult();

            if (schedule != null) {
                String identification = scheduleIdentification(schedule);

                if (!identification.isEmpty()) {
                    return translate(SCHEDULE_TITLE_TRANSLATION_KEY, locale, identification,
                            scheduleStateLabel(schedule.getStringField(L_SCHEDULE_STATE), locale));
                }
            }
        }

        return translate(TITLE_TRANSLATION_KEY, locale);
    }

    /**
     * Returns the identification of a {@link #SCHEDULE_TITLE_QUERY} row from its number and name, each as plain text without
     * leading and trailing whitespace: both joined with {@code " - "} when neither is null or blank, the one that is not
     * null or blank alone otherwise, and an empty string when both are null or blank. The {@code title} field HTML-encodes
     * the whole title when it renders it.
     * <p>
     * For example number {@code " PS-1 "} and name {@code "<b>Week</b>"} give {@code PS-1 - <b>Week</b>}.
     */
    private String scheduleIdentification(final Entity schedule) {
        String number = schedule.getStringField(L_SCHEDULE_NUMBER);
        String name = schedule.getStringField(L_SCHEDULE_NAME);
        boolean hasNumber = StringUtils.isNotBlank(number);
        boolean hasName = StringUtils.isNotBlank(name);

        if (hasNumber && hasName) {
            return number.trim() + L_IDENTIFICATION_SEPARATOR + name.trim();
        }

        if (hasNumber) {
            return number.trim();
        }

        if (hasName) {
            return name.trim();
        }

        return StringUtils.EMPTY;
    }

    /**
     * Returns the state label of a schedule: the translation of
     * {@link ProductionMaintenanceGanttChartItemResolver#ITEM_STATE_UNSPECIFIED_KEY} when the state is null or blank; the
     * translation of {@code orders.productionLineSchedule.state.value.} followed by the state when the state is the string
     * value of one of the {@link ScheduleState} constants; otherwise the translation of
     * {@link ProductionMaintenanceGanttChartItemResolver#ITEM_STATE_UNKNOWN_KEY}. A message code is built only from the
     * string value of a declared state, never from the given state.
     * <p>
     * For example {@code 02approved} gives the translation of {@code orders.productionLineSchedule.state.value.02approved},
     * and {@code 09archived} gives the translation of {@link ProductionMaintenanceGanttChartItemResolver#ITEM_STATE_UNKNOWN_KEY}.
     *
     * @param state
     *            the persisted state of the schedule, may be null
     * @param locale
     *            the locale of the translation
     * @return the state label
     */
    private String scheduleStateLabel(final String state, final Locale locale) {
        String messageCode = ProductionMaintenanceGanttChartItemResolver.ITEM_STATE_UNKNOWN_KEY;

        if (StringUtils.isBlank(state)) {
            messageCode = ProductionMaintenanceGanttChartItemResolver.ITEM_STATE_UNSPECIFIED_KEY;
        } else {
            for (ScheduleState declaredState : ScheduleState.values()) {
                if (declaredState.getStringValue().equals(state)) {
                    messageCode = SCHEDULE_STATE_VALUE_PREFIX + declaredState.getStringValue();

                    break;
                }
            }
        }

        return translate(messageCode, locale);
    }

    /**
     * Returns the translation of the message code, or an empty string when the translation is null.
     */
    private String translate(final String messageCode, final Locale locale, final String... args) {
        return StringUtils.defaultString(translationService.translate(messageCode, locale, args));
    }

}
