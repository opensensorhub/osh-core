/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are copyright (C) 2018, Sensia Software LLC
 All Rights Reserved. This software is the property of Sensia Software LLC.
 It cannot be duplicated, used, or distributed without the express written
 consent of Sensia Software LLC.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Set;


/**
 * <p>
 * Generic utility method to deal with objects
 * </p>
 *
 * @author Alex Robin
 * @date Mar 19, 2018
 */
public class ObjectUtils
{
    
    private ObjectUtils() {};
    
    
    public static String toString(Object object, boolean recursive)
    {
        return toString(object, recursive, false);
    }
    
    
    public static String toString(Object object, boolean recursive, Set<String> collapsedFields)
    {
        return toString(object, recursive, false, collapsedFields);
    }
    
    
    public static String toString(Object object, boolean recursive, boolean hideNulls)
    {        
        return toString(object, recursive, hideNulls, null);
    }
    
    
    public static String toString(Object object, boolean recursive, boolean hideNulls, Set<String> collapsedFields)
    {
        if (object == null)
            return "null";
        
        if (collapsedFields == null)
            collapsedFields = Collections.emptySet();

        // build class hierarchy so we can list fields of super class first
        Class<?> clazz = object.getClass();
        LinkedList<Class<?>> classHierarchy = new LinkedList<>();
        while (clazz != null && !clazz.equals(Object.class))
        {
            classHierarchy.addFirst(clazz);
            if (!recursive)
                break;
            clazz = clazz.getSuperclass();
        }

        // print all fields starting from the top of the hierarchy
        StringBuilder sb = new StringBuilder(object.getClass().getSimpleName()).append(" { ");
        for (Class<?> c: classHierarchy)
        {
            Field[] fields = c.getDeclaredFields();
            for (Field f : fields)
            {
                if (!Modifier.isStatic(f.getModifiers()))
                {
                    try
                    {
                        f.setAccessible(true);
                        Object val = f.get(object);
                        if (!hideNulls || val != null)
                        {
                            String objString =
                                (val == null) ? "null" : 
                                (val != null && collapsedFields.contains(f.getName())) ? "..." :
                                val.getClass().getSimpleName().contains("$$Lambda$") ? "lambda" :
                                f.getType() == double[].class ? Arrays.toString((double[])val) :
                                f.getType() == float[].class ? Arrays.toString((float[])val) :
                                f.getType() == int[].class ? Arrays.toString((int[])val) :
                                val.toString();
                            sb.append(f.getName()).append(": ").append(objString).append(", ");
                        }
                    }
                    catch (IllegalAccessException e)
                    {
                    }
                }
            }
        }

        sb.deleteCharAt(sb.lastIndexOf(","));
        return sb.append("}").toString();
    }

}
