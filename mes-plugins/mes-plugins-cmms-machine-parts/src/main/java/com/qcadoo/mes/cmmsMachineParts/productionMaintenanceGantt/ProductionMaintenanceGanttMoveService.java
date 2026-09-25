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

import org.springframework.stereotype.Service;

/**
 * Moves a production line schedule position on the production and maintenance Gantt board.
 * <p>
 * Declares the rejection reason keys of a move and {@link MoveRejectedException}, the exception that carries the reason of a
 * rejected move.
 */
@Service
public class ProductionMaintenanceGanttMoveService {

    /** Message key of a move rejected because the board no longer matches the stored data. */
    public static final String OPTIMISTIC_LOCK_KEY = "qcadooView.validate.global.optimisticLock";

    /** Message key of a move rejected because the moved position could not be saved. */
    public static final String SAVE_FAILED_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.saveFailed";

    /** Message key of a move rejected because a following position could not be recomputed. */
    public static final String RECOMPUTE_FAILED_KEY = "cmmsMachineParts.productionMaintenanceGantt.move.error.recomputeFailed";

    /**
     * Carries the message key and message arguments of a rejected move.
     */
    public static class MoveRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String messageKey;

        private final String[] args;

        /**
         * Creates the exception of a rejected move.
         *
         * @param messageKey
         *            message key of the rejection reason
         * @param args
         *            message arguments of the rejection reason
         */
        public MoveRejectedException(final String messageKey, final String... args) {
            super(messageKey);
            this.messageKey = messageKey;
            if (args == null) {
                this.args = new String[0];
            } else {
                this.args = args.clone();
            }
        }

        /**
         * Returns the message key of the rejection reason.
         *
         * @return message key
         */
        public String getMessageKey() {
            return messageKey;
        }

        /**
         * Returns a copy of the message arguments of the rejection reason, never null.
         *
         * @return message arguments
         */
        public String[] getArgs() {
            return args.clone();
        }

    }

}
