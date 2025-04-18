/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package com.draeger.medical.sdccc.util.junit.util;

import java.lang.reflect.Method;

/**
 * Utilities related to extracting information from classes.
 */
public interface ClassUtil {

    /**
     * Retrieves a method given a classname with full package path and a method name.
     *
     * @param className  classname with full package path
     * @param methodName name of the method
     * @return the method object
     */
    Method getMethod(String className, String methodName) throws ClassNotFoundException, NoSuchMethodException;
}
