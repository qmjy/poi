/*
 * @(#) Configuration.java 	 version 2.0   24/2/2018
 *
 * Copyright (C) 2013-2019 Information Systems Management Institute, Athena R.C., Greece.
 *
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.github.qmjy.poi.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles exceptions raised by TripleGeo utilities. Issuing exit codes to the operation system.
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */
public class ExceptionHandler {

    /** SLF4J logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionHandler.class);


    /**
     * Prints warnings regarding issues raised during transformation.
     *
     * @param e   Exception raised in any stage of the transformation process.
     * @param msg Message to be issued along with the warning.
     */
    public static void warn(Exception e, String msg) {
        LOGGER.warn(msg, e);
    }

    /**
     * Terminates execution of the transformation process due to an error.
     *
     * @param e   Exception raised in any stage of the transformation process.
     * @param msg Message to be issued along with the error.
     */
    public static void abort(Exception e, String msg) {
        LOGGER.error("Transformation process terminated abnormally. {}", msg, e);
        System.exit(1);                              //Issue signal to the operation system that execution terminated abnormally
    }
}
